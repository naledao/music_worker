# Windows 桌面端与 Android 端功能对齐文档

## 1. 文档目标

本文档用于指导当前 `music_worker` 项目的 Windows 桌面端继续向 Android 端功能对齐。

这里的“对齐”不是要求桌面端完全复刻 Android 的页面结构，而是要求：

1. 两端覆盖相同的核心业务能力
2. 两端基于同一套本地 API 语义工作
3. 两端在关键任务上的用户预期保持一致

桌面端仍然保留桌面工作台布局，不回退成移动端页面形态。

## 2. 当前结论

截至 `2026-03-30`，桌面端已经完成一部分核心能力，但还没有与 Android 端完全对齐。

结论可以直接分成两类：

- 核心下载链路、排行榜、代理、更新、目录设置，大体已经对齐
- 应用内在线播放主链路与首版播放器体验，桌面端已完成代码落地，待人工验证

当前专项推进进度：

- `M1. 播放状态建模` 已完成第一批代码落地
- `M2. 搜索结果接入在线播放` 已完成代码落地，待人工验证
- `M3. 桌面播放器 UI` 已完成代码落地，待人工验证
- 桌面端已新增 `DesktopPlaybackUiState`
- 桌面搜索页已经可以感知播放相关状态：
  - `准备播放`
  - `缓冲中`
  - `播放中`
  - `播放失败`
- 搜索结果行已新增 `在线播放` 按钮，并会根据状态展示：
  - `在线播放`
  - `准备中…`
  - `缓冲中…`
  - `播放中`
  - `重试播放`
- 桌面端已补 `taskFileUrl(...)`，并已把 `/api/files/{taskId}` 接入播放状态
- 桌面端已新增 `playInApp(item)` 与独立播放轮询逻辑，用于复用服务端准备音频任务
- 桌面端已新增 `DesktopPlaybackController`
  - 当前使用 `JavaFX MediaPlayer` 播放服务端 HTTP 音频流
  - 已接入播放/暂停/缓冲/进度/完成/错误回调
- 桌面端已新增底部播放条与展开详情面板
  - 可显示封面、歌曲信息、服务端任务状态、播放进度与时长
  - 支持播放/暂停、拖动进度、关闭播放器
- 桌面端最近一次编译验证已通过：
  - `GRADLE_USER_HOME=/codes/music_worker/.gradle-user /codes/music_worker/android-app/gradlew -p /codes/music_worker/desktop-app compileKotlin`

也就是说：

- 现在可以说桌面端“主要工作台能力已形成”
- 但还不能说“桌面端功能已完全与 Android 对齐”
- 当前最大的真实缺口已经从“没有播放状态模型”缩小为：
  - `M2 / M3` 还缺 Windows 实机人工联调验证
  - 排行榜多来源切换 UI 还没有补齐
  - 播放器增强体验仍有继续打磨空间

## 3. 当前已对齐能力

以下能力桌面端已经具备，且与 Android 端目标基本一致：

## 3.1 搜索与下载

- 支持输入关键词搜索 YouTube 结果
- 支持发起下载任务
- 支持轮询任务状态
- 支持任务完成后导出到用户选择的 Windows 目录
- 支持已下载歌曲状态回填

当前桌面端实现位置：

- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)
- [DesktopSearchPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchPage.kt)

对应 Android 端：

- [AppViewModel.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/ui/AppViewModel.kt)
- [ResultsScreen.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/ui/screens/ResultsScreen.kt)

## 3.2 排行榜

- 桌面端已支持 `GET /api/charts/sources`
- 桌面端已支持 `GET /api/charts`
- 已支持 `排行榜 -> 搜索结果` 链路
- 已支持地区切换与榜单刷新

当前桌面端实现位置：

- [DesktopChartsPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopChartsPage.kt)
- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)

对应 Android 端：

- [ChartsScreen.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/ui/screens/ChartsScreen.kt)
- [AppViewModel.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/ui/AppViewModel.kt)

## 3.3 代理与日志

- 桌面端支持查看当前代理节点
- 支持查看日志
- 支持带密码切换节点

当前桌面端实现位置：

- [DesktopOperationsPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopOperationsPage.kt)
- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)

对应 Android 端：

- 设置页与 `AppViewModel`

## 3.4 更新与配置

- 桌面端支持配置 API 地址
- 支持选择下载目录
- 支持检查更新
- 支持下载安装包并拉起安装

