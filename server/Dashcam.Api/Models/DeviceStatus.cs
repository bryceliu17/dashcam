namespace Dashcam.Api.Models;

public sealed class DeviceStatus
{
    public required string DeviceId { get; set; }
    public required string DeviceName { get; set; }
    public required string Manufacturer { get; set; }
    public required string Model { get; set; }
    public required string AndroidVersion { get; set; }
    public required string AppVersion { get; set; }
    public int BatteryLevel { get; set; }
    public bool IsCharging { get; set; }
    public required string ChargingSource { get; set; }
    public bool PowerSaveMode { get; set; }
    public bool VideoRecordingActive { get; set; }
    public bool AudioRecordingActive { get; set; }
    public bool LiveAccessEnabled { get; set; }
    public bool LiveRequested { get; set; }
    public bool LiveStreaming { get; set; }
    public required string LiveError { get; set; }
    public DateTime LastSeenAt { get; set; }
    public DateTime FirstSeenAt { get; set; }
}
