# Android 歌曲排行榜功能开发文档

## 1. 文档目标

本文档用于指导在当前 `music_worker` 项目中新增“歌曲排行榜”能力，并优先在 Android 客户端落地。

### 1.1 当前进度

截至 `2026-03-30`，本文档对应的实际代码状态如下：

- `M1. 后端 MVP` 已完成
- `M2. Android 数据层接入` 已完成
- `M3. Android 页面落地` 已完成（代码接入）
- 已新增：
  - `music_charts.py`
  - `android-app/app/src/main/java/com/openclaw/musicworker/ui/screens/ChartsScreen.kt`
- 已改动：
  - `music_local_api.py`
  - `android-app/app/src/main/java/com/openclaw/musicworker/data/api/MusicApiModels.kt`
  - `android-app/app/src/main/java/com/openclaw/musicworker/data/api/MusicApiClient.kt`
  - `android-app/app/src/main/java/com/openclaw/musicworker/data/MusicRepository.kt`
  - `android-app/app/src/main/java/com/openclaw/musicworker/ui/AppViewModel.kt`
  - `android-app/app/src/main/java/com/openclaw/musicworker/ui/MusicWorkerApp.kt`
- 已上线接口：
  - `GET /api/charts/sources`
  - `GET /api/charts`
- 当前已接通的榜单来源：
  - `Apple Music`
  - `songs`
  - `daily`
- 当前已支持地区：
  - `us`
  - `jp`
  - `gb`
  - `kr`
  - `cn`
- 当前已实现能力：
  - 服务端抓取 Apple 官方 RSS 榜单
  - 服务端 15 分钟缓存
  - `force_refresh=1` 强制刷新
  - Apple 榜单封面自动转换为 `/api/cover` 代理链接
  - 非法 `region / source / type / period` 参数会返回明确错误
  - Android 端已接入榜单模型、API client、Repository、`ChartsUiState`
  - Android `ViewModel` 已支持榜单初始化加载、地区切换、刷新状态管理
  - Android 端已新增正式 `排行榜` 页面
  - Android 端已接入榜单导航入口、地区筛选、刷新按钮、榜单列表卡片
  - 榜单项已可直接带入现有搜索链路并跳转到搜索结果页
- 已验证：
  - Python 语法校验通过
  - `GET /api/charts/sources` 可返回结构化来源配置
  - `GET /api/charts?source=apple_music&type=songs&period=daily&region=us&limit=5`
  - `GET /api/charts?source=apple_music&type=songs&period=daily&region=jp&limit=3&force_refresh=1`
  - Android `./gradlew :app:compileDebugKotlin` 通过
  - Android `./gradlew :app:assembleDebug` 通过
- 下一步主任务：
  - Android 真机人工验证

这里的“Android 端实现”不是指 Android App 直接去互联网抓榜单，而是：

1. 由本地 API 统一抓取、清洗和缓存官方榜单数据
2. Android App 通过现有本地 API 获取榜单
3. Android 端提供可用、可扩展、适合手机使用的榜单页面和交互

这样做的原因很直接：

- 当前项目已经是 `Python 本地 API + Android App` 架构
- 代理、日志、错误处理、缓存都在服务端统一管理更稳
- Android 端只负责展示和交互，复杂度更低

## 2. 功能结论

截至 `2026-03-30`，这个功能已经完成后端 MVP、Android 数据层接入和 Android 榜单页面代码落地，当前需要做真机人工验证与联调。

### 2.1 官方来源结论

优先建议接入以下官方来源：

1. `Apple Music Daily Top 100`
   - 适合“今天的歌曲排行榜”这个需求
   - 官方 Top Charts 页面明确提供 `Daily Top 100`
   - 当前服务端已先落地：
     - `USA`
     - `Japan`
     - `UK`
     - `Korea`
     - `China`
2. `YouTube Music Charts`
   - 适合作为第二阶段扩展
   - 官方说明里有：
     - `Daily Top Music Videos`
     - `Daily Top Songs on Shorts`
     - `Weekly Top Songs`
     - `Weekly Top Music Videos`
   - 但严格来说，YouTube 官方帮助页里“标准的 Daily Top Songs”并不像 Apple Music 那样直接单独列出

