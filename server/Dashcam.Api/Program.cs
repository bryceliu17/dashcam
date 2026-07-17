using System.Globalization;
using System.Diagnostics;
using System.Text.Json;
using Dashcam.Api.Data;
using Dashcam.Api.Models;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(options => options.Limits.MaxRequestBodySize = 4L * 1024 * 1024 * 1024);

var connectionString = builder.Configuration.GetConnectionString("DashcamDatabase")
    ?? "Data Source=dashcam.db";
builder.Services.AddDbContext<DashcamDbContext>(options => options.UseSqlite(connectionString));
builder.Services.AddCors(options => options.AddDefaultPolicy(policy =>
    policy.AllowAnyOrigin().AllowAnyHeader().AllowAnyMethod()));
builder.Services.Configure<FormOptions>(options =>
    options.MultipartBodyLengthLimit = 4L * 1024 * 1024 * 1024);

var app = builder.Build();
app.UseCors();

await using (var scope = app.Services.CreateAsyncScope())
{
    var db = scope.ServiceProvider.GetRequiredService<DashcamDbContext>();
    await db.Database.EnsureCreatedAsync();
    await EnsurePlaybackRotationColumnAsync(db);
    await EnsureAudioTableAsync(db);
}

app.MapGet("/api/health", () => Results.Ok(new
{
    status = "ok",
    serverTime = DateTime.UtcNow
}));

app.MapPost("/api/videos/upload", async (
    HttpRequest request,
    DashcamDbContext db,
    IConfiguration configuration,
    CancellationToken cancellationToken) =>
{
    if (!request.HasFormContentType)
        return Results.BadRequest(new { error = "multipart/form-data is required." });

    var form = await request.ReadFormAsync(cancellationToken);
    var file = form.Files.GetFile("file");
    if (file is null || file.Length == 0)
        return Results.BadRequest(new { error = "A non-empty file field is required." });

    var originalFilename = Path.GetFileName(form["filename"].FirstOrDefault() ?? file.FileName);
    if (!string.Equals(Path.GetExtension(originalFilename), ".mp4", StringComparison.OrdinalIgnoreCase))
        return Results.BadRequest(new { error = "Only MP4 files are accepted." });

    if (!TryDate(form["startTime"].FirstOrDefault(), out var startTime) ||
        !TryDate(form["endTime"].FirstOrDefault(), out var endTime) ||
        !int.TryParse(form["durationSeconds"].FirstOrDefault(), out var durationSeconds))
        return Results.BadRequest(new { error = "startTime, endTime and durationSeconds are required and must be valid." });

    if (durationSeconds < 0 || endTime < startTime)
        return Results.BadRequest(new { error = "The video time range is invalid." });

    var playbackRotationDegrees = int.TryParse(form["playbackRotationDegrees"].FirstOrDefault(), out var parsedRotation)
        ? parsedRotation
        : 0;
    if (!IsValidRotation(playbackRotationDegrees))
        return Results.BadRequest(new { error = "playbackRotationDegrees must be 0, 90, 180 or 270." });

    var configuredSize = long.TryParse(form["fileSizeBytes"].FirstOrDefault(), out var parsedSize)
        ? parsedSize
        : file.Length;
    if (configuredSize != file.Length)
        return Results.BadRequest(new { error = "fileSizeBytes does not match the uploaded file." });

    var storageRoot = GetStorageRoot(configuration);
    var dateDirectory = Path.Combine(storageRoot, startTime.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
    Directory.CreateDirectory(dateDirectory);

    var safeBaseName = CleanFileBase(Path.GetFileNameWithoutExtension(originalFilename));
    var storedFilename = $"{safeBaseName}_{Guid.NewGuid():N}.mp4";
    var finalPath = Path.Combine(dateDirectory, storedFilename);
    var temporaryPath = finalPath + ".uploading";

    try
    {
        await using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1024 * 1024, true))
            await file.CopyToAsync(stream, cancellationToken);
        File.Move(temporaryPath, finalPath);

        var now = DateTime.UtcNow;
        var video = new Video
        {
            Filename = storedFilename,
            OriginalFilename = originalFilename,
            FilePath = finalPath,
            StartTime = startTime,
            EndTime = endTime,
            DurationSeconds = durationSeconds,
            FileSizeBytes = file.Length,
            Locked = false,
            PlaybackRotationDegrees = playbackRotationDegrees,
            UploadedAt = now,
            CreatedAt = now
        };
        db.Videos.Add(video);
        await db.SaveChangesAsync(cancellationToken);
        return Results.Created($"/api/videos/{video.Id}", ToResponse(video));
    }
    catch
    {
        TryDelete(temporaryPath);
        TryDelete(finalPath);
        throw;
    }
});

