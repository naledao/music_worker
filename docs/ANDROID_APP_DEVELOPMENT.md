# Android 独立 App 开发文档

## 1. 文档目标

本文档用于指导把当前 `music_worker` 脚本能力单独包装成一个 Android App。

目标不是简单做一个 WebView 外壳，而是做一个可以独立完成以下能力的原生 Android 应用：

- 搜索 YouTube 音乐
- 下载音频并转成 MP3
- 展示下载进度、结果和错误
- 管理代理、Cookie、日志和节点
- 在 Android 后台稳定执行下载任务

### 1.1 当前进度

截至 `2026-03-26`，本文档对应的代码状态如下：

- `M1. 重构 Python 核心` 已完成
- `M2. 增加本地 API` 已完成最小可运行版本
- `M3. Android App 骨架` 已启动，当前进行中
- 已新增：
  - `music_config.py`
  - `music_core.py`
  - `music_local_api.py`
  - `android-app/`
- 已重构：
  - `music_worker_ws.py` 已从“大一统脚本”改为“WebSocket 兼容层”
- 已实现本地 API：
  - `GET /api/health`
  - `GET /api/proxy/current`
  - `POST /api/proxy/select`
  - `POST /api/search`
  - `POST /api/download`
  - `GET /api/tasks`
  - `GET /api/tasks/{id}`
  - `GET /api/files/{id}`
  - `GET /api/logs`
- 已验证：
  - 健康检查可返回运行时快照
  - 可读取当前 mihomo 节点并切换节点
  - 搜索接口可返回 YouTube 搜索结果
  - 下载接口可创建异步任务并成功产出 MP3
  - 文件接口可返回 `audio/mpeg`
  - 已实际验证“搜索 -> 创建下载任务 -> 轮询任务 -> 获取 MP3 文件”完整链路
  - Android 工程骨架已生成 `gradlew` / `gradle-wrapper.jar`
  - Android 侧本地 API 数据模型、仓库、ViewModel、Compose 四页骨架已落地
  - `gradle :app:compileDebugKotlin` 已通过
  - `gradle :app:assembleDebug` 已在当前设备构建成功
  - 已产出 APK：
    - `/codes/music_worker/android-app/app/build/outputs/apk/debug/app-debug.apk`
    - 大小：约 `20M`
    - `sha256`：`59be18d8278c3fc5cea6989afdb1c76d78e7945f01df96f94756306ea8f16c89`
- 下一步主任务：
  - `M3. 真机安装 APK 并继续本地 API 页面联调`


## 2. 当前脚本现状

### 2.1 核心文件

- `music_config.py`
  - 配置中心
  - 负责路径、代理、Cookie、PO Token、WS 地址等配置读取
- `music_core.py`
  - 能力层
  - 负责日志、搜索、下载、错误分类、启动摘要
- `music_worker_ws.py`
  - 当前主入口
  - 只保留以下职责：
    - 通过 WebSocket 与远端服务通信
    - 接收命令并调用 `music_core`
    - 返回搜索结果、下载元信息和二进制分片
- `music_worker_supervisor.sh`
  - 负责拉起并保活 Python worker
  - 启动时注入 `.pydeps` 和 `yt-dlp-plugins` 到 `PYTHONPATH`
- `mihomo_supervisor.sh`
  - 负责拉起并保活 `mihomo`
  - 当前代理入口为 `127.0.0.1:7890`
  - 控制口为 `127.0.0.1:10097`
- `70-mihomo-keepalive.sh`
  - Magisk `service.d` 启动 `mihomo` 保活
- `90-music-worker-keepalive.sh`
  - Magisk `service.d` 启动 Python worker 保活
- `android-app/`
  - Android 独立 App 工程目录
  - 当前已经包含：
    - Gradle 工程骨架
    - 本地 API 数据模型和客户端
    - Repository / ViewModel
    - Compose 首页、搜索页、结果页、设置页
  - 关键文件：
    - `android-app/app/src/main/java/com/openclaw/musicworker/MainActivity.kt`
    - `android-app/app/src/main/java/com/openclaw/musicworker/data/api/MusicApiClient.kt`
    - `android-app/app/src/main/java/com/openclaw/musicworker/ui/AppViewModel.kt`
    - `android-app/app/src/main/java/com/openclaw/musicworker/ui/screens/`