因此本功能的产品定义建议如下：

- `MVP`：先做 `Apple Music 今日歌曲榜`
- `V2`：再补 `YouTube Weekly Top Songs`、`Daily Top Music Videos` 等扩展榜单

## 3. 官方参考

以下是本功能设计时参考的官方页面：

- Apple Music Top Charts
  - `https://music.apple.com/us/new/top-charts`
  - 页面明确展示 `Daily Top 100`
- YouTube Music Charts 帮助页
  - `https://support.google.com/youtubemusic/answer/9014376?hl=en`
  - 页面明确说明：
    - Daily Top Music Videos
    - Daily Top Songs on Shorts
    - Weekly Top Songs
    - Weekly Top Music Videos

注意：

- 本项目只应抓取官方公开榜单页或官方公开数据接口
- 不要接第三方聚合榜单站点
- 不要在 Android 端直接做网页抓取

## 4. 产品范围

## 4.1 MVP 功能范围

第一阶段只做一个真正能用的版本：

- 新增 Android `排行榜` 页面
- 支持查看 `Apple Music Daily Top Songs`
- 支持切换国家/地区
- 支持手动刷新
- 支持点击榜单歌曲后，跳转到现有搜索流程
- 支持把榜单歌曲一键带入现有搜索关键词

### 4.2 MVP 不做的内容

第一阶段先不做：

- Android 端直接播放 Apple Music 音频
- Apple Music 登录
- MusicKit 集成
- 在榜单页直接下载 Apple Music 内容
- 多来源混排
- 复杂的图表分析
- 历史榜单趋势页

### 4.3 与现有下载链路的关系

这个功能只是“发现歌曲”的入口，不改变现有下载主链路。

具体逻辑：

1. 用户在排行榜页看到一首歌
2. 点击 `搜索这首歌`
3. Android 把关键词组织成：
   - `歌名 艺人`
4. 跳转到当前搜索页或直接发起现有 `POST /api/search`
5. 用户继续使用现有 YouTube 搜索、播放、下载能力

这样可以避免一个关键问题：

- 榜单来源不等于下载来源
- Apple Music 榜单项没有直接对应的 YouTube `video id`
- 所以榜单页应该做“搜索导流”，而不是强行直接下载

## 5. 推荐架构

## 5.1 总体结构

推荐继续沿用现有分层：

1. Python 本地 API
   - 负责官方榜单抓取、解析、缓存、错误处理
2. Android Repository
   - 负责调用新榜单接口
3. Android ViewModel
   - 负责页面状态、筛选、刷新、跳转搜索
4. Android Compose UI
   - 负责榜单页面展示和交互

## 5.2 为什么不让 Android 直接抓榜

因为直接抓榜会引入这些问题：

- Android 端网络环境更不稳定
- 代理策略不统一
- HTML 解析和缓存难以统一管理
- 排查问题时日志分散在客户端
- 后续 Windows 端无法复用

所以正确做法是：

- 服务端统一抓榜
- 客户端统一消费

## 6. 后端接口设计

## 6.1 新增接口

建议新增以下接口：

### `GET /api/charts/sources`

返回客户端可用榜单来源、周期和地区。

示例：

```json
{
  "ok": true,
  "payload": {
    "sources": [
      {
        "id": "apple_music",
        "label": "Apple Music",
        "types": ["songs"],
        "periods": ["daily"],
        "regions": [
          {"id": "us", "label": "USA"},
          {"id": "jp", "label": "Japan"},
          {"id": "gb", "label": "UK"},
          {"id": "kr", "label": "Korea"},
          {"id": "cn", "label": "China"}
        ]
      }
    ]
  }
}
```

### `GET /api/charts`

参数建议：

- `source`
- `type`
- `period`
- `region`
- `limit`
- `force_refresh`

示例：

`GET /api/charts?source=apple_music&type=songs&period=daily&region=us&limit=50`

返回示例：

