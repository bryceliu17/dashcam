using System.Globalization;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace Dashcam.Api.Services;

public sealed class ArchiveMutationGate
{
    private readonly SemaphoreSlim gate = new(1, 1);

    public async Task<IDisposable> EnterAsync(CancellationToken token)
    {
        await gate.WaitAsync(token);
        return new Releaser(gate);
    }

    private sealed class Releaser(SemaphoreSlim gate) : IDisposable
    {
        private SemaphoreSlim? value = gate;
        public void Dispose() => Interlocked.Exchange(ref value, null)?.Release();
    }
}

public sealed class ArchiveMigrationService
{
    private readonly IConfiguration configuration;
    private readonly ILogger<ArchiveMigrationService> logger;
    private readonly ArchiveMutationGate mutationGate;
    private readonly object sync = new();
    private CancellationTokenSource? jobCancellation;
    private MigrationPlan? plan;
    private ArchiveMigrationStatus status;

    public ArchiveMigrationService(
        IConfiguration configuration,
        ILogger<ArchiveMigrationService> logger,
        ArchiveMutationGate mutationGate)
    {
        this.configuration = configuration;
        this.logger = logger;
        this.mutationGate = mutationGate;
        status = EmptyStatus("idle", "Place the old server data in the import folder, then scan it.");
    }

    public bool IsImporting
    {
        get { lock (sync) return status.Phase == "importing"; }
    }

    public ArchiveMigrationStatus GetStatus()
    {
        lock (sync) return status;
    }

    public MigrationCommandResult BeginScan(
        string importRoot,
        bool consumeSource,
        string? displayPath = null,
        string? cleanupRoot = null)
    {
        CancellationToken token;
        lock (sync)
        {
            if (status.Phase is "scanning" or "importing")
                return new(false, "A migration job is already running.");

            jobCancellation?.Dispose();
            jobCancellation = new CancellationTokenSource();
            token = jobCancellation.Token;
            plan = null;
            status = EmptyStatus("scanning", "Checking the import database and media files...") with
            {
                ImportHostPath = displayPath ?? configuration["ImportHostPath"] ?? "Selected folder",
                ImportContainerPath = Path.GetFullPath(importRoot),
                StartedAt = DateTime.UtcNow
            };
        }

        _ = Task.Run(() => ScanAsync(importRoot, consumeSource, cleanupRoot, token), CancellationToken.None);
        return new(true, null);
    }

    public MigrationCommandResult BeginImport(bool allowOverCapacity)
    {
        MigrationPlan selectedPlan;
        CancellationToken token;
        lock (sync)
        {
            if (status.Phase != "ready" || plan is null)
                return new(false, "Scan the import data before starting the merge.");
            if (status.MissingFiles > 0)
                return new(false, "Some database records have missing files. Fix the import folder and scan again.");
            if (status.ImportBytes > status.AvailableDiskSpaceBytes)
                return new(false, "There is not enough free disk space for this merge.");
            if (status.RequiresCapacityConfirmation && !allowOverCapacity)
                return new(false, "The merge exceeds an archive limit and requires confirmation.");

            jobCancellation?.Dispose();
            jobCancellation = new CancellationTokenSource();
            token = jobCancellation.Token;
            selectedPlan = plan;
            status = status with
            {
                Phase = "importing",
                Message = "Waiting for current archive writes to finish...",
                ProcessedItems = 0,
                TotalItems = selectedPlan.Items.Count,
                ProgressPercent = 0,
                StartedAt = DateTime.UtcNow,
                FinishedAt = null,
                Error = null
            };
        }

        _ = Task.Run(() => ImportAsync(selectedPlan, allowOverCapacity, token), CancellationToken.None);
        return new(true, null);
    }

    public MigrationCommandResult Cancel()
    {
        lock (sync)
        {
            if (status.Phase is not ("scanning" or "importing"))
                return new(false, "No migration job is running.");
            jobCancellation?.Cancel();
            return new(true, null);
        }
    }

