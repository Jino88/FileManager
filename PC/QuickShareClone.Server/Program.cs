using Microsoft.AspNetCore.Http.Features;
using QuickShareClone.Server;
using System.Net;
using System.Net.Http.Headers;
using System.Text;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(options =>
{
    options.Limits.MaxRequestBodySize = null;
});

builder.Services.Configure<UploadOptions>(builder.Configuration.GetSection("UploadOptions"));
builder.Services.Configure<DiscoveryOptions>(builder.Configuration.GetSection("DiscoveryOptions"));
builder.Services.Configure<FormOptions>(options => options.MultipartBodyLengthLimit = long.MaxValue);
builder.Services.AddSingleton<UploadStore>();
builder.Services.AddSingleton<ChunkFileService>();
builder.Services.AddSingleton<DeviceIdentityService>();
builder.Services.AddSingleton<AndroidDeviceStore>();
builder.Services.AddSingleton<AndroidOutboundTransferStore>();
builder.Services.AddHostedService<CleanupService>();
builder.Services.AddHostedService<DiscoveryBroadcastService>();

var app = builder.Build();

app.MapGet("/", (DeviceIdentityService identityService) =>
{
    var device = identityService.GetCurrentDevice();
    var primaryUrl = device.ServerUrls.FirstOrDefault() ?? "http://127.0.0.1:5070";
    var html =
        $$"""
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Quick Share Clone</title>
          <style>
            :root {
              --bg: #f3efe6;
              --panel: rgba(255,255,255,.78);
              --ink: #1d1d1f;
              --muted: #5f6368;
              --accent: #0f766e;
              --accent2: #f97316;
              --line: rgba(29,29,31,.08);
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              font-family: "Segoe UI", "Pretendard", sans-serif;
              color: var(--ink);
              background:
                radial-gradient(circle at top left, rgba(15,118,110,.18), transparent 28%),
                radial-gradient(circle at top right, rgba(249,115,22,.18), transparent 25%),
                linear-gradient(135deg, #f7f3eb, #eef5f3 60%, #f8efe8);
              min-height: 100vh;
            }
            .shell {
              max-width: 1120px;
              margin: 0 auto;
              padding: 32px 24px 48px;
            }
            .panel {
              background: var(--panel);
              backdrop-filter: blur(18px);
              border: 1px solid rgba(255,255,255,.55);
              box-shadow: 0 20px 70px rgba(25, 33, 61, .10);
              border-radius: 28px;
            }
            .hero { margin-bottom: 20px; }
            .eyebrow {
              display: inline-block;
              padding: 8px 12px;
              background: rgba(15,118,110,.10);
              color: var(--accent);
              border-radius: 999px;
              font-size: 12px;
              font-weight: 700;
              letter-spacing: .08em;
              text-transform: uppercase;
            }
            h1 { margin: 16px 0 12px; font-size: 46px; line-height: 1; }
            p { margin: 0; color: var(--muted); line-height: 1.5; }
            .stats {
              display: grid;
              grid-template-columns: repeat(2, minmax(0, 1fr));
              gap: 12px;
            }
            .stat {
              padding: 18px;
              background: rgba(255,255,255,.82);
              border: 1px solid var(--line);
              border-radius: 22px;
            }
            .stat strong { display: block; font-size: 28px; margin-bottom: 8px; }
            .grid { display: grid; gap: 20px; }
            .panel { padding: 22px; }
            .panel h2 { margin: 0 0 16px; font-size: 20px; }
            .device-pill, .url-chip {
              display: inline-flex;
              align-items: center;
              gap: 8px;
              padding: 10px 14px;
              border-radius: 999px;
              background: rgba(15,118,110,.08);
              margin: 0 10px 10px 0;
              color: var(--accent);
              font-weight: 600;
            }
            .url-chip { background: rgba(249,115,22,.10); color: #c2410c; }
            .session-list {
              display: grid;
              gap: 12px;
            }
            .session-item {
              padding: 16px;
              border-radius: 22px;
              background: rgba(255,255,255,.75);
              border: 1px solid var(--line);
            }
            .device-item {
              padding: 16px;
              border-radius: 22px;
              background: rgba(255,255,255,.75);
              border: 1px solid var(--line);
            }
            .session-footer {
              display: flex;
              justify-content: space-between;
              gap: 12px;
              margin-top: 10px;
              flex-wrap: wrap;
            }
            .field {
              width: 100%;
              padding: 12px 14px;
              border-radius: 14px;
              border: 1px solid var(--line);
              background: rgba(255,255,255,.9);
              color: var(--ink);
            }
            .button {
              border: 0;
              border-radius: 14px;
              padding: 12px 18px;
              background: var(--ink);
              color: white;
              font-weight: 700;
              cursor: pointer;
            }
            .button:disabled {
              opacity: .5;
              cursor: wait;
            }
            .session-item header {
              display: flex;
              justify-content: space-between;
              gap: 12px;
              margin-bottom: 10px;
            }
            .muted { color: var(--muted); font-size: 13px; }
            .bar {
              height: 10px;
              background: rgba(29,29,31,.08);
              border-radius: 999px;
              overflow: hidden;
            }
            .bar > span {
              display: block;
              height: 100%;
              background: linear-gradient(90deg, var(--accent), #14b8a6);
              border-radius: inherit;
            }
            .empty {
              padding: 22px;
              border-radius: 22px;
              background: rgba(255,255,255,.6);
              color: var(--muted);
            }
            @media (max-width: 860px) { h1 { font-size: 36px; } }
          </style>
        </head>
        <body>
          <div class="shell">
            <section class="panel hero">
              <div>
                <span class="eyebrow">Nearby Share Node</span>
                <h1>{{device.DeviceName}}</h1>
                <p>{{primaryUrl}}</p>
              </div>
              <div class="stats">
                <div class="stat">
                  <strong id="sessionCount">0</strong>
                  <span class="muted">active upload sessions</span>
                </div>
                <div class="stat">
                  <strong id="completedCount">0</strong>
                  <span class="muted">completed transfers</span>
                </div>
                <div class="stat">
                  <strong id="androidCount">0</strong>
                  <span class="muted">connected Android devices</span>
                </div>
                <div class="stat">
                  <strong>UDP {{builder.Configuration["DiscoveryOptions:BroadcastPort"] ?? "37845"}}</strong>
                  <span class="muted">beacon port</span>
                </div>
              </div>
            </section>

            <section class="panel">
              <h2>Connected Android</h2>
              <div id="androidDevices" class="session-list">
                <div class="empty">No Android devices have registered yet.</div>
              </div>
            </section>

            <section class="panel" style="margin-top:20px">
              <h2>Send To Android</h2>
              <form id="sendToAndroidForm" style="display:grid; gap:12px;">
                <select id="androidDeviceSelect" class="field">
                  <option value="">Choose an Android device</option>
                </select>
                <input id="androidFileInput" class="field" type="file" multiple>
                <button id="androidSendButton" class="button" type="submit">Send To Android</button>
                <div id="androidSendStatus" class="muted">Open the Android app first so it can register with this PC.</div>
              </form>
            </section>

            <section class="panel" style="margin-top:20px">
              <h2>Transfer History</h2>
              <div id="androidOutboundTransfers" class="session-list" style="margin-bottom:16px">
                <div class="empty">No PC to Android transfers yet.</div>
              </div>
              <div id="sessions" class="session-list">
                <div class="empty">Waiting for transfers...</div>
              </div>
            </section>
          </div>
          <script>
            async function refreshAndroidDevices() {
              const response = await fetch('/api/android/devices');
              const devices = await response.json();
              document.getElementById('androidCount').textContent = devices.length;
              const container = document.getElementById('androidDevices');
              const select = document.getElementById('androidDeviceSelect');
              const currentValue = select.value;

              select.innerHTML = '<option value="">Choose an Android device</option>' + devices.map(device =>
                `<option value="${device.deviceId}">${device.deviceName} - ${device.receiveUrl}</option>`
              ).join('');

              if (devices.some(device => device.deviceId === currentValue)) {
                select.value = currentValue;
              }

              if (!devices.length) {
                container.innerHTML = '<div class="empty">No Android devices have registered yet.</div>';
                return;
              }

              container.innerHTML = devices.map(device => `
                <article class="device-item">
                  <header>
                    <div>
                      <strong>${device.deviceName}</strong>
                      <div class="muted">${device.receiveUrl}</div>
                    </div>
                    <div>${device.platform}</div>
                  </header>
                  <div class="muted">Last seen ${new Date(device.lastSeenAt).toLocaleTimeString()}</div>
                </article>
              `).join('');
            }

            async function refreshAndroidOutboundTransfers() {
              const response = await fetch('/api/android/outbound-transfers');
              const transfers = await response.json();
              const container = document.getElementById('androidOutboundTransfers');
              window.__androidOutboundSamples = window.__androidOutboundSamples || {};
              const now = Date.now();

              if (!transfers.length) {
                container.innerHTML = '<div class="empty">No PC to Android transfers yet.</div>';
                return;
              }

              container.innerHTML = transfers.map(transfer => {
                const progress = transfer.totalBytes > 0
                  ? Math.round((transfer.sentBytes / transfer.totalBytes) * 100)
                  : 0;
                const sample = window.__androidOutboundSamples[transfer.transferId];
                const deltaBytes = sample ? Math.max(0, transfer.sentBytes - sample.bytes) : 0;
                const deltaMs = sample ? Math.max(1, now - sample.ts) : 1;
                const speedMBps = (deltaBytes / 1024 / 1024) / (deltaMs / 1000);
                window.__androidOutboundSamples[transfer.transferId] = { bytes: transfer.sentBytes, ts: now };
                const speedText = transfer.isCompleted
                  ? transfer.statusText
                  : `${speedMBps.toFixed(1)} MB/s (${progress}%)`;

                return `
                  <article class="session-item">
                    <header>
                      <div>
                        <strong>${transfer.fileName}</strong>
                        <div class="muted">To ${transfer.deviceName}</div>
                      </div>
                      <div>${transfer.isCompleted ? 'Done' : progress + '%'}</div>
                    </header>
                    <div class="bar"><span style="width:${Math.min(progress, 100)}%"></span></div>
                    <div class="session-footer">
                      <div class="muted">${speedText}</div>
                      <div class="muted">${Math.round(transfer.sentBytes / 1024 / 1024)} / ${Math.max(1, Math.round(transfer.totalBytes / 1024 / 1024))} MB</div>
                    </div>
                  </article>
                `;
              }).join('');
            }

            async function refresh() {
              const [sessionResponse] = await Promise.all([
                fetch('/api/upload/sessions'),
                refreshAndroidDevices(),
                refreshAndroidOutboundTransfers()
              ]);
              const sessions = await sessionResponse.json();
              window.__quickShareSamples = window.__quickShareSamples || {};
              const now = Date.now();
              document.getElementById('sessionCount').textContent = sessions.length;
              document.getElementById('completedCount').textContent = sessions.filter(x => x.isCompleted).length;
              const container = document.getElementById('sessions');
              if (!sessions.length) {
                container.innerHTML = '<div class="empty">Waiting for transfers...</div>';
                return;
              }

              container.innerHTML = sessions.map(session => {
                const total = session.totalChunks ?? 0;
                const received = session.receivedChunkCount;
                const progress = session.totalBytes > 0
                  ? Math.round(session.receivedBytes / session.totalBytes * 100)
                  : (total > 0 ? Math.round(received / total * 100) : 0);
                const sample = window.__quickShareSamples[session.fileId];
                const deltaBytes = sample ? Math.max(0, session.receivedBytes - sample.bytes) : 0;
                const deltaMs = sample ? Math.max(1, now - sample.ts) : 1;
                const speedMBps = (deltaBytes / 1024 / 1024) / (deltaMs / 1000);
                window.__quickShareSamples[session.fileId] = { bytes: session.receivedBytes, ts: now };
                const speedText = session.isCompleted
                  ? 'Completed'
                  : (session.destinationSelected
                      ? `${speedMBps.toFixed(1)} MB/s (${progress}%)`
                      : 'Waiting for folder selection on PC');
                const receivedMb = (session.receivedBytes / 1024 / 1024).toFixed(1);
                const totalMb = session.totalBytes > 0
                  ? (session.totalBytes / 1024 / 1024).toFixed(1)
                  : null;
                const locationText = session.finalFilePath
                  ? `Saved to: ${session.finalFilePath}`
                  : (session.destinationDirectory
                      ? `Destination: ${session.destinationDirectory}`
                      : 'Destination pending');
                return `
                  <article class="session-item">
                    <header>
                      <div>
                        <strong>${session.fileName}</strong>
                        <div class="muted">${session.fileId}</div>
                      </div>
                      <div>${session.isCompleted ? 'Completed' : progress + '%'}</div>
                    </header>
                    <div class="bar"><span style="width:${progress}%"></span></div>
                    <div class="session-footer">
                      <div class="muted">${speedText}</div>
                      <div class="muted">${totalMb ? `${receivedMb} / ${totalMb} MB` : `${receivedMb} MB received`}</div>
                    </div>
                    <div class="muted" style="margin-top:8px">${locationText}</div>
                  </article>
                `;
              }).join('');
            }

            document.getElementById('sendToAndroidForm').addEventListener('submit', async (event) => {
              event.preventDefault();
              const deviceId = document.getElementById('androidDeviceSelect').value;
              const files = document.getElementById('androidFileInput').files;
              const button = document.getElementById('androidSendButton');
              const status = document.getElementById('androidSendStatus');

              if (!deviceId) {
                status.textContent = 'Choose an Android device first.';
                return;
              }

              if (!files.length) {
                status.textContent = 'Choose at least one file from this PC first.';
                return;
              }

              const formData = new FormData();
              formData.append('deviceId', deviceId);
              Array.from(files).forEach(file => formData.append('files', file, file.name));

              button.disabled = true;
              status.textContent = 'Sending files to Android...';

              try {
                const response = await fetch('/api/android/send', {
                  method: 'POST',
                  body: formData
                });
                const result = await response.json();
                if (!response.ok) {
                  throw new Error(result.message || 'Send failed');
                }

                const failures = (result.results || []).filter(item => !item.success);
                status.textContent = failures.length
                  ? failures.map(item => `${item.fileName}: ${item.message}`).join(' | ')
                  : `Sent ${result.results.length} file(s) to Android.`;
                document.getElementById('androidFileInput').value = '';
              } catch (error) {
                status.textContent = error.message || 'Send failed';
              } finally {
                button.disabled = false;
              }
            });

            refresh();
            setInterval(refresh, 2000);
          </script>
        </body>
        </html>
        """;

    return Results.Content(html, "text/html; charset=utf-8");
});