```json
{
  "ok": true,
  "payload": {
    "source": "apple_music",
    "type": "songs",
    "period": "daily",
    "region": "us",
    "title": "Apple Music Top Songs (US)",
    "updatedAt": "2026-03-29T15:20:00Z",
    "fromCache": true,
    "items": [
      {
        "rank": 1,
        "title": "Song Name",
        "artist": "Artist Name",
        "cover": "https://...",
        "album": "Album Name",
        "durationSec": 195,
        "deeplink": "https://music.apple.com/...",
        "searchKeyword": "Song Name Artist Name",
        "sourceId": "optional_source_id",
        "releaseDate": "2026-03-29"
      }
    ]
  }
}
```

当前服务端实现说明：

- 已实际可用
- 当前仅支持：
  - `source=apple_music`
  - `type=songs`
  - `period=daily`
- `limit` 当前会限制在 `1..100`

## 6.2 不建议的接口设计

不建议 Android 端拿一个 HTML 地址自己去解析。

也不建议返回过于原始的页面片段。

本地 API 应该返回已经适配客户端的结构化 JSON。

## 6.3 服务端缓存策略

建议：

- `Daily` 榜单缓存 `15 分钟`
- `Weekly` 榜单缓存 `1 小时`
- `force_refresh=1` 时允许手动跳过缓存

缓存键建议由以下字段组成：

- `source`
- `type`
- `period`
- `region`
- `limit`

推荐缓存目录：

- `run/state/charts_cache/`

如果后续需要更稳定的缓存，也可以放 SQLite。

当前实现状态：

- 已实现 `15 分钟` 文件缓存
- 当前缓存目录为：
  - `run/state/charts_cache/`
- 当前还没有落到 SQLite

## 6.4 代理策略

榜单抓取应复用当前服务端代理能力。

建议：

- 与现有 `MUSIC_YTDLP_PROXY` 统一
- 或新增一个独立配置，例如：
  - `MUSIC_CHARTS_PROXY`

优先级建议：

1. `MUSIC_CHARTS_PROXY`
2. `MUSIC_YTDLP_PROXY`
3. 无代理

当前实现状态：

- 已直接复用 `MUSIC_YTDLP_PROXY`
- `MUSIC_CHARTS_PROXY` 还没有单独拆出

## 7. 数据模型设计

Android 端建议新增以下模型：

```kotlin
@Serializable
data class ChartSourceInfo(
    val id: String,
    val label: String,
    val types: List<String> = emptyList(),
    val periods: List<String> = emptyList(),
    val regions: List<ChartRegionInfo> = emptyList(),
)

@Serializable
data class ChartRegionInfo(
    val id: String,
    val label: String,
)

@Serializable
data class ChartItem(
    val rank: Int,
    val title: String,
    val artist: String,
    val cover: String? = null,
    val album: String? = null,
    val durationSec: Double? = null,
    val deeplink: String? = null,
    val searchKeyword: String,
    val sourceId: String? = null,
)

@Serializable
data class ChartPayload(
    val source: String,
    val type: String,
    val period: String,
    val region: String,
    val title: String,
    val updatedAt: String? = null,
    val fromCache: Boolean = false,
    val items: List<ChartItem> = emptyList(),
)
```

说明：

- 当前后端返回字段已经按这个方向对齐
- Android 端接入时可以基本按本文档模型直接实现
- `releaseDate` 已经在后端返回，但 Android 第一阶段可以先不展示

## 8. Android 状态设计

建议在 `AppViewModel.kt` 中新增：

```kotlin
data class ChartsUiState(
    val availableSources: List<ChartSourceInfo> = emptyList(),
    val selectedSource: String = "apple_music",
    val selectedType: String = "songs",
    val selectedPeriod: String = "daily",
    val selectedRegion: String = "us",
    val title: String = "",
    val updatedAt: String? = null,
    val fromCache: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val items: List<ChartItem> = emptyList(),
    val errorMessage: String? = null,
)
```

并在 `AppUiState` 中新增：

```kotlin
val charts: ChartsUiState = ChartsUiState()
```

## 9. Android 页面设计

## 9.1 导航入口

Android 端建议新增一个主导航页：

- `排行榜`

当前主导航已有：

- 首页
- 搜索
- 结果
- 设置