    private async Task ScanAsync(string selectedImportRoot, bool consumeSource, string? cleanupRoot, CancellationToken token)
    {
        try
        {
            var importRoot = Path.GetFullPath(selectedImportRoot);
            var sourceDatabase = Path.Combine(importRoot, "dashcam.db");
            if (!Directory.Exists(importRoot))
                throw new DirectoryNotFoundException($"Import folder was not found: {importRoot}");
            if (!File.Exists(sourceDatabase))
                throw new FileNotFoundException("dashcam.db was not found in the import folder.", sourceDatabase);

            var targetDatabase = GetTargetDatabasePath();
            if (PathsEqual(sourceDatabase, targetDatabase))
                throw new InvalidOperationException("The import database cannot be the active server database.");

            var sourceRecords = await ReadSourceRecordsAsync(sourceDatabase, importRoot, token);
            var targetCandidates = await ReadTargetCandidatesAsync(targetDatabase, token);
            var hashCache = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            var items = new List<MigrationRecord>();
            var duplicateVideos = 0;
            var duplicateAudio = 0;
            var missing = new List<string>();
            var processed = 0;

            SetStatus(current => current with
            {
                Message = "Checking files and duplicates...",
                TotalItems = sourceRecords.Count
            });

            foreach (var record in sourceRecords)
            {
                token.ThrowIfCancellationRequested();
                if (record.SourcePath is null || !File.Exists(record.SourcePath) || new FileInfo(record.SourcePath).Length != record.FileSizeBytes)
                {
                    missing.Add($"{record.Kind}: {record.OriginalFilename}");
                }
                else
                {
                    var key = DuplicateKey(record);
                    var isDuplicate = false;
                    if (targetCandidates.TryGetValue(key, out var candidates))
                    {
                        foreach (var candidate in candidates.Where(File.Exists))
                        {
                            if (new FileInfo(candidate).Length != record.FileSizeBytes) continue;
                            var sourceHash = await GetHashAsync(record.SourcePath, hashCache, token);
                            var targetHash = await GetHashAsync(candidate, hashCache, token);
                            if (sourceHash == targetHash)
                            {
                                isDuplicate = true;
                                break;
                            }
                        }
                    }

                    if (isDuplicate)
                    {
                        if (record.Kind == "video") duplicateVideos++;
                        else duplicateAudio++;
                    }
                    else
                    {
                        items.Add(record);
                        if (!targetCandidates.TryGetValue(key, out var accepted))
                            targetCandidates[key] = accepted = [];
                        accepted.Add(record.SourcePath);
                    }
                }

                processed++;
                if (processed == sourceRecords.Count || processed % 10 == 0)
                {
                    var completed = processed;
                    SetStatus(current => current with
                    {
                        ProcessedItems = completed,
                        ProgressPercent = Percent(completed, sourceRecords.Count)
                    });
                }
            }

            var totals = await ReadCurrentTotalsAsync(targetDatabase, token);
            var importVideoBytes = items.Where(x => x.Kind == "video").Sum(x => x.FileSizeBytes);
            var importAudioBytes = items.Where(x => x.Kind == "audio").Sum(x => x.FileSizeBytes);
            var maxVideoBytes = GetLimitBytes("MaxStorageGB", 240);
            var maxAudioBytes = GetLimitBytes("MaxAudioStorageGB", 10);
            var available = GetAvailableSpace(GetVideoRoot());
            var newPlan = new MigrationPlan(sourceDatabase, items, consumeSource, cleanupRoot);
            var nothingToImport = consumeSource && missing.Count == 0 && items.Count == 0;

            lock (sync)
            {
                plan = nothingToImport ? null : newPlan;
                status = status with
                {
                    Phase = nothingToImport ? "completed" : "ready",
                    Message = nothingToImport
                        ? "Scan complete. Every recording is already in this archive."
                        : missing.Count == 0
                            ? "Scan complete. Review the result before merging."
                        : "Scan complete, but some referenced files are missing.",
                    ProcessedItems = sourceRecords.Count,
                    TotalItems = sourceRecords.Count,
                    ProgressPercent = 100,
                    SourceVideoCount = sourceRecords.Count(x => x.Kind == "video"),
                    SourceVideoBytes = sourceRecords.Where(x => x.Kind == "video").Sum(x => x.FileSizeBytes),
                    SourceAudioCount = sourceRecords.Count(x => x.Kind == "audio"),
                    SourceAudioBytes = sourceRecords.Where(x => x.Kind == "audio").Sum(x => x.FileSizeBytes),
                    DuplicateVideos = duplicateVideos,
                    DuplicateAudio = duplicateAudio,
                    MissingFiles = missing.Count,
                    MissingFileExamples = missing.Take(8).ToArray(),
                    ImportVideoCount = items.Count(x => x.Kind == "video"),
                    ImportVideoBytes = importVideoBytes,
                    ImportAudioCount = items.Count(x => x.Kind == "audio"),
                    ImportAudioBytes = importAudioBytes,
                    ImportBytes = importVideoBytes + importAudioBytes,
                    CurrentVideoBytes = totals.VideoBytes,
                    CurrentAudioBytes = totals.AudioBytes,
                    ProjectedVideoBytes = totals.VideoBytes + importVideoBytes,
                    ProjectedAudioBytes = totals.AudioBytes + importAudioBytes,
                    MaxVideoBytes = maxVideoBytes,
                    MaxAudioBytes = maxAudioBytes,
                    AvailableDiskSpaceBytes = available,
                    RequiresCapacityConfirmation = totals.VideoBytes + importVideoBytes > maxVideoBytes ||
                        totals.AudioBytes + importAudioBytes > maxAudioBytes,
                    FinishedAt = DateTime.UtcNow,
                    Error = null
                };
            }
            if (nothingToImport && cleanupRoot is not null) DeleteCompletedUploadSession(cleanupRoot);
        }
        catch (OperationCanceledException)
        {
            SetCancelled();
        }
        catch (Exception error)
        {
            logger.LogError(error, "Archive migration scan failed");
            SetFailed(error.Message);
        }
    }