### 2.2 当前能力

当前运行中的 worker 仍然支持两类命令：

1. `search`
2. `download`

搜索逻辑：

- 使用 `yt_dlp` 执行 `ytsearch{limit}:{keyword}`
- 返回结果字段：
  - `id`
  - `title`
  - `channel`
  - `duration`
  - `cover`

下载逻辑：

- 输入 `music_id`
- 走多策略下载：
  - `mweb`
  - `default`
  - `web_safari`
  - `tv_downgraded`
- Cookie 开和关各跑一轮
- 下载音频后用 `ffmpeg` 转成 MP3
- 嵌入缩略图和元数据

### 2.3 当前代码分层状态

当前代码已经不是单文件结构，而是分为三层：

1. 配置层
   - `music_config.py`
2. 核心能力层
   - `music_core.py`
3. 兼容入口层
   - `music_worker_ws.py`

这样做的目的，是为后续本地 API 和 Android App 接入提供稳定边界。

### 2.4 当前运行依赖

- Python 3
- `yt_dlp`
- `websockets`
- `ffmpeg`
- `mihomo`
- `bgutil-pot`
- `node`
- YouTube Cookie 文件

### 2.5 当前配置点

脚本中的关键配置包括：

- `MUSIC_COOKIES_FILE`
- `MUSIC_YTDLP_PROXY`
- `MUSIC_WS_PROXY`
- `MUSIC_YTDLP_JS_RUNTIME`
- `MUSIC_YTDLP_REMOTE_COMPONENTS`
- `MUSIC_YTDLP_PLUGIN_DIR`
- `MUSIC_YTDLP_POT_HTTP_BASE_URL`
- `MUSIC_YTDLP_POT_SCRIPT_PATH`
- `MUSIC_YTDLP_POT_CLI_PATH`
- `MUSIC_YTDLP_PLAYER_CLIENTS`
- `MUSIC_YTDLP_FETCH_POT`
- `MUSIC_YTDLP_POT_TRACE`

当前代码中还存在硬编码远端连接：

- `B_WS_URL`
- `WS_AUTH_TOKEN`
- 远端 IP

这部分不适合作为 Android 独立 App 的长期方案，必须配置化。

### 2.6 当前已知遗留问题

- `music_worker_supervisor.sh` 的 `status` 子命令存在误判
  - 在 worker 实际运行时，偶尔仍显示 `worker: stopped`
  - 这不影响当前 worker 实际工作
  - 但在后续做本地 API 和 Android 设置页时，需要补一个更可靠的健康检查接口


## 3. 当前协议

### 3.1 文本消息

当前 WebSocket 文本协议格式：

```json
{
  "type": "search",
  "requestId": "uuid",
  "payload": {
    "keyword": "张三的歌"
  }
}
```

```json
{
  "type": "download",
  "requestId": "uuid",
  "payload": {
    "music_id": "gKQhk4GMFZI"
  }
}
```

搜索结果：

```json
{
  "type": "search_result",
  "requestId": "uuid",
  "ok": true,
  "payload": {
    "results": [
      {
        "id": "video_id",
        "title": "title",
        "channel": "channel",
        "duration": 249,
        "cover": "https://..."
      }
    ]
  }
}
```

下载元信息：

```json
{
  "type": "download_meta",
  "requestId": "uuid",
  "ok": true,
  "payload": {
    "filename": "xxx.mp3",
    "filesize": 9878754
  }
}
```

下载完成：

```json
{
  "type": "download_done",
  "requestId": "uuid",
  "ok": true,
  "payload": {}
}
```

错误：

```json
{
  "type": "error",
  "requestId": "uuid",
  "ok": false,
  "payload": {
    "message": "error text"
  }
}
```

### 3.2 二进制分片