app.MapPost("/api/audio/upload", async (
    HttpRequest request,
    DashcamDbContext db,
    IConfiguration configuration,
    CancellationToken cancellationToken) =>
{
    if (!request.HasFormContentType)
        return Results.BadRequest(new { error = "multipart/form-data is required." });

    var form = await request.ReadFormAsync(cancellationToken);
    var file = form.Files.GetFile("file");
    if (file is null || file.Length == 0)
        return Results.BadRequest(new { error = "A non-empty file field is required." });

    var originalFilename = Path.GetFileName(form["filename"].FirstOrDefault() ?? file.FileName);
    if (!string.Equals(Path.GetExtension(originalFilename), ".m4a", StringComparison.OrdinalIgnoreCase))
        return Results.BadRequest(new { error = "Only M4A files are accepted." });

    if (!TryDate(form["startTime"].FirstOrDefault(), out var startTime) ||
        !TryDate(form["endTime"].FirstOrDefault(), out var endTime) ||
        !int.TryParse(form["durationSeconds"].FirstOrDefault(), out var durationSeconds))
        return Results.BadRequest(new { error = "startTime, endTime and durationSeconds are required and must be valid." });

    if (durationSeconds < 0 || endTime < startTime)
        return Results.BadRequest(new { error = "The audio time range is invalid." });

    var configuredSize = long.TryParse(form["fileSizeBytes"].FirstOrDefault(), out var parsedSize)
        ? parsedSize
        : file.Length;
    if (configuredSize != file.Length)
        return Results.BadRequest(new { error = "fileSizeBytes does not match the uploaded file." });

    var storageRoot = GetAudioStorageRoot(configuration);
    var dateDirectory = Path.Combine(storageRoot, startTime.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
    Directory.CreateDirectory(dateDirectory);
    var safeBaseName = CleanFileBase(Path.GetFileNameWithoutExtension(originalFilename));
    var storedFilename = $"{safeBaseName}_{Guid.NewGuid():N}.m4a";
    var finalPath = Path.Combine(dateDirectory, storedFilename);
    var temporaryPath = finalPath + ".uploading";

    try
    {
        await using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1024 * 1024, true))
            await file.CopyToAsync(stream, cancellationToken);
        File.Move(temporaryPath, finalPath);

        var now = DateTime.UtcNow;
        var audio = new AudioRecording
        {
            Filename = storedFilename,
            OriginalFilename = originalFilename,
            FilePath = finalPath,
            StartTime = startTime,
            EndTime = endTime,
            DurationSeconds = durationSeconds,
            FileSizeBytes = file.Length,
            Locked = false,
            UploadedAt = now,
            CreatedAt = now
        };
        db.AudioRecordings.Add(audio);
        await db.SaveChangesAsync(cancellationToken);
        return Results.Created($"/api/audio/{audio.Id}", ToAudioResponse(audio));
    }
    catch
    {
        TryDelete(temporaryPath);
        TryDelete(finalPath);
        throw;
    }
});

app.MapGet("/api/videos", async (
    DateOnly? date,
    bool? locked,
    int page,
    int pageSize,
    DashcamDbContext db,
    CancellationToken cancellationToken) =>
{
    page = Math.Max(page, 1);
    pageSize = Math.Clamp(pageSize == 0 ? 50 : pageSize, 1, 200);
    var query = db.Videos.AsNoTracking();
    if (date.HasValue)
    {
        var from = date.Value.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc);
        var to = from.AddDays(1);
        query = query.Where(x => x.StartTime >= from && x.StartTime < to);
    }
    if (locked.HasValue) query = query.Where(x => x.Locked == locked.Value);
    var totalCount = await query.CountAsync(cancellationToken);
    var rows = await query.OrderByDescending(x => x.StartTime)
        .Skip((page - 1) * pageSize).Take(pageSize)
        .ToListAsync(cancellationToken);
    var videos = rows.Select(ToResponse).ToList();
    return Results.Ok(new { items = videos, page, pageSize, totalCount });
});

