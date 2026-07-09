# Local Dashcam Auto Backup System

一个以旧 Android 手机作为行车记录仪、通过家庭 Wi‑Fi 自动备份到电脑的练手项目。MVP 的原则是：视频先可靠地保存在手机；只有服务器明确确认上传成功后才标记为 `Uploaded`；服务器离线、网络中断或上传失败都不会删除手机原文件。

## 当前实现

- Android：Kotlin、CameraX VideoCapture、前台服务、3 分钟自动切片、Room 本地队列、WorkManager Wi‑Fi 上传、断电停止、保守清理策略。
- 服务端：ASP.NET Core 8 Minimal API、EF Core、SQLite、本机文件夹存储、Range 视频流、容量清理。
- 管理页：React 19 + Vite，提供健康状态、容量、列表筛选、播放、下载、锁定及删除。

```text
旧 Android 手机
  CameraX 每 3 分钟保存 MP4
  Room: Pending / Uploading / Uploaded / Failed
  WorkManager: 仅 Wi-Fi、先 health check、逐个上传
             │
             ▼ 家庭局域网
ASP.NET Core API ── SQLite metadata
             └──── 本机 MP4 文件夹
             │
             ▼
React 管理页（列表 / 播放 / 下载 / 锁定 / 删除）
```

## 快速启动

### 1. 后端

要求 .NET 8 SDK。

```powershell
cd server\Dashcam.Api
dotnet restore
dotnet run
```

默认监听 `http://0.0.0.0:5000`。先访问 `http://localhost:5000/api/health` 确认服务正常。

在 [appsettings.json](server/Dashcam.Api/appsettings.json) 中调整：

```json
{
  "ConnectionStrings": { "DashcamDatabase": "Data Source=dashcam.db" },
  "VideoStoragePath": "D:\\DashcamVideos",
  "MaxStorageGB": 200
}
```

相对存储路径以启动后端时的工作目录为基准。首次运行会自动创建 SQLite 数据库及视频目录。Windows 防火墙需要允许 TCP 5000 的家庭/专用网络入站连接。

### 2. React 管理页

要求 Node.js 20 或更新版本。

```powershell
cd web-dashboard
npm install
npm run dev
```

打开 `http://localhost:5173`。开发服务器会将 `/api` 代理到 `http://localhost:5000`。如果前后端分开部署，可创建 `.env.local`：

```text
VITE_API_BASE_URL=http://192.168.1.50:5000
```

生产构建使用 `npm run build`，文件输出到 `web-dashboard/dist`。

### 3. Android App

推荐 Android Studio，JDK 17 或更新版本，Android SDK 36。打开 `android-app`，等待 Gradle 同步后将 App 安装到 Android 8.0（API 26）或更高版本的旧手机。命令行构建：

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

调试 APK 位于 `android-app/app/build/outputs/apk/debug/app-debug.apk`。首次使用：

1. 手机与电脑连接同一个家庭 Wi‑Fi。
2. 在 App 中把服务器地址改为电脑的局域网地址，例如 `http://192.168.1.50:5000`，不要填写 `localhost`。
3. 授予相机和通知权限。
4. 接通充电电源后点击 **Start Dashcam**。持续通知出现后可以熄屏。
5. 点击 **Upload Now** 可立即排队测试；自动任务也会在 Wi‑Fi 可用时运行。
6. 长按本地视频条目可切换本地锁定状态。

Android 的 `local.properties` 是本机 SDK 路径文件且已被 Git 忽略。换电脑后让 Android Studio 自动生成，或手动填写 `sdk.dir`。

## 核心可靠性逻辑

录制完成后，MP4 和 Room 记录先保存在手机，初始状态为 `Pending`。上传任务必须同时满足以下条件：

1. 当前网络是已验证的 Wi‑Fi；
2. `GET /api/health` 成功；
3. 视频文件仍存在。

上传逐个执行。成功后保存服务器返回的 ID、上传时间并改为 `Uploaded`；失败后改为 `Failed`，保存错误和重试次数。WorkManager 使用退避重试，App 启动、手动上传和每个新片段完成时都会安排一次唯一上传任务。服务器离线时任务退出并等待下次机会，不改动原文件。

手机默认最大视频空间为 20 GB。自动清理仅考虑上传成功、未锁定且已保留 24 小时的视频。`Pending`、`Failed`、`Uploading`、`Locked` 永远不会自动删除。如果达到上限或磁盘可用空间低于 512 MB，且没有安全候选可删，录制会停止并提示 `Storage full, recording stopped.`。

断开充电电源会触发当前片段的安全结束；有效片段写入 Room 后停止前台服务。为了避免从未充电状态启动后持续消耗电池，MVP 仅允许在充电时启动录制。

## API

| Method | Path | 用途 |
|---|---|---|
| `GET` | `/api/health` | 健康检查和服务器 UTC 时间 |
| `POST` | `/api/videos/upload` | multipart 上传 MP4 和 metadata |
| `GET` | `/api/videos?date=&locked=&page=&pageSize=` | 分页及筛选视频 |
| `GET` | `/api/videos/{id}/stream` | 支持 Range 的 HTML5 MP4 视频流 |
| `GET` | `/api/videos/{id}/download` | 下载原始 MP4 |
| `DELETE` | `/api/videos/{id}` | 删除数据库记录和物理文件 |
| `PATCH` | `/api/videos/{id}/lock` | `{ "locked": true }` 锁定或解锁 |
| `GET` | `/api/storage/status` | 视频数量、已用空间、上限及可用空间 |
| `POST` | `/api/videos/cleanup` | 超限时删除最旧的未锁定视频 |

上传表单字段：`file`、`filename`、`startTime`、`endTime`、`durationSeconds`、`fileSizeBytes`。服务端会校验扩展名、时间范围和实际文件大小，并使用 GUID 生成磁盘文件名，避免路径注入及重名覆盖。

## 已知限制

- MVP 面向可信家庭局域网，未实现登录、TLS 和权限控制；不要把 5000 端口暴露到公网。
- Android 录制必须由用户点击启动，受新版本 Android 的相机前台服务限制，这是预期行为。
- 不同旧手机的 CameraX 编码能力不同；质量选择优先 1080p，不支持时自动回退到 720p 或设备可用质量。
- 尚未加入 GPS、碰撞检测、停车监控、移动网络上传、Wake-on-LAN、实时预览和云端存储。
- 实机的熄屏录制、厂商省电策略、六分钟连续切片和长时间温控仍需在目标旧手机上验收。

## MVP 验收建议

1. 让后端和管理页保持运行，手机接通充电并开始录制。
2. 熄屏至少 6 分 20 秒，确认 Room 列表出现两个独立 MP4。
3. 关闭电脑后端，再录制一个片段并点击 Upload Now；确认 App 不崩溃且状态保持 `Pending`/`Failed`。
4. 重启后端并连接 Wi‑Fi；确认视频逐个变为 `Uploaded`。
5. 在网页完成播放、Range 拖动、下载、锁定、解锁和删除测试。
6. 用小容量测试配置验证：未上传或锁定视频不会被自动清理。

## 后续改进

优先增加自动化集成测试、上传校验和服务端后台定时清理；其后可评估 Wake-on-LAN、事故锁定按钮、加速度传感器、GPS 轨迹及低码率夜间模式。
