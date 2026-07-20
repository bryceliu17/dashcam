# Local Dashcam

A self-hosted dashcam system that records video and audio on an Android phone, keeps local recordings in rotating storage, and uploads them to a home server when Wi-Fi and the server are available.

The project contains:

- An Android recorder written in Kotlin.
- An ASP.NET Core 8 API with SQLite metadata and filesystem storage.
- A React/Vite management dashboard.
- Docker Compose deployment for the API and dashboard.

The phone remains the source of truth until an upload succeeds. An offline server or failed upload does not remove the local recording.

## English

### Architecture

```text
Android phone
  MP4 video segments (up to 5 minutes)
  M4A audio segments (up to 30 minutes)
  Room database: Pending / Uploading / Uploaded / Failed
  WorkManager: validated Wi-Fi, health check, retry queue
                 |
                 v
ASP.NET Core API (port 5000)
  SQLite metadata
  MP4 and M4A files grouped by recording date
  Range streaming, downloads, locking, cleanup, audio waveform cache
                 |
                 v
React dashboard (port 8080 with Docker)
  Browse / play / seek / rotate / download / lock / delete
```

Recording times are uploaded and stored in UTC. The dashboard converts them to the browser's local time zone.

### Branches

| Branch | Android support | Video limit on phone | Notes |
|---|---:|---:|---|
| `main` | Android 8.0 / API 26+ | 15 GiB | Current Android APIs and CameraX 1.4.x |
| `android-5-compatible` | Android 5.0 / API 21+ | 5.5 GiB | Legacy camera fallback and older compatible AndroidX versions |

Both branches contain the same server, dashboard, video, audio, upload, and management features. The Android 5 branch only changes Android compatibility code and its phone video limit.

### Android features

#### Video recording

- Foreground recording with a live, aspect-correct preview.
- Manual background recording through a foreground service.
- Automatic MP4 segmentation every 5 minutes; the next segment starts immediately.
- Recording timer, manual-session start time, generated segment count, and overwritten count.
- Screen-off background recording with a partial wake lock, subject to the phone manufacturer's camera and battery restrictions.
- Local video list with upload status, playback, seeking, playback rotation, locking, and deletion.
- List scroll position is restored after returning from a video.

#### Audio recording

- AAC audio stored in `.m4a` containers at 128 kbps and 44.1 kHz.
- Automatic segmentation every 30 minutes; the next segment starts immediately.
- Compact local audio player with a seek bar.
- Local audio list with upload status, locking, and deletion.
- Video and audio recording are mutually exclusive.

#### Recording modes

The home-screen **Recording Mode** selector has four mutually exclusive modes:

| Mode | Behavior |
|---|---|
| `Frontend Recording` | Enables normal preview and manual foreground/background controls. |
| `Power Auto Background` | Starts background video when power is connected. On disconnect, the current segment finishes and recording stops. |
| `Volume Up Double-Press Video` | Double-press Volume Up within 700 ms to start background video. |
| `Volume Up Double-Press Audio` | Double-press Volume Up within 700 ms to start audio recording. Preview controls remain available while idle. |

The two volume-key modes require **Dashcam Volume Up Double-Press** to be enabled in Android Accessibility settings. Key delivery while the screen is fully off depends on the phone firmware. It can work while the lock screen is awake if the operating system forwards the key event.

Power Auto only controls recordings while that mode is enabled. Disabling Power Auto does not make an existing manual recording stop merely because the phone is not charging.

#### Phone storage policy

Video limits:

- `main`: 15 GiB.
- `android-5-compatible`: 5.5 GiB.
- Minimum-free-space trigger: 1 GiB.

Before each video segment, the app checks the Room video total and available filesystem space. If the configured video limit is reached, or free space is below 1 GiB, it attempts to delete exactly one oldest unlocked video. If the configured limit was reached and deletion succeeds, recording continues. A low-free-space trigger by itself does not block recording when nothing can be deleted. Locked videos are never selected for automatic deletion.

Because cleanup removes one file per segment check, the total may temporarily exceed the displayed limit by approximately one segment. The limits are binary GiB values even though the UI labels them as GB; for example, 5.5 GiB is about 5.9 decimal GB in Android system storage screens.

Audio has a separate 1.5 GiB limit. When audio exceeds that limit, the oldest unlocked audio files are removed until usage returns to the limit. Audio does not use the 1 GiB free-space trigger.

#### Upload behavior