app.MapGet("/api/audio", async (
    DateOnly? date,
    bool? locked,
    int page,
    int pageSize,
    DashcamDbContext db,
    CancellationToken cancellationToken) =>
{
    page = Math.Max(page, 1);
    pageSize = Math.Clamp(pageSize == 0 ? 50 : pageSize, 1, 200);
    var query = db.AudioRecordings.AsNoTracking();
    if (date.HasValue)
    {
        var from = date.Value.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc);
        var to = from.AddDays(1);
        query = query.Where(x => x.StartTime >= from && x.StartTime < to);
    }
    if (locked.HasValue) query = query.Where(x => x.Locked == locked.Value);
    var totalCount = await query.CountAsync(cancellationToken);
    var rows = await query.OrderByDescending(x => x.StartTime)
        .Skip((page - 1) * pageSize).Take(pageSize)
        .ToListAsync(cancellationToken);
    return Results.Ok(new { items = rows.Select(ToAudioResponse).ToList(), page, pageSize, totalCount });
});

app.MapGet("/api/audio/{id:int}/stream", async (int id, DashcamDbContext db, CancellationToken token) =>
{
    var audio = await db.AudioRecordings.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    if (audio is null) return Results.NotFound();
    if (!File.Exists(audio.FilePath)) return Results.NotFound(new { error = "Audio file is missing." });
    return Results.File(audio.FilePath, "audio/mp4", enableRangeProcessing: true);
});

app.MapGet("/api/audio/{id:int}/waveform", async (
    int id, int points, DashcamDbContext db, CancellationToken token) =>
{
    points = Math.Clamp(points == 0 ? 1200 : points, 200, 4000);
    var audio = await db.AudioRecordings.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    if (audio is null) return Results.NotFound();
    if (!File.Exists(audio.FilePath)) return Results.NotFound(new { error = "Audio file is missing." });

    var cachePath = $"{audio.FilePath}.waveform-{points}.json";
    if (File.Exists(cachePath) && File.GetLastWriteTimeUtc(cachePath) >= File.GetLastWriteTimeUtc(audio.FilePath))
        return Results.File(cachePath, "application/json");

    try
    {
        var peaks = await GenerateWaveformAsync(audio.FilePath, points, token);
        var json = JsonSerializer.SerializeToUtf8Bytes(new
        {
            points = peaks.Length,
            durationSeconds = audio.DurationSeconds,
            peaks
        });
        var temporaryPath = $"{cachePath}.{Guid.NewGuid():N}.tmp";
        await File.WriteAllBytesAsync(temporaryPath, json, token);
        File.Move(temporaryPath, cachePath, true);
        return Results.Bytes(json, "application/json");
    }
    catch (Exception error) when (error is not OperationCanceledException)
    {
        return Results.Problem($"Unable to generate waveform: {error.Message}", statusCode: 500);
    }
});

app.MapGet("/api/audio/{id:int}/download", async (int id, DashcamDbContext db, CancellationToken token) =>
{
    var audio = await db.AudioRecordings.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    if (audio is null) return Results.NotFound();
    if (!File.Exists(audio.FilePath)) return Results.NotFound(new { error = "Audio file is missing." });
    return Results.File(audio.FilePath, "audio/mp4", audio.OriginalFilename, enableRangeProcessing: true);
});

app.MapDelete("/api/audio/{id:int}", async (int id, DashcamDbContext db, CancellationToken token) =>
{
    var audio = await db.AudioRecordings.SingleOrDefaultAsync(x => x.Id == id, token);
    if (audio is null) return Results.NotFound();
    if (!TryDelete(audio.FilePath))
        return Results.Problem("The physical audio file could not be deleted. The database record was preserved.", statusCode: 500);
    TryDeleteWaveformCaches(audio.FilePath);
    db.AudioRecordings.Remove(audio);
    await db.SaveChangesAsync(token);
    return Results.NoContent();
});

app.MapPatch("/api/audio/{id:int}/lock", async (
    int id, LockRequest request, DashcamDbContext db, CancellationToken token) =>
{
    var audio = await db.AudioRecordings.SingleOrDefaultAsync(x => x.Id == id, token);
    if (audio is null) return Results.NotFound();
    audio.Locked = request.Locked;
    await db.SaveChangesAsync(token);
    return Results.Ok(ToAudioResponse(audio));
});

app.MapGet("/api/videos/{id:int}/stream", async (int id, DashcamDbContext db, CancellationToken token) =>
{
    var video = await db.Videos.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    if (video is null) return Results.NotFound();
    if (!File.Exists(video.FilePath)) return Results.NotFound(new { error = "Video file is missing." });
    return Results.File(video.FilePath, "video/mp4", enableRangeProcessing: true);
});

