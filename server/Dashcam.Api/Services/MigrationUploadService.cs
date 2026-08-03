using System.Globalization;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace Dashcam.Api.Services;

public sealed class MigrationUploadService
{
    public const int ChunkSizeBytes = 8 * 1024 * 1024;
    private readonly IConfiguration configuration;
    private readonly ArchiveMigrationService migration;
    private readonly ILogger<MigrationUploadService> logger;
    private readonly SemaphoreSlim gate = new(1, 1);

    public MigrationUploadService(
        IConfiguration configuration,
        ArchiveMigrationService migration,
        ILogger<MigrationUploadService> logger)
    {
        this.configuration = configuration;
        this.migration = migration;
        this.logger = logger;
    }

    public async Task<UploadCommandResult> CreateOrResumeAsync(MigrationUploadSessionRequest request, CancellationToken token)
    {
        if (!IsHex(request.Fingerprint, 64)) return UploadCommandResult.Fail("Invalid folder fingerprint.");
        if (request.Files is null || request.Files.Count is < 1 or > 100_000)
            return UploadCommandResult.Fail("The selected folder must contain between 1 and 100,000 supported files.");

        var normalizedFiles = new List<MigrationUploadFile>(request.Files.Count);
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var item in request.Files)
        {
            var path = NormalizeRelativePath(item.Path);
            if (path is null || !IsSupportedPath(path) || item.Size <= 0 || item.LastModified < 0)
                return UploadCommandResult.Fail($"Unsupported or unsafe file path: {item.Path}");
            if (!seen.Add(path)) return UploadCommandResult.Fail($"Duplicate file path: {path}");
            normalizedFiles.Add(new(path, item.Size, item.LastModified));
        }
        if (!normalizedFiles.Any(x => string.Equals(x.Path, "dashcam.db", StringComparison.OrdinalIgnoreCase) && x.Size > 0))
            return UploadCommandResult.Fail("The selected folder does not contain a non-empty dashcam.db at its root.");