建议改成：

- 首页
- 搜索
- 排行榜
- 结果
- 设置

如果底部导航空间不足，可以采用两种方案：

1. 保留五个 tab
2. 把 `结果` 改成搜索流程内部页，不放在底部主导航里

从产品角度看，第二种更合理。

## 9.2 页面结构

推荐结构：

1. 顶部概览区
   - 当前榜单名称
   - 更新时间
   - 是否来自缓存
2. 筛选区
   - 来源
   - 周期
   - 地区
3. 榜单列表
   - 排名
   - 封面
   - 歌名
   - 艺人
   - 时长
   - 搜索按钮
4. 下拉刷新或刷新按钮

## 9.3 列表项设计

单个榜单项建议包含：

- 排名数字
- 封面
- 标题
- 艺人
- 专辑名
- 时长
- 操作按钮

操作按钮建议：

- `搜索这首歌`
- `复制关键词`

第一阶段不建议直接提供：

- `下载 MP3`

原因是榜单项本身没有直接对应到 YouTube 下载 ID。

## 9.4 与现有搜索页联动

推荐交互：

1. 用户点击 `搜索这首歌`
2. App 自动把 `searchKeyword` 写入现有搜索输入框
3. 自动跳到 `搜索结果页`
4. 自动执行一次 `search()`

这样用户感知会非常顺畅。

## 10. 推荐 UI 风格

这个页面不应该做成“纯表格工具页”，也不应该像日志页。

推荐风格：

- 顶部突出榜单标题和日期
- 封面和排名有明显层级
- 前三名可有视觉强调
- 采用卡片式列表而不是密集表格
- 使用现有 Android 主题里的暖色调

可做的增强细节：

- `Top 3` 使用不同色阶徽章
- 提供 `USA / Japan / UK` 这样的胶囊筛选
- 列表首屏展示前 `20` 首，支持继续加载

## 11. 后端抓取实现建议

## 11.1 Apple Music

推荐顺序：

1. 优先尝试 Apple 官方公开 feed 或官方公开 chart 页面里的结构化数据
2. 如果公开 feed 不稳定，再解析官方 Top Charts 页面中的嵌入数据

实现原则：

- 只抓取官方页面
- 只提取元数据
- 不抓取音频流

建议输出字段：

- 排名
- 歌名
- 艺人
- 专辑
- 封面
- 时长
- deeplink
- 搜索关键词

## 11.2 YouTube Music

第二阶段再加。

建议优先做：

- `Weekly Top Songs`
- `Daily Top Music Videos`

说明原因：

- 官方帮助页对这些榜单定义清晰
- 与当前项目的 YouTube 搜索生态关系更近

## 12. Android 侧改动文件建议

当前已经改动这些文件：

- `android-app/app/src/main/java/com/openclaw/musicworker/data/api/MusicApiModels.kt`
  - 新增榜单模型
- `android-app/app/src/main/java/com/openclaw/musicworker/data/api/MusicApiClient.kt`
  - 新增榜单接口调用
- `android-app/app/src/main/java/com/openclaw/musicworker/data/MusicRepository.kt`
  - 新增榜单仓库方法
- `android-app/app/src/main/java/com/openclaw/musicworker/ui/AppViewModel.kt`
  - 新增 `ChartsUiState`
  - 新增刷新、切换来源、切换地区、跳转搜索逻辑
- `android-app/app/src/main/java/com/openclaw/musicworker/ui/MusicWorkerApp.kt`
  - 新增 `排行榜` 路由和导航入口
- `android-app/app/src/main/java/com/openclaw/musicworker/ui/screens/ChartsScreen.kt`
  - 新建榜单页面

服务端已新增或改动：

- `music_local_api.py`
  - 新增榜单接口
- `music_charts.py`
  - 专门负责榜单抓取、解析和缓存

## 13. 里程碑拆分

## M1. 后端 MVP

目标：

- 跑通 `Apple Music Daily Top 100`
- 返回结构化 JSON
- 具备 15 分钟缓存

交付：

- `GET /api/charts/sources`
- `GET /api/charts`

验收：