当前桌面端实现位置：

- [DesktopApp.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopApp.kt)
- [DesktopUpdateManager.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopUpdateManager.kt)
- [DesktopSettingsStore.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSettingsStore.kt)

对应 Android 端：

- 设置页
- `AppViewModel`

## 4. 当前未对齐能力

以下能力是桌面端和 Android 端当前最明显的差距。

## 4.1 应用内在线播放仍待联调收口

这是当前最大的未对齐项。

Android 端已经具备：

- 搜索结果里直接点 `在线播放`
- 通知服务端准备音频
- 等待服务端串流地址可用
- 在应用内完成播放
- 显示播放状态、缓冲状态、任务状态

Android 端实现位置：

- [ResultsScreen.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/ui/screens/ResultsScreen.kt)
- [PlayerScreen.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/ui/screens/PlayerScreen.kt)
- [AppViewModel.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/ui/AppViewModel.kt)

桌面端当前已经具备：

- 搜索结果里的 `在线播放` 按钮
- 调用服务端准备音频的主链路
- 任务完成后把 `/api/files/{taskId}` 写入播放状态
- `DesktopPlaybackController + JavaFX MediaPlayer` 的真实播放实现
- 底部播放条与展开详情面板
- 基于真实播放器回传的进度、时长、缓冲与播放状态同步

桌面端当前仍需要验证：

- Windows 实机是否稳定出声播放
- 服务端刚完成任务时，桌面端是否稳定切入播放
- 暂停、恢复、拖动进度、切歌、关闭播放器时是否存在状态串扰
- 网络抖动、缓冲失败、服务端任务失败时的错误提示是否符合预期

这部分已经不再是“缺实现”，而是“缺联调验证和细节收口”。

## 4.2 桌面端播放器体验仍有增强空间

Android 端当前有独立的：

- `PlaybackUiState`

桌面端现在已经补了可驱动播放器的一版：

- `DesktopPlaybackUiState`

当前已完成：

- 在 [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt) 新增 `DesktopPlaybackUiState`
- 已接入 `DesktopUiState`
- 已补状态更新方法：
  - 准备播放
  - 绑定服务端任务
  - 标记串流可播放
  - 更新缓冲/播放状态
  - 更新真实播放进度与时长
  - 播放完成收口
  - 播放错误
  - 清空播放状态
- 已让搜索页上下文条和结果状态可以感知播放状态
- 已由 [DesktopPlaybackController.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopPlaybackController.kt) 实际驱动播放状态

但当前仍然缺：

- 更完整的桌面快捷键控制
- 更细的播放错误恢复策略
- 最近播放、播放历史等增强体验

## 4.3 榜单来源切换能力还没有落地完整 UI

虽然当前排行榜数据模型已经支持 `availableSources`，但桌面端页面还没有完整的来源选择器 UI。

这在目前只有 `Apple Music` 时不算硬伤，但后续接入 `YouTube Music Weekly Top Songs` 后会成为正式缺口。

也就是说：

- 当前桌面端排行榜对“单来源”可用
- 但对“多来源”还没有完全对齐 Android 未来目标

## 5. 对齐原则

桌面端对齐 Android 时，必须遵守以下原则：

## 5.1 业务语义对齐，不强求页面形态一致

对齐的是能力，不是像素级页面结构。

例如：

- Android 可以是播放器页
- Windows 可以是播放器底栏 + 浮层

只要两端都能完成：

- 应用内在线播放
- 展示播放状态
- 展示当前歌曲
- 基本播放控制

就算能力对齐。

## 5.2 同一条 API 语义

桌面端与 Android 端应该继续复用同一套后端接口语义。

例如在线播放仍然应该继续基于：

- 搜索结果项
- 服务端准备音频
- 服务端返回串流地址

而不是桌面端单独发明另一套下载再本地播放逻辑。

## 5.3 先补核心主链路，再补增强体验

对齐顺序必须明确：

1. 先补在线播放主链路
2. 再补播放器控制与视觉细节
3. 再补多来源排行榜切换与周榜展示扩展

## 6. 推荐对齐范围

## 6.1 第一优先级

必须尽快补齐：

- `M2 / M3` 的人工联调回归
- Windows 实机播放、暂停、拖动、切歌验证
- 缓冲失败与服务端任务异常场景验证

