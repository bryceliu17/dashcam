using System.Collections.Concurrent;
using System.Globalization;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Net.Http.Json;
using Dashcam.Api.Data;
using Dashcam.Api.Models;
using Dashcam.Api.Services;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

const int DeviceOnlineThresholdSeconds = 75;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(options => options.Limits.MaxRequestBodySize = 4L * 1024 * 1024 * 1024);

var connectionString = builder.Configuration.GetConnectionString("DashcamDatabase")
    ?? "Data Source=dashcam.db";
builder.Services.AddDbContext<DashcamDbContext>(options => options.UseSqlite(connectionString));
builder.Services.AddSingleton<LiveFrameStore>();
builder.Services.AddSingleton<DeviceWebSocketHub>();
builder.Services.AddSingleton<BatteryHistoryBroker>();
builder.Services.AddSingleton<ArchiveMutationGate>();
builder.Services.AddSingleton<ArchiveMigrationService>();
builder.Services.AddSingleton<MigrationUploadService>();
builder.Services.AddHttpClient("TranscriptionWorker", client =>
{
    var workerUrl = builder.Configuration["TranscriptionWorkerUrl"] ?? "http://transcription:8000";
    client.BaseAddress = new Uri($"{workerUrl.TrimEnd('/')}/");
    client.Timeout = TimeSpan.FromHours(2);
});
builder.Services.AddCors(options => options.AddDefaultPolicy(policy =>
    policy.AllowAnyOrigin().AllowAnyHeader().AllowAnyMethod()));
builder.Services.Configure<FormOptions>(options =>
    options.MultipartBodyLengthLimit = 4L * 1024 * 1024 * 1024);

var app = builder.Build();
app.UseWebSockets(new WebSocketOptions
{
    KeepAliveInterval = TimeSpan.FromSeconds(60)
});
app.UseCors();
var videoCleanupGate = new SemaphoreSlim(1, 1);
var audioCleanupGate = new SemaphoreSlim(1, 1);
var sessionExportGate = new SemaphoreSlim(1, 1);
var transcriptionGate = new SemaphoreSlim(1, 1);
var videoExportJobs = new ConcurrentDictionary<Guid, VideoExportJob>();
var audioExportJobs = new ConcurrentDictionary<Guid, AudioExportJob>();

app.Lifetime.ApplicationStopping.Register(() =>
{
    foreach (var job in videoExportJobs.Values)
        if (job.ExportPath is not null) TryDelete(job.ExportPath);
    foreach (var job in audioExportJobs.Values)
        if (job.ExportPath is not null) TryDelete(job.ExportPath);
});

app.Use(async (context, next) =>
{
    var isArchiveMutation = !HttpMethods.IsGet(context.Request.Method) &&
        !HttpMethods.IsHead(context.Request.Method) &&
        (context.Request.Path.StartsWithSegments("/api/videos") ||
         context.Request.Path.StartsWithSegments("/api/audio"));
    if (!isArchiveMutation)
    {
        await next();
        return;
    }

    var migration = context.RequestServices.GetRequiredService<ArchiveMigrationService>();
    if (migration.IsImporting)
    {
        context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
        await context.Response.WriteAsJsonAsync(new { error = "Archive migration is in progress. Try again after it finishes." });
        return;
    }

    var mutationGate = context.RequestServices.GetRequiredService<ArchiveMutationGate>();
    using var lease = await mutationGate.EnterAsync(context.RequestAborted);
    if (migration.IsImporting)
    {
        context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
        await context.Response.WriteAsJsonAsync(new { error = "Archive migration is in progress. Try again after it finishes." });
        return;
    }
    await next();
});

await using (var scope = app.Services.CreateAsyncScope())
{
    var db = scope.ServiceProvider.GetRequiredService<DashcamDbContext>();
    await db.Database.EnsureCreatedAsync();
    await EnsurePlaybackRotationColumnAsync(db);
    await EnsureAudioTableAsync(db);
    await EnsureDeviceStatusTableAsync(db);
    await db.Database.ExecuteSqlRawAsync(
        "UPDATE AudioRecordings SET TranscriptStatus = 'failed', TranscriptError = 'Transcription was interrupted by a server restart.' WHERE TranscriptStatus IN ('queued', 'processing')");
    await db.Database.ExecuteSqlRawAsync(
        "UPDATE DeviceStatuses SET LiveRequested = 0, LiveStreaming = 0, LiveError = ''");
}

app.MapGet("/api/health", () => Results.Ok(new
{
    status = "ok",
    serverTime = DateTime.UtcNow
}));

app.MapGet("/api/devices/socket", async (
    HttpContext context,
    DashcamDbContext db,
    DeviceWebSocketHub sockets,
    BatteryHistoryBroker batteryHistory,
    LiveFrameStore liveFrames,
    IServiceScopeFactory serviceScopeFactory,
    CancellationToken cancellationToken) =>
{
    if (!context.WebSockets.IsWebSocketRequest)
    {
        context.Response.StatusCode = StatusCodes.Status400BadRequest;
        await context.Response.WriteAsJsonAsync(new { error = "A WebSocket upgrade is required." }, cancellationToken);
        return;
    }

    var deviceId = CleanRequiredText(context.Request.Query["deviceId"], 128);
    if (deviceId is null)
    {
        context.Response.StatusCode = StatusCodes.Status400BadRequest;
        await context.Response.WriteAsJsonAsync(new { error = "deviceId is required." }, cancellationToken);
        return;
    }

    var device = await db.DeviceStatuses.FindAsync([deviceId], cancellationToken);
    if (device is null)
    {
        context.Response.StatusCode = StatusCodes.Status404NotFound;
        await context.Response.WriteAsJsonAsync(new { error = "Device not found." }, cancellationToken);
        return;
    }

    if (device.LiveRequested &&
        !liveFrames.HasRecentViewer(deviceId, TimeSpan.FromSeconds(10)))
    {
        device.LiveRequested = false;
        device.LiveStreaming = false;
        device.LiveError = string.Empty;
        liveFrames.Remove(deviceId);
        await db.SaveChangesAsync(cancellationToken);
    }

    using var socket = await context.WebSockets.AcceptWebSocketAsync();
    var remoteIpAddress = NormalizeIpAddress(context.Connection.RemoteIpAddress?.ToString());
    await sockets.RunDeviceAsync(
        deviceId,
        socket,
        device.LiveRequested,
        async (message, messageCancellationToken) =>
        {
            var historyResponse = ParseBatteryHistoryResponse(message);
            if (historyResponse is not null)
            {
                batteryHistory.TryComplete(deviceId, historyResponse);
                return;
            }
            var heartbeat = ParseSocketHeartbeat(message);
            var reportedDeviceId = CleanRequiredText(heartbeat?.DeviceId, 128);
            var deviceName = CleanRequiredText(heartbeat?.DeviceName, 160);
            if (heartbeat is null || reportedDeviceId != deviceId || deviceName is null ||
                heartbeat.BatteryLevel is < 0 or > 100)
                return;

            await using var messageScope = serviceScopeFactory.CreateAsyncScope();
            var messageDb = messageScope.ServiceProvider.GetRequiredService<DashcamDbContext>();
            await ApplyDeviceHeartbeatAsync(
                heartbeat,
                deviceId,
                deviceName,
                remoteIpAddress,
                "websocket",
                messageDb,
                liveFrames,
                messageCancellationToken);
            await messageDb.SaveChangesAsync(messageCancellationToken);
        },
        cancellationToken);
});

app.MapPost("/api/devices/heartbeat", async (
    DeviceHeartbeatRequest request,
    HttpContext httpContext,
    DashcamDbContext db,
    LiveFrameStore liveFrames,
    DeviceWebSocketHub sockets,
    BatteryHistoryBroker batteryHistory,
    CancellationToken cancellationToken) =>
{
    var deviceId = CleanRequiredText(request.DeviceId, 128);
    var deviceName = CleanRequiredText(request.DeviceName, 160);
    if (deviceId is null || deviceName is null || request.BatteryLevel is < 0 or > 100)
        return Results.BadRequest(new { error = "deviceId, deviceName and a batteryLevel from 0 to 100 are required." });

    var device = await ApplyDeviceHeartbeatAsync(
        request,
        deviceId,
        deviceName,
        NormalizeIpAddress(httpContext.Connection.RemoteIpAddress?.ToString()),
        "http",
        db,
        liveFrames,
        cancellationToken);

    await db.SaveChangesAsync(cancellationToken);
    return Results.Ok(new
    {
        device.LiveRequested,
        batteryHistoryRequest = batteryHistory.GetPendingRequest(deviceId)
    });
});

app.MapGet("/api/devices/{deviceId}/battery-history", async (
    string deviceId,
    int? hours,
    DashcamDbContext db,
    DeviceWebSocketHub sockets,
    BatteryHistoryBroker batteryHistory,
    CancellationToken cancellationToken) =>
{
    if (!await db.DeviceStatuses.AnyAsync(device => device.DeviceId == deviceId, cancellationToken))
        return Results.NotFound(new { error = "Device not found." });
    var requestedHours = (hours ?? 24) is 8 or 24 or 72 ? hours ?? 24 : 24;
    try
    {
        var response = await batteryHistory.RequestAsync(
            deviceId,
            requestedHours,
            async (requestId, requestedRange, token) =>
            {
                await sockets.SendBatteryHistoryRequestAsync(deviceId, requestId, requestedRange, token);
            },
            cancellationToken);
        return Results.Ok(new
        {
            deviceId,
            hours = requestedHours,
            response.GeneratedAt,
            items = response.Items
                .Where(item => item.RecordedAt > 0 && item.TemperatureTenthsC is >= -500 and <= 1000)
                .TakeLast(1_000)
        });
    }
    catch (TimeoutException)
    {
        return Results.Json(
            new { error = "The phone did not answer. Keep the app running or enable Live Access and try again." },
            statusCode: StatusCodes.Status504GatewayTimeout);
    }
});

app.MapPost("/api/devices/{deviceId}/battery-history-response", (
    string deviceId,
    BatteryHistoryResponse response,
    BatteryHistoryBroker batteryHistory) =>
{
    if (response.Items.Count > 1_000)
        return Results.BadRequest(new { error = "Too many samples." });
    return batteryHistory.TryComplete(deviceId, response)
        ? Results.Ok(new { accepted = true })
        : Results.NotFound(new { error = "The battery history request has expired." });
});

app.MapGet("/api/devices", async (
    DashcamDbContext db,
    DeviceWebSocketHub sockets,
    CancellationToken cancellationToken) =>
{
    var now = DateTime.UtcNow;
    var devices = await db.DeviceStatuses.AsNoTracking()
        .OrderByDescending(device => device.LastSeenAt)
        .ToListAsync(cancellationToken);
    return Results.Ok(new
    {
        serverTime = now,
        onlineThresholdSeconds = DeviceOnlineThresholdSeconds,
        items = devices.Select(device => ToDeviceResponse(
            device,
            now,
            device.LiveAccessEnabled ? sockets.GetConnectionState(device.DeviceId) : null))
    });
});