    private async Task ImportAsync(MigrationPlan selectedPlan, bool allowOverCapacity, CancellationToken token)
    {
        var prepared = new List<PreparedImport>();
        string? manifestPath = null;
        var committed = false;
        try
        {
            using var gate = await mutationGate.EnterAsync(token);
            SetStatus(current => current with { Message = "Backing up the current database..." });

            var targetDatabase = GetTargetDatabasePath();
            var totals = await ReadCurrentTotalsAsync(targetDatabase, token);
            var importVideoBytes = selectedPlan.Items.Where(x => x.Kind == "video").Sum(x => x.FileSizeBytes);
            var importAudioBytes = selectedPlan.Items.Where(x => x.Kind == "audio").Sum(x => x.FileSizeBytes);
            var overCapacity = totals.VideoBytes + importVideoBytes > GetLimitBytes("MaxStorageGB", 240) ||
                totals.AudioBytes + importAudioBytes > GetLimitBytes("MaxAudioStorageGB", 10);
            if (overCapacity && !allowOverCapacity)
                throw new InvalidOperationException("The archive size changed and now exceeds a configured limit. Scan again.");

            var requiredBytes = selectedPlan.ConsumeSource ? 0 : importVideoBytes + importAudioBytes;
            if (requiredBytes > GetAvailableSpace(GetVideoRoot()))
                throw new IOException("There is not enough free disk space to complete the merge.");

            var backupPath = BackupDatabase(targetDatabase);
            SetStatus(current => current with { BackupPath = backupPath, Message = "Copying and verifying media files..." });

            var processed = 0;
            foreach (var record in selectedPlan.Items)
            {
                token.ThrowIfCancellationRequested();
                if (record.SourcePath is null || !File.Exists(record.SourcePath))
                    throw new FileNotFoundException($"Import file disappeared: {record.OriginalFilename}", record.SourcePath);
                if (new FileInfo(record.SourcePath).Length != record.FileSizeBytes)
                    throw new IOException($"Import file size changed: {record.OriginalFilename}");

                var destinationRoot = record.Kind == "video" ? GetVideoRoot() : GetAudioRoot();
                var destinationDirectory = Path.Combine(destinationRoot, record.StartTime.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
                Directory.CreateDirectory(destinationDirectory);
                var extension = record.Kind == "video" ? ".mp4" : ".m4a";
                var filename = $"{CleanFileBase(Path.GetFileNameWithoutExtension(record.OriginalFilename))}_{Guid.NewGuid():N}{extension}";
                var finalPath = Path.Combine(destinationDirectory, filename);
                var temporaryPath = finalPath + ".merging";

                if (selectedPlan.ConsumeSource)
                    File.Move(record.SourcePath, temporaryPath);
                else
                    await CopyFileAsync(record.SourcePath, temporaryPath, token);
                if (new FileInfo(temporaryPath).Length != record.FileSizeBytes)
                    throw new IOException($"Copied file verification failed: {record.OriginalFilename}");
                prepared.Add(new(record, filename, temporaryPath, finalPath, selectedPlan.ConsumeSource));

                processed++;
                var completed = processed;
                SetStatus(current => current with
                {
                    ProcessedItems = completed,
                    ProgressPercent = Percent(completed, selectedPlan.Items.Count)
                });
            }

            manifestPath = await WriteManifestAsync(targetDatabase, selectedPlan, prepared, token);
            SetStatus(current => current with { Message = "Updating the archive database..." });

            await using var connection = OpenConnection(targetDatabase, readOnly: false);
            await connection.OpenAsync(token);
            await using var transaction = await connection.BeginTransactionAsync(token);
            try
            {
                foreach (var item in prepared)
                {
                    File.Move(item.TemporaryPath, item.FinalPath);
                    await InsertRecordAsync(connection, transaction, item, token);
                }
                await transaction.CommitAsync(token);
                committed = true;
            }
            catch
            {
                await transaction.RollbackAsync(CancellationToken.None);
                throw;
            }

            if (manifestPath is not null && File.Exists(manifestPath))
                File.Move(manifestPath, Path.ChangeExtension(manifestPath, ".completed.json"), true);
            if (selectedPlan.ConsumeSource && selectedPlan.CleanupRoot is not null)
                DeleteCompletedUploadSession(selectedPlan.CleanupRoot);

            SetStatus(current => current with
            {
                Phase = "completed",
                Message = $"Merge complete. Imported {prepared.Count} recording(s).",
                ProcessedItems = prepared.Count,
                TotalItems = prepared.Count,
                ProgressPercent = 100,
                FinishedAt = DateTime.UtcNow,
                Error = null
            });
        }
        catch (OperationCanceledException)
        {
            if (!committed) CleanupPrepared(prepared);
            RenameFailedManifest(manifestPath);
            SetCancelled();
        }
        catch (Exception error)
        {
            if (!committed) CleanupPrepared(prepared);
            RenameFailedManifest(manifestPath);
            logger.LogError(error, "Archive migration failed");
            SetFailed(error.Message);
        }
    }

    private async Task<List<MigrationRecord>> ReadSourceRecordsAsync(string databasePath, string importRoot, CancellationToken token)
    {
        await using var connection = OpenConnection(databasePath, readOnly: true);
        await connection.OpenAsync(token);
        var records = new List<MigrationRecord>();
        var videoRoot = Path.Combine(importRoot, "videos");
        var audioRoot = Path.Combine(importRoot, "audio");
        var videoIndex = BuildFileIndex(videoRoot, ".mp4");
        var audioIndex = BuildFileIndex(audioRoot, ".m4a");

        if (!await TableExistsAsync(connection, "Videos", token))
            throw new InvalidDataException("The import database does not contain a Videos table.");
        records.AddRange(await ReadTableAsync(connection, "Videos", "video", videoRoot, videoIndex, token));
        if (await TableExistsAsync(connection, "AudioRecordings", token))
            records.AddRange(await ReadTableAsync(connection, "AudioRecordings", "audio", audioRoot, audioIndex, token));
        return records;
    }

    private static async Task<List<MigrationRecord>> ReadTableAsync(
        SqliteConnection connection,
        string table,
        string kind,
        string mediaRoot,
        Dictionary<string, List<string>> fileIndex,
        CancellationToken token)
    {
        var columns = await GetColumnsAsync(connection, table, token);
        string Column(string name, string fallback) => columns.Contains(name) ? $"[{name}]" : $"{fallback} AS [{name}]";
        await using var command = connection.CreateCommand();
        command.CommandText = $"""
            SELECT {Column("Filename", "''")}, {Column("OriginalFilename", "[Filename]")},
                   {Column("FilePath", "''")}, {Column("StartTime", "''")}, {Column("EndTime", "[StartTime]")},
                   {Column("DurationSeconds", "0")}, {Column("FileSizeBytes", "0")}, {Column("Locked", "0")},
                   {Column("PlaybackRotationDegrees", "0")}, {Column("UploadedAt", "[StartTime]")},
                   {Column("CreatedAt", "[StartTime]")}
            FROM [{table}]
            ORDER BY [StartTime], [Id]
            """;
        var records = new List<MigrationRecord>();
        await using var reader = await command.ExecuteReaderAsync(token);
        while (await reader.ReadAsync(token))
        {
            var filename = Path.GetFileName(reader.GetString(0));
            var originalFilename = Path.GetFileName(reader.GetString(1));
            var storedPath = reader.GetString(2);
            var startTime = ReadDate(reader.GetValue(3));
            var endTime = ReadDate(reader.GetValue(4));
            var size = reader.GetInt64(6);
            var sourcePath = ResolveSourceFile(mediaRoot, kind, storedPath, filename, startTime, size, fileIndex);
            records.Add(new(
                kind,
                filename,
                originalFilename,
                sourcePath,
                startTime,
                endTime,
                reader.GetInt32(5),
                size,
                reader.GetBoolean(7),
                kind == "video" ? reader.GetInt32(8) : 0,
                ReadDate(reader.GetValue(9)),
                ReadDate(reader.GetValue(10))));
        }
        return records;
    }

    private static async Task<Dictionary<string, List<string>>> ReadTargetCandidatesAsync(string databasePath, CancellationToken token)
    {
        await using var connection = OpenConnection(databasePath, readOnly: true);
        await connection.OpenAsync(token);
        var result = new Dictionary<string, List<string>>(StringComparer.Ordinal);
        foreach (var (table, kind) in new[] { ("Videos", "video"), ("AudioRecordings", "audio") })
        {
            if (!await TableExistsAsync(connection, table, token)) continue;
            var columns = await GetColumnsAsync(connection, table, token);
            var rotation = columns.Contains("PlaybackRotationDegrees") ? "PlaybackRotationDegrees" : "0";
            await using var command = connection.CreateCommand();
            command.CommandText = $"SELECT Filename, OriginalFilename, FilePath, StartTime, EndTime, DurationSeconds, FileSizeBytes, Locked, {rotation}, UploadedAt, CreatedAt FROM {table}";
            await using var reader = await command.ExecuteReaderAsync(token);
            while (await reader.ReadAsync(token))
            {
                var record = new MigrationRecord(
                    kind, reader.GetString(0), reader.GetString(1), reader.GetString(2),
                    ReadDate(reader.GetValue(3)), ReadDate(reader.GetValue(4)), reader.GetInt32(5),
                    reader.GetInt64(6), reader.GetBoolean(7), kind == "video" ? reader.GetInt32(8) : 0,
                    ReadDate(reader.GetValue(9)), ReadDate(reader.GetValue(10)));
                var key = DuplicateKey(record);
                if (!result.TryGetValue(key, out var paths)) result[key] = paths = [];
                paths.Add(record.SourcePath!);
            }
        }
        return result;
    }

    private static async Task InsertRecordAsync(SqliteConnection connection, System.Data.Common.DbTransaction transaction, PreparedImport item, CancellationToken token)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = (SqliteTransaction)transaction;
        if (item.Record.Kind == "video")
        {
            command.CommandText = """
                INSERT INTO Videos (Filename, OriginalFilename, FilePath, StartTime, EndTime, DurationSeconds,
                    FileSizeBytes, Locked, PlaybackRotationDegrees, UploadedAt, CreatedAt)
                VALUES ($filename, $original, $path, $start, $end, $duration, $size, $locked, $rotation, $uploaded, $created)
                """;
            command.Parameters.AddWithValue("$rotation", item.Record.PlaybackRotationDegrees);
        }
        else
        {
            command.CommandText = """
                INSERT INTO AudioRecordings (Filename, OriginalFilename, FilePath, StartTime, EndTime, DurationSeconds,
                    FileSizeBytes, Locked, UploadedAt, CreatedAt)
                VALUES ($filename, $original, $path, $start, $end, $duration, $size, $locked, $uploaded, $created)
                """;
        }
        command.Parameters.AddWithValue("$filename", item.Filename);
        command.Parameters.AddWithValue("$original", item.Record.OriginalFilename);
        command.Parameters.AddWithValue("$path", item.FinalPath);
        command.Parameters.AddWithValue("$start", item.Record.StartTime);
        command.Parameters.AddWithValue("$end", item.Record.EndTime);
        command.Parameters.AddWithValue("$duration", item.Record.DurationSeconds);
        command.Parameters.AddWithValue("$size", item.Record.FileSizeBytes);
        command.Parameters.AddWithValue("$locked", item.Record.Locked);
        command.Parameters.AddWithValue("$uploaded", item.Record.UploadedAt);
        command.Parameters.AddWithValue("$created", item.Record.CreatedAt);
        await command.ExecuteNonQueryAsync(token);
    }