app.MapGet("/api/upload/status", (string fileId, ChunkFileService chunks) =>
{
    var session = app.Services.GetRequiredService<UploadStore>().Find(fileId);
    return Results.Ok(new UploadStatusResponse(
        fileId,
        chunks.GetReceivedChunks(fileId),
        !string.IsNullOrWhiteSpace(session?.DestinationDirectory)));
});

app.MapGet("/api/upload/sessions", (UploadStore store) => Results.Ok(store.List()));
app.MapGet("/api/discovery/self", (DeviceIdentityService identityService) => Results.Ok(identityService.GetCurrentDevice()));
app.MapGet("/api/android/devices", (AndroidDeviceStore store) => Results.Ok(store.List()));
app.MapGet("/api/android/outbound-transfers", (AndroidOutboundTransferStore store) => Results.Ok(store.List()));

app.MapPost("/api/android/register", (AndroidDeviceRegistrationRequest request, AndroidDeviceStore store) =>
{
    if (string.IsNullOrWhiteSpace(request.DeviceId) ||
        string.IsNullOrWhiteSpace(request.DeviceName) ||
        string.IsNullOrWhiteSpace(request.ReceiveUrl))
    {
        return Results.BadRequest(new { message = "Invalid Android registration payload." });
    }

    store.Register(request);
    app.Logger.LogInformation("Android device registered: {DeviceName} -> {ReceiveUrl}", request.DeviceName, request.ReceiveUrl);
    return Results.Ok();
});