app.MapPost("/api/devices/{deviceId}/live", async (
    string deviceId,
    LiveRequest request,
    DashcamDbContext db,
    LiveFrameStore liveFrames,
    DeviceWebSocketHub sockets,
    CancellationToken cancellationToken) =>
{
    var device = await db.DeviceStatuses.FindAsync([deviceId], cancellationToken);
    if (device is null) return Results.NotFound(new { error = "Device not found." });

    if (request.Enabled)
    {
        var socketState = sockets.GetConnectionState(deviceId);
        var online = socketState ?? (DateTime.UtcNow - AsUtc(device.LastSeenAt) <=
            TimeSpan.FromSeconds(DeviceOnlineThresholdSeconds));
        if (!online) return Results.Conflict(new { error = "Phone is offline." });
        if (!device.LiveAccessEnabled) return Results.Conflict(new { error = "Live Access is disabled on the phone." });
        if (device.VideoRecordingActive || device.AudioRecordingActive)
            return Results.Conflict(new { error = "Phone is recording." });
        device.LiveError = string.Empty;
        liveFrames.TouchViewer(deviceId);
        liveFrames.SetRequested(deviceId);
    }
    else
    {
        device.LiveStreaming = false;
        device.LiveError = string.Empty;
        liveFrames.Remove(deviceId);
    }

    device.LiveRequested = request.Enabled;
    await db.SaveChangesAsync(cancellationToken);
    await sockets.SendLiveRequestAsync(deviceId, request.Enabled, cancellationToken);
    return Results.Ok(ToDeviceResponse(
        device,
        DateTime.UtcNow,
        device.LiveAccessEnabled ? sockets.GetConnectionState(deviceId) : null));
});

app.MapPost("/api/devices/{deviceId}/live/frame", async (
    string deviceId,
    HttpRequest request,
    LiveFrameStore liveFrames,
    CancellationToken cancellationToken) =>
{
    const int maxFrameBytes = 2 * 1024 * 1024;
    if (!string.Equals(request.ContentType, "image/jpeg", StringComparison.OrdinalIgnoreCase))
        return Results.BadRequest(new { error = "image/jpeg is required." });
    if (request.ContentLength is null or <= 0 || request.ContentLength > maxFrameBytes)
        return Results.BadRequest(new { error = "A JPEG frame up to 2 MB is required." });

    if (!liveFrames.IsRequested(deviceId))
        return Results.Conflict(new { error = "Live viewing is not requested." });

    await using var buffer = new MemoryStream((int)request.ContentLength.Value);
    await request.Body.CopyToAsync(buffer, cancellationToken);
    if (buffer.Length == 0 || buffer.Length > maxFrameBytes)
        return Results.BadRequest(new { error = "Invalid JPEG frame." });
    liveFrames.Set(deviceId, buffer.ToArray());
    return Results.NoContent();
});

app.MapGet("/api/devices/{deviceId}/live/frame", async (
    string deviceId,
    long? after,
    HttpResponse response,
    LiveFrameStore liveFrames,
    CancellationToken cancellationToken) =>
{
    if (!liveFrames.IsRequested(deviceId))
        return Results.Conflict(new { error = "Live viewing is not requested." });
    liveFrames.TouchViewer(deviceId);
    var frame = await liveFrames.WaitForNewAsync(
        deviceId,
        after ?? -1,
        TimeSpan.FromSeconds(2),
        cancellationToken);
    if (frame is null) return Results.NoContent();
    response.Headers["X-Live-Sequence"] = frame.Sequence.ToString(CultureInfo.InvariantCulture);
    return Results.File(frame.Jpeg, "image/jpeg", lastModified: frame.CapturedAt);
});

app.MapPost("/api/videos/upload", async (
    HttpRequest request,
    DashcamDbContext db,
    IConfiguration configuration,
    ILoggerFactory loggerFactory,
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

        var duplicate = await FindExactVideoDuplicateAsync(
            db, originalFilename, startTime, endTime, durationSeconds, file.Length, finalPath, cancellationToken);
        if (duplicate is not null)
        {
            if (!TryDelete(finalPath)) throw new IOException("The duplicate upload could not be discarded.");
            loggerFactory.CreateLogger("Dashcam.UploadDeduplication").LogInformation(
                "Discarded duplicate video upload {OriginalFilename}; returning existing video {VideoId}",
                originalFilename,
                duplicate.Id);
            return Results.Ok(ToResponse(duplicate));
        }

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

        try
        {
            await CleanupVideosAsync(db, configuration, videoCleanupGate, CancellationToken.None);
        }
        catch (Exception cleanupError)
        {
            loggerFactory.CreateLogger("Dashcam.VideoCleanup").LogError(
                cleanupError,
                "Automatic video cleanup failed after uploading video {VideoId}",
                video.Id);
        }

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
    ILoggerFactory loggerFactory,
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

        var duplicate = await FindExactAudioDuplicateAsync(
            db, originalFilename, startTime, endTime, durationSeconds, file.Length, finalPath, cancellationToken);
        if (duplicate is not null)
        {
            if (!TryDelete(finalPath)) throw new IOException("The duplicate upload could not be discarded.");
            loggerFactory.CreateLogger("Dashcam.UploadDeduplication").LogInformation(
                "Discarded duplicate audio upload {OriginalFilename}; returning existing audio {AudioId}",
                originalFilename,
                duplicate.Id);
            return Results.Ok(ToAudioResponse(duplicate));
        }

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

        try
        {
            await CleanupAudioAsync(db, configuration, audioCleanupGate, CancellationToken.None);
        }
        catch (Exception cleanupError)
        {
            loggerFactory.CreateLogger("Dashcam.AudioCleanup").LogError(
                cleanupError,
                "Automatic audio cleanup failed after uploading audio {AudioId}",
                audio.Id);
        }

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
    int timezoneOffsetMinutes,
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
        timezoneOffsetMinutes = Math.Clamp(timezoneOffsetMinutes, -14 * 60, 14 * 60);
        var from = DateTime.SpecifyKind(
            date.Value.ToDateTime(TimeOnly.MinValue).AddMinutes(timezoneOffsetMinutes),
            DateTimeKind.Utc);
        var to = from.AddDays(1);
        query = query.Where(x => x.StartTime >= from && x.StartTime < to);
    }
    if (locked.HasValue) query = query.Where(x => x.Locked == locked.Value);
    var totalCount = await query.CountAsync(cancellationToken);
    var totalDurationSeconds = await query.SumAsync(x => (long)x.DurationSeconds, cancellationToken);
    var rows = await query.OrderByDescending(x => x.StartTime)
        .Skip((page - 1) * pageSize).Take(pageSize)
        .ToListAsync(cancellationToken);
    var videos = rows.Select(ToResponse).ToList();
    return Results.Ok(new { items = videos, page, pageSize, totalCount, totalDurationSeconds });
});

app.MapGet("/api/audio", async (
    DateOnly? date,
    bool? locked,
    int timezoneOffsetMinutes,
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
        timezoneOffsetMinutes = Math.Clamp(timezoneOffsetMinutes, -14 * 60, 14 * 60);
        var from = DateTime.SpecifyKind(
            date.Value.ToDateTime(TimeOnly.MinValue).AddMinutes(timezoneOffsetMinutes),
            DateTimeKind.Utc);
        var to = from.AddDays(1);
        query = query.Where(x => x.StartTime >= from && x.StartTime < to);
    }
    if (locked.HasValue) query = query.Where(x => x.Locked == locked.Value);
    var totalCount = await query.CountAsync(cancellationToken);
    var totalDurationSeconds = await query.SumAsync(x => (long)x.DurationSeconds, cancellationToken);
    var rows = await query.OrderByDescending(x => x.StartTime)
        .Skip((page - 1) * pageSize).Take(pageSize)
        .ToListAsync(cancellationToken);
    return Results.Ok(new { items = rows.Select(ToAudioResponse).ToList(), page, pageSize, totalCount, totalDurationSeconds });
});

