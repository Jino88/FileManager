using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace QuickShareClone.Desktop;

public sealed class DashboardForm : Form
{
    private const string LocalDashboardUrl = "http://127.0.0.1:5070/";
    private readonly Label _statusLabel;
    private readonly WebView2 _webView;
    private Process? _serverProcess;
    private readonly System.Windows.Forms.Timer _requestTimer;
    private readonly HashSet<string> _handledPrompts = [];
    private bool _isShuttingDown;

    public DashboardForm()
    {
        Text = "Quick Share Clone";
        Width = 1400;
        Height = 920;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(240, 244, 242);

        var topBar = new Panel
        {
            Dock = DockStyle.Top,
            Height = 44,
            Padding = new Padding(18, 10, 18, 10),
            BackColor = Color.FromArgb(234, 241, 239)
        };

        _statusLabel = new Label
        {
            Dock = DockStyle.Fill,
            Text = "Starting local Quick Share node...",
            Font = new Font("Segoe UI", 10F),
            ForeColor = Color.FromArgb(52, 64, 84)
        };

        _webView = new WebView2
        {
            Dock = DockStyle.Fill,
            DefaultBackgroundColor = Color.White
        };

        topBar.Controls.Add(_statusLabel);
        Controls.Add(_webView);
        Controls.Add(topBar);

        Load += DashboardForm_Load;
        FormClosing += DashboardForm_FormClosing;
        FormClosed += DashboardForm_FormClosed;
        Application.ApplicationExit += Application_ApplicationExit;
        AppDomain.CurrentDomain.ProcessExit += CurrentDomain_ProcessExit;

        _requestTimer = new System.Windows.Forms.Timer
        {
            Interval = 1500
        };
        _requestTimer.Tick += RequestTimer_Tick;
    }

