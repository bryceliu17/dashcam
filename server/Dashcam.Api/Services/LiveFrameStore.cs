using System.Collections.Concurrent;

namespace Dashcam.Api.Services;

public sealed class LiveFrameStore
{
    private readonly ConcurrentDictionary<string, LiveFrame> frames = new();
    private readonly ConcurrentDictionary<string, DateTime> viewers = new();
    private readonly ConcurrentDictionary<string, byte> requested = new();
    private long sequence;

    public void Set(string deviceId, byte[] jpeg) =>
        frames[deviceId] = new LiveFrame(jpeg, DateTime.UtcNow, Interlocked.Increment(ref sequence));

    public bool TryGet(string deviceId, out LiveFrame frame) =>
        frames.TryGetValue(deviceId, out frame!);

    public async Task<LiveFrame?> WaitForNewAsync(
        string deviceId,
        long afterSequence,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            if (TryGet(deviceId, out var frame) && frame.Sequence > afterSequence) return frame;
            await Task.Delay(25, cancellationToken);
        }
        return null;
    }

    public void SetRequested(string deviceId) => requested[deviceId] = 0;

    public bool IsRequested(string deviceId) => requested.ContainsKey(deviceId);

    public void TouchViewer(string deviceId) => viewers[deviceId] = DateTime.UtcNow;

    public bool HasRecentViewer(string deviceId, TimeSpan maximumAge) =>
        viewers.TryGetValue(deviceId, out var seenAt) && DateTime.UtcNow - seenAt <= maximumAge;

    public void Remove(string deviceId)
    {
        frames.TryRemove(deviceId, out _);
        viewers.TryRemove(deviceId, out _);
        requested.TryRemove(deviceId, out _);
    }
}

public sealed record LiveFrame(byte[] Jpeg, DateTime CapturedAt, long Sequence);
