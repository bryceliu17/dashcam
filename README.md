# Local Dashcam

A self-hosted Android dashcam and audio-journal system. Phones record locally first, keep a rotating local archive, and upload completed recordings to a home server when validated Wi-Fi and the server are available.

The phone remains the source of truth until an upload succeeds. A server outage or failed upload never removes the local recording.

## English

### Maintained branches

| Branch | Android support | Phone video archive | Camera implementation | Responsibility |
|---|---:|---:|---|---|
| `main` | Android 8.0 / API 26+ | 25 GiB | CameraX for foreground preview recording; Camera2 for background recording and Live Access | Maintained Android client, API, dashboard, Docker deployment, and documentation |
| `android-5-compatible` | Android 5.0 / API 21+ | 5.5 GiB | Legacy `android.hardware.Camera` on Android 5/5.1; Camera2 only on newer systems | Maintained Android 5 client only |

The server, web dashboard, Docker files, and deployment documentation in `android-5-compatible` are historical snapshots. Do not update or deploy them; deploy `main`.

### Architecture

```text
Android phone
  MP4 video and M4A audio segments
  Room database: Pending / Uploading / Uploaded / Failed
  WorkManager upload queue and retry policy
  Optional Live Access WebSocket
                 |
                 v
ASP.NET Core 8 API (port 5000)
  SQLite metadata and date-based media folders
  Uploads, Range streaming, exports, cleanup, device status, Live Access
                 |
                 v
React dashboard (port 8080 with Docker)
  Archive management, sessions, device telemetry, live viewing, migration
```

Recording timestamps are saved in UTC. The dashboard displays them in the browser's local time zone.

### Android features

#### Video and audio recording

- Foreground video recording with an aspect-correct preview.
- Manual background recording through a foreground service, including screen-off recording subject to device battery/camera policy.
- Video segment choices: 1, 3, 5, or 10 minutes, unlimited, or a custom duration. Default: 5 minutes.
- Audio segment choices: 5, 10, 15, 30, or 60 minutes, unlimited, or a custom duration. Default: 30 minutes.
- Video and audio are mutually exclusive.
- Local video/audio lists support status, playback, seeking, rotation where applicable, locking, and deletion.

#### Recording modes

| Mode | Behavior |
|---|---|
| `Frontend Recording` | Normal preview plus manual foreground/background recording controls. |
| `Power Auto Background` | Starts background video when charging begins. When power disconnects, the active segment finishes before recording stops. |
| `Power Auto + Start Alert` | Power Auto plus an audible start alert and a brief screen-on “Recording started” notice. |
| `Volume Up Double-Press Video` | Double-press Volume Up within 700 ms to start background video. |
| `Volume Up Double-Press Audio` | Double-press Volume Up within 700 ms to start audio recording. |

The volume-key modes require **Dashcam Volume Up Double-Press** to be enabled in Android Accessibility settings. Whether a fully screen-off phone delivers the key event depends on its firmware.

#### Phone storage and upload

- `main` keeps up to **25 GiB** of local video; `android-5-compatible` keeps up to **5.5 GiB**.
- Local audio has a separate **1.5 GiB** rotating archive.
- Before a new video segment, the app checks the video archive and remaining filesystem space. Low free space uses a 1 GiB trigger.
- Automatic cleanup only removes the oldest unlocked local recordings. If required cleanup cannot remove an unlocked video, the next video segment does not start. Locked recordings are never selected.
- Automatic uploads require validated Wi-Fi and a successful server health check. Finished recordings enter the WorkManager queue and failed items retry with backoff.
- `Upload Now`, `Upload Video Only`, and `Upload Audio Only` are available for manual transfer. A recording becomes `Uploaded` only after server confirmation.

#### Live Access, flashlight, and battery history

- Enable **Live Access** on the phone to keep a control WebSocket available to the server.
- The dashboard can request a live camera only while the phone is not recording video or audio.
- The live viewer supports rotation, fullscreen, and a phone flashlight control when the selected back camera exposes a torch.
- The flashlight can be turned off manually. It is also turned off when live viewing is closed, the web page becomes hidden, the control connection closes, or the camera is released.
- On `main`, the live camera and torch use Camera2. On Android 5/5.1, the compatibility client uses the legacy Camera API and its torch setting.
- Phones keep local battery-temperature history. The phone UI and dashboard can show the chart and selected readings.

### Server and dashboard features

