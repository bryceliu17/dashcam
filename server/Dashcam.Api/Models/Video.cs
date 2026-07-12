namespace Dashcam.Api.Models;

public sealed class Video
{
    public int Id { get; set; }
    public required string Filename { get; set; }
    public required string OriginalFilename { get; set; }
    public required string FilePath { get; set; }
    public DateTime StartTime { get; set; }
    public DateTime EndTime { get; set; }
    public int DurationSeconds { get; set; }
    public long FileSizeBytes { get; set; }
    public bool Locked { get; set; }
    public int PlaybackRotationDegrees { get; set; }
    public DateTime UploadedAt { get; set; }
    public DateTime CreatedAt { get; set; }
}