app.MapPost("/api/android/send", async (HttpRequest request, AndroidDeviceStore deviceStore, AndroidOutboundTransferStore transferStore, CancellationToken cancellationToken) =>
{
    if (!request.HasFormContentType)
    {
        return Results.BadRequest(new { message = "Multipart form-data is required." });
    }

    var form = await request.ReadFormAsync(cancellationToken);
    var deviceId = form["deviceId"].ToString();
    if (string.IsNullOrWhiteSpace(deviceId))
    {
        return Results.BadRequest(new { message = "deviceId is required." });
    }

    var device = deviceStore.Find(deviceId);
    if (device is null)
    {
        return Results.NotFound(new { message = "Android device is not registered." });
    }

    var files = form.Files.GetFiles("files");
    if (files.Count == 0)
    {
        return Results.BadRequest(new { message = "Choose at least one file to send." });
    }

    using var httpClient = new HttpClient
    {
        Timeout = TimeSpan.FromMinutes(15)
    };

    app.Logger.LogInformation(
        "PC -> Android send request received. Device={DeviceName}, FileCount={FileCount}",
        device.DeviceName,
        files.Count);

    var offer = new AndroidTransferOfferRequest(
        OfferId: Guid.NewGuid().ToString("N"),
        FileCount: files.Count,
        TotalBytes: files.Sum(static x => x.Length),
        Files: files.Select(static file => new AndroidTransferOfferFile(file.FileName, file.Length)).ToArray());

    using (var offerResponse = await httpClient.PostAsJsonAsync(
               $"{device.ReceiveUrl}/api/device/offer",
               offer,
               cancellationToken))
    {
        var offerResponseText = await offerResponse.Content.ReadAsStringAsync(cancellationToken);
        if (!offerResponse.IsSuccessStatusCode)
        {
            app.Logger.LogWarning(
                "PC -> Android batch offer rejected for {DeviceName}: {ResponseText}",
                device.DeviceName,
                offerResponseText);
            return Results.Json(
                new { message = offerResponseText },
                statusCode: (int)offerResponse.StatusCode);
        }
    }

    var approvalDeadline = DateTimeOffset.UtcNow.AddSeconds(60);
    while (DateTimeOffset.UtcNow < approvalDeadline)
    {
        using var statusResponse = await httpClient.GetAsync(
            $"{device.ReceiveUrl}/api/device/offer/status?offerId={offer.OfferId}",
            cancellationToken);
        var statusText = await statusResponse.Content.ReadAsStringAsync(cancellationToken);

        if (statusResponse.StatusCode == HttpStatusCode.Conflict)
        {
            return Results.Json(new { message = "Transfer declined on Android." }, statusCode: 409);
        }

        if (statusResponse.IsSuccessStatusCode && statusText.Contains("\"approved\"", StringComparison.OrdinalIgnoreCase))
        {
            break;
        }

        await Task.Delay(500, cancellationToken);
    }

    if (DateTimeOffset.UtcNow >= approvalDeadline)
    {
        return Results.Json(new { message = "Android approval timed out." }, statusCode: 408);
    }

    var results = new List<AndroidTransferResult>(files.Count);
    foreach (var file in files)
    {
        var transfer = transferStore.Start(deviceId, device.DeviceName, file.FileName, file.Length);
        try
        {
            app.Logger.LogInformation(
                "PC -> Android preparing file {FileName} ({FileSizeBytes} bytes) for {DeviceName}",
                file.FileName,
                file.Length,
                device.DeviceName);

            await using var stream = file.OpenReadStream();
            using var content = new ProgressStreamContent(
                stream,
                file.Length,
                bytesSent => transferStore.UpdateProgress(transfer.TransferId, bytesSent, "Sending to Android"),
                onTransferStarted: () =>
                {
                    app.Logger.LogInformation(
                        "PC -> Android first byte started for {FileName} to {DeviceName}",
                        file.FileName,
                        device.DeviceName);
                });
            content.Headers.ContentType = MediaTypeHeaderValue.Parse(file.ContentType ?? "application/octet-stream");

            using var outboundRequest = new HttpRequestMessage(HttpMethod.Post, $"{device.ReceiveUrl}/api/device/receive")
            {
                Content = content
            };
            outboundRequest.Headers.Add("X-QuickShare-File-Name-Base64", Convert.ToBase64String(Encoding.UTF8.GetBytes(file.FileName)));
            outboundRequest.Headers.Add("X-QuickShare-Device-Id", deviceId);
            outboundRequest.Headers.Add("X-QuickShare-Offer-Id", offer.OfferId);

            app.Logger.LogInformation(
                "PC -> Android waiting for Android response for {FileName} at {ReceiveUrl}",
                file.FileName,
                device.ReceiveUrl);

            using var response = await httpClient.SendAsync(
                outboundRequest,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken);
            var responseText = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                app.Logger.LogWarning(
                    "PC -> Android Android-side rejection for {FileName}: {ResponseText}",
                    file.FileName,
                    responseText);
                throw new InvalidOperationException(responseText);
            }

            app.Logger.LogInformation("PC -> Android transfer completed for {FileName} to {DeviceName}", file.FileName, device.DeviceName);
            transferStore.Complete(transfer.TransferId, "Saved on Android");
            results.Add(new AndroidTransferResult(file.FileName, true, "Saved to QuickShare folder"));
        }
        catch (Exception ex)
        {
            app.Logger.LogWarning(ex, "PC -> Android transfer failed for {FileName} to {DeviceName}", file.FileName, device.DeviceName);
            transferStore.Fail(transfer.TransferId, ex.Message);
            results.Add(new AndroidTransferResult(file.FileName, false, ex.Message));
        }
    }

    return Results.Ok(new
    {
        deviceId,
        results
    });
});

