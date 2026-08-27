using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace Dashcam.Api.Services;

public sealed class ArchiveStorageSettingsService
{
    public const double RecommendationRatio = 0.76;
    private const double DefaultVideoGb = 350;
    private const double DefaultAudioGb = 20;
    private const double MinimumLimitGb = 0.1;
    private const double MaximumLimitGb = 1_000_000;
    private const long BytesPerGiB = 1024L * 1024 * 1024;

    private readonly IConfiguration configuration;
    private readonly ILogger<ArchiveStorageSettingsService> logger;
    private readonly string settingsPath;
    private readonly object sync = new();
    private ArchiveStorageLimits limits;

    public ArchiveStorageSettingsService(
        IConfiguration configuration,
        ILogger<ArchiveStorageSettingsService> logger)
    {
        this.configuration = configuration;
        this.logger = logger;
        settingsPath = GetSettingsPath(configuration);
        limits = Load() ?? ReadConfiguredDefaults(configuration);
    }

    public ArchiveStorageLimits GetLimits()
    {
        lock (sync) return limits;
    }

    public ArchiveStorageLimits Save(double maxVideoStorageGb, double maxAudioStorageGb)
    {
        ValidateLimit(maxVideoStorageGb, "Video storage");
        ValidateLimit(maxAudioStorageGb, "Audio storage");
        var updated = CreateLimits(maxVideoStorageGb, maxAudioStorageGb);

        lock (sync)
        {
            var directory = Path.GetDirectoryName(settingsPath);
            if (!string.IsNullOrWhiteSpace(directory)) Directory.CreateDirectory(directory);
            var temporaryPath = settingsPath + ".tmp";
            try
            {
                File.WriteAllText(temporaryPath, JsonSerializer.Serialize(new StoredArchiveStorageSettings(
                    updated.MaxVideoStorageGb,
                    updated.MaxAudioStorageGb), new JsonSerializerOptions { WriteIndented = true }));
                File.Move(temporaryPath, settingsPath, true);
                limits = updated;
            }
            finally
            {
                try { if (File.Exists(temporaryPath)) File.Delete(temporaryPath); }
                catch { }
            }
        }

        return updated;
    }

    public ArchiveDiskRecommendation GetRecommendation()
    {
        var videoRoot = GetStorageRoot("VideoStoragePath", "videos");
        var audioRoot = GetStorageRoot("AudioStoragePath", "audio");
        Directory.CreateDirectory(videoRoot);
        Directory.CreateDirectory(audioRoot);

        var videoDrive = FindDrive(videoRoot);
        var audioDrive = FindDrive(audioRoot);
        var sameDrive = videoDrive is not null && audioDrive is not null &&
            string.Equals(videoDrive.RootDirectory.FullName, audioDrive.RootDirectory.FullName, StringComparison.OrdinalIgnoreCase);
        var drive = videoDrive ?? audioDrive;
        if (drive is null)
            return new ArchiveDiskRecommendation(0, 0, 0, 0, 0, sameDrive);

        var totalBytes = drive.TotalSize;
        var availableBytes = drive.AvailableFreeSpace;
        var recommendationBytes = (long)(totalBytes * RecommendationRatio);
        var current = GetLimits();
        var combinedGb = current.MaxVideoStorageGb + current.MaxAudioStorageGb;
        var videoRatio = combinedGb > 0 ? current.MaxVideoStorageGb / combinedGb : 0.95;
        var recommendedVideoBytes = RoundToTenthGiB((long)(recommendationBytes * videoRatio));
        var recommendedAudioBytes = Math.Max(
            (long)(MinimumLimitGb * BytesPerGiB),
            recommendationBytes - recommendedVideoBytes);
        recommendedVideoBytes = Math.Max(
            (long)(MinimumLimitGb * BytesPerGiB),
            recommendationBytes - recommendedAudioBytes);

        return new ArchiveDiskRecommendation(
            totalBytes,
            availableBytes,
            recommendationBytes,
            recommendedVideoBytes,
            recommendedAudioBytes,
            sameDrive);
    }

