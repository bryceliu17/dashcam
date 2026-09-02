using System.Collections.Concurrent;

namespace Dashcam.Api.Services;

public sealed class LiveTorchBroker
{
    private sealed class PendingRequest(bool enabled)
    {
        public string RequestId { get; } = Guid.NewGuid().ToString("N");
        public bool Enabled { get; } = enabled;
        public TaskCompletionSource<LiveTorchResponse> Completion { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private readonly ConcurrentDictionary<string, PendingRequest> pendingByDevice =
        new(StringComparer.Ordinal);

    public async Task<LiveTorchResponse> RequestAsync(
        string deviceId,
        bool enabled,
        Func<string, bool, CancellationToken, Task<bool>> dispatch,
        CancellationToken cancellationToken)
    {
        var pending = new PendingRequest(enabled);
        if (!pendingByDevice.TryAdd(deviceId, pending))
            throw new InvalidOperationException("A flashlight request is already in progress.");
        try
        {
            if (!await dispatch(pending.RequestId, pending.Enabled, cancellationToken))
                throw new InvalidOperationException("The phone control connection is unavailable.");
            return await pending.Completion.Task.WaitAsync(TimeSpan.FromSeconds(8), cancellationToken);
        }
        finally
        {
            if (pendingByDevice.TryGetValue(deviceId, out var current) && ReferenceEquals(current, pending))
                pendingByDevice.TryRemove(deviceId, out _);
        }
    }

    public bool TryComplete(string deviceId, LiveTorchResponse response)
    {
        if (!pendingByDevice.TryGetValue(deviceId, out var pending) ||
            !string.Equals(pending.RequestId, response.RequestId, StringComparison.Ordinal))
            return false;
        return pending.Completion.TrySetResult(response);
    }
}

public sealed record LiveTorchResponse(
    string Type,
    string RequestId,
    bool Available,
    bool Enabled,
    string? Error);