当前下载数据通过 WebSocket 二进制帧发送，格式为：

- 前 36 字节：`requestId` 的 ASCII 文本
- 后 4 字节：`seq`，大端 `uint32`
- 剩余部分：二进制文件分片

当前分片大小：

- `CHUNK_SIZE = 256 * 1024`

这个协议适合远端 worker 模式，不适合 Android 本地 App 直接消费文件。


## 4. Android App 推荐方案

### 4.1 总体建议

推荐把现有方案拆成两层：

1. Python 核心能力层
2. Android 原生 App 层

即：

- 保留现有 Python 的 YouTube 搜索和下载能力
- 删除或弱化“必须通过远端 WebSocket”这一约束
- 改成 Android App 调本地服务

### 不推荐直接做的方案

不推荐第一版就把 `yt-dlp + PO Token + 多 client 回退 + ffmpeg` 全部重写成 Kotlin。

原因：

- 当前下载链路复杂
- YouTube 风控变化频繁
- `yt-dlp` 与 `bgutil-pot` 已经是现成可用链路
- 原生重写成本高，且风险远大于 UI 层开发

### 推荐方案

第一阶段采用：

- Android 原生 UI：Kotlin + Jetpack Compose
- 下载核心：继续复用 Python
- Android 与 Python 之间通过本地 API 通信


### 4.2 推荐架构

```text
Android App UI
    |
    |  HTTP / WebSocket / Binder
    v
Local Service API
    |
    v
Python Download Core
    |
    +-- yt-dlp
    +-- ffmpeg
    +-- bgutil-pot
    +-- cookies
    +-- mihomo(7890)
```

### 角色拆分

#### Android App

负责：

- 搜索输入与结果展示
- 下载任务管理
- 通知栏进度
- 本地文件展示
- Cookie 导入
- 节点选择
- 错误展示和日志查看

不负责：

- 直接实现 YouTube 抓取逻辑
- 直接处理 PO Token

#### Python Core

负责：

- 搜索
- 下载
- 回退策略
- 转码
- 日志
- 代理使用

不负责：

- 远端页面展示
- 原生界面


## 5. 必须做的代码重构

这一步的重构已经完成。当前代码已经按下面的方式拆分。

### 5.1 拆分建议

#### `music_core.py`

抽离纯能力代码：

- `ytdlp_search`
- `ytdlp_download_mp3`
- 日志工具
- 配置读取
- 错误分类

#### `music_local_api.py`

提供本地接口，供 Android App 调用。

建议提供：

- `POST /api/search`
- `POST /api/download`
- `GET /api/tasks/{id}`
- `GET /api/files/{id}`
- `GET /api/logs`
- `GET /api/health`
- `GET /api/proxy/current`
- `POST /api/proxy/select`
- `POST /api/cookies/import`

#### `music_worker_ws.py`

保留远端 worker 模式，只做兼容层：

- 接 WebSocket 命令
- 调 `music_core`
- 返回结果

这样可以同时支持：

- 当前旧系统
- 新 Android App

### 5.2 当前完成情况

已完成：

- 将配置从 `music_worker_ws.py` 抽离到 `music_config.py`
- 将搜索、下载、日志和错误分类抽离到 `music_core.py`
- 保留 `music_worker_ws.py` 作为兼容层，不破坏现有远端调用方式
- 新增 `music_local_api.py`
- 已打通本地 HTTP API 最小闭环：
  - 健康检查
  - 搜索
  - 下载任务创建
  - 任务查询
  - 文件获取
  - 日志读取
  - mihomo 当前节点读取与切换
- 已验证：
  - 新结构可以正常导入
  - `music_core.ytdlp_search()` 可独立调用
  - 重构后的 worker 能重新连接现有远端 WebSocket
  - 本地 API 可成功下载测试歌曲并返回 MP3 文件
  - 下载任务在策略切换后不会残留上一次失败的错误字段
  - 已新增 `android-app/` 工程骨架
  - 已落地 Android 侧：
    - 本地 API 数据模型
    - HTTP 客户端
    - Repository
    - AppViewModel
    - Compose 首页、搜索页、结果页、设置页
  - 已验证 Android 侧源码：
    - `gradlew` 已生成
    - `gradle :app:compileDebugKotlin` 可通过
    - `gradle :app:assembleDebug` 可通过

