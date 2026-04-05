# Quick Share Clone

Quick Share Clone is a two-part sample project:

- `PC/QuickShareClone.Server`: ASP.NET Core upload/discovery server
- `PC/QuickShareClone.Desktop`: Windows desktop shell that hosts the server UI in WebView2
- `ANDROID`: Android app with Compose UI, share-target flow, and nearby-PC discovery

## Features

- Desktop WebView dashboard with live transfer list
- Android share menu integration for single and multiple files
- UDP-based nearby device discovery
- Chunk-based uploads (default 1 MB)
- Resume support via uploaded-chunk status lookup
- Streaming I/O on both client and server

## PC Desktop

```powershell
cd .\PC\QuickShareClone.Desktop
dotnet run
```

The desktop app starts the local HTTP node and opens the dashboard in WebView2.

API:

- `GET /api/upload/status?fileId=...`
- `POST /api/upload/chunk`
- `POST /api/upload/complete`
- `GET /api/upload/sessions`
- `GET /api/discovery/self`

## Android App

Open `ANDROID` in Android Studio or run the provided build/install script.

Important:

- Nearby PCs should appear automatically when the desktop app is running on the same LAN
- The Android app expects chunk uploads over HTTP multipart form-data
- Shared files are streamed and never fully loaded into memory
- Resume works by querying uploaded chunk indexes before sending
