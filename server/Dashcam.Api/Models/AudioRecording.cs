namespace Dashcam.Api.Models;

public sealed class AudioRecording
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
    public string TranscriptStatus { get; set; } = "none";
    public string TranscriptText { get; set; } = string.Empty;
    public string TranscriptLanguage { get; set; } = string.Empty;
    public double TranscriptLanguageProbability { get; set; }
    public string TranscriptModel { get; set; } = string.Empty;
    public string TranscriptSegmentsJson { get; set; } = string.Empty;
    public string TranscriptError { get; set; } = string.Empty;
    public DateTime? TranscriptCreatedAt { get; set; }
    public DateTime UploadedAt { get; set; }
    public DateTime CreatedAt { get; set; }
}
