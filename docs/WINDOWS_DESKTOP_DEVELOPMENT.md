# Windows 桌面客户端开发文档

## 1. 文档目标

本文档用于指导基于 `Kotlin Multiplatform + Compose Multiplatform Desktop` 为当前 `music_worker` 项目开发 Windows 桌面客户端。

目标不是重新实现下载核心，也不是第一阶段就把 Python、代理和所有依赖全部封装进一个单文件绿色版，而是先做一个可稳定使用、可持续演进的桌面客户端。

首阶段目标：

- 在 Windows 上提供原生桌面客户端
- 复用当前本地 API，而不是重写 Python 下载链路
- 尽量对齐 Android 客户端的数据模型、状态管理和交互逻辑
- 为后续 Android / Windows 共享业务层做铺垫

## 2. 推荐方案结论

推荐技术方案如下：

- 语言：`Kotlin`
- UI：`Compose Multiplatform Desktop`
- 架构：`MVVM + StateFlow + Coroutines`
- 网络层：`Ktor Client`
- 序列化：`kotlinx.serialization`
- 构建：`Gradle Kotlin DSL`
- 打包：`Compose Desktop Native Distributions + jpackage`
- 后端接入方式：继续调用当前 `music_local_api.py`

这是当前仓库最合适的方案，原因只有一个：

- 现有 Android 客户端已经基于 `Kotlin + Compose`
- 搜索、下载、更新、日志、设置等业务模型已经在 Android 端落地
- Windows 端如果继续使用 Kotlin / Compose，后续共享业务层和部分 UI 模式的成本最低

## 3. 为什么选这个方案

### 3.1 与现有代码最接近

当前 Android 端已经存在完整的客户端雏形，关键文件包括：

- `android-app/app/src/main/java/com/openclaw/musicworker/data/api/MusicApiClient.kt`
- `android-app/app/src/main/java/com/openclaw/musicworker/data/api/MusicApiModels.kt`
- `android-app/app/src/main/java/com/openclaw/musicworker/data/MusicRepository.kt`
- `android-app/app/src/main/java/com/openclaw/musicworker/ui/AppViewModel.kt`

这些代码已经验证了：

- 本地 API 调用链路可用
- 状态模型基本完整
- 下载和保存进度链路已经跑通
- 设置、更新、日志、代理切换等页面能力已经具备雏形

如果 Windows 端改用 `WinUI 3`、`WPF`、`Avalonia` 或 `Tauri`，这些业务层需要重新翻译一遍。

### 3.2 不强行重写 Python 核心

当前 Python 侧已经具备：

- YouTube 搜索
- 多策略下载
- MP3 转码
- Cookie 使用
- `mihomo` 节点读取与切换
- 本地 API

这部分已经是本项目最复杂、最脆弱、最有运行环境差异的部分。

因此 Windows 客户端第一阶段不应尝试重写下载核心，而应继续复用：

- `music_config.py`
- `music_core.py`
- `music_local_api.py`

### 3.3 有利于后续共享业务层

虽然当前 Android 项目还不是完整的 KMP 工程，但这不影响我们按 KMP 的方式设计 Windows 客户端：

- 第一阶段：先共享协议模型和业务层设计思路
- 第二阶段：把 Android 现有的数据层逐步抽到 `shared/`
- 第三阶段：Windows / Android 共同依赖 `shared/`

这条路线比“先把 Android 项目整体推倒重做成 KMP”更稳。

## 4. 当前项目基础

截至当前仓库状态，已经具备以下可复用基础：

- Python 核心下载与搜索能力
- 本地 HTTP API
- Android 客户端页面与状态模型
- App 更新机制
- 日志查看
- 代理节点查看与切换
- 导出 MP3 到用户目录

当前本地 API 关键接口：

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

这意味着 Windows 客户端完全可以先作为一个“桌面 UI + 本地 API 客户端”落地。

## 5. 总体架构设计

### 5.1 第一阶段架构

第一阶段不要求 Android 与 Windows 真正共享 UI，只要求：

- Windows 客户端使用与 Android 相同的业务模型
- 通过相同的本地 API 与 Python 服务通信
- 桌面端单独实现 Compose Desktop UI

架构如下：

1. Python 服务层
   - `music_local_api.py`
   - 提供搜索、下载、代理、日志、更新等接口
2. Windows 桌面端
   - `desktop-app/`
   - 负责 UI、状态管理、配置、文件保存和安装包更新
3. Android 端
   - 保持现有 `android-app/`

### 5.2 第二阶段架构

第二阶段再把公共数据层抽取成共享模块：

- `shared/`
  - API Models
  - API Client
  - Repository 接口
  - 通用 UseCase
  - 部分 ViewModel 状态模型

Android 和 Windows 各自保留自己的平台 UI。

### 5.3 为什么不先共享整套 UI

因为当前 Android 端已经有一定页面状态和交互逻辑积累，但项目尚未进行 KMP 化改造。

如果一开始就追求“Windows / Android 共享同一套 Compose UI”，会出现三个问题：