app.MapPost("/api/upload/request", (UploadRequest request, UploadStore store) =>
{
    if (string.IsNullOrWhiteSpace(request.FileId) ||
        string.IsNullOrWhiteSpace(request.FileName) ||
        request.TotalChunks <= 0 ||
        request.TotalBytes < 0)
    {
        return Results.BadRequest("Invalid upload request.");
    }

    store.GetOrCreate(request.FileId, request.FileName, request.TotalChunks, request.TotalBytes);
    return Results.Accepted();
});

app.MapPost("/api/upload/destination", (UploadDestinationRequest request, UploadStore store) =>
{
    if (string.IsNullOrWhiteSpace(request.FileId) || string.IsNullOrWhiteSpace(request.DestinationDirectory))
    {
        return Results.BadRequest("Invalid destination request.");
    }

    store.SetDestination(request.FileId, request.DestinationDirectory);
    return Results.Ok();
});

app.MapPost("/api/upload/chunk", async (HttpRequest request, UploadStore store, ChunkFileService chunks, CancellationToken cancellationToken) =>
{
    if (!request.HasFormContentType)
    {
        return Results.BadRequest("Multipart form-data is required.");
    }

    var form = await request.ReadFormAsync(cancellationToken);
    var fileId = form["fileId"].ToString();
    var fileName = form["fileName"].ToString();
    var chunkIndexText = form["chunkIndex"].ToString();
    var totalChunksText = form["totalChunks"].ToString();
    var file = form.Files.GetFile("chunk");

    if (string.IsNullOrWhiteSpace(fileId) ||
        string.IsNullOrWhiteSpace(fileName) ||
        !int.TryParse(chunkIndexText, out var chunkIndex) ||
        !int.TryParse(totalChunksText, out var totalChunks) ||
        file is null)
    {
        return Results.BadRequest("Missing required chunk fields.");
    }

    if (chunkIndex < 0 || totalChunks <= 0 || chunkIndex >= totalChunks)
    {
        return Results.BadRequest("Invalid chunk index.");
    }

    var session = store.GetOrCreate(fileId, fileName, totalChunks);
    if (string.IsNullOrWhiteSpace(session.DestinationDirectory))
    {
        return Results.Conflict(new { message = "Destination folder has not been selected on the PC yet." });
    }

    await using var stream = file.OpenReadStream();
    var bytesSaved = await chunks.SaveChunkAsync(fileId, chunkIndex, stream, cancellationToken);
    store.MarkReceived(fileId, chunkIndex, bytesSaved);

    return Results.Ok(new { fileId, chunkIndex, totalChunks, saved = true });
});