- Automatic upload can be enabled or disabled from the home screen and is enabled by default.
- Automatic work runs periodically and is also queued when a segment completes.
- Uploads require validated Wi-Fi and a successful `GET /api/health` response.
- An offline server causes automatic work to retry later with backoff.
- `Upload Now` uploads all pending/failed video and audio.
- `Upload Audio Only` and `Upload Video Only` restrict a manual run to one media type.
- A file is marked `Uploaded` only after the server returns success and its server ID.
- Failed files remain on the phone with `Failed` status and retry metadata.

### Quick start with Docker

Docker Desktop is the simplest way to run the server and dashboard.

```powershell
git clone https://github.com/bryceliu17/dashcam.git
cd dashcam
docker compose up -d --build
```

Open:

- Dashboard: `http://localhost:8080`
- API health: `http://localhost:5000/api/health`

On each Android phone, set the server URL to the computer's LAN address, for example `http://192.168.1.50:5000`. Do not use `localhost` on the phone.

Docker persists data outside the containers by default:

```text
D:\DashcamData\dashcam.db
D:\DashcamData\videos\YYYY-MM-DD\
D:\DashcamData\audio\YYYY-MM-DD\
```

Create a `.env` file beside `compose.yaml` to change the location or server limits:

```dotenv
DASHCAM_DATA_PATH=E:/DashcamData
DASHCAM_MAX_STORAGE_GB=200
DASHCAM_MAX_AUDIO_STORAGE_GB=50
```

Useful commands:

```powershell
docker compose ps
docker compose logs -f
docker compose up -d --build
docker compose down
```

`docker compose down` removes containers but keeps the mapped data directory.

### Run without Docker

#### API

Requirements: .NET 8 SDK. Audio waveform generation also requires `ffmpeg` on `PATH`.

```powershell
cd server\Dashcam.Api
dotnet restore
dotnet run
```

The API listens on the URL in `launchSettings.json` during development. Configuration is in `server/Dashcam.Api/appsettings.json`:

```json
{
  "ConnectionStrings": { "DashcamDatabase": "Data Source=dashcam.db" },
  "VideoStoragePath": "videos",
  "AudioStoragePath": "audio",
  "MaxStorageGB": 200,
  "MaxAudioStorageGB": 50
}
```

#### Web dashboard

Requirements: Node.js 22 recommended.

```powershell
cd web-dashboard
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` to `http://localhost:5000`. Build production files with:

```powershell
npm run build
```

### Build and install Android

Requirements: JDK 17, Android SDK 36, and USB debugging for ADB installation.

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

APK output:

```text
android-app\app\build\outputs\apk\debug\app-debug.apk
```

List connected phones and install to one explicit serial number:

```powershell
adb devices
adb -s PHONE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

Always use `-s PHONE_SERIAL` when two or more phones are connected.

To build the Android 5 variant:

```powershell
git switch android-5-compatible
cd android-app
.\gradlew.bat assembleDebug
```

Switch back to the current branch with `git switch main` before building the main variant.

### First phone setup

1. Install the APK and grant Camera, Microphone, and Notification permissions.
2. Connect the phone and server computer to the same Wi-Fi network.
3. Enter the computer's LAN API URL in the app and press **Save**.
4. Confirm **Home Server: Online**.
5. Choose a recording mode.
6. For a volume-key mode, enable the Dashcam accessibility service when Android settings opens.
7. Record a short video or audio file and verify it in the local list.
8. Press **Upload Now**, then verify it in the web dashboard.
9. Exempt the app from aggressive battery optimization if the phone vendor stops background services.

### Server and dashboard features

- Separate video and audio archives with paging and date/lock filters.
- HTML5 Range streaming and downloads.
- Video playback rotation saved on the server.
- Audio waveform generation and caching through `ffmpeg`; the first waveform load can take a few seconds.
- Lock/unlock and explicit deletion for both media types.
- Storage totals and manual cleanup endpoints.
- UTC API timestamps rendered in the browser's local time zone.

Server storage cleanup removes oldest unlocked files until the configured server limit is met. It is invoked through the cleanup endpoints/dashboard; it is separate from phone storage rotation.

### API summary

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/health` | Health check and UTC server time |
| `POST` | `/api/videos/upload` | Upload MP4 plus metadata |
| `GET` | `/api/videos` | Paginated video list with date/lock filters |
| `GET` | `/api/videos/{id}/stream` | Range-enabled video stream |
| `GET` | `/api/videos/{id}/download` | Download original video |
| `PATCH` | `/api/videos/{id}/lock` | Lock or unlock video |
| `PATCH` | `/api/videos/{id}/rotation` | Save playback rotation |
| `DELETE` | `/api/videos/{id}` | Delete video metadata and file |
| `POST` | `/api/audio/upload` | Upload M4A plus metadata |
| `GET` | `/api/audio` | Paginated audio list with date/lock filters |
| `GET` | `/api/audio/{id}/stream` | Range-enabled audio stream |
| `GET` | `/api/audio/{id}/waveform` | Generate or read cached waveform peaks |
| `GET` | `/api/audio/{id}/download` | Download original audio |
| `PATCH` | `/api/audio/{id}/lock` | Lock or unlock audio |
| `DELETE` | `/api/audio/{id}` | Delete audio metadata, file, and waveform cache |
| `GET` | `/api/storage/status` | Video/audio totals and configured limits |
| `POST` | `/api/videos/cleanup` | Remove oldest unlocked videos above server limit |
| `POST` | `/api/audio/cleanup` | Remove oldest unlocked audio above server limit |