- Separate video and audio archives with paging, date/lock filtering, and date availability indicators.
- Range-enabled playback, playback rotation, original downloads, timestamp-overlay video downloads, and session downloads/exports.
- Group nearby recordings into sessions for continuous video or audio playback while retaining individual controls.
- Bulk select, lock/unlock, rotate videos, and delete recordings.
- Audio waveform generation and caching through `ffmpeg`.
- One-click transcription for audio recordings up to 30 minutes, with language detection, transcript viewing, and TXT download. Docker runs a `faster-whisper` transcription worker configured for CUDA by default.
- Device list with online transport, battery/charging state, Live Access state, and battery-temperature history.
- Dashboard storage settings for separate video/audio server limits. It offers a recommendation equal to 76% of the storage drive, preserving the current video/audio split.
- Browser-assisted archive migration: select a previous archive folder containing `dashcam.db` plus `videos` and/or `audio`, upload it to the current server, and merge it through the migration workflow.

Server cleanup is independent of the phone's own rotating archive: after uploads, the server removes the oldest unlocked archive files when a configured server limit is exceeded.

### Quick start with Docker

Requirements: Docker Desktop. The included transcription container is configured to use an NVIDIA CUDA GPU; remove or adapt its GPU settings in `compose.yaml` if transcription should run differently.

```powershell
git clone https://github.com/bryceliu17/dashcam.git
cd dashcam
docker compose up -d --build
```

Open:

- Dashboard: `http://localhost:8080`
- API health: `http://localhost:5000/api/health`

On each phone, set the server URL to the computer's LAN address, such as `http://192.168.1.50:5000`. Do not use `localhost` on the phone.

Docker stores persistent data in the host folder selected by `.env` and mounts it as `/data` in containers:

```text
data\dashcam.db
data\videos\YYYY-MM-DD\
data\audio\YYYY-MM-DD\
data\archive-storage-settings.json
```

Copy `.env.example` to `.env` to choose a host folder or initial server limits:

```dotenv
# Windows: E:/DashcamData
# Linux:   /srv/dashcam-data
DASHCAM_DATA_PATH=./data
DASHCAM_MAX_STORAGE_GB=350
DASHCAM_MAX_AUDIO_STORAGE_GB=20
```

The dashboard can later save different server limits; those saved values take precedence over these initial fallbacks. `.env` is ignored by Git.

Useful commands:

```powershell
docker compose ps
docker compose logs -f
docker compose up -d --build
docker compose down
```

`docker compose down` removes containers but retains the mapped data directory.

### Run without Docker

#### API

Requirements: .NET 8 SDK. Waveform generation and video/audio export require `ffmpeg` on `PATH`. Transcription also requires a compatible transcription worker.

```powershell
cd server\Dashcam.Api
dotnet restore
dotnet run
```

`server/Dashcam.Api/appsettings.json` provides non-Docker defaults:

```json
{
  "ConnectionStrings": { "DashcamDatabase": "Data Source=dashcam.db" },
  "VideoStoragePath": "videos",
  "AudioStoragePath": "audio",
  "MaxStorageGB": 350,
  "MaxAudioStorageGB": 20
}
```

#### Web dashboard

Requirements: Node.js 22 recommended.

```powershell
cd web-dashboard
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` to `http://localhost:5000`. Build production files with `npm run build`.

### Build and install Android

Requirements: JDK 17, Android SDK 36, and USB debugging when installing with ADB.

Build `main`:

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

APK output:

```text
android-app\app\build\outputs\apk\debug\app-debug.apk
```

Install to one explicit connected device:

```powershell
adb devices
adb -s PHONE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

Always use `-s PHONE_SERIAL` when more than one phone is connected.

Build the Android 5 client:

```powershell
git switch android-5-compatible
cd android-app
.\gradlew.bat assembleDebug
```

Switch back with `git switch main` before building or deploying the maintained server/dashboard.

### First phone setup

1. Install the APK and grant Camera, Microphone, and Notification permissions.
2. Connect the phone and server computer to the same Wi-Fi network.
3. Enter the computer's LAN API URL in the app and press **Save**.
4. Confirm **Home Server: Online**.
5. Select a recording mode and desired video/audio segment duration.
6. Enable **Live Access** if dashboard live viewing or remote battery history is wanted.
7. Record a short video or audio file, then verify it in the local list.
8. Upload it and verify it in the dashboard.
9. Exempt the app from aggressive battery optimization if the vendor stops background services.

### Security and limitations

- The API has no accounts or TLS. Use it only on a trusted LAN, or behind a correctly configured VPN/reverse proxy. Do not expose port 5000 directly to the public internet.
- Background camera, charging detection, Wi-Fi behavior, and key events vary by phone manufacturer, firmware, lock-screen state, heat, and battery policy.
- Video bitrate, frame rate, low-light behavior, and resulting file size depend on each device camera/encoder.
- Live Access is intended for on-demand viewing, not as a security-camera replacement.
- The project does not currently include GPS, collision detection, cloud storage, multi-user accounts, speaker diarization, or automated Android integration tests.

---

## 中文

### 维护中的分支

| 分支 | Android 支持 | 手机视频归档上限 | 相机实现 | 负责范围 |
|---|---:|---:|---|---|
| `main` | Android 8.0 / API 26+ | 25 GiB | 前台预览录制使用 CameraX；后台录像和 Live Access 使用 Camera2 | 维护中的 Android 客户端、API、网页管理页、Docker 部署和文档 |
| `android-5-compatible` | Android 5.0 / API 21+ | 5.5 GiB | Android 5/5.1 使用旧 `android.hardware.Camera`；更高版本系统才使用 Camera2 | 只维护 Android 5 客户端 |

`android-5-compatible` 分支里的服务端、网页、Docker 和部署文档都是历史快照，不应继续更新或部署；部署请使用 `main`。

### 项目架构

```text
Android 手机
  MP4 视频和 M4A 音频分段
  Room 数据库：Pending / Uploading / Uploaded / Failed
  WorkManager 上传队列和失败重试
  可选的 Live Access WebSocket
                 |
                 v
