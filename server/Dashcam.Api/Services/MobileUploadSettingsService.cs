using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace Dashcam.Api.Services;

public sealed class MobileUploadSettingsService
{
    private readonly ILogger<MobileUploadSettingsService> logger;
    private readonly string settingsPath;
    private readonly object sync = new();
    private bool allowed;

    public MobileUploadSettingsService(
        IConfiguration configuration,
        ILogger<MobileUploadSettingsService> logger)
    {
        this.logger = logger;
        settingsPath = GetSettingsPath(configuration);
        allowed = Load();
    }

    public bool IsAllowed
    {
        get { lock (sync) return allowed; }
    }

    public bool Save(bool acceptMobileUploads)
    {
        lock (sync)
        {
            var directory = Path.GetDirectoryName(settingsPath);
            if (!string.IsNullOrWhiteSpace(directory)) Directory.CreateDirectory(directory);
            var temporaryPath = settingsPath + ".tmp";
            try
            {
                File.WriteAllText(
                    temporaryPath,
                    JsonSerializer.Serialize(
                        new StoredMobileUploadSettings(acceptMobileUploads),
                        new JsonSerializerOptions { WriteIndented = true }));
                File.Move(temporaryPath, settingsPath, true);
                allowed = acceptMobileUploads;
            }
            finally
            {
                try { if (File.Exists(temporaryPath)) File.Delete(temporaryPath); }
                catch { }
            }
            return allowed;
        }
    }

    private bool Load()
    {
        try
        {
            if (!File.Exists(settingsPath)) return true;
            var stored = JsonSerializer.Deserialize<StoredMobileUploadSettings>(File.ReadAllText(settingsPath));
            return stored?.AcceptMobileUploads ?? true;
        }
        catch (Exception error)
        {
            logger.LogWarning(
                error,
                "Could not read mobile upload settings from {SettingsPath}; accepting uploads by default",
                settingsPath);
            return true;
        }
    }

    private static string GetSettingsPath(IConfiguration configuration)
    {
        var configured = configuration["MobileUploadSettingsPath"];
        if (!string.IsNullOrWhiteSpace(configured)) return Path.GetFullPath(configured);

        var connectionString = configuration.GetConnectionString("DashcamDatabase") ?? "Data Source=dashcam.db";
        var databasePath = Path.GetFullPath(new SqliteConnectionStringBuilder(connectionString).DataSource);
        return Path.Combine(Path.GetDirectoryName(databasePath) ?? AppContext.BaseDirectory, "mobile-upload-settings.json");
    }

    private sealed record StoredMobileUploadSettings(bool AcceptMobileUploads);
}