## 6.2 第二优先级

应继续补齐：

- 排行榜来源切换 UI
- 排行榜附加字段展示
- 与 Android 一致的榜单状态提示
- 播放器体验细节收口

## 6.3 第三优先级

可作为增强项：

- 播放历史
- 最近播放
- 当前播放歌曲的封面大图弹层
- 更完整的快捷键控制

## 7. 已落地方案与下一步实现建议

## 7.1 桌面播放状态已落地

桌面端已经引入：

```kotlin
data class DesktopPlaybackUiState(
    val currentMusicId: String? = null,
    val currentTitle: String? = null,
    val currentChannel: String? = null,
    val currentDurationSec: Double? = null,
    val currentPositionMs: Long = 0L,
    val playbackDurationMs: Long? = null,
    val currentCoverUrl: String? = null,
    val currentTask: DownloadTask? = null,
    val playbackUrl: String? = null,
    val source: String? = null,
    val isPreparing: Boolean = false,
    val isBuffering: Boolean = false,
    val playRequestToken: Long = 0L,
    val isPlaying: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)
```

当前实现位置：

- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)

## 7.2 搜索结果已接入在线播放按钮

桌面搜索结果行当前已具备两个主动作：

- `在线播放`
- `下载 MP3`

当前实现位置：

- [DesktopSearchPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchPage.kt)
- [DesktopSearchComponents.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchComponents.kt)

## 7.3 播放器 UI 形态已按桌面工作台落地

本轮实际采用：

- 底部固定播放条
- 点击封面或标题后展开播放器详情层

当前已落地结构：

1. `Mini Player`
   - 歌曲名
   - 艺人
   - 播放/暂停
   - 当前进度
   - 缓冲/准备状态
2. `Player Detail Sheet` 或右侧详情栏
   - 大封面
   - 完整标题
   - 服务端任务状态
   - 时间轴
   - 播放控制

当前实现位置：

- [DesktopPlayerBar.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopPlayerBar.kt)
- [DesktopScaffold.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopScaffold.kt)
- [DesktopApp.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopApp.kt)

## 7.4 播放引擎已选型并落地

当前实现：

- 使用 `JavaFX MediaPlayer`
- 通过 [DesktopPlaybackController.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopPlaybackController.kt) 统一封装
- 可直接播放服务端返回的 HTTP 音频流
- 已接入播放、暂停、缓冲、进度、完成、错误回调

当前构建层补充：

- 在 [build.gradle.kts](/codes/music_worker/desktop-app/build.gradle.kts) 增加 `javafx-base / graphics / swing / media`

实现已满足：

- 可播放 HTTP 音频流
- 可拿到时长和当前位置
- 支持暂停、恢复、拖动进度

## 8. 文件改动建议

当前已落地或下一阶段继续改动的文件：

- `desktop-app/build.gradle.kts`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopApp.kt`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopScaffold.kt`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchPage.kt`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchComponents.kt`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopMusicApiClient.kt`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopPlaybackController.kt`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopPlayerBar.kt`

可能新增：

- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopPlayerPanel.kt`

如果后端在线播放接口还需补字段，再视情况改：

- `music_local_api.py`
- `shared/src/commonMain/kotlin/com/openclaw/musicworker/shared/api/MusicApiModels.kt`

## 9. 里程碑拆分

## M1. 播放状态建模

目标：

- 桌面端具备独立播放状态

交付：

- `DesktopPlaybackUiState`
- 对应状态更新方法

验收：

- 编译通过
- 搜索页能感知“准备中 / 播放中 / 错误”

当前状态：

- 已完成

本轮实际落地：

- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)
  - 新增 `DesktopPlaybackUiState`
  - 新增播放状态更新方法
- [DesktopMusicApiClient.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopMusicApiClient.kt)
  - 新增 `taskFileUrl(...)`
- [DesktopSearchPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchPage.kt)
  - 搜索页接入播放状态上下文
- [DesktopSearchComponents.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchComponents.kt)
  - 搜索上下文条与结果状态支持播放态展示

## M2. 搜索结果接入在线播放

目标：

- 结果行可直接触发在线播放

交付：

- `在线播放` 按钮
- 调用服务端准备音频链路
- 服务端任务完成后把 `/api/files/{taskId}` 作为桌面端播放地址接入播放状态

验收：