app.MapGet("/api/videos/{id:int}/download", async (int id, DashcamDbContext db, CancellationToken token) =>
{
    var video = await db.Videos.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    if (video is null) return Results.NotFound();
    if (!File.Exists(video.FilePath)) return Results.NotFound(new { error = "Video file is missing." });
    return Results.File(video.FilePath, "video/mp4", video.OriginalFilename, enableRangeProcessing: true);
});

app.MapDelete("/api/videos/{id:int}", async (int id, DashcamDbContext db, CancellationToken token) =>
{
    var video = await db.Videos.SingleOrDefaultAsync(x => x.Id == id, token);
    if (video is null) return Results.NotFound();
    if (!TryDelete(video.FilePath))
        return Results.Problem("The physical video file could not be deleted. The database record was preserved.", statusCode: 500);
    db.Videos.Remove(video);
    await db.SaveChangesAsync(token);
    return Results.NoContent();
});

app.MapPatch("/api/videos/{id:int}/lock", async (
    int id, LockRequest request, DashcamDbContext db, CancellationToken token) =>
{
    var video = await db.Videos.SingleOrDefaultAsync(x => x.Id == id, token);
    if (video is null) return Results.NotFound();
    video.Locked = request.Locked;
    await db.SaveChangesAsync(token);
    return Results.Ok(ToResponse(video));
});

app.MapPatch("/api/videos/{id:int}/rotation", async (
    int id, RotationRequest request, DashcamDbContext db, CancellationToken token) =>
{
    if (!IsValidRotation(request.PlaybackRotationDegrees))
        return Results.BadRequest(new { error = "playbackRotationDegrees must be 0, 90, 180 or 270." });
    var video = await db.Videos.SingleOrDefaultAsync(x => x.Id == id, token);
    if (video is null) return Results.NotFound();
    video.PlaybackRotationDegrees = request.PlaybackRotationDegrees;
    await db.SaveChangesAsync(token);
    return Results.Ok(ToResponse(video));
});

app.MapGet("/api/storage/status", async (DashcamDbContext db, IConfiguration config, CancellationToken token) =>
{
    var totalVideoCount = await db.Videos.CountAsync(token);
    var totalSizeBytes = await db.Videos.SumAsync(x => (long?)x.FileSizeBytes, token) ?? 0;
    var totalAudioCount = await db.AudioRecordings.CountAsync(token);
    var totalAudioSizeBytes = await db.AudioRecordings.SumAsync(x => (long?)x.FileSizeBytes, token) ?? 0;
    var maxStorageBytes = GetMaxStorageBytes(config);
    var maxAudioStorageBytes = GetMaxAudioStorageBytes(config);
    var storageRoot = GetStorageRoot(config);
    Directory.CreateDirectory(storageRoot);
    var root = Path.GetPathRoot(Path.GetFullPath(storageRoot));
    var driveAvailable = root is null ? 0 : new DriveInfo(root).AvailableFreeSpace;
    return Results.Ok(new
    {
        totalVideoCount,
        totalSizeBytes,
        maxStorageBytes,
        availableSpaceBytes = Math.Min(Math.Max(0, maxStorageBytes - totalSizeBytes), driveAvailable),
        totalAudioCount,
        totalAudioSizeBytes,
        maxAudioStorageBytes,
        audioAvailableSpaceBytes = Math.Min(Math.Max(0, maxAudioStorageBytes - totalAudioSizeBytes), driveAvailable)
    });
});

app.MapPost("/api/videos/cleanup", async (DashcamDbContext db, IConfiguration config, CancellationToken token) =>
{
    var maxBytes = GetMaxStorageBytes(config);
    var totalBytes = await db.Videos.SumAsync(x => (long?)x.FileSizeBytes, token) ?? 0;
    var removedCount = 0;
    var removedBytes = 0L;

    if (totalBytes > maxBytes)
    {
        var candidates = await db.Videos.Where(x => !x.Locked)
            .OrderBy(x => x.StartTime).ToListAsync(token);
        foreach (var video in candidates)
        {
            if (totalBytes <= maxBytes) break;
            if (!TryDelete(video.FilePath)) continue;
            totalBytes -= video.FileSizeBytes;
            removedBytes += video.FileSizeBytes;
            removedCount++;
            db.Videos.Remove(video);
        }
        await db.SaveChangesAsync(token);
    }
    return Results.Ok(new { removedCount, removedBytes, totalSizeBytes = totalBytes, maxStorageBytes = maxBytes });
});