- `us / jp / gb` 至少三个地区返回正常
- 单次请求稳定在合理时间内
- 失败时能返回明确错误

当前状态：

- 已完成
- 当前实际上线地区为：
  - `us`
  - `jp`
  - `gb`
  - `kr`
  - `cn`
- 已完成接口：
  - `GET /api/charts/sources`
  - `GET /api/charts`
- 已完成能力：
  - Apple 榜单抓取
  - 15 分钟缓存
  - 强制刷新
  - 封面代理
  - 参数校验

## M2. Android 数据层接入

目标：

- Android 端可以获取榜单数据
- `ViewModel` 状态齐全

交付：

- API client
- Repository
- `ChartsUiState`

验收：

- 可切换地区并正确刷新
- 加载、空态、错误态完整

当前状态：

- 已完成
- 已落地：
  - API client 榜单接口调用
  - Repository 榜单封装
  - `ChartsUiState`
  - `ViewModel` 榜单初始化加载
  - 地区切换与刷新逻辑
- 已验证：
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:assembleDebug`

## M3. Android 页面落地

目标：

- 有正式榜单页
- 可以点击条目进入现有搜索链路

交付：

- `ChartsScreen.kt`
- 导航入口
- 列表样式

验收：

- 榜单页可正常进入
- 可点击 `搜索这首歌`
- 搜索结果页能自动显示结果

当前状态：

- 已完成（代码接入）
- 已落地：
  - `ChartsScreen.kt`
  - `排行榜` 导航入口
  - 榜单顶部概览卡片
  - 地区筛选 UI
  - 刷新按钮
  - 榜单封面卡片列表
  - `搜索这首歌` 到搜索结果页链路
- 已验证：
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:assembleDebug`
- 待补充：
  - Android 真机人工验证

## M4. 第二阶段扩展

目标：

- 增加第二个来源

优先顺序：

1. `YouTube Weekly Top Songs`
2. `YouTube Daily Top Music Videos`

## 14. 测试与验收

## 14.1 服务端测试

至少覆盖：

- 正常抓榜
- 地区切换
- 缓存命中
- 强制刷新
- 上游超时
- 上游返回结构变化
- 代理不可用

## 14.2 Android 测试

至少覆盖：

- 初次进入榜单页
- 切换地区
- 手动刷新
- 网络失败提示
- 点击榜单歌曲跳转搜索
- 从榜单页多次切换来源后状态是否正确

## 14.3 人工验收标准

满足以下条件即可认为第一阶段完成：

1. Android 端有独立榜单页
2. 能稳定显示一个官方“今日歌曲榜”
3. 用户可以切换地区
4. 用户可以把榜单歌曲带入现有搜索链路
5. 服务端和客户端都有明确错误提示

## 15. 风险与注意事项

## 15.1 页面结构变化风险

官方网页结构未来可能变化。

应对方式：

- 封装独立解析器
- 增加解析失败日志
- 缓存最后一次成功结果

## 15.2 地区可用性差异

不同来源可支持的地区可能不同。

应对方式：

- `GET /api/charts/sources` 由服务端动态下发可选地区
- 客户端不写死全部地区

## 15.3 榜单来源和下载来源不一致

这是设计层面的正常现象，不是 bug。

榜单用于发现歌曲，下载仍然通过现有 YouTube 搜索链路完成。

## 15.4 代理和地域结果可能不同

服务端出口地区可能影响抓到的页面内容或默认地区。

因此：

- 所有榜单请求必须显式带 `region`
- 服务端日志里要记录 `source / region / period`

## 16. 推荐下一步

如果按这份文档推进，最合理的下一步是：

1. 在 Android 真机上验证排行榜页是否正常显示
2. 验证地区切换后榜单是否刷新到对应地区
3. 验证点击 `搜索这首歌` 后是否自动进入结果页并拿到搜索结果
4. 验证加载态、空态、错误态显示是否合理
5. 验证榜单封面通过服务端代理加载是否稳定

也就是说：

- 服务端 `M1` 已经完成
- Android `M2` 已经完成
- Android `M3` 代码已经完成
- 现在应该进入人工验证与联调阶段
