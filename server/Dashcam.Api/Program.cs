using System.Globalization;
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

app.MapGet("/api/storage/status", async (DashcamDbContext db, IConfiguration config, CancellationToken token) =>
{
    var totalVideoCount = await db.Videos.CountAsync(token);
    var totalSizeBytes = await db.Videos.SumAsync(x => (long?)x.FileSizeBytes, token) ?? 0;
    var maxStorageBytes = GetMaxStorageBytes(config);
    var storageRoot = GetStorageRoot(config);
    Directory.CreateDirectory(storageRoot);
    var root = Path.GetPathRoot(Path.GetFullPath(storageRoot));
    var driveAvailable = root is null ? 0 : new DriveInfo(root).AvailableFreeSpace;
    return Results.Ok(new
    {
        totalVideoCount,
        totalSizeBytes,
        maxStorageBytes,
        availableSpaceBytes = Math.Min(Math.Max(0, maxStorageBytes - totalSizeBytes), driveAvailable)
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

static long GetMaxStorageBytes(IConfiguration config)
{
    var maxGb = config.GetValue<double?>("MaxStorageGB") ?? 200;
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

static string CleanFileBase(string value)
{
    var invalid = Path.GetInvalidFileNameChars().ToHashSet();
    var cleaned = new string(value.Where(character => !invalid.Contains(character)).ToArray()).Trim();
    if (string.IsNullOrWhiteSpace(cleaned)) cleaned = "dashcam";
    return cleaned.Length <= 100 ? cleaned : cleaned[..100];
}

static object ToResponse(Video video) => new
{
    video.Id,
    video.Filename,
    video.OriginalFilename,
    video.StartTime,
    video.EndTime,
    video.DurationSeconds,
    video.FileSizeBytes,
    video.Locked,
    video.UploadedAt,
    streamUrl = $"/api/videos/{video.Id}/stream"
};

public sealed record LockRequest(bool Locked);