    private static async Task<ArchiveTotals> ReadCurrentTotalsAsync(string databasePath, CancellationToken token)
    {
        await using var connection = OpenConnection(databasePath, readOnly: true);
        await connection.OpenAsync(token);
        async Task<(int Count, long Bytes)> ReadAsync(string table)
        {
            if (!await TableExistsAsync(connection, table, token)) return (0, 0);
            await using var command = connection.CreateCommand();
            command.CommandText = $"SELECT COUNT(*), COALESCE(SUM(FileSizeBytes), 0) FROM {table}";
            await using var reader = await command.ExecuteReaderAsync(token);
            await reader.ReadAsync(token);
            return (reader.GetInt32(0), reader.GetInt64(1));
        }
        var videos = await ReadAsync("Videos");
        var audio = await ReadAsync("AudioRecordings");
        return new(videos.Count, videos.Bytes, audio.Count, audio.Bytes);
    }

    private string BackupDatabase(string targetDatabase)
    {
        var backupDirectory = Path.Combine(Path.GetDirectoryName(targetDatabase)!, "backups");
        Directory.CreateDirectory(backupDirectory);
        var backupPath = Path.Combine(backupDirectory, $"dashcam-before-merge-{DateTime.UtcNow:yyyyMMdd-HHmmss}.db");
        using var source = OpenConnection(targetDatabase, readOnly: true);
        using var destination = OpenConnection(backupPath, readOnly: false);
        source.Open();
        destination.Open();
        source.BackupDatabase(destination);
        return backupPath;
    }