app.MapPost("/api/audio/cleanup", async (DashcamDbContext db, IConfiguration config, CancellationToken token) =>
{
    var maxBytes = GetMaxAudioStorageBytes(config);
    var totalBytes = await db.AudioRecordings.SumAsync(x => (long?)x.FileSizeBytes, token) ?? 0;
    var removedCount = 0;
    var removedBytes = 0L;
    if (totalBytes > maxBytes)
    {
        var candidates = await db.AudioRecordings.Where(x => !x.Locked)
            .OrderBy(x => x.StartTime).ToListAsync(token);
        foreach (var audio in candidates)
        {
            if (totalBytes <= maxBytes) break;
            if (!TryDelete(audio.FilePath)) continue;
            TryDeleteWaveformCaches(audio.FilePath);
            totalBytes -= audio.FileSizeBytes;
            removedBytes += audio.FileSizeBytes;
            removedCount++;
            db.AudioRecordings.Remove(audio);
        }
        await db.SaveChangesAsync(token);
    }
    return Results.Ok(new { removedCount, removedBytes, totalSizeBytes = totalBytes, maxStorageBytes = maxBytes });
});

app.Run();

static bool TryDate(string? value, out DateTime result)
{
    if (DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var dto))
    {
        result = dto.UtcDateTime;
        return true;
    }
    result = default;
    return false;
}

static string GetStorageRoot(IConfiguration config)
{
    var configured = config["VideoStoragePath"];
    var path = string.IsNullOrWhiteSpace(configured)
        ? Path.Combine(AppContext.BaseDirectory, "videos")
        : configured;
    return Path.GetFullPath(path);
}

static string GetAudioStorageRoot(IConfiguration config)
{
    var configured = config["AudioStoragePath"];
    var path = string.IsNullOrWhiteSpace(configured)
        ? Path.Combine(AppContext.BaseDirectory, "audio")
        : configured;
    return Path.GetFullPath(path);
}

static long GetMaxStorageBytes(IConfiguration config)
{
    var maxGb = config.GetValue<double?>("MaxStorageGB") ?? 200;
    return (long)(Math.Max(0.1, maxGb) * 1024 * 1024 * 1024);
}

static long GetMaxAudioStorageBytes(IConfiguration config)
{
    var maxGb = config.GetValue<double?>("MaxAudioStorageGB") ?? 50;
    return (long)(Math.Max(0.1, maxGb) * 1024 * 1024 * 1024);
}

static bool TryDelete(string path)
{
    try
    {
        if (File.Exists(path)) File.Delete(path);
        return true;
    }
    catch (IOException) { return false; }
    catch (UnauthorizedAccessException) { return false; }
}

static bool IsValidRotation(int degrees) => degrees is 0 or 90 or 180 or 270;

static async Task EnsurePlaybackRotationColumnAsync(DashcamDbContext db)
{
    var connection = db.Database.GetDbConnection();
    await connection.OpenAsync();
    await using var check = connection.CreateCommand();
    check.CommandText = "PRAGMA table_info('Videos')";
    var exists = false;
    await using (var reader = await check.ExecuteReaderAsync())
    {
        while (await reader.ReadAsync())
        {
            if (string.Equals(reader.GetString(1), "PlaybackRotationDegrees", StringComparison.OrdinalIgnoreCase))
            {
                exists = true;
                break;
            }
        }
    }
    if (!exists)
        await db.Database.ExecuteSqlRawAsync("ALTER TABLE Videos ADD COLUMN PlaybackRotationDegrees INTEGER NOT NULL DEFAULT 0");
}

static async Task EnsureAudioTableAsync(DashcamDbContext db)
{
    await db.Database.ExecuteSqlRawAsync("""
        CREATE TABLE IF NOT EXISTS AudioRecordings (
            Id INTEGER NOT NULL CONSTRAINT PK_AudioRecordings PRIMARY KEY AUTOINCREMENT,
            Filename TEXT NOT NULL,
            OriginalFilename TEXT NOT NULL,
            FilePath TEXT NOT NULL,
            StartTime TEXT NOT NULL,
            EndTime TEXT NOT NULL,
            DurationSeconds INTEGER NOT NULL,
            FileSizeBytes INTEGER NOT NULL,
            Locked INTEGER NOT NULL,
            UploadedAt TEXT NOT NULL,
            CreatedAt TEXT NOT NULL
        )
        """);
    await db.Database.ExecuteSqlRawAsync(
        "CREATE INDEX IF NOT EXISTS IX_AudioRecordings_StartTime ON AudioRecordings (StartTime)");
    await db.Database.ExecuteSqlRawAsync(
        "CREATE INDEX IF NOT EXISTS IX_AudioRecordings_Locked ON AudioRecordings (Locked)");
}