app.MapPost("/api/upload/complete", async (CompleteUploadRequest request, UploadStore store, ChunkFileService chunks, CancellationToken cancellationToken) =>
{
    if (string.IsNullOrWhiteSpace(request.FileId) || string.IsNullOrWhiteSpace(request.FileName) || request.TotalChunks <= 0)
    {
        return Results.BadRequest("Invalid completion request.");
    }

    var receivedChunks = chunks.GetReceivedChunks(request.FileId);
    var missingChunks = Enumerable.Range(0, request.TotalChunks).Except(receivedChunks).ToArray();
    if (missingChunks.Length > 0)
    {
        return Results.BadRequest(new { message = "Upload is incomplete.", missingChunks });
    }

    var session = store.GetOrCreate(request.FileId, request.FileName, request.TotalChunks);
    if (string.IsNullOrWhiteSpace(session.DestinationDirectory))
    {
        return Results.Conflict(new { message = "Destination folder has not been selected on the PC yet." });
    }

    var outputPath = await chunks.MergeChunksAsync(
        request.FileId,
        request.FileName,
        request.TotalChunks,
        session.DestinationDirectory,
        cancellationToken);
    store.SetFinalPath(request.FileId, outputPath);
    store.MarkCompleted(request.FileId);
    app.Logger.LogInformation("Upload completed for {FileName}. Saved to {OutputPath}", request.FileName, outputPath);
    return Results.Ok(new { request.FileId, outputPath });
});

app.Run("http://0.0.0.0:5070");