Upload forms include `file`, `filename`, `startTime`, `endTime`, `durationSeconds`, and `fileSizeBytes`. Video uploads can also include `playbackRotationDegrees`. The API validates extensions, time ranges, rotation values, and actual file sizes, and writes through a temporary `.uploading` file before committing metadata.

### Security and limitations

- The API has no authentication or TLS. Use it only on a trusted LAN or place it behind a properly secured reverse proxy/VPN. Do not expose port 5000 directly to the public internet.
- Android background camera and key-event behavior varies by manufacturer, lock-screen state, thermal policy, and battery optimization.
- Video bitrate and resulting segment size are selected by the device camera/encoder profile, so different phones produce different file sizes.
- The project does not currently include GPS, collision detection, cloud storage, multi-user accounts, or server-side continuous-video concatenation.
- Automated Android integration tests are not yet included; long-running recording and storage rotation should be validated on each target phone.

---

## 中文

### 项目架构

```text
Android 手机
  MP4 视频分段（每段最长 5 分钟）
  M4A 音频分段（每段最长 30 分钟）
  Room 数据库：Pending / Uploading / Uploaded / Failed
  WorkManager：有效 Wi-Fi、健康检查、失败重试
                 |
                 v
ASP.NET Core API（端口 5000）
  SQLite 元数据
  按录制日期保存 MP4 和 M4A 文件
  Range 流播放、下载、锁定、清理、音频波形缓存
                 |
                 v
React 管理页面（Docker 默认端口 8080）
  浏览 / 播放 / 拖动 / 旋转 / 下载 / 锁定 / 删除
```

录制时间以 UTC 上传和保存，管理页面会自动转换成浏览器所在设备的本地时区。

### 分支区别

| 分支 | Android 支持 | 手机视频上限 | 说明 |
|---|---:|---:|---|
| `main` | Android 8.0 / API 26 以上 | 15 GiB | 使用当前 Android API 和 CameraX 1.4.x |
| `android-5-compatible` | Android 5.0 / API 21 以上 | 5.5 GiB | 包含旧 Camera 回退和兼容版本 AndroidX |

两个分支的服务端、管理页面、视频、音频、上传和管理功能一致。版本 5 分支只保留 Android 兼容性差异和 5.5 GiB 手机视频上限。

### Android 功能

#### 视频录制

- 主界面前台录制，并显示比例正确的实时预览。
- 使用前台服务手动进行后台录制。
- 每 5 分钟自动保存一个 MP4，并立即开始下一段。
- 显示当前计时、本次手动启动时间、生成分段数量和覆盖数量。
- 使用部分唤醒锁支持熄屏后台录制，但仍受不同手机厂商的相机和省电策略限制。
- 本地视频列表支持上传状态、播放、进度拖动、播放旋转、锁定和删除。
- 从视频详情返回列表时恢复之前的滚动位置。

#### 音频录制

- 使用 AAC 编码并保存为 `.m4a`，码率 128 kbps，采样率 44.1 kHz。
- 每 30 分钟自动保存一段并立即开始下一段。
- 本地音频使用紧凑播放控件和独立进度条。
- 本地音频列表支持上传状态、锁定和删除。
- 视频和音频不能同时录制。

#### 录制模式

主界面的 **Recording Mode** 下拉菜单包含四种互斥模式：