- 现有 Android 工程改造量过大
- 平台差异会把简单项目提前拖进复杂状态
- Windows 客户端的首版落地速度会明显变慢

因此推荐策略是：

- 先共享业务
- 再考虑共享 UI

## 6. 目录与模块规划

推荐在仓库中新增以下结构：

```text
music_worker/
├── android-app/
├── desktop-app/
│   ├── build.gradle.kts
│   └── src/
│       └── jvmMain/
│           ├── kotlin/
│           └── resources/
├── shared/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/
│       ├── androidMain/
│       └── jvmMain/
└── docs/
    ├── ANDROID_APP_DEVELOPMENT.md
    └── WINDOWS_DESKTOP_DEVELOPMENT.md
```

### 6.1 `shared/` 模块职责

建议第一批迁移内容：

- `MusicApiModels`
- `SearchPayload`
- `DownloadTask`
- `AppUpdateInfo`
- 通用 JSON 封装和错误解析
- API Client 接口定义
- Repository 接口定义

### 6.2 `desktop-app/` 模块职责

- Compose Desktop 启动入口
- Windows 窗口和导航
- 桌面配置持久化
- 下载任务状态查看
- 服务端文件信息展示
- 日志查看
- 应用更新下载和安装

## 7. 关键技术选型

### 7.1 UI：Compose Multiplatform Desktop

用途：

- 构建 Windows 原生桌面 UI
- 保持与 Android 端相同的 Compose 开发模型

原因：

- 与现有 Android 项目最接近
- 未来可共享部分 UI 组件和状态结构
- 打包安装流程成熟，适合产出 MSI / EXE

### 7.2 网络：Ktor Client

用途：

- 作为未来共享模块的通用 HTTP 客户端

原因：

- 比 `OkHttp` 更适合作为 KMP 共享层网络方案
- Android 和 JVM Desktop 都能复用
- 后续如果项目继续扩展到其他平台，迁移成本更低

注意：

- 第一阶段为了加快落地，也允许先在 Desktop 端继续使用 JVM 下的 `OkHttp`
- 但长期推荐统一收口到 `Ktor Client`

### 7.3 序列化：kotlinx.serialization

用途：

- API 请求 / 响应模型解析
- 本地配置文件读写

原因：

- 已适配 Kotlin 多平台
- 可直接作为 KMP 共享层标准序列化方案

### 7.4 状态管理：Coroutines + StateFlow

用途：

- 搜索状态
- 下载任务轮询
- App 更新进度

原因：

- Android 端已经采用这套思路
- Desktop 端复用成本低

### 7.5 配置存储：本地 JSON 文件

Windows 端推荐把配置保存在：

- `%APPDATA%/YinZhao/config.json`

建议保存内容：

- 本地 API Host / Port
- 日志行数
- 最近使用的关键词
- 自动检查更新开关

不建议第一阶段引入过重的桌面本地数据库。

## 8. 与现有 Android 代码的关系

当前 Android 端不是立即“代码复制”到 Windows，而是“按可迁移边界拆分”。

推荐拆分顺序：

1. 先复制数据模型定义到 `shared/`
2. 再迁移 API Client 的通用请求逻辑
3. 再迁移 Repository 中纯业务逻辑部分
4. 最后根据需要迁移状态模型

不建议第一阶段迁移的内容：

- Android `Activity`
- `DocumentFile`
- `FileProvider`
- Android DataStore
- Android 权限流程

这些都属于平台层，Windows 端应该单独实现。

## 9. Windows 客户端 MVP 功能范围

第一版只做以下内容：

### 9.1 首页

- 显示本地 API 地址
- 显示服务健康状态
- 显示当前代理节点
- 显示最近下载任务状态

### 9.2 搜索页

- 输入多行关键词
- 发起搜索
- 展示搜索结果列表

### 9.3 结果页

- 显示当前下载任务
- 展示下载进度、速度、错误信息
- 下载完成后显示服务端文件名与文件路径
- 不负责再次导出到 Windows 本机目录

### 9.4 设置页

- 配置本地 API 地址
- 查看当前代理
- 切换代理节点
- 查看最近日志
- 检查桌面客户端更新

## 10. Windows 端特有设计

### 10.1 文件目录

建议目录约定：

- 配置：`%APPDATA%/YinZhao/`
- 临时文件：`%LOCALAPPDATA%/YinZhao/cache/`
- 更新包缓存：`%LOCALAPPDATA%/YinZhao/app-updates/`

### 10.2 服务端文件落地策略

当前桌面端不再承担“把服务端已完成的 MP3 再复制到 Windows 用户目录”的职责。

首阶段约定改为：

- MP3 仍由 Python 服务端保存
- 桌面端负责发起任务、查看状态和显示服务端文件信息
- 不新增桌面端目录选择器

### 10.3 应用更新

首阶段建议沿用当前思路：

- `GET /api/app/update?platform=desktop`
- 下载桌面端安装包
- 下载完成后触发安装

但 Windows 端和 Android 端不同：

- Android 是 APK 安装
- Windows 端建议分发 MSI 或 EXE 安装器