- 能从搜索结果触发播放准备
- 能看到准备状态
- 能在播放状态里拿到可用的串流地址

当前状态：

- 代码已落地，待人工验证

本轮实际落地：

- [DesktopApp.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopApp.kt)
  - 将 `onPlayInApp = appState::playInApp` 接入搜索页
  - 将播放准备中的任务纳入活跃任务判断
- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)
  - 新增 `playInApp(item)`
  - 新增独立播放轮询逻辑
  - 服务端任务完成后写入 `playbackUrl`
- [DesktopMusicApiClient.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopMusicApiClient.kt)
  - 新增 `taskFileUrl(...)`
- [DesktopSearchPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchPage.kt)
  - 搜索页接入 `onPlayInApp`
- [DesktopSearchComponents.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchComponents.kt)
  - 结果行新增 `在线播放` 按钮
  - 按钮文案随播放状态变化

本阶段验证状态：

- `M2` 的目标还不是把桌面播放器完整做完
- `M2` 已完成“能触发在线播放”这条主链路
- 真正的播放器 UI 放在 `M3`
- 最近一次编译验证已通过：
  - `GRADLE_USER_HOME=/codes/music_worker/.gradle-user /codes/music_worker/android-app/gradlew -p /codes/music_worker/desktop-app compileKotlin`
- 仍需人工联调验证：
  - 服务端任务完成后桌面状态是否稳定切到可播放
  - 不同任务状态下按钮文案是否正确
  - 搜索、下载、在线播放并发时的状态回填是否正常

## M3. 桌面播放器 UI

目标：

- 桌面端具备正式播放器区域

交付：

- 底部播放条
- 展开详情面板
- 播放控制
- 真实音频播放能力
- 进度与时长展示

验收：

- 可暂停/恢复
- 可看到进度
- 可看到当前歌曲信息
- `playbackUrl` 能真正播放

当前状态：

- 代码已落地，待人工验证

本轮实际落地：

- [build.gradle.kts](/codes/music_worker/desktop-app/build.gradle.kts)
  - 增加 `javafx-base / graphics / swing / media`
- [DesktopPlaybackController.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopPlaybackController.kt)
  - 新增桌面播放器控制器
  - 使用 `JavaFX MediaPlayer` 播放服务端串流
- [DesktopPlayerBar.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopPlayerBar.kt)
  - 新增底部播放条与展开详情面板
  - 支持播放/暂停、拖动进度、展示封面/歌曲/任务状态
- [DesktopScaffold.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopScaffold.kt)
  - 新增 `bottomBar` 槽位
- [DesktopApp.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopApp.kt)
  - 接入播放器控制器
  - 接入自动开始播放、暂停、拖动进度、关闭播放器
- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)
  - 新增播放进度、时长状态
  - 新增播放进度 / 完成 / 错误收口方法

本阶段验证状态：

- 最近一次编译验证已通过：
  - `GRADLE_USER_HOME=/codes/music_worker/.gradle-user /codes/music_worker/android-app/gradlew -p /codes/music_worker/desktop-app compileKotlin`
- 仍需人工联调验证：
  - Windows 实机是否能稳定出声播放
  - 暂停、恢复、拖动进度是否符合预期
  - 连续切歌、关闭播放器时是否有旧回调串扰
  - 弱网、缓冲失败、服务端任务失败时的 UI 提示是否合理

## M4. 排行榜来源切换补齐

目标：

- 为多来源榜单做 UI 对齐准备

交付：

- 来源选择器
- 来源切换状态重算

验收：

- 新来源接入后不需要再重构页面骨架

## M5. 联调回归

目标：

- 保证桌面端在主要能力上与 Android 一致

验收：

- 搜索下载正常
- 在线播放正常
- 排行榜正常
- 代理切换正常
- 更新下载正常

## 10. 当前优先建议

当前最应该开始的是：

1. 先完成 `M2 / M3` 人工联调回归
2. 重点验证播放、缓冲、拖动进度、切歌、关闭播放器
3. 然后进入 `M4. 排行榜来源切换补齐`
4. 再做播放器增强体验收口

原因很简单：

- `M1 / M2 / M3` 已经把播放状态、主链路和首版播放器都接起来了
- 当前最大的风险已经从“没有实现”变成“缺人工验证”
- 联调验证通过后，下一阶段最明确的功能缺口就是 `M4`