| 模式 | 行为 |
|---|---|
| `Frontend Recording` | 使用正常预览和手动前台/后台录制按钮。 |
| `Power Auto Background` | 插电后自动开始后台视频；拔电后录完当前分段再停止。 |
| `Volume Up Double-Press Video` | 在 700 毫秒内双击音量加键，启动后台视频。 |
| `Volume Up Double-Press Audio` | 在 700 毫秒内双击音量加键，启动音频录制；空闲时不禁用预览按钮。 |

两个音量键模式都需要在 Android 无障碍设置中启用 **Dashcam Volume Up Double-Press**。熄屏状态下系统是否把按键事件交给 App 取决于手机固件；锁屏界面已经亮起时，如果系统转发按键，就可以触发。

Power Auto 只在该模式启用时控制录制。关闭 Power Auto 后，手动开始的录制不会仅仅因为手机未充电而停止。

#### 手机容量策略

视频上限：

- `main`：15 GiB。
- `android-5-compatible`：5.5 GiB。
- 可用空间触发值：1 GiB。

每个视频分段开始前，App 会检查 Room 中的视频总量和文件系统可用空间。达到视频上限或可用空间低于 1 GiB 时，尝试删除一个时间最早且未锁定的视频。达到额定上限并成功删除后会继续录制；如果只是外部环境造成可用空间低于 1 GiB，即使没有文件可删也不会直接禁止录制。锁定的视频不会被自动删除。

因为每次检查只删除一个视频，总量可能暂时比上限多出大约一个分段。代码使用二进制 GiB，但界面显示为 GB；例如 5.5 GiB 在 Android 系统的十进制容量页面中约为 5.9 GB。

音频使用独立的 1.5 GiB 上限。超出后会从最早的未锁定音频开始删除，直到恢复到上限以内。音频不使用“可用空间低于 1 GiB”的触发规则。

#### 上传逻辑

- 主界面可以自由开启或关闭自动上传，默认开启。
- 自动任务每隔一段时间运行，新分段完成时也会加入任务。
- 上传要求当前是已验证的 Wi-Fi，并且 `GET /api/health` 成功。
- 服务器未启动时，自动任务会退避并等待以后重试。
- `Upload Now` 上传所有待处理/失败的视频和音频。
- `Upload Audio Only` 和 `Upload Video Only` 只上传指定类型。
- 只有服务器明确返回成功和服务器 ID 后，文件才标记为 `Uploaded`。
- 上传失败的文件仍保留在手机中，状态为 `Failed`，并保存错误和重试信息。

### 使用 Docker 快速启动

推荐使用 Docker Desktop 同时运行 API 和管理页面。

```powershell
git clone https://github.com/bryceliu17/dashcam.git
cd dashcam
docker compose up -d --build
```

打开：

- 管理页面：`http://localhost:8080`
- API 健康检查：`http://localhost:5000/api/health`

Android 手机中的服务器地址必须填写电脑局域网地址，例如 `http://192.168.1.50:5000`，不能填写手机自己的 `localhost`。

Docker 默认把持久数据保存到容器外：

```text
D:\DashcamData\dashcam.db
D:\DashcamData\videos\YYYY-MM-DD\
D:\DashcamData\audio\YYYY-MM-DD\
```

如需修改位置或服务端容量，在 `compose.yaml` 旁创建 `.env`：

```dotenv
DASHCAM_DATA_PATH=E:/DashcamData
DASHCAM_MAX_STORAGE_GB=200
DASHCAM_MAX_AUDIO_STORAGE_GB=50
```

常用命令：

```powershell
docker compose ps
docker compose logs -f
docker compose up -d --build
docker compose down
```

`docker compose down` 会删除容器，但不会删除映射到电脑上的数据目录。

### 不使用 Docker 启动

#### API

需要 .NET 8 SDK。生成音频波形还要求 `ffmpeg` 已加入 `PATH`。

```powershell
cd server\Dashcam.Api
dotnet restore
dotnet run
```

配置位于 `server/Dashcam.Api/appsettings.json`：

```json
{
  "ConnectionStrings": { "DashcamDatabase": "Data Source=dashcam.db" },
  "VideoStoragePath": "videos",
  "AudioStoragePath": "audio",
  "MaxStorageGB": 200,
  "MaxAudioStorageGB": 50
}
```

#### Web 管理页面

推荐 Node.js 22。

```powershell
cd web-dashboard
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 会把 `/api` 代理到 `http://localhost:5000`。生产构建命令：