    private static async Task<string> WriteManifestAsync(string targetDatabase, MigrationPlan selectedPlan, List<PreparedImport> prepared, CancellationToken token)
    {
        var backupDirectory = Path.Combine(Path.GetDirectoryName(targetDatabase)!, "backups");
        Directory.CreateDirectory(backupDirectory);
        var path = Path.Combine(backupDirectory, $"merge-{DateTime.UtcNow:yyyyMMdd-HHmmss}.in-progress.json");
        var document = new
        {
            startedAt = DateTime.UtcNow,
            sourceDatabase = selectedPlan.SourceDatabase,
            items = prepared.Select(x => new { x.Record.Kind, x.Record.OriginalFilename, x.TemporaryPath, x.FinalPath })
        };
        await File.WriteAllTextAsync(path, JsonSerializer.Serialize(document, new JsonSerializerOptions { WriteIndented = true }), token);
        return path;
    }

    private static void CleanupPrepared(IEnumerable<PreparedImport> prepared)
    {
        foreach (var item in prepared)
        {
            if (item.SourceMoved && item.Record.SourcePath is not null)
            {
                var movedPath = File.Exists(item.FinalPath) ? item.FinalPath : item.TemporaryPath;
                try
                {
                    if (File.Exists(movedPath))
                    {
                        Directory.CreateDirectory(Path.GetDirectoryName(item.Record.SourcePath)!);
                        File.Move(movedPath, item.Record.SourcePath, true);
                    }
                }
                catch { }
            }
            else
            {
                TryDelete(item.TemporaryPath);
                TryDelete(item.FinalPath);
            }
        }
    }