    private ArchiveStorageLimits? Load()
    {
        try
        {
            if (!File.Exists(settingsPath)) return null;
            var stored = JsonSerializer.Deserialize<StoredArchiveStorageSettings>(File.ReadAllText(settingsPath));
            if (stored is null) return null;
            ValidateLimit(stored.MaxVideoStorageGb, "Video storage");
            ValidateLimit(stored.MaxAudioStorageGb, "Audio storage");
            return CreateLimits(stored.MaxVideoStorageGb, stored.MaxAudioStorageGb);
        }
        catch (Exception error)
        {
            logger.LogWarning(error, "Could not read archive storage settings from {SettingsPath}; using configured defaults", settingsPath);
            return null;
        }
    }

    private string GetStorageRoot(string key, string fallbackDirectory)
    {
        var configured = configuration[key];
        return Path.GetFullPath(string.IsNullOrWhiteSpace(configured)
            ? Path.Combine(AppContext.BaseDirectory, fallbackDirectory)
            : configured);
    }

    private static DriveInfo? FindDrive(string path)
    {
        var fullPath = Path.GetFullPath(path);
        return DriveInfo.GetDrives()
            .Where(drive => drive.IsReady && fullPath.StartsWith(
                drive.RootDirectory.FullName,
                OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal))
            .OrderByDescending(drive => drive.RootDirectory.FullName.Length)
            .FirstOrDefault();
    }

    private static long RoundToTenthGiB(long bytes) =>
        (long)(Math.Round(bytes / (double)BytesPerGiB, 1, MidpointRounding.AwayFromZero) * BytesPerGiB);

    private static ArchiveStorageLimits ReadConfiguredDefaults(IConfiguration configuration) => CreateLimits(
        configuration.GetValue<double?>("MaxStorageGB") ?? DefaultVideoGb,
        configuration.GetValue<double?>("MaxAudioStorageGB") ?? DefaultAudioGb);

    private static ArchiveStorageLimits CreateLimits(double videoGb, double audioGb) => new(
        videoGb,
        audioGb,
        checked((long)(videoGb * BytesPerGiB)),
        checked((long)(audioGb * BytesPerGiB)));

    private static void ValidateLimit(double value, string label)
    {
        if (!double.IsFinite(value) || value < MinimumLimitGb || value > MaximumLimitGb)
            throw new ArgumentOutOfRangeException(nameof(value), $"{label} must be between {MinimumLimitGb} and {MaximumLimitGb:N0} GB.");
    }

    private static string GetSettingsPath(IConfiguration configuration)
    {
        var configured = configuration["ArchiveStorageSettingsPath"];
        if (!string.IsNullOrWhiteSpace(configured)) return Path.GetFullPath(configured);

        var connectionString = configuration.GetConnectionString("DashcamDatabase") ?? "Data Source=dashcam.db";
        var databasePath = Path.GetFullPath(new SqliteConnectionStringBuilder(connectionString).DataSource);
        return Path.Combine(Path.GetDirectoryName(databasePath) ?? AppContext.BaseDirectory, "archive-storage-settings.json");
    }

    private sealed record StoredArchiveStorageSettings(double MaxVideoStorageGb, double MaxAudioStorageGb);
}

public sealed record ArchiveStorageLimits(
    double MaxVideoStorageGb,
    double MaxAudioStorageGb,
    long MaxVideoStorageBytes,
    long MaxAudioStorageBytes);

public sealed record ArchiveDiskRecommendation(
    long DiskTotalBytes,
    long DiskAvailableBytes,
    long RecommendedCombinedStorageBytes,
    long RecommendedVideoStorageBytes,
    long RecommendedAudioStorageBytes,
    bool UsesSharedDisk);