未完成：

- `POST /api/cookies/import`
- 任务状态持久化
- 本地 API 的守护启动和固定端口协调
- Android App 与本地服务的真机联调


## 6. Android 侧技术选型

### 6.1 建议技术栈

- 语言：Kotlin
- UI：Jetpack Compose
- 架构：MVVM
- 网络：OkHttp + Kotlin Serialization
- 本地数据库：Room
- 状态流：StateFlow
- 依赖注入：Hilt
- 后台任务：
  - 活跃下载：Foreground Service
  - 非实时补偿任务：WorkManager
- 音频播放：Media3

### 6.2 模块建议

```text
app/
core/network/
core/storage/
core/log/
feature/search/
feature/download/
feature/settings/
feature/history/
service/download/
```

### 6.3 数据模型建议

#### SearchItem

```kotlin
data class SearchItem(
    val id: String,
    val title: String,
    val channel: String?,
    val duration: Double?,
    val cover: String?
)
```

#### DownloadTask

```kotlin
data class DownloadTask(
    val taskId: String,
    val musicId: String,
    val title: String?,
    val status: String,
    val progress: Int,
    val filePath: String?,
    val errorMessage: String?
)
```

### 6.4 当前 Android 工程实际落地

截至 `2026-03-26`，当前 Android 工程已经不是空骨架，而是已经落地到以下结构：

```text
android-app/
  app/
    src/main/java/com/openclaw/musicworker/
      MainActivity.kt
      data/
        AppContainer.kt
        MusicRepository.kt
        api/
          MusicApiClient.kt
          MusicApiModels.kt
        settings/
          AppSettingsStore.kt
      ui/
        AppViewModel.kt
        MusicWorkerApp.kt
        screens/
          HomeScreen.kt
          SearchScreen.kt
          ResultsScreen.kt
          SettingsScreen.kt
        theme/
          Color.kt
          Theme.kt
```

当前代码职责：

- `MainActivity.kt`
  - 启动 Compose
  - 创建 `AppViewModel`
- `MusicApiClient.kt`
  - 对接本地 Python API
  - 当前已接：
    - `GET /api/health`
    - `GET /api/proxy/current`
    - `POST /api/proxy/select`
    - `POST /api/search`
    - `POST /api/download`
    - `GET /api/tasks/{id}`
    - `GET /api/tasks`
    - `GET /api/logs`
- `AppViewModel.kt`
  - 负责页面状态
  - 负责搜索、下载创建、轮询任务状态、刷新日志和节点
- `screens/`
  - 已具备 M3 所需的四个页面骨架

### 6.5 当前 Android 构建说明

当前工程配置状态：

- Gradle wrapper：已生成
- AGP：`8.7.3`
- Kotlin：`2.0.21`
- `compileSdk = 36`
- `targetSdk = 36`
- `buildToolsVersion = 36.1.0`
- `sdk.dir = /opt/android-sdk`

本轮已实际执行并验证的命令：

```bash
cd /codes/music_worker/android-app
GRADLE_USER_HOME=/codes/music_worker/.gradle-user \
HTTP_PROXY=http://127.0.0.1:7890 \
HTTPS_PROXY=http://127.0.0.1:7890 \
gradle wrapper --gradle-version 8.10.2 --no-daemon --console plain
```

```bash
cd /codes/music_worker/android-app
GRADLE_USER_HOME=/codes/music_worker/.gradle-user \
ANDROID_USER_HOME=/codes/music_worker/.android-user \
HTTP_PROXY=http://127.0.0.1:7890 \
HTTPS_PROXY=http://127.0.0.1:7890 \
gradle :app:compileDebugKotlin --no-daemon --console plain \
  -x :app:processDebugResources \
  -x :app:mergeDebugResources \
  -x :app:packageDebugResources \
  -x :app:checkDebugAarMetadata
```