    private void DeleteCompletedUploadSession(string path)
    {
        try
        {
            var stagingRoot = Path.GetFullPath(configuration["ImportStagingPath"] ??
                Path.Combine(Path.GetDirectoryName(GetTargetDatabasePath())!, "import-staging"));
            var sessionRoot = Path.GetFullPath(path);
            if (IsInside(sessionRoot, stagingRoot) && Directory.Exists(sessionRoot))
                Directory.Delete(sessionRoot, true);
        }
        catch (Exception error)
        {
            logger.LogWarning(error, "Could not remove completed migration upload session {Path}", path);
        }
    }

    private static void RenameFailedManifest(string? path)
    {
        if (path is not null && File.Exists(path))
        {
            try { File.Move(path, Path.ChangeExtension(path, ".failed.json"), true); }
            catch { }
        }
    }

    private static async Task CopyFileAsync(string source, string destination, CancellationToken token)
    {
        await using var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, true);
        await using var output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1024 * 1024, true);
        await input.CopyToAsync(output, 1024 * 1024, token);
        await output.FlushAsync(token);
    }

    private static async Task<string> GetHashAsync(string path, Dictionary<string, string> cache, CancellationToken token)
    {
        if (cache.TryGetValue(path, out var existing)) return existing;
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, true);
        var hash = Convert.ToHexString(await SHA256.HashDataAsync(stream, token));
        cache[path] = hash;
        return hash;
    }

    private static Dictionary<string, List<string>> BuildFileIndex(string root, string extension)
    {
        var result = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
        if (!Directory.Exists(root)) return result;
        foreach (var path in Directory.EnumerateFiles(root, $"*{extension}", SearchOption.AllDirectories))
        {
            var filename = Path.GetFileName(path);
            if (!result.TryGetValue(filename, out var paths)) result[filename] = paths = [];
            paths.Add(path);
        }
        return result;
    }

    private static string? ResolveSourceFile(
        string mediaRoot,
        string kind,
        string storedPath,
        string filename,
        DateTime startTime,
        long expectedSize,
        Dictionary<string, List<string>> fileIndex)
    {
        var candidates = new List<string>();
        var normalized = storedPath.Replace('\\', '/');
        var marker = $"/{(kind == "video" ? "videos" : "audio")}/";
        var markerIndex = normalized.LastIndexOf(marker, StringComparison.OrdinalIgnoreCase);
        if (markerIndex >= 0)
        {
            var relative = normalized[(markerIndex + marker.Length)..];
            var parts = relative.Split('/', StringSplitOptions.RemoveEmptyEntries);
            var candidate = Path.GetFullPath(Path.Combine([mediaRoot, .. parts]));
            if (IsInside(candidate, mediaRoot)) candidates.Add(candidate);
        }
        candidates.Add(Path.Combine(mediaRoot, startTime.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture), filename));
        if (fileIndex.TryGetValue(filename, out var indexed)) candidates.AddRange(indexed);
        return candidates.Distinct(StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault(path => File.Exists(path) && new FileInfo(path).Length == expectedSize);
    }

    private static bool IsInside(string path, string root)
    {
        var normalizedRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        return Path.GetFullPath(path).StartsWith(normalizedRoot, StringComparison.OrdinalIgnoreCase);
    }

    private static async Task<HashSet<string>> GetColumnsAsync(SqliteConnection connection, string table, CancellationToken token)
    {
        var columns = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        await using var command = connection.CreateCommand();
        command.CommandText = $"PRAGMA table_info([{table}])";
        await using var reader = await command.ExecuteReaderAsync(token);
        while (await reader.ReadAsync(token)) columns.Add(reader.GetString(1));
        return columns;
    }

    private static async Task<bool> TableExistsAsync(SqliteConnection connection, string table, CancellationToken token)
    {
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = $name";
        command.Parameters.AddWithValue("$name", table);
        return Convert.ToInt64(await command.ExecuteScalarAsync(token), CultureInfo.InvariantCulture) > 0;
    }

    private static SqliteConnection OpenConnection(string databasePath, bool readOnly)
    {
        var builder = new SqliteConnectionStringBuilder
        {
            DataSource = databasePath,
            Mode = readOnly ? SqliteOpenMode.ReadOnly : SqliteOpenMode.ReadWriteCreate
        };
        return new SqliteConnection(builder.ToString());
    }

    private string GetTargetDatabasePath()
    {
        var connectionString = configuration.GetConnectionString("DashcamDatabase") ?? "Data Source=dashcam.db";
        var dataSource = new SqliteConnectionStringBuilder(connectionString).DataSource;
        return Path.GetFullPath(dataSource);
    }

    private string GetVideoRoot() => Path.GetFullPath(configuration["VideoStoragePath"] ?? Path.Combine(AppContext.BaseDirectory, "videos"));
    private string GetAudioRoot() => Path.GetFullPath(configuration["AudioStoragePath"] ?? Path.Combine(AppContext.BaseDirectory, "audio"));

    private long GetLimitBytes(string key, double fallbackGb)
    {
        var gb = configuration.GetValue<double?>(key) ?? fallbackGb;
        return (long)(Math.Max(0.1, gb) * 1024 * 1024 * 1024);
    }

    private static long GetAvailableSpace(string path)
    {
        var fullPath = Path.GetFullPath(path);
        var drive = DriveInfo.GetDrives()
            .Where(x => x.IsReady && fullPath.StartsWith(x.RootDirectory.FullName, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(x => x.RootDirectory.FullName.Length)
            .FirstOrDefault();
        return drive?.AvailableFreeSpace ?? 0;
    }

    private static string DuplicateKey(MigrationRecord record) => string.Join('|',
        record.Kind,
        record.OriginalFilename.ToUpperInvariant(),
        record.StartTime.ToUniversalTime().Ticks,
        record.EndTime.ToUniversalTime().Ticks,
        record.DurationSeconds,
        record.FileSizeBytes);

    private static DateTime ReadDate(object value)
    {
        if (value is DateTime date) return date.Kind switch
        {
            DateTimeKind.Utc => date,
            DateTimeKind.Local => date.ToUniversalTime(),
            _ => DateTime.SpecifyKind(date, DateTimeKind.Utc)
        };
        if (DateTimeOffset.TryParse(Convert.ToString(value, CultureInfo.InvariantCulture), CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal, out var offset)) return offset.UtcDateTime;
        throw new InvalidDataException($"Invalid date value in import database: {value}");
    }

    private static string CleanFileBase(string value)
    {
        var invalid = Path.GetInvalidFileNameChars().ToHashSet();
        var cleaned = new string(value.Where(character => !invalid.Contains(character)).ToArray()).Trim();
        if (string.IsNullOrWhiteSpace(cleaned)) cleaned = "dashcam";
        return cleaned.Length <= 100 ? cleaned : cleaned[..100];
    }

    private static bool PathsEqual(string left, string right) =>
        string.Equals(Path.GetFullPath(left), Path.GetFullPath(right), StringComparison.OrdinalIgnoreCase);

    private static int Percent(int processed, int total) => total == 0 ? 100 : (int)Math.Round(processed * 100d / total);

    private static bool TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); return true; }
        catch { return false; }
    }

    private void SetStatus(Func<ArchiveMigrationStatus, ArchiveMigrationStatus> update)
    {
        lock (sync) status = update(status);
    }

    private void SetCancelled() => SetStatus(current => current with
    {
        Phase = "cancelled",
        Message = "Migration cancelled. Existing archive data was not changed.",
        FinishedAt = DateTime.UtcNow,
        Error = null
    });

    private void SetFailed(string error) => SetStatus(current => current with
    {
        Phase = "failed",
        Message = "Migration could not be completed.",
        FinishedAt = DateTime.UtcNow,
        Error = error
    });

    private ArchiveMigrationStatus EmptyStatus(string phase, string message) => new(
        Phase: phase,
        Message: message,
        ImportHostPath: "Selected folder",
        ImportContainerPath: string.Empty,
        ProcessedItems: 0,
        TotalItems: 0,
        ProgressPercent: 0,
        SourceVideoCount: 0,
        SourceVideoBytes: 0,
        SourceAudioCount: 0,
        SourceAudioBytes: 0,
        DuplicateVideos: 0,
        DuplicateAudio: 0,
        MissingFiles: 0,
        ImportVideoCount: 0,
        ImportVideoBytes: 0,
        ImportAudioCount: 0,
        ImportAudioBytes: 0,
        ImportBytes: 0,
        CurrentVideoBytes: 0,
        CurrentAudioBytes: 0,
        ProjectedVideoBytes: 0,
        ProjectedAudioBytes: 0,
        MaxVideoBytes: 0,
        MaxAudioBytes: 0,
        AvailableDiskSpaceBytes: 0,
        RequiresCapacityConfirmation: false,
        MissingFileExamples: [],
        BackupPath: null,
        StartedAt: null,
        FinishedAt: null,
        Error: null);

    private sealed record MigrationPlan(
        string SourceDatabase,
        List<MigrationRecord> Items,
        bool ConsumeSource,
        string? CleanupRoot);
    private sealed record MigrationRecord(
        string Kind,
        string Filename,
        string OriginalFilename,
        string? SourcePath,
        DateTime StartTime,
        DateTime EndTime,
        int DurationSeconds,
        long FileSizeBytes,
        bool Locked,
        int PlaybackRotationDegrees,
        DateTime UploadedAt,
        DateTime CreatedAt);
    private sealed record PreparedImport(
        MigrationRecord Record,
        string Filename,
        string TemporaryPath,
        string FinalPath,
        bool SourceMoved);
    private sealed record ArchiveTotals(int VideoCount, long VideoBytes, int AudioCount, long AudioBytes);
}

public sealed record MigrationCommandResult(bool Started, string? Error);
public sealed record MigrationStartRequest(bool AllowOverCapacity);
public sealed record ArchiveMigrationStatus(
    string Phase,
    string Message,
    string ImportHostPath,
    string ImportContainerPath,
    int ProcessedItems,
    int TotalItems,
    int ProgressPercent,
    int SourceVideoCount,
    long SourceVideoBytes,
    int SourceAudioCount,
    long SourceAudioBytes,
    int DuplicateVideos,
    int DuplicateAudio,
    int MissingFiles,
    int ImportVideoCount,
    long ImportVideoBytes,
    int ImportAudioCount,
    long ImportAudioBytes,
    long ImportBytes,
    long CurrentVideoBytes,
    long CurrentAudioBytes,
    long ProjectedVideoBytes,
    long ProjectedAudioBytes,
    long MaxVideoBytes,
    long MaxAudioBytes,
    long AvailableDiskSpaceBytes,
    bool RequiresCapacityConfirmation,
    string[] MissingFileExamples,
    string? BackupPath,
    DateTime? StartedAt,
    DateTime? FinishedAt,
    string? Error);
