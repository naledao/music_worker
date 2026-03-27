# 音爪 / music_worker

`music_worker` 是一个围绕 YouTube 音乐搜索与 MP3 下载构建的项目，包含三部分：

- Python 核心能力层：负责搜索、下载、转码、日志、代理和 Cookie 处理
- 本地 HTTP API：供 Android App 或其他客户端调用
- Android 原生 App：提供搜索、下载、保存、代理切换、日志查看和应用更新

这个仓库当前更适合作为个人部署和二次开发基础，而不是开箱即用的公共服务。

## 主要功能

- 搜索 YouTube 音乐结果
- 创建异步下载任务并导出 MP3
- 通过 `mihomo` 读取和切换代理节点
- 使用 Cookie、`yt-dlp` 插件和 `bgutil-pot` 提高 YouTube 下载成功率
- 提供本地 API 给 Android App 调用
- 提供 Android 独立 App 工程，可直接构建签名 APK
- 提供 Supervisor 与 Magisk `service.d` 脚本做保活

## 项目结构

- [music_config.py](./music_config.py)
  配置入口，负责路径、代理、Cookie、mihomo 控制器和本地 API 参数
- [music_core.py](./music_core.py)
  搜索、下载、日志、错误分类等核心能力
- [music_local_api.py](./music_local_api.py)
  本地 HTTP API，供 App 调用
- [music_worker_ws.py](./music_worker_ws.py)
  兼容旧 WebSocket 模式的入口
- [android-app](./android-app)
  Android 原生应用工程，应用名为“音爪”
- [desktop-app](./desktop-app)
  Windows 桌面客户端工程，基于 Kotlin + Compose Desktop，当前已接通 health / search / download / proxy / logs
- [shared](./shared)
  Android / Windows 预留的共享数据模型模块
- [music_worker_supervisor.sh](./music_worker_supervisor.sh)
  Python worker 保活脚本
- [mihomo_supervisor.sh](./mihomo_supervisor.sh)
  mihomo 保活脚本
- [60-music-stack-root-watchdog.sh](./60-music-stack-root-watchdog.sh)
- [70-mihomo-keepalive.sh](./70-mihomo-keepalive.sh)
- [90-music-worker-keepalive.sh](./90-music-worker-keepalive.sh)
  Android / Magisk 侧启动与保活脚本
- [docs/ANDROID_APP_DEVELOPMENT.md](./docs/ANDROID_APP_DEVELOPMENT.md)
  详细开发文档

## 运行依赖

- Python 3
- `ffmpeg`
- `node`
- `yt-dlp`
- `mihomo`
- YouTube Cookie 文件
- `bgutil-pot`

仓库中不包含 Cookie、签名密钥和本地缓存，这些需要自行准备。

## 快速启动

### 1. 准备运行环境

至少需要准备：

- `cookies.txt`
- 可用的本地代理，例如 `http://127.0.0.1:7890`
- 可用的 `mihomo` 控制口
- `.pydeps` 和 `yt-dlp-plugins`

常用环境变量：

- `MUSIC_COOKIES_FILE`
- `MUSIC_YTDLP_PROXY`
- `MUSIC_LOCAL_API_PORT`
- `MUSIC_MIHOMO_CONFIG_FILE`
- `MUSIC_MIHOMO_CONTROLLER_URL`
- `MUSIC_MIHOMO_SECRET`
- `MUSIC_MIHOMO_SELECTOR_NAME`
- `MUSIC_WS_AUTH_TOKEN`
- `MUSIC_B_WS_URL`

### 2. 启动本地 API

```bash
export PYTHONPATH=/codes/music_worker/.pydeps:/codes/music_worker/yt-dlp-plugins${PYTHONPATH:+:$PYTHONPATH}
export MUSIC_LOCAL_API_PORT=18081
python3 -u /codes/music_worker/music_local_api.py
```

默认会监听 `127.0.0.1:18081`。

### 3. 常用 API

- `GET /api/health`
- `GET /api/proxy/current`
- `POST /api/proxy/select`
- `POST /api/search`
- `POST /api/download`
- `GET /api/tasks`
- `GET /api/tasks/{id}`
- `GET /api/files/{id}`
- `GET /api/logs`
- `GET /api/app/update`
- `GET /api/app/apk`
- `GET /api/app/package?platform=desktop`

## Android App

Android 工程位于 [android-app](./android-app)，当前 App 名称为“音爪”。

已实现的主要界面：

- 首页
- 搜索页
- 结果页
- 设置页

已支持的 App 侧能力：

- 自定义本地 API 地址
- 搜索关键词多行输入
- 下载任务进度显示
- 保存到用户目录并显示保存百分比
- App 更新检查与下载进度显示
- 私有目录清理
- 代理节点查看和切换

### 构建 APK

示例：

```bash
cd /codes/music_worker/android-app
GRADLE_USER_HOME=/codes/music_worker/.gradle-user \
ANDROID_USER_HOME=/codes/music_worker/.android-user \
HTTP_PROXY=http://127.0.0.1:7890 \
HTTPS_PROXY=http://127.0.0.1:7890 \
gradle :app:assembleRelease
```

产物路径：

- `android-app/app/build/outputs/apk/release/app-release.apk`

## 保活相关

项目包含两层保活思路：

- Linux / proot 侧通过 `music_worker_supervisor.sh`、`mihomo_supervisor.sh`
- Android / Magisk 侧通过 `service.d` 脚本和 root watchdog

相关脚本：

- [music_stack_root_watchdog.sh](./music_stack_root_watchdog.sh)
- [60-music-stack-root-watchdog.sh](./60-music-stack-root-watchdog.sh)
- [70-mihomo-keepalive.sh](./70-mihomo-keepalive.sh)
- [90-music-worker-keepalive.sh](./90-music-worker-keepalive.sh)

## 安全说明

- 本仓库不会提交 `cookies.txt`、签名 keystore、日志和本地缓存
- `WS_AUTH_TOKEN` 不再硬编码在仓库内，应通过环境变量注入
- `mihomo` 的 controller secret 也应通过环境变量或本地配置文件提供

## 已知事项

- 仓库内包含 `bin/bgutil-pot` 二进制，可直接使用，但文件较大
- 当前更偏向单机部署和自用场景
- 如果要公开分发或多人协作，建议进一步整理依赖下载方式、环境初始化脚本和发布流程

## 文档

- 详细设计与开发记录见 [docs/ANDROID_APP_DEVELOPMENT.md](./docs/ANDROID_APP_DEVELOPMENT.md)
- Windows 客户端技术方案见 [docs/WINDOWS_DESKTOP_DEVELOPMENT.md](./docs/WINDOWS_DESKTOP_DEVELOPMENT.md)
- Windows 安装包 GitHub Actions 工作流见 [`.github/workflows/windows-desktop-package.yml`](./.github/workflows/windows-desktop-package.yml)