```bash
cd /codes/music_worker/android-app
GRADLE_USER_HOME=/codes/music_worker/.gradle-user \
ANDROID_USER_HOME=/codes/music_worker/.android-user \
HTTP_PROXY=http://127.0.0.1:7890 \
HTTPS_PROXY=http://127.0.0.1:7890 \
gradle :app:assembleDebug --no-daemon --console plain
```

当前结果：

- Kotlin 源码可以编译通过
- 说明 Android 侧的数据层、ViewModel 和 Compose 页面骨架目前没有阻塞性编译错误
- `gradle :app:assembleDebug` 也已经可以在这台设备上成功执行
- 当前 APK 产物：
  - `/codes/music_worker/android-app/app/build/outputs/apk/debug/app-debug.apk`
  - 大小：约 `20M`
  - `sha256`：`59be18d8278c3fc5cea6989afdb1c76d78e7945f01df96f94756306ea8f16c89`

本轮修正点：

- 当前 `/opt/android-sdk/build-tools/*/aapt2` 是 `x86_64` Linux 二进制
- 当前运行环境是 `aarch64` 的 `proot Debian`
- 已通过新增 `android-app/tools/aapt2` 包装器解决：
  - 使用 `qemu-x86_64` 启动 `aapt2`
  - 通过 `android.aapt2FromMavenOverride` 强制 AGP 使用该包装器
- 另外补充了 `com.google.android.material:material` 依赖，修复主题资源缺失问题

此前的典型报错：

```text
AAPT2 aapt2-8.7.3-12006047-linux Daemon #0: Daemon startup failed
Failed to start AAPT2 process.
```

结论：

- 代码主线可以继续推进
- 当前这台设备已经可以完成 APK 构建
- 后续重点不再是构建环境，而是安装 APK 后继续做本地 API 联调


## 7. Android App 功能范围

### 7.1 MVP 功能

- 搜索歌曲
- 展示搜索结果
- 发起下载
- 显示下载中状态
- 下载完成后展示本地文件
- 导入 Cookie 文件
- 查看当前代理节点
- 查看错误日志

### 7.2 第二阶段功能

- 下载历史
- 节点切换
- 手动重试
- 任务取消
- 自动清理临时文件
- 媒体库导出
- 本地播放

### 7.3 第三阶段功能

- 批量下载
- 收藏列表
- 搜索建议
- 歌词和封面增强
- 节点测速与自动选择


## 8. 本地 API 设计建议

由于 Android App 与下载服务运行在同一台设备上，不建议继续使用“下载文件通过 WebSocket 二进制分片回传”的方式。

更适合的方案是：

- 搜索结果：JSON 返回
- 下载任务：异步任务
- 下载文件：保存在本地路径
- App 通过任务状态获取结果路径

### 8.0 当前实际实现状态

截至 `2026-03-26`，代码中的本地 API 已经落地，当前实际接口如下：

- `GET /api/health`
- `GET /api/proxy/current`
- `POST /api/proxy/select`
- `POST /api/search`
- `POST /api/download`
- `GET /api/tasks`
- `GET /api/tasks/{taskId}`
- `GET /api/files/{taskId}`
- `GET /api/logs`

当前实现细节：

- 服务实现：标准库 `ThreadingHTTPServer`
- 任务管理：内存 `TaskManager` + `ThreadPoolExecutor`
- 节点管理：通过 mihomo controller REST API
- controller 地址和 secret：优先从环境变量读取，否则从 `/etc/mihomo/config.yaml` 自动解析
- 默认端口配置：`MUSIC_LOCAL_API_PORT`
- Android 侧建议交互方式：HTTP + 轮询 `GET /api/tasks/{taskId}`

本机验证说明：

- 默认设计端口是 `18080`
- 但当前这台机器的 `127.0.0.1:18080` 已被其他本地进程占用
- 本轮验证实际使用端口：`18081`
- 因此 Android App 侧不要把端口写死，应该改为可配置

