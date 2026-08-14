using System.Collections.Concurrent;

namespace Dashcam.Api.Services;

public sealed class BatteryHistoryBroker
{
    private sealed class PendingRequest(string deviceId, int hours)
    {
        public string DeviceId { get; } = deviceId;
        public string RequestId { get; } = Guid.NewGuid().ToString("N");
        public int Hours { get; } = hours;
        public TaskCompletionSource<BatteryHistoryResponse> Completion { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private readonly ConcurrentDictionary<string, PendingRequest> pendingByDevice =
        new(StringComparer.Ordinal);

    public object? GetPendingRequest(string deviceId)
    {
        if (!pendingByDevice.TryGetValue(deviceId, out var pending)) return null;
        return new { pending.RequestId, pending.Hours };
    }

    public async Task<BatteryHistoryResponse> RequestAsync(
        string deviceId,
        int hours,
        Func<string, int, CancellationToken, Task> dispatch,
        CancellationToken cancellationToken)
    {
        var pending = pendingByDevice.GetOrAdd(deviceId, id => new PendingRequest(id, hours));
        await dispatch(pending.RequestId, pending.Hours, cancellationToken);
        try
        {
            return await pending.Completion.Task.WaitAsync(TimeSpan.FromSeconds(70), cancellationToken);
        }
        finally
        {
            if (pendingByDevice.TryGetValue(deviceId, out var current) && ReferenceEquals(current, pending))
                pendingByDevice.TryRemove(deviceId, out _);
        }
    }

    public bool TryComplete(string deviceId, BatteryHistoryResponse response)
    {
        if (!pendingByDevice.TryGetValue(deviceId, out var pending) ||
            !string.Equals(pending.RequestId, response.RequestId, StringComparison.Ordinal))
            return false;
        return pending.Completion.TrySetResult(response);
    }
}

public sealed record BatteryHistorySample(
    long RecordedAt,
    int TemperatureTenthsC,
    int BatteryLevel,
    bool IsCharging,
    bool VideoRecordingActive,
    bool AudioRecordingActive);

public sealed record BatteryHistoryResponse(
    string RequestId,
    long GeneratedAt,
    IReadOnlyList<BatteryHistorySample> Items);
