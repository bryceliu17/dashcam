using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace Dashcam.Api.Services;

public sealed class DeviceWebSocketHub
{
    private sealed class DeviceConnection(WebSocket socket)
    {
        public WebSocket Socket { get; } = socket;
        public SemaphoreSlim SendGate { get; } = new(1, 1);
    }

    private readonly ConcurrentDictionary<string, DeviceConnection> devices =
        new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, byte> knownDevices =
        new(StringComparer.Ordinal);

    public bool? GetConnectionState(string deviceId)
    {
        if (devices.TryGetValue(deviceId, out var connection) &&
            connection.Socket.State == WebSocketState.Open)
            return true;
        return knownDevices.ContainsKey(deviceId) ? false : null;
    }

    public async Task RunDeviceAsync(
        string deviceId,
        WebSocket socket,
        bool initialLiveRequested,
        CancellationToken cancellationToken)
    {
        var connection = new DeviceConnection(socket);
        knownDevices[deviceId] = 0;
        if (devices.TryGetValue(deviceId, out var previous)) previous.Socket.Abort();
        devices[deviceId] = connection;

        try
        {
            await SendLiveRequestAsync(deviceId, initialLiveRequested, cancellationToken);
            while (!cancellationToken.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                if (!await ReceiveTextAsync(socket, cancellationToken)) break;
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (WebSocketException)
        {
        }
        finally
        {
            if (devices.TryGetValue(deviceId, out var current) && ReferenceEquals(current, connection))
                devices.TryRemove(deviceId, out _);
            socket.Dispose();
        }
    }

    public Task<bool> SendLiveRequestAsync(
        string deviceId,
        bool enabled,
        CancellationToken cancellationToken) =>
        SendJsonAsync(deviceId, new { type = "live_request", enabled }, cancellationToken);

    private Task<bool> SendJsonAsync(
        string deviceId,
        object payload,
        CancellationToken cancellationToken) =>
        SendTextAsync(deviceId, JsonSerializer.Serialize(payload), cancellationToken);

    private async Task<bool> SendTextAsync(
        string deviceId,
        string payload,
        CancellationToken cancellationToken)
    {
        if (!devices.TryGetValue(deviceId, out var connection) ||
            connection.Socket.State != WebSocketState.Open)
            return false;

        var bytes = Encoding.UTF8.GetBytes(payload);
        await connection.SendGate.WaitAsync(cancellationToken);
        try
        {
            if (connection.Socket.State != WebSocketState.Open) return false;
            await connection.Socket.SendAsync(
                bytes,
                WebSocketMessageType.Text,
                endOfMessage: true,
                cancellationToken);
            return true;
        }
        catch (WebSocketException)
        {
            connection.Socket.Abort();
            return false;
        }
        finally
        {
            connection.SendGate.Release();
        }
    }

    private static async Task<bool> ReceiveTextAsync(
        WebSocket socket,
        CancellationToken cancellationToken)
    {
        var buffer = new byte[4096];
        var receivedBytes = 0;
        while (receivedBytes <= 128 * 1024)
        {
            var result = await socket.ReceiveAsync(buffer, cancellationToken);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                    await socket.CloseOutputAsync(
                        WebSocketCloseStatus.NormalClosure,
                        "Closing",
                        CancellationToken.None);
                return false;
            }
            if (result.MessageType != WebSocketMessageType.Text) return false;
            receivedBytes += result.Count;
            if (result.EndOfMessage) return true;
        }
        await socket.CloseOutputAsync(
            WebSocketCloseStatus.MessageTooBig,
            "Message is too large",
            CancellationToken.None);
        return false;
    }
}