当前启动方式示例：

```bash
export PYTHONPATH=/codes/music_worker/.pydeps:/codes/music_worker/yt-dlp-plugins${PYTHONPATH:+:$PYTHONPATH}
export MUSIC_LOCAL_API_PORT=18081
python3 -u /codes/music_worker/music_local_api.py
```

### 8.1 搜索接口

`POST /api/search`

请求：

```json
{
  "keyword": "张三的歌",
  "limit": 30
}
```

响应：

```json
{
  "ok": true,
  "payload": {
    "results": []
  }
}
```

### 8.2 创建下载任务

`POST /api/download`

请求：

```json
{
  "musicId": "gKQhk4GMFZI"
}
```

响应：

```json
{
  "ok": true,
  "payload": {
    "taskId": "uuid",
    "type": "download",
    "musicId": "gKQhk4GMFZI",
    "status": "queued",
    "stage": "queued",
    "progress": 0
  }
}
```

### 8.3 查询任务状态

`GET /api/tasks/{taskId}`

响应：

```json
{
  "ok": true,
  "payload": {
    "taskId": "uuid",
    "type": "download",
    "musicId": "gKQhk4GMFZI",
    "status": "finished",
    "stage": "finished",
    "progress": 100,
    "filename": "xxx.mp3",
    "filePath": "/data/user/0/xxx/files/music/xxx.mp3",
    "fileSize": 9878754,
    "downloadedBytes": 4915421,
    "totalBytes": 4915421,
    "speedBps": 3622650.76,
    "etaSec": 0,
    "strategy": "default|cookies=on",
    "errorMessage": null,
    "errorClass": null
  }
}
```

说明：

- `status` 用于整体状态判断：`queued` / `running` / `finished` / `failed`
- `stage` 用于更细的阶段展示：如 `attempt_start`、`downloading`、`download_finished`
- Android UI 建议优先展示 `progress`、`stage`、`speedBps`、`errorMessage`

### 8.3.1 查询任务列表

`GET /api/tasks`

用途：

- App 启动后恢复当前内存中的任务列表
- 列出最近下载任务

注意：

- 当前实现为内存任务表
- Python 进程重启后，任务列表不会自动恢复
- 因此这部分仍属于后续“任务持久化”增强项

### 8.4 获取当前代理节点

`GET /api/proxy/current`

响应：

```json
{
  "ok": true,
  "payload": {
    "selector": "赔钱机场",
    "alive": true,
    "options": [],
    "name": "current_node_name"
  }
}
```

### 8.5 切换节点

`POST /api/proxy/select`

请求：

```json
{
  "name": "🇸🇬新加坡 | 高速专线-hy2"
}
```

### 8.6 健康检查

`GET /api/health`

用途：

- App 启动时确认 Python 服务是否在线
- 读取运行时信息、Cookie 状态、代理配置、任务统计

建议：

- 设置页可直接展示该接口返回的 `runtime` 和 `tasks`
- 这也可以替代 `music_worker_supervisor.sh status` 的误判问题

### 8.7 日志接口

`GET /api/logs?lines=100`

用途：

- 查看最近日志
- 下载失败后让前端快速拉取最近若干行并展示

当前约束：

- `lines` 会被限制在 `1` 到 `1000`
- 当前返回的是 `music_worker.log` 的尾部行数组


## 9. Android 后台执行设计

### 9.1 下载必须用 Foreground Service

原因：

- 下载和转码可能持续较长时间
- Android 后台限制严格
- 用户需要看到进度通知

Foreground Service 负责：

- 维持下载任务生命周期
- 更新通知栏
- 接收取消指令
- 处理失败重试

### 9.2 WorkManager 的使用边界

WorkManager 只建议做：

- 启动后恢复未完成任务
- 清理临时文件
- 补偿重试

不建议把长时间下载主链路完全交给 WorkManager。


## 10. 存储设计

### 10.1 文件目录建议

- App 私有目录：
  - 日志
  - Cookie
  - 临时文件
  - 中间下载产物