static string CleanFileBase(string value)
{
    var invalid = Path.GetInvalidFileNameChars().ToHashSet();
    var cleaned = new string(value.Where(character => !invalid.Contains(character)).ToArray()).Trim();
    if (string.IsNullOrWhiteSpace(cleaned)) cleaned = "dashcam";
    return cleaned.Length <= 100 ? cleaned : cleaned[..100];
}

static async Task<double[]> GenerateWaveformAsync(string filePath, int points, CancellationToken token)
{
    var startInfo = new ProcessStartInfo
    {
        FileName = "ffmpeg",
        RedirectStandardOutput = true,
        RedirectStandardError = true,
        UseShellExecute = false,
        CreateNoWindow = true
    };
    foreach (var argument in new[] { "-v", "error", "-i", filePath, "-ac", "1", "-ar", "100", "-f", "u8", "pipe:1" })
        startInfo.ArgumentList.Add(argument);

    using var process = new Process { StartInfo = startInfo };
    if (!process.Start()) throw new InvalidOperationException("ffmpeg could not be started.");
    await using var samples = new MemoryStream();
    var copyTask = process.StandardOutput.BaseStream.CopyToAsync(samples, token);
    var errorTask = process.StandardError.ReadToEndAsync(token);
    try
    {
        await Task.WhenAll(copyTask, process.WaitForExitAsync(token));
    }
    catch
    {
        if (!process.HasExited) process.Kill(true);
        throw;
    }
    var error = await errorTask;
    if (process.ExitCode != 0)
        throw new InvalidOperationException(string.IsNullOrWhiteSpace(error) ? "ffmpeg failed." : error.Trim());

    var bytes = samples.ToArray();
    var peaks = new double[points];
    if (bytes.Length == 0) return peaks;
    for (var point = 0; point < points; point++)
    {
        var start = point * bytes.Length / points;
        var end = Math.Max(start + 1, (point + 1) * bytes.Length / points);
        var peak = 0d;
        for (var index = start; index < Math.Min(end, bytes.Length); index++)
            peak = Math.Max(peak, Math.Abs(bytes[index] - 128) / 127d);
        peaks[point] = Math.Round(peak, 4);
    }
    return peaks;
}

static void TryDeleteWaveformCaches(string audioPath)
{
    var directory = Path.GetDirectoryName(audioPath);
    if (directory is null || !Directory.Exists(directory)) return;
    var pattern = $"{Path.GetFileName(audioPath)}.waveform-*.json";
    foreach (var cachePath in Directory.EnumerateFiles(directory, pattern)) TryDelete(cachePath);
}

static object ToResponse(Video video) => new
{
    video.Id,
    video.Filename,
    video.OriginalFilename,
    StartTime = AsUtc(video.StartTime),
    EndTime = AsUtc(video.EndTime),
    video.DurationSeconds,
    video.FileSizeBytes,
    video.Locked,
    video.PlaybackRotationDegrees,
    UploadedAt = AsUtc(video.UploadedAt),
    streamUrl = $"/api/videos/{video.Id}/stream"
};

static object ToAudioResponse(AudioRecording audio) => new
{
    audio.Id,
    audio.Filename,
    audio.OriginalFilename,
    StartTime = AsUtc(audio.StartTime),
    EndTime = AsUtc(audio.EndTime),
    audio.DurationSeconds,
    audio.FileSizeBytes,
    audio.Locked,
    UploadedAt = AsUtc(audio.UploadedAt),
    streamUrl = $"/api/audio/{audio.Id}/stream"
};

static DateTime AsUtc(DateTime value) => value.Kind switch
{
    DateTimeKind.Utc => value,
    DateTimeKind.Local => value.ToUniversalTime(),
    _ => DateTime.SpecifyKind(value, DateTimeKind.Utc)
};

public sealed record LockRequest(bool Locked);
public sealed record RotationRequest(int PlaybackRotationDegrees);