    private async void DashboardForm_Load(object? sender, EventArgs e)
    {
        try
        {
            Console.WriteLine($"{Timestamp()} [Desktop] Starting dashboard shell");
            Console.WriteLine($"{Timestamp()} [Desktop] Step 1/4: checking local server availability");
            var (url, process) = await WaitForServerAsync();
            _serverProcess = process;
            Console.WriteLine($"{Timestamp()} [Desktop] Step 2/4: creating WebView2 environment");
            var environment = await CreateWebViewEnvironmentAsync();
            Console.WriteLine($"{Timestamp()} [Desktop] Step 3/4: initializing embedded WebView");
            await _webView.EnsureCoreWebView2Async(environment);
            _webView.CoreWebView2.WebMessageReceived += CoreWebView2_WebMessageReceived;
            _webView.Source = new Uri(url);
            _statusLabel.Text = $"Connected to {url}";
            Console.WriteLine($"{Timestamp()} [Desktop] Step 4/4: WebView connected to {url}");
            _requestTimer.Start();
        }
        catch (Exception ex)
        {
            _statusLabel.Text = "Server start failed";
            Console.WriteLine($"{Timestamp()} [Desktop] Startup failed: {ex}");
            MessageBox.Show(this, ex.Message, "Quick Share Clone", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void DashboardForm_FormClosed(object? sender, FormClosedEventArgs e)
    {
        ShutdownOwnedResources();
    }

    private void DashboardForm_FormClosing(object? sender, FormClosingEventArgs e)
    {
        ShutdownOwnedResources();
    }

    private void Application_ApplicationExit(object? sender, EventArgs e)
    {
        ShutdownOwnedResources();
    }

    private void CurrentDomain_ProcessExit(object? sender, EventArgs e)
    {
        ShutdownOwnedResources();
    }

    private async void RequestTimer_Tick(object? sender, EventArgs e)
    {
        _requestTimer.Stop();
        try
        {
            await CheckPendingRequestsAsync();
        }
        finally
        {
            if (!IsDisposed)
            {
                _requestTimer.Start();
            }
        }
    }

    private static Process StartServerProcess()
    {
        var serverPath = ResolveServerExecutable();
        var info = new ProcessStartInfo
        {
            FileName = serverPath,
            WorkingDirectory = Path.GetDirectoryName(serverPath)!,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };

        var process = Process.Start(info) ?? throw new InvalidOperationException("Unable to start Quick Share server process.");
        process.OutputDataReceived += (_, args) =>
        {
            if (!string.IsNullOrWhiteSpace(args.Data))
            {
                Console.WriteLine($"{Timestamp()} [Server] {args.Data}");
            }
        };
        process.ErrorDataReceived += (_, args) =>
        {
            if (!string.IsNullOrWhiteSpace(args.Data))
            {
                Console.Error.WriteLine($"{Timestamp()} [Server] {args.Data}");
            }
        };
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        process.EnableRaisingEvents = true;
        Console.WriteLine($"{Timestamp()} [Desktop] Spawned server process: {serverPath}");
        return process;
    }

    private static string Timestamp() => $"[{DateTime.Now:HH:mm:ss.fff}]";

    private static async Task<CoreWebView2Environment> CreateWebViewEnvironmentAsync()
    {
        var userDataFolder = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "QuickShareClone",
            "WebView2");

        Directory.CreateDirectory(userDataFolder);
        Console.WriteLine($"[Desktop] WebView2 user data folder: {userDataFolder}");
        return await CoreWebView2Environment.CreateAsync(userDataFolder: userDataFolder);
    }

    private static string ResolveServerExecutable()
    {
        var baseDirectory = AppContext.BaseDirectory;
        var serverDirectory = Path.GetFullPath(Path.Combine(baseDirectory, "..", "..", "..", "..", "QuickShareClone.Server", "bin", "Debug", "net8.0"));
        var executablePath = Path.Combine(serverDirectory, "QuickShareClone.Server.exe");
        if (!File.Exists(executablePath))
        {
            throw new FileNotFoundException("QuickShareClone.Server.exe was not found. Build the server project first.", executablePath);
        }

        return executablePath;
    }

    private static async Task<(string url, Process? process)> WaitForServerAsync()
    {
        using var client = new HttpClient();
        const string target = LocalDashboardUrl;

        if (await IsServerReachableAsync(client, target))
        {
            Console.WriteLine($"{Timestamp()} [Desktop] Reusing existing server on port 5070");
            return (target, null);
        }

        Console.WriteLine($"{Timestamp()} [Desktop] Local server not reachable, starting project server");
        var process = StartServerProcess();

        for (var attempt = 0; attempt < 40; attempt++)
        {
            if (process.HasExited)
            {
                throw new InvalidOperationException("Quick Share server process exited before becoming reachable.");
            }

            if (await IsServerReachableAsync(client, target))
            {
                Console.WriteLine($"{Timestamp()} [Desktop] Local server became reachable after {attempt + 1} probe(s)");
                return (target, process);
            }

            Console.WriteLine($"{Timestamp()} [Desktop] Waiting for local server... attempt {attempt + 1}/40");
            await Task.Delay(500);
        }

        throw new TimeoutException("The local web dashboard did not become reachable on http://127.0.0.1:5070.");
    }

    private static async Task<bool> IsServerReachableAsync(HttpClient client, string target)
    {
        try
        {
            using var response = await client.GetAsync(target);
            return response.IsSuccessStatusCode;
        }
        catch
        {
            return false;
        }
    }

    private async Task CheckPendingRequestsAsync()
    {
        using var client = new HttpClient();
        var response = await client.GetAsync("http://127.0.0.1:5070/api/upload/sessions");
        response.EnsureSuccessStatusCode();

        var body = await response.Content.ReadAsStringAsync();
        var sessions = JsonSerializer.Deserialize<List<DesktopUploadSession>>(body, new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true
        }) ?? [];

        var pendingSessions = sessions
            .Where(x => !x.DestinationSelected && !x.IsCompleted)
            .Where(x => !_handledPrompts.Contains(x.FileId))
            .ToList();

        if (pendingSessions.Count == 0)
        {
            return;
        }

        foreach (var session in pendingSessions)
        {
            _handledPrompts.Add(session.FileId);
        }

        var totalBytes = pendingSessions.Sum(static x => x.TotalBytes ?? 0L);
        var label = pendingSessions.Count == 1
            ? pendingSessions[0].FileName
            : $"{pendingSessions.Count} files ({FormatSize(totalBytes)})";
        var previewNames = string.Join(
            Environment.NewLine,
            pendingSessions.Take(3).Select(static session => $"• {session.FileName}"));
        if (pendingSessions.Count > 3)
        {
            previewNames += $"{Environment.NewLine}• + {pendingSessions.Count - 3} more";
        }

        var confirmationText =
            $"Incoming transfer from Android{Environment.NewLine}{Environment.NewLine}" +
            $"Files: {pendingSessions.Count}{Environment.NewLine}" +
            $"Total size: {FormatSize(totalBytes)}{Environment.NewLine}{Environment.NewLine}" +
            $"{previewNames}{Environment.NewLine}{Environment.NewLine}" +
            "Press OK to choose the destination folder.";

        var confirmationResult = MessageBox.Show(
            this,
            confirmationText,
            "Receive Files",
            MessageBoxButtons.OKCancel,
            MessageBoxIcon.Information);

        if (confirmationResult != DialogResult.OK)
        {
            Console.WriteLine($"[Desktop] Incoming transfer confirmation skipped for {label}");
            foreach (var session in pendingSessions)
            {
                _handledPrompts.Remove(session.FileId);
            }
            return;
        }

        using var dialog = new FolderBrowserDialog
        {
            Description = $"Choose a destination folder for {label}",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = true
        };

        var result = dialog.ShowDialog(this);
        if (result != DialogResult.OK || string.IsNullOrWhiteSpace(dialog.SelectedPath))
        {
            Console.WriteLine($"[Desktop] Destination selection skipped for {label}");
            foreach (var session in pendingSessions)
            {
                _handledPrompts.Remove(session.FileId);
            }
            return;
        }

        foreach (var session in pendingSessions)
        {
            var payload = JsonSerializer.Serialize(new
            {
                fileId = session.FileId,
                destinationDirectory = dialog.SelectedPath
            });

            var request = new HttpRequestMessage(HttpMethod.Post, "http://127.0.0.1:5070/api/upload/destination")
            {
                Content = new StringContent(payload, Encoding.UTF8, "application/json")
            };

            var saveResponse = await client.SendAsync(request);
            saveResponse.EnsureSuccessStatusCode();
            Console.WriteLine($"[Desktop] Destination approved for {session.FileName}: {dialog.SelectedPath}");
        }
    }

    private async void CoreWebView2_WebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        try
        {
            using var document = JsonDocument.Parse(e.WebMessageAsJson);
            if (!document.RootElement.TryGetProperty("type", out var typeElement))
            {
                return;
            }

            var messageType = typeElement.GetString();
            if (!string.Equals(messageType, "pickAndroidFiles", StringComparison.Ordinal))
            {
                return;
            }

            using var dialog = new OpenFileDialog
            {
                Title = "Choose files to send to Android",
                Multiselect = true,
                CheckFileExists = true,
                Filter = "All files (*.*)|*.*"
            };

            if (dialog.ShowDialog(this) != DialogResult.OK || dialog.FileNames.Length == 0)
            {
                await PushNativeSelectionToWebAsync(null);
                return;
            }

            var files = dialog.FileNames
                .Select(path =>
                {
                    var info = new FileInfo(path);
                    return new
                    {
                        fileName = info.Name,
                        filePath = info.FullName,
                        fileSizeBytes = info.Length
                    };
                })
                .ToArray();

            using var client = new HttpClient();
            var payload = JsonSerializer.Serialize(new { files });
            using var request = new HttpRequestMessage(HttpMethod.Post, "http://127.0.0.1:5070/api/android/native-selection")
            {
                Content = new StringContent(payload, Encoding.UTF8, "application/json")
            };
            using var response = await client.SendAsync(request);
            response.EnsureSuccessStatusCode();
            var body = await response.Content.ReadAsStringAsync();
            await PushNativeSelectionToWebAsync(body);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"{Timestamp()} [Desktop] Native file picker failed: {ex.Message}");
            await PushNativeSelectionToWebAsync(JsonSerializer.Serialize(new
            {
                type = "nativeAndroidFilesSelected",
                success = false,
                message = ex.Message
            }));
        }
    }

    private Task PushNativeSelectionToWebAsync(string? jsonPayload)
    {
        if (_webView.CoreWebView2 is null)
        {
            return Task.CompletedTask;
        }

        var payload = string.IsNullOrWhiteSpace(jsonPayload)
            ? "{\"type\":\"nativeAndroidFilesSelected\",\"cancelled\":true}"
            : jsonPayload;

        return _webView.CoreWebView2.ExecuteScriptAsync(
            $"window.onNativeAndroidFilesSelected?.({payload});");
    }

    private static string FormatSize(long sizeBytes)
    {
        if (sizeBytes <= 0)
        {
            return "unknown size";
        }

        var sizeMb = sizeBytes / 1024d / 1024d;
        return sizeMb >= 1024d
            ? $"{sizeMb / 1024d:F2} GB"
            : $"{sizeMb:F1} MB";
    }

    private sealed class DesktopUploadSession
    {
        public string FileId { get; set; } = "";
        public string FileName { get; set; } = "";
        public long? TotalBytes { get; set; }
        public bool DestinationSelected { get; set; }
        public bool IsCompleted { get; set; }
    }

    private void ShutdownOwnedResources()
    {
        if (_isShuttingDown)
        {
            return;
        }

        _isShuttingDown = true;

        try
        {
            _requestTimer.Stop();
            _requestTimer.Dispose();
        }
        catch
        {
        }

        try
        {
            if (_webView.CoreWebView2 is not null)
            {
                _webView.CoreWebView2.WebMessageReceived -= CoreWebView2_WebMessageReceived;
                _webView.CoreWebView2.Stop();
            }
            _webView.Dispose();
        }
        catch
        {
        }

        try
        {
            if (_serverProcess is not null)
            {
                if (!_serverProcess.HasExited)
                {
                    Console.WriteLine("[Desktop] Stopping local server process");
                    _serverProcess.Kill(entireProcessTree: true);
                    _serverProcess.WaitForExit(5000);
                }

                _serverProcess.CancelOutputRead();
                _serverProcess.CancelErrorRead();
                _serverProcess.Dispose();
                _serverProcess = null;
            }
            else
            {
                StopReusableProjectServer();
            }
        }
        catch
        {
        }

        try
        {
            Application.ApplicationExit -= Application_ApplicationExit;
            AppDomain.CurrentDomain.ProcessExit -= CurrentDomain_ProcessExit;
        }
        catch
        {
        }
    }

    private static void StopReusableProjectServer()
    {
        foreach (var process in Process.GetProcessesByName("QuickShareClone.Server"))
        {
            try
            {
                var processPath = process.MainModule?.FileName ?? string.Empty;
                if (!processPath.EndsWith("QuickShareClone.Server.exe", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                if (process.HasExited)
                {
                    continue;
                }

                Console.WriteLine($"[Desktop] Stopping reused project server process: {processPath}");
                process.Kill(entireProcessTree: true);
                process.WaitForExit(5000);
            }
            catch
            {
            }
        }
    }
}