- 用户可见目录：
  - 最终 MP3 文件

### 10.2 建议目录结构

```text
files/
  logs/
  cookies/
  temp_music/
  downloads/
```

### 10.3 文件导出

如果要让系统音乐软件能识别，建议最终文件写入：

- `MediaStore.Audio`

不要依赖传统全盘读写权限。


## 11. Cookie、代理和节点管理

### 11.1 Cookie

当前脚本依赖 Cookie 来降低 YouTube 风控。

App 需要提供：

- 从文件选择器导入 Cookie
- 校验是否包含 `youtube.com`
- 展示导入时间
- 支持替换和备份

### 11.2 代理

当前脚本默认走：

- `http://127.0.0.1:7890`

App 需要提供：

- 显示当前代理状态
- 显示当前选中的节点
- 可手动切换节点
- 在下载失败时提示是否切换节点

### 11.3 当前已知经验

基于现有排查结果：

- 某些节点速度快，但会触发 YouTube bot 校验
- 某些节点速度慢，但能稳定下载
- 节点选择要同时考虑：
  - 普通带宽
  - YouTube 搜索可用性
  - YouTube 下载可用性

因此 App 不应只按延迟选节点。


## 12. 安全与配置要求

### 12.1 必须去硬编码

以下内容不能继续写死在代码里：

- WebSocket Token
- 远端 IP
- 代理节点名称
- Cookie 路径
- 控制口地址

### 12.2 配置来源建议

- 本地 `settings.json`
- Android `SharedPreferences` 或 `DataStore`
- 首次启动引导页

### 12.3 日志脱敏

日志中需要注意脱敏：

- Token
- 代理认证信息
- Cookie 原文


## 13. 兼容性与发布边界

### 13.1 如果目标设备是当前这类 Root Android

可以采用：

- App 负责 UI
- 继续使用当前 `proot Debian + mihomo + Python` 方案

优点：

- 迁移成本低
- 可最大程度复用现有脚本

缺点：

- 安装和环境复杂
- 不适合普通用户分发

### 13.2 如果目标是普通 Android 用户

需要重新评估：

- Python 运行时如何打包
- `ffmpeg` 如何集成
- `bgutil-pot` 如何在 Android 中分发
- `node` 是否还能保留

这条路线的复杂度明显更高。

### 13.3 推荐结论

如果目标是尽快落地：

- 第一版优先支持 Root / 已有现成环境的设备

如果目标是公开分发：

- 第二阶段再评估“完全 App 化”与“服务端化”


## 14. 开发里程碑

### M1. 重构 Python 核心

- 状态：已完成
- 从 `music_worker_ws.py` 抽离 `music_core.py`
- 抽离配置和日志模块到 `music_config.py` / `music_core.py`
- 保持现有远端 WebSocket worker 兼容
- 已完成基础验证：导入、搜索、worker 重载

### M2. 增加本地 API

- 状态：已完成最小可运行版本
- 已实现本地 HTTP API
- 已支持搜索、下载、状态查询、文件获取、日志查询、节点查看与切换
- 已保留旧 WebSocket 兼容层
- 剩余增强项：
  - Cookie 导入接口
  - 任务持久化
  - 固定启动端口与守护策略

### M3. Android App 骨架

- 状态：进行中
- 已完成：
  - 建立 `android-app/` 工程骨架
  - 生成 `gradlew` 和 wrapper
  - 新增 Compose 首页
  - 新增搜索页
  - 新增结果页
  - 新增设置页
  - 定义本地 API 数据模型
  - 接入本地 HTTP 客户端 / Repository / AppViewModel
  - 接通 `GET /api/health`、`POST /api/search`、`POST /api/download`、`GET /api/tasks/{id}` 的调用代码
  - 下载轮询逻辑已经落地到 ViewModel
  - 已接入系统目录选择器 `OpenDocumentTree`
  - 已支持持久化下载目录配置
  - 已支持把 `GET /api/files/{taskId}` 直接流式写入用户所选目录
  - Android 侧不再经过应用私有下载中转目录
  - 已新增“清理应用私有目录”功能
  - 已新增“检查更新 / 下载并安装更新”功能
  - 本地 API 已新增 APK 元信息与 APK 下载接口
  - 新增 `android-app/tools/aapt2` 包装器
  - 通过 `qemu-x86_64` 拉起 `aapt2`
  - 已成功构建首个 debug APK
