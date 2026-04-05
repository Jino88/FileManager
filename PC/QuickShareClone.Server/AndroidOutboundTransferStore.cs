using System.Collections.Concurrent;

namespace QuickShareClone.Server;

public sealed class AndroidOutboundTransferStore
{
    private readonly ConcurrentDictionary<string, AndroidOutboundTransfer> _transfers = new();

    public AndroidOutboundTransfer Start(string deviceId, string deviceName, string fileName, long totalBytes)
    {
        var transfer = new AndroidOutboundTransfer
        {
            TransferId = Guid.NewGuid().ToString("N"),
            DeviceId = deviceId,
            DeviceName = deviceName,
            FileName = fileName,
            TotalBytes = totalBytes,
            StatusText = "Waiting for Android approval"
        };

        _transfers[transfer.TransferId] = transfer;
        return transfer;
    }

    public void UpdateProgress(string transferId, long sentBytes, string? statusText = null)
    {
        if (!_transfers.TryGetValue(transferId, out var transfer))
        {
            return;
        }

        lock (transfer)
        {
            transfer.SentBytes = Math.Max(transfer.SentBytes, sentBytes);
            if (!string.IsNullOrWhiteSpace(statusText))
            {
                transfer.StatusText = statusText;
            }

            transfer.UpdatedAt = DateTimeOffset.UtcNow;
        }
    }

    public void Complete(string transferId, string statusText)
    {
        if (!_transfers.TryGetValue(transferId, out var transfer))
        {
            return;
        }

        lock (transfer)
        {
            transfer.SentBytes = transfer.TotalBytes;
            transfer.IsCompleted = true;
            transfer.StatusText = statusText;
            transfer.UpdatedAt = DateTimeOffset.UtcNow;
        }
    }

    public void Fail(string transferId, string statusText)
    {
        if (!_transfers.TryGetValue(transferId, out var transfer))
        {
            return;
        }

        lock (transfer)
        {
            transfer.IsCompleted = true;
            transfer.StatusText = statusText;
            transfer.UpdatedAt = DateTimeOffset.UtcNow;
        }
    }

    public IReadOnlyCollection<AndroidOutboundTransferSummary> List() =>
        _transfers.Values
            .OrderByDescending(x => x.UpdatedAt)
            .Select(x => new AndroidOutboundTransferSummary(
                x.TransferId,
                x.DeviceId,
                x.DeviceName,
                x.FileName,
                x.TotalBytes,
                x.SentBytes,
                x.IsCompleted,
                x.StatusText,
                x.StartedAt,
                x.UpdatedAt))
            .ToArray();
}