当前本地 API 已扩展为：

- Android 继续使用默认 `GET /api/app/update` 与 `GET /api/app/apk`
- 桌面端使用 `GET /api/app/update?platform=desktop`
- 桌面端安装包下载使用 `GET /api/app/package?platform=desktop`

也就是说，多平台安装包元数据与下载路径已经分流完成。

## 11. 打包与发布策略

### 11.1 首选输出

建议优先输出：

- `MSI`

必要时再补：

- `EXE`

### 11.2 发布方式

推荐顺序：

1. 先生成本地安装包
2. 再上传到 GitHub Releases
3. 再接桌面端自更新机制

当前仓库已补充：

- GitHub Actions 工作流：`.github/workflows/windows-desktop-package.yml`
- 在 `windows-latest` 上执行 `packageReleaseExe` 与 `packageReleaseMsi`
- 自动上传 `.exe/.msi` artifact
- 当 tag 匹配 `desktop-v*` 时，自动附加到 GitHub Release

### 11.3 Python 依赖打包策略

第一阶段不做：

- Python 解释器内嵌
- `mihomo` 自动安装
- `ffmpeg` 自动安装
- `bgutil-pot` 自动下载

第一阶段推荐方式：

- Windows 客户端只负责调用已有本地 API
- Python 服务仍由现有部署方式负责

第二阶段再考虑做 Windows 一体化分发。

## 12. 开发阶段规划

### M1. 桌面工程骨架

目标：

- 新增 `desktop-app/`
- 能启动一个 Compose Desktop 窗口
- 配置应用名、图标和基础主题

### M2. 抽取共享数据层

目标：

- 新增 `shared/`
- 迁移 API models
- 迁移 JSON 解析
- 迁移 API Client 抽象

### M3. 实现 Windows MVP 页面

目标：

- 首页
- 搜索页
- 结果页
- 设置页

### M4. 接通下载任务链路

目标：

- 搜索结果展示
- 下载任务轮询
- 当前任务详情展示
- 服务端文件信息展示

### M5. 打包与发布

目标：

- 输出 MSI
- 本地安装验证
- 发布流程文档

### 当前进度（2026-03-27）

已完成：

- `desktop-app/` 已创建为独立 Compose Desktop Gradle 工程
- `shared/` 已创建，并迁移了首批通用 API models 与 `ApiServerConfig`
- 桌面端已实现本地 API 地址编辑与持久化
- 桌面端已接通 `GET /api/health`
- 桌面端已接通 `POST /api/search`
- 桌面端已可展示搜索结果列表
- 桌面端已接通 `POST /api/download`
- 桌面端已接通 `GET /api/tasks` 与 `GET /api/tasks/{id}`
- 桌面端已可展示当前下载任务、进度、速度与 ETA
- 桌面端已接通 `GET /api/proxy/current` 与 `POST /api/proxy/select`
- 桌面端已接通 `GET /api/logs`
- 桌面端已可展示最近日志并切换代理节点
- 本地 API 已支持 `GET /api/app/update?platform=desktop`
- 本地 API 已支持 `GET /api/app/package?platform=desktop`
- 桌面端已接通更新检查、安装包下载进度和打开已下载安装包
- 已新增 GitHub Actions Windows 打包工作流
- 当前代码已通过 `gradle -p /codes/music_worker/desktop-app build`
- `music_local_api.py` 已通过 `python3 -m py_compile`

当前还未完成：

- 实际运行 GitHub Actions 并验证 `.exe/.msi` 产物

## 13. 风险与约束

### 13.1 当前最大的技术风险

不是 UI，而是运行环境。

Python 下载链路依赖：

- Cookie
- 代理
- `mihomo`
- `ffmpeg`
- `bgutil-pot`
- `node`

Windows 客户端如果要做真正“一键可用”，最终仍要解决这些依赖分发与初始化问题。

### 13.2 当前明确不做的事情

第一阶段不做：

- 重写 Python 下载内核
- 直接抛弃本地 API
- 强行共享 Android / Windows 全部 UI
- 把桌面端做成 Electron 风格前端壳
- 把服务端 MP3 再复制到 Windows 本机目录
- 一开始就做完整安装器链路

## 14. 下一步建议

按当前代码状态，下一步最合理的动作是：

1. 把桌面端的 API 封装继续往 `shared/` 收口，减少 Android / Windows 后续分叉
2. 推送当前分支到 GitHub，并运行 `Windows Desktop Package` 工作流
3. 验证 `.exe/.msi` 产物路径，并确认 `platform=desktop` 更新接口可实际返回安装包
4. 补版本号更新规范与 GitHub Releases 发布说明

也就是说，当前已经完成了搜索、下载任务、日志、代理、更新下载代码链路和远程 Windows 打包流程，后续重点应转到“实际执行与发布验证”：

- 保持下载文件继续由服务端保存
- 先在 GitHub Actions 上产出可下载的 Windows 安装包
- 再完善版本发布与分发流程

这样推进，风险最低，也最接近可实际使用的 Windows MVP。