ASP.NET Core 8 API（端口 5000）
  SQLite 元数据和按日期保存的媒体文件
  上传、Range 播放、导出、清理、设备状态、Live Access
                 |
                 v
React 管理页面（Docker 默认端口 8080）
  归档管理、session 播放、设备状态、直播、迁移
```

录制时间以 UTC 保存；网页管理页会按浏览器所在时区显示。

### Android 功能

#### 视频和音频录制

- 前台视频录制带有比例正确的实时预览。
- 后台录像由前台服务运行；在熄屏时也可录制，但仍受手机厂商的相机和省电策略影响。
- 视频分段可选 1、3、5、10 分钟、无限或自定义；默认 5 分钟。
- 音频分段可选 5、10、15、30、60 分钟、无限或自定义；默认 30 分钟。
- 视频和音频不能同时录制。
- 本地视频/音频列表支持状态、播放、拖动、适用时的旋转、锁定和删除。

#### 录制模式

| 模式 | 行为 |
|---|---|
| `Frontend Recording` | 正常预览，以及手动前台/后台录像控制。 |
| `Power Auto Background` | 开始充电时自动后台录像；断电后让当前分段录完再停止。 |
| `Power Auto + Start Alert` | 与 Power Auto 相同，并在开始时发出提示音、短暂亮屏显示“Recording started”。 |
| `Volume Up Double-Press Video` | 在 700 毫秒内双击音量加，开始后台录像。 |
| `Volume Up Double-Press Audio` | 在 700 毫秒内双击音量加，开始音频录制。 |

音量键模式需要在 Android 无障碍设置中启用 **Dashcam Volume Up Double-Press**。彻底熄屏时系统是否转发按键取决于手机固件。

#### 手机本地容量与上传

- `main` 的本地视频最多 **25 GiB**；`android-5-compatible` 最多 **5.5 GiB**。
- 音频使用独立的 **1.5 GiB** 循环归档。
- 每段新视频开始前会检查视频归档和文件系统剩余空间；剩余空间低于 1 GiB 会触发清理检查。
- 自动清理只会删除最早、未锁定的本地录制；如果必须清理却没有可删除视频，下一段视频不会开始。锁定录制不会被自动清理。
- 自动上传需要已验证的 Wi-Fi 和成功的服务器健康检查。录制完成后进入 WorkManager 队列；失败文件会按退避策略重试。
- 可手动使用 `Upload Now`、`Upload Video Only`、`Upload Audio Only`。只有服务器确认后，文件才标记为 `Uploaded`。

#### Live Access、手电与电池温度历史

- 在手机开启 **Live Access** 后，手机会保持一个供服务器控制的 WebSocket。
- 只有手机当前没有录制视频或音频时，网页才能请求直播画面。
- 直播窗口支持旋转、全屏；所选后摄支持手电时可直接控制手机手电。
- 手电可手动关闭；关闭直播窗口、网页变为不可见、控制连接断开或相机释放时，都会自动关闭。
- `main` 的直播和手电使用 Camera2；Android 5/5.1 兼容客户端使用旧 Camera API 及其手电设置。
- 手机会在本地记录电池温度历史；手机端和网页端都可查看图表及指定采样点。

### 服务端和网页功能

- 视频和音频独立归档，支持分页、日期/锁定筛选和有录制日期提示。
- 支持 Range 播放、播放旋转、原视频下载、带时间戳的视频下载，以及 session 下载/导出。
- 将相邻录制分组为 session 连续播放，同时保留单个文件控制。
- 支持多选、批量锁定/解锁、批量旋转视频和批量删除。
- 使用 `ffmpeg` 生成和缓存音频波形。
- 最长 30 分钟的音频可以一键转文字，支持语言识别、查看文字稿和下载 TXT。Docker 默认运行使用 CUDA 的 `faster-whisper` 转写服务。
- 设备列表显示在线连接方式、电量/充电状态、Live Access 状态和电池温度历史。
- 网页可分别设置服务端视频/音频容量，并根据所在存储盘给出 76% 的推荐总容量，保持当前视频/音频比例。
- 支持浏览器辅助归档迁移：选择旧归档文件夹（包含 `dashcam.db` 和 `videos`、`audio`），上传到当前服务端并通过迁移流程合并。

上传后，如果服务器归档超过设置上限，会清理最早的未锁定文件。它与手机本地循环归档相互独立。

### 使用 Docker 快速启动

需要 Docker Desktop。附带的转文字容器默认配置为使用 NVIDIA CUDA GPU；如果希望以其他方式转写，需要相应修改 `compose.yaml` 中的 GPU 设置。

```powershell
git clone https://github.com/bryceliu17/dashcam.git
cd dashcam
docker compose up -d --build
```

打开：

- 管理页面：`http://localhost:8080`
- API 健康检查：`http://localhost:5000/api/health`