```powershell
npm run build
```

### 构建和安装 Android

需要 JDK 17、Android SDK 36；使用 ADB 安装时需要打开 USB debugging。

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

APK 输出位置：

```text
android-app\app\build\outputs\apk\debug\app-debug.apk
```

查看设备并指定序列号安装：

```powershell
adb devices
adb -s PHONE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

同时连接两台或更多手机时，必须使用 `-s PHONE_SERIAL`，避免安装到错误设备。

构建 Android 5 版本：

```powershell
git switch android-5-compatible
cd android-app
.\gradlew.bat assembleDebug
```

构建主版本前使用 `git switch main` 切回主分支。

### 手机首次设置

1. 安装 APK，允许相机、麦克风和通知权限。
2. 手机和服务器电脑连接同一个 Wi-Fi。
3. 在 App 中填写电脑的局域网 API 地址并点击 **Save**。
4. 确认主页显示 **Home Server: Online**。
5. 选择需要的录制模式。
6. 使用音量键模式时，在系统页面开启 Dashcam 无障碍服务。
7. 录制一个短视频或音频，并在本地列表确认文件正常。
8. 点击 **Upload Now**，然后在 Web 管理页面确认上传结果。
9. 如果手机厂商会杀死后台服务，将 App 加入电池优化白名单。

### 服务端和管理页面功能

- 视频和音频分开管理，支持分页、日期和锁定状态筛选。
- HTML5 Range 流播放和下载。
- 在服务器保存视频播放旋转角度。
- 使用 `ffmpeg` 生成并缓存音频波形；首次打开可能等待几秒。
- 视频和音频都支持锁定、解锁和明确删除。
- 显示容量总量，并提供手动清理接口。
- API 返回 UTC 时间，浏览器显示本地时间。

服务端清理会持续删除最早的未锁定文件，直到回到配置的服务端上限。它通过清理接口/管理页面执行，与手机本地循环覆盖互相独立。

### API 概览

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/health` | 健康检查和服务器 UTC 时间 |
| `POST` | `/api/videos/upload` | 上传 MP4 和元数据 |
| `GET` | `/api/videos` | 分页查询视频，支持日期/锁定筛选 |
| `GET` | `/api/videos/{id}/stream` | 支持 Range 的视频流 |
| `GET` | `/api/videos/{id}/download` | 下载原视频 |
| `PATCH` | `/api/videos/{id}/lock` | 锁定或解锁视频 |
| `PATCH` | `/api/videos/{id}/rotation` | 保存播放旋转角度 |
| `DELETE` | `/api/videos/{id}` | 删除视频记录和文件 |
| `POST` | `/api/audio/upload` | 上传 M4A 和元数据 |
| `GET` | `/api/audio` | 分页查询音频，支持日期/锁定筛选 |
| `GET` | `/api/audio/{id}/stream` | 支持 Range 的音频流 |
| `GET` | `/api/audio/{id}/waveform` | 生成或读取缓存波形 |
| `GET` | `/api/audio/{id}/download` | 下载原音频 |
| `PATCH` | `/api/audio/{id}/lock` | 锁定或解锁音频 |
| `DELETE` | `/api/audio/{id}` | 删除音频记录、文件和波形缓存 |
| `GET` | `/api/storage/status` | 视频/音频总量和配置上限 |
| `POST` | `/api/videos/cleanup` | 删除超过服务端上限的最早未锁定视频 |
| `POST` | `/api/audio/cleanup` | 删除超过服务端上限的最早未锁定音频 |

上传表单包含 `file`、`filename`、`startTime`、`endTime`、`durationSeconds` 和 `fileSizeBytes`；视频还可以包含 `playbackRotationDegrees`。API 会校验扩展名、时间范围、旋转角度和实际文件大小，并先写入临时 `.uploading` 文件，成功后再提交数据库记录。

### 安全和已知限制

- API 目前没有登录和 TLS。只应在可信局域网内使用，或者放在正确配置的反向代理/VPN 后面；不要把 5000 端口直接暴露到公网。
- Android 后台相机和按键事件受手机厂商、锁屏状态、温控和电池优化影响。
- 视频码率和每段文件大小由手机相机/编码器配置决定，因此不同手机生成的文件大小不同。
- 当前没有 GPS、碰撞检测、云存储、多用户账户或服务端连续视频拼接。
- 当前还没有 Android 自动化集成测试；长期录制和容量覆盖需要在每台目标手机上实际验收。
