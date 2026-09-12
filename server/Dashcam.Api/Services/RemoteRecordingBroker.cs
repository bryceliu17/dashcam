using System.Collections.Concurrent;
using System.Text.Json;

namespace Dashcam.Api.Services;

public sealed class RemoteRecordingBroker
{
    private sealed record Pending(string Id, TaskCompletionSource<RemoteRecordingResponse> Completion);
    private readonly ConcurrentDictionary<string, Pending> pending = new(StringComparer.Ordinal);

    public async Task<RemoteRecordingResponse> RequestAsync(string deviceId, RemoteRecordingRequest request,
        DeviceWebSocketHub sockets, CancellationToken cancellationToken)
    {
        var item = new Pending(Guid.NewGuid().ToString("N"),
            new(TaskCreationOptions.RunContinuationsAsynchronously));
        if (!pending.TryAdd(deviceId, item))
            throw new InvalidOperationException("Another recording command is still in progress.");
        try
        {
            if (!await sockets.SendRecordingRequestAsync(deviceId, new
            {
                type = "recording_request", requestId = item.Id,
                expiresAt = DateTimeOffset.UtcNow.AddSeconds(30).ToUnixTimeMilliseconds(),
                action = request.Action, quality = request.Quality,
                segmentMinutes = request.SegmentMinutes, startAlert = request.StartAlert
            }, cancellationToken))
                throw new InvalidOperationException("The phone is disconnected. Reconnect before trying again.");
            return await item.Completion.Task.WaitAsync(TimeSpan.FromSeconds(30), cancellationToken);
        }
        finally { pending.TryRemove(deviceId, out _); }
    }

    public bool HandleResponse(string deviceId, string message)
    {
        try
        {
            using var document = JsonDocument.Parse(message);
            if (!document.RootElement.TryGetProperty("type", out var type) || type.GetString() != "recording_response")
                return false;
            var response = JsonSerializer.Deserialize<RemoteRecordingResponse>(message,
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
            if (response is not null && pending.TryGetValue(deviceId, out var item) && item.Id == response.RequestId)
                item.Completion.TrySetResult(response);
            return true;
        }
        catch (JsonException) { return false; }
    }
}

public sealed record RemoteRecordingRequest(string Action, string? Quality, int? SegmentMinutes, string? StartAlert);
public sealed record RemoteRecordingResponse(string RequestId, bool Success, string? Error,
    bool BackgroundRecordingActive, string? Quality, int SegmentMinutes, string? StartAlert);
