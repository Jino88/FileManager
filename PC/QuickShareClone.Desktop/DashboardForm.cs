using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace QuickShareClone.Desktop;

public sealed class DashboardForm : Form
{
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
            Console.WriteLine("[Desktop] Starting dashboard shell");
            var (url, process) = await WaitForServerAsync();
            _serverProcess = process;
            var environment = await CreateWebViewEnvironmentAsync();
            await _webView.EnsureCoreWebView2Async(environment);
            _webView.Source = new Uri(url);
            _statusLabel.Text = $"Connected to {url}";
            Console.WriteLine($"[Desktop] WebView connected to {url}");
            _requestTimer.Start();
        }
        catch (Exception ex)
        {
            _statusLabel.Text = "Server start failed";
            Console.WriteLine($"[Desktop] Startup failed: {ex}");
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
                Console.WriteLine($"[Server] {args.Data}");
            }
        };
        process.ErrorDataReceived += (_, args) =>
        {
            if (!string.IsNullOrWhiteSpace(args.Data))
            {
                Console.Error.WriteLine($"[Server] {args.Data}");
            }
        };
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        process.EnableRaisingEvents = true;
        Console.WriteLine($"[Desktop] Spawned server process: {serverPath}");
        return process;
    }

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
        const string target = "http://127.0.0.1:5070/";

        if (await IsServerReachableAsync(client, target))
        {
            Console.WriteLine("[Desktop] Reusing existing server on port 5070");
            return (target, null);
        }

        var process = StartServerProcess();

        for (var attempt = 0; attempt < 40; attempt++)
        {
            if (process.HasExited)
            {
                throw new InvalidOperationException("Quick Share server process exited before becoming reachable.");
            }

            if (await IsServerReachableAsync(client, target))
            {
                return (target, process);
            }

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
}
