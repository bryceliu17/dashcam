using System.Collections.Concurrent;

namespace Dashcam.Api.Services;

public sealed class LiveFrameStore
{
    private readonly ConcurrentDictionary<string, LiveFrame> frames = new();
    private readonly ConcurrentDictionary<string, DateTime> viewers = new();

    public void Set(string deviceId, byte[] jpeg) =>
        frames[deviceId] = new LiveFrame(jpeg, DateTime.UtcNow);

    public bool TryGet(string deviceId, out LiveFrame frame) =>
        frames.TryGetValue(deviceId, out frame!);

    public void TouchViewer(string deviceId) => viewers[deviceId] = DateTime.UtcNow;

    public bool HasRecentViewer(string deviceId, TimeSpan maximumAge) =>
        viewers.TryGetValue(deviceId, out var seenAt) && DateTime.UtcNow - seenAt <= maximumAge;

    public void Remove(string deviceId)
    {
        frames.TryRemove(deviceId, out _);
        viewers.TryRemove(deviceId, out _);
    }
}

public sealed record LiveFrame(byte[] Jpeg, DateTime CapturedAt);