        var fingerprint = request.Fingerprint!;
        var sessionId = fingerprint[..32].ToLowerInvariant();
        await gate.WaitAsync(token);
        try
        {
            var sessionRoot = GetSessionRoot(sessionId);
            var filesRoot = Path.Combine(sessionRoot, "files");
            var manifestPath = Path.Combine(sessionRoot, "upload-session.json");
            Directory.CreateDirectory(filesRoot);

            UploadManifest manifest;
            if (File.Exists(manifestPath))
            {
                manifest = JsonSerializer.Deserialize<UploadManifest>(await File.ReadAllTextAsync(manifestPath, token))
                    ?? throw new InvalidDataException("The saved upload session is invalid.");
                if (!string.Equals(manifest.Fingerprint, fingerprint, StringComparison.OrdinalIgnoreCase) ||
                    !SameFiles(manifest.Files, normalizedFiles))
                    return UploadCommandResult.Fail("The saved upload session does not match this folder.");
            }
            else
            {
                manifest = new(sessionId, fingerprint.ToLowerInvariant(), CleanLabel(request.RootName),
                    DateTime.UtcNow, normalizedFiles);
                await WriteManifestAsync(manifestPath, manifest, token);
            }

            var response = BuildStatus(manifest, filesRoot);
            if (response.RemainingBytes > response.AvailableDiskSpaceBytes)
                return UploadCommandResult.Fail("There is not enough server disk space to upload this folder.");
            return UploadCommandResult.Ok(response);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
            logger.LogError(error, "Could not create migration upload session");
            return UploadCommandResult.Fail(error.Message);
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task<UploadChunkResult> UploadChunkAsync(
        string sessionId,
        string? requestedPath,
        long offset,
        Stream body,
        long? contentLength,
        CancellationToken token)
    {
        if (!IsHex(sessionId, 32)) return UploadChunkResult.Fail("Invalid upload session.");
        var path = NormalizeRelativePath(requestedPath);
        if (path is null) return UploadChunkResult.Fail("Invalid file path.");
        if (offset < 0) return UploadChunkResult.Fail("Invalid chunk offset.");
        if (contentLength is > ChunkSizeBytes) return UploadChunkResult.Fail("Upload chunk is too large.");

        await gate.WaitAsync(token);
        try
        {
            var sessionRoot = GetSessionRoot(sessionId);
            var manifest = await ReadManifestAsync(sessionRoot, token);
            var expected = manifest.Files.FirstOrDefault(x => string.Equals(x.Path, path, StringComparison.OrdinalIgnoreCase));
            if (expected is null) return UploadChunkResult.Fail("This file is not part of the upload session.");

            var filesRoot = Path.Combine(sessionRoot, "files");
            var finalPath = SafeCombine(filesRoot, expected.Path);
            var partialPath = finalPath + ".part";
            if (File.Exists(finalPath))
            {
                var finalLength = new FileInfo(finalPath).Length;
                if (finalLength == expected.Size) return UploadChunkResult.Ok(finalLength, true);
                return UploadChunkResult.Fail("The completed server file has the wrong size.");
            }

            var currentLength = File.Exists(partialPath) ? new FileInfo(partialPath).Length : 0;
            if (offset != currentLength)
                return UploadChunkResult.OffsetMismatch(currentLength);
            if (offset > expected.Size) return UploadChunkResult.Fail("The server upload is larger than expected.");

            Directory.CreateDirectory(Path.GetDirectoryName(finalPath)!);
            await using (var output = new FileStream(partialPath, FileMode.Append, FileAccess.Write, FileShare.None, 1024 * 1024, true))
            {
                var buffer = new byte[128 * 1024];
                var written = 0L;
                while (true)
                {
                    var read = await body.ReadAsync(buffer, token);
                    if (read == 0) break;
                    written += read;
                    if (written > ChunkSizeBytes || offset + written > expected.Size)
                        throw new InvalidDataException("The upload chunk exceeds the expected file size.");
                    await output.WriteAsync(buffer.AsMemory(0, read), token);
                }
                await output.FlushAsync(token);
            }

            var uploadedBytes = new FileInfo(partialPath).Length;
            if (uploadedBytes == expected.Size)
            {
                File.Move(partialPath, finalPath);
                return UploadChunkResult.Ok(uploadedBytes, true);
            }
            return UploadChunkResult.Ok(uploadedBytes, false);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidDataException or JsonException)
        {
            logger.LogError(error, "Migration upload chunk failed for session {SessionId}", sessionId);
            return UploadChunkResult.Fail(error.Message);
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task<UploadCompleteResult> CompleteAsync(string sessionId, CancellationToken token)
    {
        if (!IsHex(sessionId, 32)) return UploadCompleteResult.Fail("Invalid upload session.");
        await gate.WaitAsync(token);
        try
        {
            var sessionRoot = GetSessionRoot(sessionId);
            var manifest = await ReadManifestAsync(sessionRoot, token);
            var filesRoot = Path.Combine(sessionRoot, "files");
            foreach (var file in manifest.Files)
            {
                var path = SafeCombine(filesRoot, file.Path);
                if (!File.Exists(path) || new FileInfo(path).Length != file.Size)
                    return UploadCompleteResult.Fail($"Upload is incomplete: {file.Path}");
            }

            var result = migration.BeginScan(filesRoot, consumeSource: true,
                displayPath: manifest.RootName, cleanupRoot: sessionRoot);
            return result.Started
                ? UploadCompleteResult.Ok(migration.GetStatus())
                : UploadCompleteResult.Fail(result.Error ?? "Could not start the migration scan.");
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidDataException or JsonException)
        {
            logger.LogError(error, "Could not complete migration upload session {SessionId}", sessionId);
            return UploadCompleteResult.Fail(error.Message);
        }
        finally
        {
            gate.Release();
        }
    }

    private MigrationUploadStatus BuildStatus(UploadManifest manifest, string filesRoot)
    {
        var files = new List<MigrationUploadFileStatus>(manifest.Files.Count);
        var uploadedTotal = 0L;
        foreach (var file in manifest.Files)
        {
            var finalPath = SafeCombine(filesRoot, file.Path);
            var partialPath = finalPath + ".part";
            var uploaded = File.Exists(finalPath) ? new FileInfo(finalPath).Length :
                File.Exists(partialPath) ? new FileInfo(partialPath).Length : 0;
            uploaded = Math.Min(uploaded, file.Size);
            uploadedTotal += uploaded;
            files.Add(new(file.Path, file.Size, uploaded, uploaded == file.Size));
        }
        var total = manifest.Files.Sum(x => x.Size);
        return new(manifest.SessionId, manifest.RootName, ChunkSizeBytes, files.Count, total,
            uploadedTotal, Math.Max(0, total - uploadedTotal), GetAvailableSpace(GetStagingRoot()), files);
    }

    private async Task<UploadManifest> ReadManifestAsync(string sessionRoot, CancellationToken token)
    {
        var manifestPath = Path.Combine(sessionRoot, "upload-session.json");
        if (!File.Exists(manifestPath)) throw new FileNotFoundException("Upload session was not found.");
        return JsonSerializer.Deserialize<UploadManifest>(await File.ReadAllTextAsync(manifestPath, token))
            ?? throw new InvalidDataException("Upload session is invalid.");
    }

    private static async Task WriteManifestAsync(string path, UploadManifest manifest, CancellationToken token)
    {
        var temporary = path + ".tmp";
        await File.WriteAllTextAsync(temporary,
            JsonSerializer.Serialize(manifest, new JsonSerializerOptions { WriteIndented = true }), token);
        File.Move(temporary, path, true);
    }

    private string GetStagingRoot()
    {
        var configured = configuration["ImportStagingPath"];
        if (!string.IsNullOrWhiteSpace(configured)) return Path.GetFullPath(configured);
        var connectionString = configuration.GetConnectionString("DashcamDatabase") ?? "Data Source=dashcam.db";
        var database = Path.GetFullPath(new SqliteConnectionStringBuilder(connectionString).DataSource);
        return Path.Combine(Path.GetDirectoryName(database)!, "import-staging");
    }

    private string GetSessionRoot(string sessionId)
    {
        var root = GetStagingRoot();
        var path = Path.GetFullPath(Path.Combine(root, sessionId));
        if (!IsInside(path, root)) throw new InvalidDataException("Invalid upload session path.");
        return path;
    }

    private static string SafeCombine(string root, string relativePath)
    {
        var parts = relativePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        var path = Path.GetFullPath(Path.Combine([root, .. parts]));
        if (!IsInside(path, root)) throw new InvalidDataException("Invalid upload file path.");
        return path;
    }

    private static string? NormalizeRelativePath(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        var normalized = value.Replace('\\', '/').Trim('/');
        if (normalized.Length is 0 or > 2048) return null;
        var parts = normalized.Split('/');
        if (parts.Any(x => x is "" or "." or ".." || x.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)) return null;
        return string.Join('/', parts);
    }

    private static bool IsSupportedPath(string path)
    {
        if (path.Equals("dashcam.db", StringComparison.OrdinalIgnoreCase) ||
            path.Equals("dashcam.db-wal", StringComparison.OrdinalIgnoreCase) ||
            path.Equals("dashcam.db-shm", StringComparison.OrdinalIgnoreCase)) return true;
        return path.StartsWith("videos/", StringComparison.OrdinalIgnoreCase) && path.EndsWith(".mp4", StringComparison.OrdinalIgnoreCase) ||
            path.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) && path.EndsWith(".m4a", StringComparison.OrdinalIgnoreCase);
    }

    private static bool SameFiles(IReadOnlyCollection<MigrationUploadFile> left, IReadOnlyCollection<MigrationUploadFile> right)
    {
        if (left.Count != right.Count) return false;
        var expected = left.ToDictionary(x => x.Path, StringComparer.OrdinalIgnoreCase);
        return right.All(x => expected.TryGetValue(x.Path, out var item) && item.Size == x.Size && item.LastModified == x.LastModified);
    }

    private static bool IsHex(string? value, int length) => value?.Length == length && value.All(Uri.IsHexDigit);

    private static string CleanLabel(string? value)
    {
        var label = string.IsNullOrWhiteSpace(value) ? "Selected browser folder" : value.Trim();
        return label.Length <= 160 ? label : label[..160];
    }

    private static bool IsInside(string path, string root)
    {
        var normalizedRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        return Path.GetFullPath(path).StartsWith(normalizedRoot, StringComparison.OrdinalIgnoreCase);
    }

    private static long GetAvailableSpace(string path)
    {
        Directory.CreateDirectory(path);
        var fullPath = Path.GetFullPath(path);
        var drive = DriveInfo.GetDrives()
            .Where(x => x.IsReady && fullPath.StartsWith(x.RootDirectory.FullName, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(x => x.RootDirectory.FullName.Length)
            .FirstOrDefault();
        return drive?.AvailableFreeSpace ?? 0;
    }

    private sealed record UploadManifest(
        string SessionId,
        string Fingerprint,
        string RootName,
        DateTime CreatedAt,
        List<MigrationUploadFile> Files);
}

public sealed record MigrationUploadSessionRequest(
    string? Fingerprint,
    string? RootName,
    List<MigrationUploadFile>? Files);
public sealed record MigrationUploadFile(string Path, long Size, long LastModified);
public sealed record MigrationUploadFileStatus(string Path, long Size, long UploadedBytes, bool Complete);
public sealed record MigrationUploadStatus(
    string SessionId,
    string RootName,
    int ChunkSizeBytes,
    int FileCount,
    long TotalBytes,
    long UploadedBytes,
    long RemainingBytes,
    long AvailableDiskSpaceBytes,
    List<MigrationUploadFileStatus> Files);
public sealed record UploadCommandResult(bool Success, string? Error, MigrationUploadStatus? Status)
{
    public static UploadCommandResult Ok(MigrationUploadStatus status) => new(true, null, status);
    public static UploadCommandResult Fail(string error) => new(false, error, null);
}
public sealed record UploadChunkResult(bool Success, string? Error, long UploadedBytes, bool Complete, long? ExpectedOffset)
{
    public static UploadChunkResult Ok(long uploadedBytes, bool complete) => new(true, null, uploadedBytes, complete, null);
    public static UploadChunkResult Fail(string error) => new(false, error, 0, false, null);
    public static UploadChunkResult OffsetMismatch(long expectedOffset) => new(false, "Upload offset does not match the server.", expectedOffset, false, expectedOffset);
}
public sealed record UploadCompleteResult(bool Success, string? Error, ArchiveMigrationStatus? Status)
{
    public static UploadCompleteResult Ok(ArchiveMigrationStatus status) => new(true, null, status);
    public static UploadCompleteResult Fail(string error) => new(false, error, null);
}