手机中的服务器地址必须填写电脑局域网地址，例如 `http://192.168.1.50:5000`；不能填写手机自己的 `localhost`。

Docker 把持久数据保存在 `.env` 选定的电脑文件夹，并映射为容器内的 `/data`：

```text
data\dashcam.db
data\videos\YYYY-MM-DD\
data\audio\YYYY-MM-DD\
data\archive-storage-settings.json
```

复制 `.env.example` 为 `.env`，可以设置保存位置和初始服务端容量：

```dotenv
# Windows：E:/DashcamData
# Linux：  /srv/dashcam-data
DASHCAM_DATA_PATH=./data
DASHCAM_MAX_STORAGE_GB=350
DASHCAM_MAX_AUDIO_STORAGE_GB=20
```

之后网页可保存不同的服务端容量；保存后的值会优先于这些初始默认值。`.env` 已被 Git 忽略。

常用命令：

```powershell
docker compose ps
docker compose logs -f
docker compose up -d --build
docker compose down
```

`docker compose down` 会删除容器，但保留映射到电脑的数据目录。

### 不使用 Docker 启动

#### API

需要 .NET 8 SDK。生成波形、视频/音频导出需要 `ffmpeg` 在 `PATH` 中；语音转文字还需要兼容的转写服务。

```powershell
cd server\Dashcam.Api
dotnet restore
dotnet run
```

`server/Dashcam.Api/appsettings.json` 提供非 Docker 的默认配置：

```json
{
  "ConnectionStrings": { "DashcamDatabase": "Data Source=dashcam.db" },
  "VideoStoragePath": "videos",
  "AudioStoragePath": "audio",
  "MaxStorageGB": 350,
  "MaxAudioStorageGB": 20
}
```

#### 网页管理页

推荐 Node.js 22：

```powershell
cd web-dashboard
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 会把 `/api` 代理到 `http://localhost:5000`。生产构建使用 `npm run build`。

### 构建和安装 Android

需要 JDK 17、Android SDK 36；使用 ADB 安装时还需要开启 USB debugging。

构建 `main`：

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

APK 输出：

```text
android-app\app\build\outputs\apk\debug\app-debug.apk
```

指定一台已连接设备安装：

```powershell
adb devices
adb -s PHONE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

同时连接多台手机时，必须使用 `-s PHONE_SERIAL`。

构建 Android 5 客户端：

```powershell
git switch android-5-compatible
cd android-app
.\gradlew.bat assembleDebug
```

构建或部署维护中的服务端/网页前，使用 `git switch main` 切回主分支。

### 手机首次设置

1. 安装 APK，允许相机、麦克风和通知权限。
2. 手机和服务器电脑连接同一个 Wi-Fi。
3. 在 App 中填写电脑局域网 API 地址并点击 **Save**。
4. 确认主页显示 **Home Server: Online**。
5. 选择录制模式以及视频/音频分段时长。
6. 如需网页直播或远程查看电池历史，在手机开启 **Live Access**。
7. 录制一个短视频或音频，在本地列表确认。
8. 上传后在网页管理页确认。
9. 如果厂商会杀死后台服务，将 App 加入电池优化白名单。

### 安全和限制

- API 目前没有账号登录和 TLS。只应在可信局域网使用，或放在正确配置的 VPN/反向代理后；不要把 5000 端口直接暴露到公网。
- 后台相机、充电检测、Wi-Fi、按键行为会受手机厂商、固件、锁屏、温度和省电策略影响。
- 视频码率、帧率、夜视效果和文件大小都依赖手机本身的相机/编码器。
- Live Access 适合按需查看，不是安全摄像头的替代方案。
- 当前没有 GPS、碰撞检测、云存储、多用户账号、说话人分离或 Android 自动化集成测试。