- 已验证：
  - `gradle :app:compileDebugKotlin` 通过
  - `gradle :app:assembleDebug` 通过
  - APK 路径：
    - `/codes/music_worker/android-app/app/build/outputs/apk/debug/app-debug.apk`
    - 版本：`0.2.0 (2)`
    - 大小：约 `20.0 MiB`
    - `sha256`：`07654cb0e0b948ddf1db91360d6891c9c74a15b9c38ae2fa78219482b40c8b52`
- 当前剩余：
  - 还未做 APK 安装后的真机页面联调
  - 还未验证不同文档提供方下的目录授权兼容性
  - 还未真机验证“检查更新 -> 下载 APK -> 拉起安装器 -> 完成覆盖安装”的完整闭环
- 下一步：
  - 安装 APK 到设备
  - 真机连接本地 API 做页面联调
  - 验证“选择目录后直接保存”在目标设备上的实际表现
  - 验证未知来源安装授权后的更新流程

### M4. 下载链路接通

- 下载任务创建
- Foreground Service
- 通知栏进度
- 下载结果展示

### M5. 配置管理

- Cookie 导入
- 节点查看与切换
- 日志查看

### M6. 稳定性修正

- 失败重试
- 节点切换建议
- 清理策略
- 崩溃恢复


## 15. 验收标准

至少满足以下验收条件：

- 搜索请求成功率大于 95%
- 下载成功率在可用 Cookie 和可用节点前提下可稳定复现
- 前台下载时 App 不被系统轻易杀死
- 下载完成文件可在本地打开
- 节点切换后新任务能走新节点
- 错误能落日志并在 UI 中看到简明提示


## 16. 主要风险

### 16.1 YouTube 风控

表现：

- `Sign in to confirm you're not a bot`
- `No video formats found`
- `The page needs to be reloaded`

应对：

- Cookie 管理
- 多 client 回退
- 节点切换
- PO Token 方案保留

### 16.2 节点可用性与速度不一致

表现：

- 某些节点快但不可下载
- 某些节点慢但稳定

应对：

- 节点测速不能只看延迟
- 要区分：
  - YouTube 搜索可用性
  - YouTube 下载可用性
  - 普通文件带宽

### 16.3 Android 后台限制

表现：

- App 被系统挂起
- 前台服务被回收

应对：

- Foreground Service
- 明确下载状态持久化
- 进程重启后任务恢复

### 16.4 运行环境复杂

表现：

- Python
- ffmpeg
- mihomo
- node
- bgutil-pot

应对：

- 第一版限定运行环境
- 先做可用，再做简化


## 17. 推荐的第一版交付边界

第一版不要试图一次性解决所有问题。

建议目标收敛到：

- Root Android 设备可运行
- App 原生 UI 可搜索、下载、看日志
- 复用现有 Python 下载能力
- 支持 Cookie 导入
- 支持节点查看和切换

先把“能用的独立 App”做出来，再考虑：

- 完全摆脱 `proot`
- 完全摆脱 `mihomo`
- 完全重写下载核心


## 18. 推荐的后续代码改造清单

- 已完成：
  - 新增 `music_core.py`
  - 新增 `music_config.py`
  - 保留并瘦身 `music_worker_ws.py`

- 下一步：
- 新增 `music_local_api.py`
- 增加任务状态持久化文件或 SQLite
- 增加节点可用性测试接口
- 增加 Cookie 导入和验证接口
- 修正 `music_worker_supervisor.sh status` 的误判问题


## 19. 一句话结论

最稳妥的路线不是“重写这个脚本”，而是“把这个脚本变成 Android App 背后的本地能力层，再用 Kotlin 原生界面包起来”。