app.MapGet("/api/archive/dates", async (
    string type,
    int year,
    int month,
    int timezoneOffsetMinutes,
    bool? locked,
    DashcamDbContext db,
    CancellationToken cancellationToken) =>
{
    if (year < 2000 || year > 9999 || month < 1 || month > 12)
        return Results.BadRequest(new { error = "A valid year and month are required." });

    timezoneOffsetMinutes = Math.Clamp(timezoneOffsetMinutes, -14 * 60, 14 * 60);
    var from = DateTime.SpecifyKind(
        new DateTime(year, month, 1).AddMinutes(timezoneOffsetMinutes),
        DateTimeKind.Utc);
    var to = from.AddMonths(1);
    List<DateTime> timestamps;

    if (string.Equals(type, "video", StringComparison.OrdinalIgnoreCase))
    {
        var query = db.Videos.AsNoTracking().Where(x => x.StartTime >= from && x.StartTime < to);
        if (locked.HasValue) query = query.Where(x => x.Locked == locked.Value);
        timestamps = await query.Select(x => x.StartTime).ToListAsync(cancellationToken);
    }
    else if (string.Equals(type, "audio", StringComparison.OrdinalIgnoreCase))
    {
        var query = db.AudioRecordings.AsNoTracking().Where(x => x.StartTime >= from && x.StartTime < to);
        if (locked.HasValue) query = query.Where(x => x.Locked == locked.Value);
        timestamps = await query.Select(x => x.StartTime).ToListAsync(cancellationToken);
    }
    else
    {
        return Results.BadRequest(new { error = "type must be video or audio." });
    }

    var dates = timestamps
        .Select(timestamp => DateOnly.FromDateTime(timestamp.AddMinutes(-timezoneOffsetMinutes)))
        .Distinct().OrderBy(x => x).Select(x => x.ToString("yyyy-MM-dd"));
    return Results.Ok(new { dates });
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

app.MapPost("/api/audio/{id:int}/transcription", async (
    int id,
    DashcamDbContext db,
    IServiceScopeFactory scopeFactory,
    IHttpClientFactory httpClientFactory,
    ILoggerFactory loggerFactory,
    CancellationToken token) =>
{
    var audio = await db.AudioRecordings.SingleOrDefaultAsync(x => x.Id == id, token);
    if (audio is null) return Results.NotFound();
    if (!File.Exists(audio.FilePath)) return Results.NotFound(new { error = "Audio file is missing." });
    if (audio.DurationSeconds > 30 * 60)
        return Results.BadRequest(new { error = "Only audio recordings up to 30 minutes can be transcribed." });
    if (audio.TranscriptStatus is "queued" or "processing")
        return Results.Conflict(new { error = "This recording is already being transcribed." });

    audio.TranscriptStatus = "queued";
    audio.TranscriptText = string.Empty;
    audio.TranscriptLanguage = string.Empty;
    audio.TranscriptLanguageProbability = 0;
    audio.TranscriptModel = string.Empty;
    audio.TranscriptSegmentsJson = string.Empty;
    audio.TranscriptError = string.Empty;
    audio.TranscriptCreatedAt = null;
    await db.SaveChangesAsync(token);

    _ = Task.Run(() => RunAudioTranscriptionAsync(
        id,
        scopeFactory,
        httpClientFactory,
        loggerFactory,
        transcriptionGate));

    return Results.Accepted($"/api/audio/{id}/transcription", ToTranscriptResponse(audio, includeText: false));
});

app.MapGet("/api/audio/{id:int}/transcription", async (
    int id, DashcamDbContext db, CancellationToken token) =>
{
    var audio = await db.AudioRecordings.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    return audio is null
        ? Results.NotFound()
        : Results.Ok(ToTranscriptResponse(audio, includeText: true));
});

app.MapGet("/api/audio/{id:int}/transcription/download", async (
    int id, DashcamDbContext db, CancellationToken token) =>
{
    var audio = await db.AudioRecordings.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    if (audio is null) return Results.NotFound();
    if (audio.TranscriptStatus != "ready")
        return Results.Conflict(new { error = "The transcript is not ready." });

    var text = BuildTranscriptFile(audio);
    var preamble = Encoding.UTF8.GetPreamble();
    var content = Encoding.UTF8.GetBytes(text);
    var bytes = new byte[preamble.Length + content.Length];
    Buffer.BlockCopy(preamble, 0, bytes, 0, preamble.Length);
    Buffer.BlockCopy(content, 0, bytes, preamble.Length, content.Length);
    var filename = $"{CleanFileBase(Path.GetFileNameWithoutExtension(audio.OriginalFilename))}_transcript.txt";
    return Results.File(bytes, "text/plain; charset=utf-8", filename);
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

app.MapPatch("/api/audio/bulk/lock", async (
    BulkLockRequest request, DashcamDbContext db, CancellationToken token) =>
{
    var ids = NormalizeBulkIds(request.Ids);
    if (ids is null) return Results.BadRequest(new { error = "ids must contain between 1 and 200 positive IDs." });
    var recordings = await db.AudioRecordings.Where(x => ids.Contains(x.Id)).ToListAsync(token);
    foreach (var recording in recordings) recording.Locked = request.Locked;
    await db.SaveChangesAsync(token);
    var foundIds = recordings.Select(x => x.Id).ToHashSet();
    return Results.Ok(new
    {
        items = recordings.Select(ToAudioResponse).ToList(),
        notFoundIds = ids.Where(id => !foundIds.Contains(id)).ToList()
    });
});

app.MapDelete("/api/audio/bulk", async (
    [FromBody] BulkIdsRequest request, DashcamDbContext db, CancellationToken token) =>
{
    var ids = NormalizeBulkIds(request.Ids);
    if (ids is null) return Results.BadRequest(new { error = "ids must contain between 1 and 200 positive IDs." });
    var recordings = await db.AudioRecordings.Where(x => ids.Contains(x.Id)).ToListAsync(token);
    var deletedIds = new List<int>();
    var failedIds = new List<int>();
    foreach (var recording in recordings)
    {
        if (!TryDelete(recording.FilePath))
        {
            failedIds.Add(recording.Id);
            continue;
        }
        TryDeleteWaveformCaches(recording.FilePath);
        db.AudioRecordings.Remove(recording);
        deletedIds.Add(recording.Id);
    }
    await db.SaveChangesAsync(token);
    var foundIds = recordings.Select(x => x.Id).ToHashSet();
    return Results.Ok(new
    {
        deletedIds,
        failedIds,
        notFoundIds = ids.Where(id => !foundIds.Contains(id)).ToList()
    });
});

app.MapPost("/api/audio-exports", async (
    AudioExportRequest request,
    DashcamDbContext db,
    CancellationToken token) =>
{
    CleanupAudioExportJobs(audioExportJobs);
    var requestedIds = request.Ids?.ToArray() ?? Array.Empty<int>();
    if (requestedIds.Length is < 1 or > 200 || requestedIds.Any(id => id <= 0))
        return Results.BadRequest(new { error = "ids must contain between 1 and 200 positive audio IDs." });
    if (requestedIds.Distinct().Count() != requestedIds.Length)
        return Results.BadRequest(new { error = "ids must not contain duplicate audio IDs." });

    var recordings = await db.AudioRecordings.AsNoTracking()
        .Where(recording => requestedIds.Contains(recording.Id))
        .OrderBy(recording => recording.StartTime)
        .ToListAsync(token);
    if (recordings.Count != requestedIds.Length)
        return Results.BadRequest(new { error = "One or more selected audio recordings no longer exist." });
    if (recordings.Any(recording => !File.Exists(recording.FilePath)))
        return Results.NotFound(new { error = "One or more selected audio files are missing." });
    if (recordings.Zip(recordings.Skip(1), (older, newer) => AudioGapSeconds(newer, older)).Any(gap => gap > 5))
        return Results.BadRequest(new { error = "The selected audio recordings do not form one continuous session." });

    var first = AsUtc(recordings[0].StartTime);
    var last = AsUtc(recordings[^1].EndTime);
    var filename = recordings.Count == 1
        ? Path.ChangeExtension(recordings[0].OriginalFilename, ".m4a")
        : $"dashcam_audio_session_{first:yyyyMMdd_HHmmss}_{last:HHmmss}.m4a";
    var jobId = Guid.NewGuid();
    var job = new AudioExportJob(
        jobId,
        "queued",
        null,
        filename,
        null,
        DateTime.UtcNow,
        null);
    audioExportJobs[jobId] = job;

    _ = Task.Run(async () =>
    {
        await sessionExportGate.WaitAsync();
        audioExportJobs.AddOrUpdate(
            jobId,
            job with { Status = "processing" },
            (_, current) => current with { Status = "processing" });
        try
        {
            var exportPath = await CreateAudioSessionDownloadAsync(recordings, CancellationToken.None);
            audioExportJobs.AddOrUpdate(
                jobId,
                job with { Status = "ready", ExportPath = exportPath, CompletedAtUtc = DateTime.UtcNow },
                (_, current) => current with
                {
                    Status = "ready",
                    ExportPath = exportPath,
                    CompletedAtUtc = DateTime.UtcNow
                });
        }
        catch (Exception exception)
        {
            app.Logger.LogError(exception, "Audio export job {AudioExportJobId} failed", jobId);
            audioExportJobs.AddOrUpdate(
                jobId,
                job with { Status = "failed", Error = "Audio export failed.", CompletedAtUtc = DateTime.UtcNow },
                (_, current) => current with
                {
                    Status = "failed",
                    Error = "Audio export failed.",
                    CompletedAtUtc = DateTime.UtcNow
                });
        }
        finally
        {
            sessionExportGate.Release();
        }
    });

    return Results.Accepted($"/api/audio-exports/{jobId}", ToAudioExportJobResponse(job));
});

app.MapGet("/api/audio-exports/{jobId:guid}", (Guid jobId) =>
{
    CleanupAudioExportJobs(audioExportJobs);
    return audioExportJobs.TryGetValue(jobId, out var job)
        ? Results.Ok(ToAudioExportJobResponse(job))
        : Results.NotFound(new { error = "Audio export no longer exists." });
});

app.MapGet("/api/audio-exports/{jobId:guid}/download", (
    Guid jobId,
    HttpContext context) =>
{
    if (!audioExportJobs.TryRemove(jobId, out var job))
        return Results.NotFound(new { error = "Audio export no longer exists." });
    if (job.Status != "ready" || job.ExportPath is null)
    {
        audioExportJobs.TryAdd(jobId, job);
        return Results.Conflict(new { error = "Audio export is not ready yet." });
    }
    if (!File.Exists(job.ExportPath))
        return Results.NotFound(new { error = "The generated audio file is missing." });

    context.Response.Headers.CacheControl = "no-store, no-cache, must-revalidate";
    context.Response.Headers.Pragma = "no-cache";
    context.Response.Headers.Expires = "0";
    try
    {
        var stream = new FileStream(
            job.ExportPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read | FileShare.Delete,
            64 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan | FileOptions.DeleteOnClose);
        return Results.Stream(stream, "audio/mp4", job.Filename, enableRangeProcessing: true);
    }
    catch
    {
        TryDelete(job.ExportPath);
        throw;
    }
});

app.MapPost("/api/video-exports", async (
    VideoExportRequest request,
    DashcamDbContext db,
    CancellationToken token) =>
{
    CleanupVideoExportJobs(videoExportJobs);
    var requestedIds = request.Ids?.ToArray() ?? Array.Empty<int>();
    if (requestedIds.Length is < 1 or > 200 || requestedIds.Any(id => id <= 0))
        return Results.BadRequest(new { error = "ids must contain between 1 and 200 positive video IDs." });
    if (requestedIds.Distinct().Count() != requestedIds.Length)
        return Results.BadRequest(new { error = "ids must not contain duplicate video IDs." });

    var videos = await db.Videos.AsNoTracking()
        .Where(video => requestedIds.Contains(video.Id))
        .OrderBy(video => video.StartTime)
        .ToListAsync(token);
    if (videos.Count != requestedIds.Length)
        return Results.BadRequest(new { error = "One or more selected videos no longer exist." });
    if (videos.Any(video => !File.Exists(video.FilePath)))
        return Results.NotFound(new { error = "One or more selected video files are missing." });
    if (videos.Zip(videos.Skip(1), (older, newer) => VideoGapSeconds(newer, older)).Any(gap => gap > 10))
        return Results.BadRequest(new { error = "The selected videos do not form one continuous session." });

    var timezoneOffset = request.WithTimestamp
        ? Math.Clamp(request.TimezoneOffsetMinutes, -14 * 60, 14 * 60)
        : (int?)null;
    var first = AsUtc(videos[0].StartTime);
    var last = AsUtc(videos[^1].EndTime);
    var suffix = request.WithTimestamp ? "_with_time" : string.Empty;
    var filename = videos.Count == 1
        ? $"{Path.GetFileNameWithoutExtension(videos[0].OriginalFilename)}{suffix}.mp4"
        : $"dashcam_session_{first:yyyyMMdd_HHmmss}_{last:HHmmss}{suffix}.mp4";
    var jobId = Guid.NewGuid();
    var job = new VideoExportJob(
        jobId,
        "queued",
        null,
        filename,
        null,
        DateTime.UtcNow,
        null);
    videoExportJobs[jobId] = job;

    _ = Task.Run(async () =>
    {
        await sessionExportGate.WaitAsync();
        videoExportJobs.AddOrUpdate(
            jobId,
            job with { Status = "processing" },
            (_, current) => current with { Status = "processing" });
        try
        {
            var exportPath = await CreateVideoSessionDownloadAsync(videos, timezoneOffset, CancellationToken.None);
            videoExportJobs.AddOrUpdate(
                jobId,
                job with { Status = "ready", ExportPath = exportPath, CompletedAtUtc = DateTime.UtcNow },
                (_, current) => current with
                {
                    Status = "ready",
                    ExportPath = exportPath,
                    CompletedAtUtc = DateTime.UtcNow
                });
        }
        catch (Exception exception)
        {
            app.Logger.LogError(exception, "Video export job {VideoExportJobId} failed", jobId);
            videoExportJobs.AddOrUpdate(
                jobId,
                job with { Status = "failed", Error = "Video export failed.", CompletedAtUtc = DateTime.UtcNow },
                (_, current) => current with
                {
                    Status = "failed",
                    Error = "Video export failed.",
                    CompletedAtUtc = DateTime.UtcNow
                });
        }
        finally
        {
            sessionExportGate.Release();
        }
    });

    return Results.Accepted($"/api/video-exports/{jobId}", ToVideoExportJobResponse(job));
});

app.MapGet("/api/video-exports/{jobId:guid}", (Guid jobId) =>
{
    CleanupVideoExportJobs(videoExportJobs);
    return videoExportJobs.TryGetValue(jobId, out var job)
        ? Results.Ok(ToVideoExportJobResponse(job))
        : Results.NotFound(new { error = "Video export no longer exists." });
});

app.MapGet("/api/video-exports/{jobId:guid}/download", (
    Guid jobId,
    HttpContext context) =>
{
    if (!videoExportJobs.TryRemove(jobId, out var job))
        return Results.NotFound(new { error = "Video export no longer exists." });
    if (job.Status != "ready" || job.ExportPath is null)
    {
        videoExportJobs.TryAdd(jobId, job);
        return Results.Conflict(new { error = "Video export is not ready yet." });
    }
    if (!File.Exists(job.ExportPath))
        return Results.NotFound(new { error = "The generated video file is missing." });

    context.Response.Headers.CacheControl = "no-store, no-cache, must-revalidate";
    context.Response.Headers.Pragma = "no-cache";
    context.Response.Headers.Expires = "0";
    try
    {
        var stream = new FileStream(
            job.ExportPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read | FileShare.Delete,
            64 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan | FileOptions.DeleteOnClose);
        return Results.Stream(stream, "video/mp4", job.Filename, enableRangeProcessing: true);
    }
    catch
    {
        TryDelete(job.ExportPath);
        throw;
    }
});

app.MapGet("/api/videos/session/download", async (
    string ids,
    bool? withTimestamp,
    int? timezoneOffsetMinutes,
    HttpContext context,
    DashcamDbContext db,
    CancellationToken token) =>
{
    var idValues = ids.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    if (idValues.Length == 0 || idValues.Length > 200 ||
        idValues.Any(value => !int.TryParse(value, out var id) || id <= 0))
        return Results.BadRequest(new { error = "ids must contain between 1 and 200 positive video IDs." });
    var requestedIds = idValues.Select(int.Parse).Distinct().ToArray();
    if (requestedIds.Length != idValues.Length)
        return Results.BadRequest(new { error = "ids must not contain duplicate video IDs." });

    var videos = await db.Videos.AsNoTracking()
        .Where(video => requestedIds.Contains(video.Id))
        .OrderBy(video => video.StartTime)
        .ToListAsync(token);
    if (videos.Count != requestedIds.Length)
        return Results.BadRequest(new { error = "One or more selected videos no longer exist." });
    if (videos.Any(video => !File.Exists(video.FilePath)))
        return Results.NotFound(new { error = "One or more selected video files are missing." });
    if (videos.Zip(videos.Skip(1), (older, newer) => VideoGapSeconds(newer, older)).Any(gap => gap > 10))
        return Results.BadRequest(new { error = "The selected videos do not form one continuous session." });

    context.Response.Headers.CacheControl = "no-store, no-cache, must-revalidate";
    context.Response.Headers.Pragma = "no-cache";
    context.Response.Headers.Expires = "0";

    await sessionExportGate.WaitAsync(token);
    string exportPath;
    try
    {
        var timestampOffset = withTimestamp == true
            ? Math.Clamp(timezoneOffsetMinutes ?? 0, -14 * 60, 14 * 60)
            : (int?)null;
        exportPath = await CreateVideoSessionDownloadAsync(videos, timestampOffset, token);
    }
    finally
    {
        sessionExportGate.Release();
    }

    try
    {
        var stream = new FileStream(
            exportPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read | FileShare.Delete,
            64 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan | FileOptions.DeleteOnClose);
        var first = AsUtc(videos[0].StartTime);
        var last = AsUtc(videos[^1].EndTime);
        var suffix = withTimestamp == true ? "_with_time" : string.Empty;
        var filename = $"dashcam_session_{first:yyyyMMdd_HHmmss}_{last:HHmmss}{suffix}.mp4";
        return Results.Stream(stream, "video/mp4", filename, enableRangeProcessing: true);
    }
    catch
    {
        TryDelete(exportPath);
        throw;
    }
});

app.MapGet("/api/videos/{id:int}/download-with-time", async (
    int id,
    int? timezoneOffsetMinutes,
    HttpContext context,
    DashcamDbContext db,
    CancellationToken token) =>
{
    context.Response.Headers.CacheControl = "no-store, no-cache, must-revalidate";
    context.Response.Headers.Pragma = "no-cache";
    context.Response.Headers.Expires = "0";
    var video = await db.Videos.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    if (video is null) return Results.NotFound();
    if (!File.Exists(video.FilePath)) return Results.NotFound(new { error = "Video file is missing." });

    await sessionExportGate.WaitAsync(token);
    string exportPath;
    try
    {
        var timestampOffset = Math.Clamp(timezoneOffsetMinutes ?? 0, -14 * 60, 14 * 60);
        exportPath = await CreateVideoSessionDownloadAsync(new[] { video }, timestampOffset, token);
    }
    finally
    {
        sessionExportGate.Release();
    }

    try
    {
        var stream = new FileStream(
            exportPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read | FileShare.Delete,
            64 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan | FileOptions.DeleteOnClose);
        var filename = $"{Path.GetFileNameWithoutExtension(video.OriginalFilename)}_with_time.mp4";
        return Results.Stream(stream, "video/mp4", filename, enableRangeProcessing: true);
    }
    catch
    {
        TryDelete(exportPath);
        throw;
    }
});

app.MapGet("/api/videos/{id:int}/stream", async (int id, DashcamDbContext db, CancellationToken token) =>
{
    var video = await db.Videos.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    if (video is null) return Results.NotFound();
    if (!File.Exists(video.FilePath)) return Results.NotFound(new { error = "Video file is missing." });
    return Results.File(video.FilePath, "video/mp4", enableRangeProcessing: true);
});

app.MapGet("/api/videos/{id:int}/download", async (
    int id, HttpContext context, DashcamDbContext db, CancellationToken token) =>
{
    context.Response.Headers.CacheControl = "no-store, no-cache, must-revalidate";
    context.Response.Headers.Pragma = "no-cache";
    context.Response.Headers.Expires = "0";
    var video = await db.Videos.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id, token);
    if (video is null) return Results.NotFound();
    if (!File.Exists(video.FilePath)) return Results.NotFound(new { error = "Video file is missing." });
    if (video.PlaybackRotationDegrees == 0)
        return Results.File(video.FilePath, "video/mp4", video.OriginalFilename, enableRangeProcessing: true);

    var rotatedPath = await CreateRotationAwareDownloadAsync(video, token);
    try
    {
        var stream = new FileStream(
            rotatedPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read | FileShare.Delete,
            64 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan | FileOptions.DeleteOnClose);
        return Results.Stream(stream, "video/mp4", video.OriginalFilename, enableRangeProcessing: true);
    }
    catch
    {
        TryDelete(rotatedPath);
        throw;
    }
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

app.MapPatch("/api/videos/bulk/lock", async (
    BulkLockRequest request, DashcamDbContext db, CancellationToken token) =>
{
    var ids = NormalizeBulkIds(request.Ids);
    if (ids is null) return Results.BadRequest(new { error = "ids must contain between 1 and 200 positive IDs." });
    var videos = await db.Videos.Where(x => ids.Contains(x.Id)).ToListAsync(token);
    foreach (var video in videos) video.Locked = request.Locked;
    await db.SaveChangesAsync(token);
    var foundIds = videos.Select(x => x.Id).ToHashSet();
    return Results.Ok(new
    {
        items = videos.Select(ToResponse).ToList(),
        notFoundIds = ids.Where(id => !foundIds.Contains(id)).ToList()
    });
});

app.MapPatch("/api/videos/bulk/rotation", async (
    BulkRotationRequest request, DashcamDbContext db, CancellationToken token) =>
{
    var ids = NormalizeBulkIds(request.Ids);
    if (ids is null) return Results.BadRequest(new { error = "ids must contain between 1 and 200 positive IDs." });
    if (!IsValidRotation(request.PlaybackRotationDegrees))
        return Results.BadRequest(new { error = "playbackRotationDegrees must be 0, 90, 180 or 270." });
    var videos = await db.Videos.Where(x => ids.Contains(x.Id)).ToListAsync(token);
    foreach (var video in videos) video.PlaybackRotationDegrees = request.PlaybackRotationDegrees;
    await db.SaveChangesAsync(token);
    var foundIds = videos.Select(x => x.Id).ToHashSet();
    return Results.Ok(new
    {
        items = videos.Select(ToResponse).ToList(),
        notFoundIds = ids.Where(id => !foundIds.Contains(id)).ToList()
    });
});

app.MapDelete("/api/videos/bulk", async (
    [FromBody] BulkIdsRequest request, DashcamDbContext db, CancellationToken token) =>
{
    var ids = NormalizeBulkIds(request.Ids);
    if (ids is null) return Results.BadRequest(new { error = "ids must contain between 1 and 200 positive IDs." });
    var videos = await db.Videos.Where(x => ids.Contains(x.Id)).ToListAsync(token);
    var deletedIds = new List<int>();
    var failedIds = new List<int>();
    foreach (var video in videos)
    {
        if (!TryDelete(video.FilePath))
        {
            failedIds.Add(video.Id);
            continue;
        }
        db.Videos.Remove(video);
        deletedIds.Add(video.Id);
    }
    await db.SaveChangesAsync(token);
    var foundIds = videos.Select(x => x.Id).ToHashSet();
    return Results.Ok(new
    {
        deletedIds,
        failedIds,
        notFoundIds = ids.Where(id => !foundIds.Contains(id)).ToList()
    });
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
    var cleanup = await CleanupVideosAsync(db, config, videoCleanupGate, token);
    return Results.Ok(new
    {
        cleanup.RemovedCount,
        cleanup.RemovedBytes,
        cleanup.TotalSizeBytes,
        cleanup.MaxStorageBytes
    });
});

app.MapPost("/api/audio/cleanup", async (DashcamDbContext db, IConfiguration config, CancellationToken token) =>
{
    var cleanup = await CleanupAudioAsync(db, config, audioCleanupGate, token);
    return Results.Ok(new
    {
        cleanup.RemovedCount,
        cleanup.RemovedBytes,
        cleanup.TotalSizeBytes,
        cleanup.MaxStorageBytes
    });
});

app.MapGet("/api/admin/migration/status", (ArchiveMigrationService migration) =>
    Results.Ok(migration.GetStatus()));

app.MapPost("/api/admin/migration/upload/session", async (
    MigrationUploadSessionRequest request,
    MigrationUploadService uploads,
    CancellationToken token) =>
{
    var result = await uploads.CreateOrResumeAsync(request, token);
    return result.Success ? Results.Ok(result.Status) : Results.BadRequest(new { error = result.Error });
});

app.MapPut("/api/admin/migration/upload/{sessionId}/chunk", async (
    string sessionId,
    string? path,
    long offset,
    HttpRequest request,
    MigrationUploadService uploads,
    CancellationToken token) =>
{
    var result = await uploads.UploadChunkAsync(sessionId, path, offset, request.Body, request.ContentLength, token);
    if (result.Success) return Results.Ok(result);
    return result.ExpectedOffset.HasValue
        ? Results.Conflict(result)
        : Results.BadRequest(new { error = result.Error });
});

app.MapPost("/api/admin/migration/upload/{sessionId}/complete", async (
    string sessionId,
    MigrationUploadService uploads,
    CancellationToken token) =>
{
    var result = await uploads.CompleteAsync(sessionId, token);
    return result.Success
        ? Results.Accepted("/api/admin/migration/status", result.Status)
        : Results.Conflict(new { error = result.Error });
});

app.MapPost("/api/admin/migration/start", (MigrationStartRequest request, ArchiveMigrationService migration) =>
{
    var result = migration.BeginImport(request.AllowOverCapacity);
    return result.Started
        ? Results.Accepted("/api/admin/migration/status", migration.GetStatus())
        : Results.Conflict(new { error = result.Error });
});

app.MapPost("/api/admin/migration/cancel", (ArchiveMigrationService migration) =>
{
    var result = migration.Cancel();
    return result.Started
        ? Results.Accepted("/api/admin/migration/status", migration.GetStatus())
        : Results.Conflict(new { error = result.Error });
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
    var maxGb = config.GetValue<double?>("MaxStorageGB") ?? 280;
    return (long)(Math.Max(0.1, maxGb) * 1024 * 1024 * 1024);
}

static async Task<VideoCleanupResult> CleanupVideosAsync(
    DashcamDbContext db,
    IConfiguration config,
    SemaphoreSlim cleanupGate,
    CancellationToken token)
{
    await cleanupGate.WaitAsync(token);
    try
    {
        var maxBytes = GetMaxStorageBytes(config);
        var totalBytes = await db.Videos.SumAsync(x => (long?)x.FileSizeBytes, token) ?? 0;
        var removedCount = 0;
        var removedBytes = 0L;

        if (totalBytes > maxBytes)
        {
            var candidates = await db.Videos
                .Where(x => !x.Locked)
                .OrderBy(x => x.StartTime)
                .ThenBy(x => x.Id)
                .ToListAsync(token);

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

        return new VideoCleanupResult(removedCount, removedBytes, totalBytes, maxBytes);
    }
    finally
    {
        cleanupGate.Release();
    }
}

static async Task<VideoCleanupResult> CleanupAudioAsync(
    DashcamDbContext db,
    IConfiguration config,
    SemaphoreSlim cleanupGate,
    CancellationToken token)
{
    await cleanupGate.WaitAsync(token);
    try
    {
        var maxBytes = GetMaxAudioStorageBytes(config);
        var totalBytes = await db.AudioRecordings.SumAsync(x => (long?)x.FileSizeBytes, token) ?? 0;
        var removedCount = 0;
        var removedBytes = 0L;

        if (totalBytes > maxBytes)
        {
            var candidates = await db.AudioRecordings
                .Where(x => !x.Locked)
                .OrderBy(x => x.StartTime)
                .ThenBy(x => x.Id)
                .ToListAsync(token);

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

        return new VideoCleanupResult(removedCount, removedBytes, totalBytes, maxBytes);
    }
    finally
    {
        cleanupGate.Release();
    }
}

static long GetMaxAudioStorageBytes(IConfiguration config)
{
    var maxGb = config.GetValue<double?>("MaxAudioStorageGB") ?? 20;
    return (long)(Math.Max(0.1, maxGb) * 1024 * 1024 * 1024);
}

static async Task<Video?> FindExactVideoDuplicateAsync(
    DashcamDbContext db,
    string originalFilename,
    DateTime startTime,
    DateTime endTime,
    int durationSeconds,
    long fileSizeBytes,
    string uploadedPath,
    CancellationToken token)
{
    var candidates = await db.Videos.AsNoTracking()
        .Where(x => x.OriginalFilename == originalFilename &&
            x.StartTime == startTime &&
            x.EndTime == endTime &&
            x.DurationSeconds == durationSeconds &&
            x.FileSizeBytes == fileSizeBytes)
        .OrderBy(x => x.Id)
        .ToListAsync(token);
    return await FindExactDuplicateAsync(candidates, x => x.FilePath, uploadedPath, token);
}

static async Task<AudioRecording?> FindExactAudioDuplicateAsync(
    DashcamDbContext db,
    string originalFilename,
    DateTime startTime,
    DateTime endTime,
    int durationSeconds,
    long fileSizeBytes,
    string uploadedPath,
    CancellationToken token)
{
    var candidates = await db.AudioRecordings.AsNoTracking()
        .Where(x => x.OriginalFilename == originalFilename &&
            x.StartTime == startTime &&
            x.EndTime == endTime &&
            x.DurationSeconds == durationSeconds &&
            x.FileSizeBytes == fileSizeBytes)
        .OrderBy(x => x.Id)
        .ToListAsync(token);
    return await FindExactDuplicateAsync(candidates, x => x.FilePath, uploadedPath, token);
}

static async Task<T?> FindExactDuplicateAsync<T>(
    IReadOnlyCollection<T> candidates,
    Func<T, string> pathSelector,
    string uploadedPath,
    CancellationToken token) where T : class
{
    if (candidates.Count == 0) return null;
    var uploadedInfo = new FileInfo(uploadedPath);
    byte[]? uploadedHash = null;
    foreach (var candidate in candidates)
    {
        var candidatePath = pathSelector(candidate);
        if (!File.Exists(candidatePath) || new FileInfo(candidatePath).Length != uploadedInfo.Length) continue;
        uploadedHash ??= await ComputeSha256Async(uploadedPath, token);
        var candidateHash = await ComputeSha256Async(candidatePath, token);
        if (CryptographicOperations.FixedTimeEquals(uploadedHash, candidateHash)) return candidate;
    }
    return null;
}

static async Task<byte[]> ComputeSha256Async(string path, CancellationToken token)
{
    await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, true);
    return await SHA256.HashDataAsync(stream, token);
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

static int NormalizeRotation(int degrees)
{
    var normalized = degrees % 360;
    return normalized < 0 ? normalized + 360 : normalized;
}

static double VideoGapSeconds(Video newer, Video older) =>
    Math.Max(0, (newer.StartTime - older.EndTime).TotalSeconds);

static double AudioGapSeconds(AudioRecording newer, AudioRecording older) =>
    Math.Max(0, (newer.StartTime - older.EndTime).TotalSeconds);

static object ToVideoExportJobResponse(VideoExportJob job) => new
{
    jobId = job.Id,
    status = job.Status,
    error = job.Error,
    filename = job.Filename,
    downloadUrl = job.Status == "ready" ? $"/api/video-exports/{job.Id}/download" : null
};

static object ToAudioExportJobResponse(AudioExportJob job) => new
{
    jobId = job.Id,
    status = job.Status,
    error = job.Error,
    filename = job.Filename,
    downloadUrl = job.Status == "ready" ? $"/api/audio-exports/{job.Id}/download" : null
};

static void CleanupVideoExportJobs(ConcurrentDictionary<Guid, VideoExportJob> jobs)
{
    var cutoff = DateTime.UtcNow.AddMinutes(-30);
    foreach (var pair in jobs)
    {
        var job = pair.Value;
        if (job.Status is not ("ready" or "failed") ||
            (job.CompletedAtUtc ?? job.CreatedAtUtc) >= cutoff ||
            !jobs.TryRemove(pair.Key, out var removed))
        {
            continue;
        }
        if (removed.ExportPath is not null) TryDelete(removed.ExportPath);
    }
}

static void CleanupAudioExportJobs(ConcurrentDictionary<Guid, AudioExportJob> jobs)
{
    var cutoff = DateTime.UtcNow.AddMinutes(-30);
    foreach (var pair in jobs)
    {
        var job = pair.Value;
        if (job.Status is not ("ready" or "failed") ||
            (job.CompletedAtUtc ?? job.CreatedAtUtc) >= cutoff ||
            !jobs.TryRemove(pair.Key, out var removed))
        {
            continue;
        }
        if (removed.ExportPath is not null) TryDelete(removed.ExportPath);
    }
}

static async Task<string> CreateAudioSessionDownloadAsync(
    IReadOnlyList<AudioRecording> recordings,
    CancellationToken token)
{
    var temporaryDirectory = Path.Combine(
        Path.GetDirectoryName(recordings[0].FilePath) ?? Path.GetTempPath(),
        ".session-download-temp");
    Directory.CreateDirectory(temporaryDirectory);
    var temporaryPath = Path.Combine(temporaryDirectory, $"audio-session-{Guid.NewGuid():N}.m4a");
    var arguments = new List<string> { "-v", "error", "-y" };
    foreach (var recording in recordings)
    {
        arguments.Add("-i");
        arguments.Add(recording.FilePath);
    }

    var filters = new List<string>();
    var concatInputs = new List<string>();
    var segmentCount = 0;
    for (var index = 0; index < recordings.Count; index++)
    {
        filters.Add(
            $"[{index}:a:0]asetpts=PTS-STARTPTS," +
            $"aresample=44100,aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[a{index}]");
        concatInputs.Add($"[a{index}]");
        segmentCount++;

        if (index >= recordings.Count - 1) continue;
        var gapSeconds = AudioGapSeconds(recordings[index + 1], recordings[index]);
        if (gapSeconds <= 0) continue;
        var gapDuration = FormatFfmpegSeconds(gapSeconds);
        filters.Add($"anullsrc=channel_layout=stereo:sample_rate=44100:d={gapDuration}[gap{index}]");
        concatInputs.Add($"[gap{index}]");
        segmentCount++;
    }
    filters.Add($"{string.Concat(concatInputs)}concat=n={segmentCount}:v=0:a=1[outa]");
    arguments.AddRange(new[]
    {
        "-filter_complex", string.Join(';', filters),
        "-map", "[outa]",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart", temporaryPath
    });

    try
    {
        await RunMediaToolAsync("ffmpeg", arguments, token);
        if (!File.Exists(temporaryPath) || new FileInfo(temporaryPath).Length == 0)
            throw new InvalidOperationException("Session export did not produce an audio file.");
        return temporaryPath;
    }
    catch
    {
        TryDelete(temporaryPath);
        throw;
    }
}

static async Task<string> CreateVideoSessionDownloadAsync(
    IReadOnlyList<Video> videos,
    int? timestampTimezoneOffsetMinutes,
    CancellationToken token)
{
    const int outputWidth = 1280;
    const int outputHeight = 720;
    const int timestampBarHeight = 80;
    const int outputFrameRate = 30;
    var media = new List<VideoProbeInfo>(videos.Count);
    foreach (var video in videos)
        media.Add(await ReadVideoProbeInfoAsync(video.FilePath, video.DurationSeconds, token));

    var temporaryDirectory = Path.Combine(
        Path.GetDirectoryName(videos[0].FilePath) ?? Path.GetTempPath(),
        ".session-download-temp");
    Directory.CreateDirectory(temporaryDirectory);
    var temporaryPath = Path.Combine(temporaryDirectory, $"session-{Guid.NewGuid():N}.mp4");
    var subtitlePath = timestampTimezoneOffsetMinutes.HasValue
        ? Path.Combine(temporaryDirectory, $"session-{Guid.NewGuid():N}.srt")
        : null;
    var arguments = new List<string> { "-v", "error", "-y" };
    foreach (var video in videos)
    {
        arguments.Add("-i");
        arguments.Add(video.FilePath);
    }

    var filters = new List<string>();
    var concatInputs = new List<string>();
    var timestampSegments = new List<TimestampSegment>();
    var outputOffsetSeconds = 0d;
    var segmentCount = 0;
    for (var index = 0; index < videos.Count; index++)
    {
        var duration = FormatFfmpegSeconds(media[index].DurationSeconds);
        var contentHeight = timestampTimezoneOffsetMinutes.HasValue
            ? outputHeight - timestampBarHeight
            : outputHeight;
        var verticalOffset = timestampTimezoneOffsetMinutes.HasValue ? "0" : "(oh-ih)/2";
        var rotation = videos[index].PlaybackRotationDegrees switch
        {
            90 => ",transpose=clock",
            180 => ",hflip,vflip",
            270 => ",transpose=cclock",
            _ => string.Empty
        };
        filters.Add(
            $"[{index}:v:0]trim=duration={duration},setpts=PTS-STARTPTS{rotation}," +
            $"scale={outputWidth}:{contentHeight}:force_original_aspect_ratio=decrease," +
            $"pad={outputWidth}:{outputHeight}:(ow-iw)/2:{verticalOffset}:black," +
            $"setsar=1,fps={outputFrameRate},format=yuv420p[v{index}]");
        if (media[index].HasAudio)
        {
            filters.Add(
                $"[{index}:a:0]atrim=duration={duration},asetpts=PTS-STARTPTS," +
                $"aresample=44100,aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[a{index}]");
        }
        else
        {
            filters.Add($"anullsrc=channel_layout=stereo:sample_rate=44100:d={duration}[a{index}]");
        }
        concatInputs.Add($"[v{index}][a{index}]");
        timestampSegments.Add(new TimestampSegment(
            outputOffsetSeconds,
            media[index].DurationSeconds,
            AsUtc(videos[index].StartTime)));
        outputOffsetSeconds += media[index].DurationSeconds;
        segmentCount++;

        if (index >= videos.Count - 1) continue;
        var gapSeconds = VideoGapSeconds(videos[index + 1], videos[index]);
        if (gapSeconds <= 0) continue;
        var gapDuration = FormatFfmpegSeconds(gapSeconds);
        filters.Add($"color=c=black:s={outputWidth}x{outputHeight}:r={outputFrameRate}:d={gapDuration}[gapv{index}]");
        filters.Add($"anullsrc=channel_layout=stereo:sample_rate=44100:d={gapDuration}[gapa{index}]");
        concatInputs.Add($"[gapv{index}][gapa{index}]");
        timestampSegments.Add(new TimestampSegment(
            outputOffsetSeconds,
            gapSeconds,
            AsUtc(videos[index].EndTime)));
        outputOffsetSeconds += gapSeconds;
        segmentCount++;
    }
    var concatVideoOutput = subtitlePath is null ? "[outv]" : "[sessionv]";
    filters.Add($"{string.Concat(concatInputs)}concat=n={segmentCount}:v=1:a=1{concatVideoOutput}[outa]");
    if (subtitlePath is not null)
    {
        await File.WriteAllTextAsync(
            subtitlePath,
            CreateTimestampSubtitles(timestampSegments, timestampTimezoneOffsetMinutes!.Value),
            Encoding.UTF8,
            token);
        var subtitleFilterPath = EscapeFfmpegFilterValue(subtitlePath);
        filters.Add(
            $"[sessionv]subtitles=filename='{subtitleFilterPath}':" +
            "force_style='FontName=DejaVu Sans,FontSize=24,PrimaryColour=&H00FFFFFF," +
            "OutlineColour=&H00000000,BorderStyle=1,Outline=1,Shadow=0,Alignment=2,MarginV=11'[outv]");
    }

    arguments.AddRange(new[]
    {
        "-filter_complex", string.Join(';', filters),
        "-map", "[outv]", "-map", "[outa]",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
        "-c:a", "aac", "-b:a", "128k",
        "-metadata:s:v:0", "rotate=0",
        "-movflags", "+faststart", temporaryPath
    });

    try
    {
        await RunMediaToolAsync("ffmpeg", arguments, token);
        if (!File.Exists(temporaryPath) || new FileInfo(temporaryPath).Length == 0)
            throw new InvalidOperationException("Session export did not produce a video file.");
        return temporaryPath;
    }
    catch
    {
        TryDelete(temporaryPath);
        throw;
    }
    finally
    {
        if (subtitlePath is not null) TryDelete(subtitlePath);
    }
}

static string CreateTimestampSubtitles(
    IEnumerable<TimestampSegment> segments,
    int timezoneOffsetMinutes)
{
    var builder = new StringBuilder();
    var cueNumber = 1;
    foreach (var segment in segments)
    {
        var elapsed = 0d;
        while (elapsed < segment.DurationSeconds - 0.0005)
        {
            var recordedAtUtc = segment.RecordedStartUtc.AddSeconds(elapsed);
            var fractionalSecond = recordedAtUtc.Ticks % TimeSpan.TicksPerSecond /
                (double)TimeSpan.TicksPerSecond;
            var untilNextSecond = fractionalSecond < 0.000001 ? 1d : 1d - fractionalSecond;
            var cueDuration = Math.Min(untilNextSecond, segment.DurationSeconds - elapsed);
            var localRecordedAt = recordedAtUtc.AddMinutes(-timezoneOffsetMinutes);
            builder.Append(cueNumber++).AppendLine();
            builder.Append(FormatSubtitleTime(segment.OutputStartSeconds + elapsed))
                .Append(" --> ")
                .Append(FormatSubtitleTime(segment.OutputStartSeconds + elapsed + cueDuration))
                .AppendLine();
            builder.Append(localRecordedAt.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture))
                .AppendLine().AppendLine();
            elapsed += cueDuration;
        }
    }
    return builder.ToString();
}

static string FormatSubtitleTime(double seconds)
{
    var totalMilliseconds = Math.Max(0L, (long)Math.Round(seconds * 1000));
    var time = TimeSpan.FromMilliseconds(totalMilliseconds);
    return $"{(long)time.TotalHours:00}:{time.Minutes:00}:{time.Seconds:00},{time.Milliseconds:000}";
}

static string EscapeFfmpegFilterValue(string value) => value
    .Replace("\\", "\\\\", StringComparison.Ordinal)
    .Replace(":", "\\:", StringComparison.Ordinal)
    .Replace("'", "\\'", StringComparison.Ordinal);

static async Task<VideoProbeInfo> ReadVideoProbeInfoAsync(
    string filePath,
    int fallbackDurationSeconds,
    CancellationToken token)
{
    var output = await RunMediaToolAsync("ffprobe", new[]
    {
        "-v", "error", "-show_entries", "format=duration:stream=codec_type", "-of", "json", filePath
    }, token);
    using var document = JsonDocument.Parse(output);
    var hasAudio = document.RootElement.TryGetProperty("streams", out var streams) &&
        streams.EnumerateArray().Any(stream =>
            stream.TryGetProperty("codec_type", out var type) && type.GetString() == "audio");
    var duration = Math.Max(0.001, fallbackDurationSeconds);
    if (document.RootElement.TryGetProperty("format", out var format) &&
        format.TryGetProperty("duration", out var durationValue) &&
        double.TryParse(durationValue.GetString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var probedDuration) &&
        probedDuration > 0)
    {
        duration = probedDuration;
    }
    return new VideoProbeInfo(duration, hasAudio);
}

static string FormatFfmpegSeconds(double seconds) =>
    Math.Max(0.001, seconds).ToString("0.###", CultureInfo.InvariantCulture);

static async Task<string> CreateRotationAwareDownloadAsync(Video video, CancellationToken token)
{
    var embeddedRotation = await ReadEmbeddedVideoRotationAsync(video.FilePath, token);
    // ffprobe reports display-matrix rotation counter-clockwise, while the dashboard's
    // CSS rotation is clockwise. Convert the dashboard adjustment into ffmpeg's sign.
    var downloadRotation = NormalizeRotation(embeddedRotation - video.PlaybackRotationDegrees);
    var temporaryDirectory = Path.Combine(
        Path.GetDirectoryName(video.FilePath) ?? Path.GetTempPath(),
        ".download-temp");
    Directory.CreateDirectory(temporaryDirectory);
    var temporaryPath = Path.Combine(
        temporaryDirectory,
        $"{Path.GetFileNameWithoutExtension(video.Filename)}-{Guid.NewGuid():N}.mp4");

    var arguments = new[]
    {
        "-v", "error", "-y", "-i", video.FilePath,
        "-map", "0", "-c", "copy",
        "-metadata:s:v:0", $"rotate={downloadRotation}",
        "-movflags", "+faststart", temporaryPath
    };

    try
    {
        await RunMediaToolAsync("ffmpeg", arguments, token);
        return temporaryPath;
    }
    catch
    {
        TryDelete(temporaryPath);
        throw;
    }
}

static async Task<int> ReadEmbeddedVideoRotationAsync(string filePath, CancellationToken token)
{
    var output = await RunMediaToolAsync("ffprobe", new[]
    {
        "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream_tags=rotate:stream_side_data=rotation",
        "-of", "json", filePath
    }, token);

    using var document = JsonDocument.Parse(output);
    if (!document.RootElement.TryGetProperty("streams", out var streams)) return 0;
    foreach (var stream in streams.EnumerateArray())
    {
        if (stream.TryGetProperty("side_data_list", out var sideData))
        {
            foreach (var entry in sideData.EnumerateArray())
            {
                if (entry.TryGetProperty("rotation", out var rotation) && rotation.TryGetInt32(out var degrees))
                    return NormalizeRotation(degrees);
            }
        }
        if (stream.TryGetProperty("tags", out var tags) &&
            tags.TryGetProperty("rotate", out var rotateTag) &&
            int.TryParse(rotateTag.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var tagDegrees))
        {
            return NormalizeRotation(tagDegrees);
        }
    }
    return 0;
}

static async Task<string> RunMediaToolAsync(
    string fileName,
    IEnumerable<string> arguments,
    CancellationToken token)
{
    var startInfo = new ProcessStartInfo
    {
        FileName = fileName,
        RedirectStandardOutput = true,
        RedirectStandardError = true,
        UseShellExecute = false,
        CreateNoWindow = true
    };
    foreach (var argument in arguments) startInfo.ArgumentList.Add(argument);

    using var process = new Process { StartInfo = startInfo };
    if (!process.Start()) throw new InvalidOperationException($"{fileName} could not be started.");
    var outputTask = process.StandardOutput.ReadToEndAsync(token);
    var errorTask = process.StandardError.ReadToEndAsync(token);
    try
    {
        await Task.WhenAll(outputTask, errorTask, process.WaitForExitAsync(token));
    }
    catch
    {
        if (!process.HasExited) process.Kill(true);
        throw;
    }
    var error = await errorTask;
    if (process.ExitCode != 0)
        throw new InvalidOperationException(string.IsNullOrWhiteSpace(error) ? $"{fileName} failed." : error.Trim());
    return await outputTask;
}

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
    await EnsureColumnAsync(db, "AudioRecordings", "TranscriptStatus", "TEXT NOT NULL DEFAULT 'none'");
    await EnsureColumnAsync(db, "AudioRecordings", "TranscriptText", "TEXT NOT NULL DEFAULT ''");
    await EnsureColumnAsync(db, "AudioRecordings", "TranscriptLanguage", "TEXT NOT NULL DEFAULT ''");
    await EnsureColumnAsync(db, "AudioRecordings", "TranscriptLanguageProbability", "REAL NOT NULL DEFAULT 0");
    await EnsureColumnAsync(db, "AudioRecordings", "TranscriptModel", "TEXT NOT NULL DEFAULT ''");
    await EnsureColumnAsync(db, "AudioRecordings", "TranscriptSegmentsJson", "TEXT NOT NULL DEFAULT ''");
    await EnsureColumnAsync(db, "AudioRecordings", "TranscriptError", "TEXT NOT NULL DEFAULT ''");
    await EnsureColumnAsync(db, "AudioRecordings", "TranscriptCreatedAt", "TEXT NULL");
}

static async Task EnsureDeviceStatusTableAsync(DashcamDbContext db)
{
    await db.Database.ExecuteSqlRawAsync("""
        CREATE TABLE IF NOT EXISTS DeviceStatuses (
            DeviceId TEXT NOT NULL CONSTRAINT PK_DeviceStatuses PRIMARY KEY,
            DeviceName TEXT NOT NULL,
            Manufacturer TEXT NOT NULL,
            Model TEXT NOT NULL,
            AndroidVersion TEXT NOT NULL,
            AppVersion TEXT NOT NULL,
            IpAddress TEXT NOT NULL DEFAULT '',
            BatteryLevel INTEGER NOT NULL,
            IsCharging INTEGER NOT NULL,
            ChargingSource TEXT NOT NULL,
            PowerSaveMode INTEGER NOT NULL,
            VideoRecordingActive INTEGER NOT NULL,
            AudioRecordingActive INTEGER NOT NULL,
            LiveAccessEnabled INTEGER NOT NULL DEFAULT 0,
            LiveRequested INTEGER NOT NULL DEFAULT 0,
            LiveStreaming INTEGER NOT NULL DEFAULT 0,
            LiveError TEXT NOT NULL DEFAULT '',
            LastSeenTransport TEXT NOT NULL DEFAULT 'http',
            LastSeenAt TEXT NOT NULL,
            FirstSeenAt TEXT NOT NULL
        )
        """);
    await db.Database.ExecuteSqlRawAsync(
        "CREATE INDEX IF NOT EXISTS IX_DeviceStatuses_LastSeenAt ON DeviceStatuses (LastSeenAt)");
    await EnsureColumnAsync(db, "DeviceStatuses", "LiveAccessEnabled", "INTEGER NOT NULL DEFAULT 0");
    await EnsureColumnAsync(db, "DeviceStatuses", "IpAddress", "TEXT NOT NULL DEFAULT ''");
    await EnsureColumnAsync(db, "DeviceStatuses", "LiveRequested", "INTEGER NOT NULL DEFAULT 0");
    await EnsureColumnAsync(db, "DeviceStatuses", "LiveStreaming", "INTEGER NOT NULL DEFAULT 0");
    await EnsureColumnAsync(db, "DeviceStatuses", "LiveError", "TEXT NOT NULL DEFAULT ''");
    await EnsureColumnAsync(db, "DeviceStatuses", "LastSeenTransport", "TEXT NOT NULL DEFAULT 'http'");
}

static async Task EnsureColumnAsync(DashcamDbContext db, string table, string column, string definition)
{
    var connection = db.Database.GetDbConnection();
    if (connection.State != System.Data.ConnectionState.Open) await connection.OpenAsync();
    await using var check = connection.CreateCommand();
    check.CommandText = $"PRAGMA table_info('{table}')";
    var exists = false;
    await using (var reader = await check.ExecuteReaderAsync())
    {
        while (await reader.ReadAsync())
        {
            if (string.Equals(reader.GetString(1), column, StringComparison.OrdinalIgnoreCase))
            {
                exists = true;
                break;
            }
        }
    }
    if (!exists)
    {
        await using var alter = connection.CreateCommand();
        alter.CommandText = $"ALTER TABLE {table} ADD COLUMN {column} {definition}";
        await alter.ExecuteNonQueryAsync();
    }
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

static async Task RunAudioTranscriptionAsync(
    int audioId,
    IServiceScopeFactory scopeFactory,
    IHttpClientFactory httpClientFactory,
    ILoggerFactory loggerFactory,
    SemaphoreSlim gate)
{
    await gate.WaitAsync();
    try
    {
        string audioPath;
        await using (var scope = scopeFactory.CreateAsyncScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<DashcamDbContext>();
            var audio = await db.AudioRecordings.SingleOrDefaultAsync(x => x.Id == audioId);
            if (audio is null || audio.TranscriptStatus != "queued") return;
            if (!File.Exists(audio.FilePath)) throw new FileNotFoundException("Audio file is missing.", audio.FilePath);
            audio.TranscriptStatus = "processing";
            audio.TranscriptError = string.Empty;
            await db.SaveChangesAsync();
            audioPath = audio.FilePath;
        }

        var client = httpClientFactory.CreateClient("TranscriptionWorker");
        using var response = await client.PostAsJsonAsync("transcribe", new { path = audioPath });
        if (!response.IsSuccessStatusCode)
        {
            var workerError = await response.Content.ReadAsStringAsync();
            throw new InvalidOperationException(
                string.IsNullOrWhiteSpace(workerError)
                    ? $"Transcription worker returned HTTP {(int)response.StatusCode}."
                    : workerError);
        }

        var result = await response.Content.ReadFromJsonAsync<AudioTranscriptionWorkerResponse>()
            ?? throw new InvalidOperationException("Transcription worker returned an empty response.");
        await using (var scope = scopeFactory.CreateAsyncScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<DashcamDbContext>();
            var audio = await db.AudioRecordings.SingleOrDefaultAsync(x => x.Id == audioId);
            if (audio is null) return;
            audio.TranscriptStatus = "ready";
            audio.TranscriptText = result.Text?.Trim() ?? string.Empty;
            audio.TranscriptLanguage = CleanOptionalText(result.Language, 24);
            audio.TranscriptLanguageProbability = Math.Clamp(result.LanguageProbability, 0, 1);
            audio.TranscriptModel = CleanOptionalText(result.Model, 80);
            audio.TranscriptSegmentsJson = JsonSerializer.Serialize(result.Segments ?? []);
            audio.TranscriptError = string.Empty;
            audio.TranscriptCreatedAt = DateTime.UtcNow;
            await db.SaveChangesAsync();
        }
    }
    catch (Exception error)
    {
        loggerFactory.CreateLogger("Dashcam.AudioTranscription").LogError(
            error,
            "Audio transcription failed for recording {AudioId}",
            audioId);
        try
        {
            await using var scope = scopeFactory.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<DashcamDbContext>();
            var audio = await db.AudioRecordings.SingleOrDefaultAsync(x => x.Id == audioId);
            if (audio is not null)
            {
                audio.TranscriptStatus = "failed";
                audio.TranscriptError = CleanOptionalText(error.Message, 1000);
                await db.SaveChangesAsync();
            }
        }
        catch (Exception statusError)
        {
            loggerFactory.CreateLogger("Dashcam.AudioTranscription").LogError(
                statusError,
                "Unable to persist the failed transcription status for recording {AudioId}",
                audioId);
        }
    }
    finally
    {
        gate.Release();
    }
}

static object ToTranscriptResponse(AudioRecording audio, bool includeText)
{
    IReadOnlyList<AudioTranscriptSegment> segments = [];
    if (includeText && !string.IsNullOrWhiteSpace(audio.TranscriptSegmentsJson))
    {
        try
        {
            segments = JsonSerializer.Deserialize<List<AudioTranscriptSegment>>(
                audio.TranscriptSegmentsJson,
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? [];
        }
        catch (JsonException)
        {
            segments = [];
        }
    }
    return new
    {
        audio.Id,
        status = audio.TranscriptStatus,
        text = includeText ? audio.TranscriptText : null,
        language = audio.TranscriptLanguage,
        languageProbability = audio.TranscriptLanguageProbability,
        model = audio.TranscriptModel,
        error = audio.TranscriptError,
        createdAt = audio.TranscriptCreatedAt.HasValue ? AsUtc(audio.TranscriptCreatedAt.Value) : (DateTime?)null,
        segments
    };
}

static string BuildTranscriptFile(AudioRecording audio)
{
    var language = string.IsNullOrWhiteSpace(audio.TranscriptLanguage) ? "Unknown" : audio.TranscriptLanguage;
    var probability = audio.TranscriptLanguageProbability > 0
        ? $" ({audio.TranscriptLanguageProbability:P0})"
        : string.Empty;
    return $"""
        Audio: {audio.OriginalFilename}
        Recorded: {AsUtc(audio.StartTime):yyyy-MM-dd HH:mm:ss} UTC
        Language: {language}{probability}
        Model: {audio.TranscriptModel}

        {audio.TranscriptText}
        """;
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
    transcriptStatus = audio.TranscriptStatus,
    transcriptLanguage = audio.TranscriptLanguage,
    transcriptLanguageProbability = audio.TranscriptLanguageProbability,
    transcriptModel = audio.TranscriptModel,
    transcriptError = audio.TranscriptError,
    transcriptCreatedAt = audio.TranscriptCreatedAt.HasValue ? AsUtc(audio.TranscriptCreatedAt.Value) : (DateTime?)null,
    UploadedAt = AsUtc(audio.UploadedAt),
    streamUrl = $"/api/audio/{audio.Id}/stream"
};

static object ToDeviceResponse(DeviceStatus device, DateTime now, bool? socketConnected = null)
{
    var httpOnline = string.Equals(device.LastSeenTransport, "http", StringComparison.OrdinalIgnoreCase) &&
        now - AsUtc(device.LastSeenAt) <= TimeSpan.FromSeconds(DeviceOnlineThresholdSeconds);
    var online = socketConnected == true || (socketConnected != true && httpOnline);
    return new
    {
    device.DeviceId,
    device.DeviceName,
    device.Manufacturer,
    device.Model,
    device.AndroidVersion,
    device.AppVersion,
    device.IpAddress,
    device.BatteryLevel,
    device.IsCharging,
    device.ChargingSource,
    device.PowerSaveMode,
    device.VideoRecordingActive,
    device.AudioRecordingActive,
    device.LiveAccessEnabled,
    device.LiveRequested,
    device.LiveStreaming,
    device.LiveError,
    Online = online,
    OnlineSource = socketConnected == true ? "websocket" : httpOnline ? "http" : null,
    LastSeenAt = AsUtc(device.LastSeenAt),
    FirstSeenAt = AsUtc(device.FirstSeenAt)
    };
}

static DeviceHeartbeatRequest? ParseSocketHeartbeat(string message)
{
    try
    {
        using var document = JsonDocument.Parse(message);
        var root = document.RootElement;
        if (!root.TryGetProperty("type", out var type) || type.GetString() != "device_status" ||
            !root.TryGetProperty("status", out var status))
            return null;
        return JsonSerializer.Deserialize<DeviceHeartbeatRequest>(status.GetRawText(), new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true
        });
    }
    catch (JsonException)
    {
        return null;
    }
}

static BatteryHistoryResponse? ParseBatteryHistoryResponse(string message)
{
    try
    {
        using var document = JsonDocument.Parse(message);
        var root = document.RootElement;
        if (!root.TryGetProperty("type", out var type) ||
            type.GetString() != "battery_history_response")
            return null;
        var response = JsonSerializer.Deserialize<BatteryHistoryResponse>(message, new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true
        });
        return response is { RequestId.Length: > 0 } && response.Items.Count <= 1_000
            ? response
            : null;
    }
    catch (JsonException)
    {
        return null;
    }
}

static async Task<DeviceStatus> ApplyDeviceHeartbeatAsync(
    DeviceHeartbeatRequest request,
    string deviceId,
    string deviceName,
    string? remoteIpAddress,
    string transport,
    DashcamDbContext db,
    LiveFrameStore liveFrames,
    CancellationToken cancellationToken)
{
    var now = DateTime.UtcNow;
    var ipAddress = NormalizeIpAddress(request.IpAddress) ?? remoteIpAddress ?? string.Empty;
    var device = await db.DeviceStatuses.FindAsync([deviceId], cancellationToken);
    if (device is null)
    {
        device = new DeviceStatus
        {
            DeviceId = deviceId,
            DeviceName = deviceName,
            Manufacturer = CleanOptionalText(request.Manufacturer, 80),
            Model = CleanOptionalText(request.Model, 120),
            AndroidVersion = CleanOptionalText(request.AndroidVersion, 80),
            AppVersion = CleanOptionalText(request.AppVersion, 40),
            IpAddress = ipAddress,
            BatteryLevel = request.BatteryLevel,
            IsCharging = request.IsCharging,
            ChargingSource = CleanOptionalText(request.ChargingSource, 32),
            PowerSaveMode = request.PowerSaveMode,
            VideoRecordingActive = request.VideoRecordingActive,
            AudioRecordingActive = request.AudioRecordingActive,
            LiveAccessEnabled = request.LiveAccessEnabled,
            LiveRequested = false,
            LiveStreaming = request.LiveStreaming,
            LiveError = CleanOptionalText(request.LiveError, 500),
            LastSeenTransport = transport,
            LastSeenAt = now,
            FirstSeenAt = now
        };
        db.DeviceStatuses.Add(device);
    }
    else
    {
        device.DeviceName = deviceName;
        device.Manufacturer = CleanOptionalText(request.Manufacturer, 80);
        device.Model = CleanOptionalText(request.Model, 120);
        device.AndroidVersion = CleanOptionalText(request.AndroidVersion, 80);
        device.AppVersion = CleanOptionalText(request.AppVersion, 40);
        device.IpAddress = ipAddress;
        device.BatteryLevel = request.BatteryLevel;
        device.IsCharging = request.IsCharging;
        device.ChargingSource = CleanOptionalText(request.ChargingSource, 32);
        device.PowerSaveMode = request.PowerSaveMode;
        device.VideoRecordingActive = request.VideoRecordingActive;
        device.AudioRecordingActive = request.AudioRecordingActive;
        device.LiveAccessEnabled = request.LiveAccessEnabled;
        device.LiveStreaming = request.LiveStreaming;
        device.LiveError = CleanOptionalText(request.LiveError, 500);
        device.LastSeenTransport = transport;
        device.LastSeenAt = now;
    }

    if (!request.LiveAccessEnabled || request.VideoRecordingActive || request.AudioRecordingActive)
    {
        device.LiveRequested = false;
        device.LiveStreaming = false;
        liveFrames.Remove(deviceId);
    }
    else if (device.LiveRequested && !liveFrames.HasRecentViewer(deviceId, TimeSpan.FromSeconds(10)))
    {
        device.LiveRequested = false;
        device.LiveStreaming = false;
        device.LiveError = string.Empty;
        liveFrames.Remove(deviceId);
    }
    return device;
}

static string? CleanRequiredText(string? value, int maxLength)
{
    var cleaned = value?.Trim();
    if (string.IsNullOrWhiteSpace(cleaned)) return null;
    return cleaned.Length <= maxLength ? cleaned : cleaned[..maxLength];
}

static string CleanOptionalText(string? value, int maxLength)
{
    var cleaned = value?.Trim() ?? string.Empty;
    return cleaned.Length <= maxLength ? cleaned : cleaned[..maxLength];
}

static string? NormalizeIpAddress(string? value)
{
    if (!System.Net.IPAddress.TryParse(value?.Trim(), out var address)) return null;
    return address.IsIPv4MappedToIPv6 ? address.MapToIPv4().ToString() : address.ToString();
}

static DateTime AsUtc(DateTime value) => value.Kind switch
{
    DateTimeKind.Utc => value,
    DateTimeKind.Local => value.ToUniversalTime(),
    _ => DateTime.SpecifyKind(value, DateTimeKind.Utc)
};

static List<int>? NormalizeBulkIds(IEnumerable<int>? requestedIds)
{
    if (requestedIds is null) return null;
    var requested = requestedIds.ToList();
    if (requested.Count is < 1 or > 200 || requested.Any(id => id <= 0)) return null;
    return requested.Distinct().ToList();
}

public sealed record LockRequest(bool Locked);
public sealed record BulkIdsRequest(int[] Ids);
public sealed record BulkLockRequest(int[] Ids, bool Locked);
public sealed record BulkRotationRequest(int[] Ids, int PlaybackRotationDegrees);
public sealed record RotationRequest(int PlaybackRotationDegrees);
public sealed record VideoExportRequest(int[] Ids, bool WithTimestamp, int TimezoneOffsetMinutes);
public sealed record AudioExportRequest(int[] Ids);
public sealed record VideoExportJob(
    Guid Id,
    string Status,
    string? ExportPath,
    string Filename,
    string? Error,
    DateTime CreatedAtUtc,
    DateTime? CompletedAtUtc);
public sealed record AudioExportJob(
    Guid Id,
    string Status,
    string? ExportPath,
    string Filename,
    string? Error,
    DateTime CreatedAtUtc,
    DateTime? CompletedAtUtc);
public sealed record VideoProbeInfo(double DurationSeconds, bool HasAudio);
public sealed record AudioTranscriptSegment(double Start, double End, string Text);
public sealed record AudioTranscriptionWorkerResponse(
    string? Text,
    string? Language,
    double LanguageProbability,
    string? Model,
    List<AudioTranscriptSegment>? Segments);
public sealed record TimestampSegment(
    double OutputStartSeconds,
    double DurationSeconds,
    DateTime RecordedStartUtc);
public sealed record VideoCleanupResult(
    int RemovedCount,
    long RemovedBytes,
    long TotalSizeBytes,
    long MaxStorageBytes);
public sealed record DeviceHeartbeatRequest(
    string? DeviceId,
    string? DeviceName,
    string? Manufacturer,
    string? Model,
    string? AndroidVersion,
    string? AppVersion,
    string? IpAddress,
    int BatteryLevel,
    bool IsCharging,
    string? ChargingSource,
    bool PowerSaveMode,
    bool VideoRecordingActive,
    bool AudioRecordingActive,
    bool LiveAccessEnabled,
    bool LiveStreaming,
    string? LiveError);
public sealed record LiveRequest(bool Enabled);
