using System.Collections.Concurrent;

namespace Dashcam.Api.Services;

public sealed class LiveCameraBroker
{
    private sealed class PendingRequest(string facing)
    {
        public string RequestId { get; } = Guid.NewGuid().ToString("N");
        public string Facing { get; } = facing;
        public TaskCompletionSource<LiveCameraResponse> Completion { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private readonly ConcurrentDictionary<string, PendingRequest> pendingByDevice =
        new(StringComparer.Ordinal);

    public async Task<LiveCameraResponse> RequestAsync(
        string deviceId,
        string facing,
        Func<string, string, CancellationToken, Task<bool>> dispatch,
        CancellationToken cancellationToken)
    {
        var pending = new PendingRequest(facing);
        if (!pendingByDevice.TryAdd(deviceId, pending))
            throw new InvalidOperationException("A live camera switch is already in progress.");
        try
        {
            if (!await dispatch(pending.RequestId, pending.Facing, cancellationToken))
                throw new InvalidOperationException("The phone control connection is unavailable.");
            return await pending.Completion.Task.WaitAsync(TimeSpan.FromSeconds(8), cancellationToken);
        }
        finally
        {
            if (pendingByDevice.TryGetValue(deviceId, out var current) && ReferenceEquals(current, pending))
                pendingByDevice.TryRemove(deviceId, out _);
        }
    }

    public bool TryComplete(string deviceId, LiveCameraResponse response)
    {
        if (!pendingByDevice.TryGetValue(deviceId, out var pending) ||
            !string.Equals(pending.RequestId, response.RequestId, StringComparison.Ordinal))
            return false;
        return pending.Completion.TrySetResult(response);
    }
}

public sealed record LiveCameraResponse(
    string Type,
    string RequestId,
    string Facing,
    string? Error);
