# YouTube Music Weekly Top Songs 接入开发文档

## 1. 文档目标

本文档用于指导在当前 `music_worker` 项目中新增 `YouTube Music Weekly Top Songs` 榜单能力，并与现有 `Apple Music` 榜单体系共存。

目标不是新增一套独立页面，而是在当前已有的排行榜架构上继续扩展：

1. 服务端统一抓取、清洗、缓存官方榜单
2. Android 和桌面端继续消费统一的 `/api/charts` 接口
3. 榜单项继续复用现有 YouTube 搜索、播放、下载链路

## 2. 当前结论

截至 `2026-03-30`，结合官方页面与当前代码库，结论如下：

- 当前项目已完成 `Apple Music` 榜单 MVP
- 当前榜单接口已经是可扩展结构，不需要重做接口形态
- `YouTube Music Weekly Top Songs` 适合作为第二个正式榜单来源
- 官方公开入口可用：
  - `https://support.google.com/youtubemusic/answer/9014376?hl=en`
  - `https://charts.youtube.com/charts/TopSongs/us`
- 当前环境直连 Google 站点不可依赖，抓取必须走本地代理 `127.0.0.1:7890`
- 从官方页面和脚本实际验证可知：
  - 页面根入口为 `https://charts.youtube.com/charts/TopSongs/<region>`
  - 页面使用 `WEB_MUSIC_ANALYTICS` 客户端上下文
  - 官方脚本中存在 `TopSongs`、`weekly`、`chartEntries`、地区列表等结构
  - 官方地区列表里可见 `US / JP / GB / KR / HK / TW / SG / ID / Global` 等
  - 当前未发现 `China / CN / Mainland China`

结论很直接：

- 这个功能可做
- 但中国区不应伪装成 `HK` 或 `TW`
- 首批上线地区应使用 YouTube 官方真实支持的地区

## 3. 产品定义

## 3.1 新增榜单来源

新增来源定义：

- `source`: `youtube_music`
- `type`: `songs`
- `period`: `weekly`

展示名称建议：

- 来源名称：`YouTube Music`
- 榜单标题：`YouTube Music 每周热门歌曲（美国）`
- 英文官方语义对应：`Weekly Top Songs`

## 3.2 首批上线地区

首批建议上线这些地区：

- `global` 全局
- `us` 美国
- `jp` 日本
- `gb` 英国
- `kr` 韩国
- `hk` 中国香港
- `tw` 中国台湾
- `sg` 新加坡
- `id` 印度尼西亚

选择理由：

- 与当前用户的使用场景更相关
- 与现有 Apple Music 榜单地区有部分重合
- 都是官方页面与官方脚本里可见、可验证的地区

第一阶段不应提供：

- `cn`

原因：

- 当前官方 YouTube Charts 公开地区列表中未见中国大陆
- 不能把 `cn` 强行映射到 `hk`、`tw` 或 `global`

## 3.3 用户侧行为

用户流程应保持和现有 Apple Music 榜单一致：

1. 用户进入 `排行榜`
2. 选择来源 `YouTube Music`
3. 选择地区
4. 查看 `Weekly Top Songs`
5. 点击 `搜索这首歌`
6. 继续走当前 YouTube 搜索、播放、下载链路

这意味着：

- 榜单页仍然是“发现歌曲入口”
- 不是直接下载榜单内容
- 不是直接播放 YouTube Charts 页面内资源

## 4. 官方来源与抓取结论

## 4.1 官方参考页面

官方参考页面：

- YouTube Music Charts 说明页
  - `https://support.google.com/youtubemusic/answer/9014376?hl=en`
- 官方榜单页面
  - `https://charts.youtube.com/charts/TopSongs/us`
  - `https://charts.youtube.com/charts/TopSongs/global/weekly`

从官方帮助页可以确认：

- YouTube Music / YouTube Charts 官方提供 `Weekly Top Songs`

从官方页面和脚本验证可以确认：

- `TopSongs` 是正式路由的一部分
- `weekly` 是正式周期的一部分
- 页面使用统一的图表应用壳，而不是静态 HTML 榜单表格

## 4.2 技术结论

当前页面首屏 HTML 不直接内嵌完整榜单 JSON。

这意味着服务端不应把“直接解析 HTML 表格”当成主方案。

推荐技术路线：

1. 先做一次技术探针，确认官方页面实际加载榜单数据的请求路径
2. 如果能稳定找到官方结构化响应，就用结构化响应作为正式抓取方案
3. 如果官方页面前端改动频繁、结构化响应难以稳定调用，再退回到无头浏览器渲染后提取已渲染结果

生产环境的优先级建议：

1. 官方结构化响应
2. 无头浏览器渲染 + DOM 提取
3. 纯 HTML 正则抓取

其中第 3 条不建议作为长期方案。

## 5. 后端设计

## 5.1 现有接口是否够用

现有接口已经够用：

- `GET /api/charts/sources`
- `GET /api/charts`

不需要新增新的公共接口路径。

只需要在现有接口语义下扩展来源配置和服务端抓取逻辑。

## 5.2 sources 接口扩展

`GET /api/charts/sources` 需要新增一项：

```json
{
  "id": "youtube_music",
  "label": "YouTube Music",
  "types": ["songs"],
  "periods": ["weekly"],
  "regions": [
    {"id": "global", "label": "全球"},
    {"id": "us", "label": "美国"},
    {"id": "jp", "label": "日本"},
    {"id": "gb", "label": "英国"},
    {"id": "kr", "label": "韩国"},
    {"id": "hk", "label": "中国香港"},
    {"id": "tw", "label": "中国台湾"},
    {"id": "sg", "label": "新加坡"},
    {"id": "id", "label": "印度尼西亚"}
  ]
}
```

## 5.3 charts 接口请求形式

请求示例：

```text
GET /api/charts?source=youtube_music&type=songs&period=weekly&region=us&limit=50
GET /api/charts?source=youtube_music&type=songs&period=weekly&region=global&limit=100
```

## 5.4 响应结构建议

继续复用当前 `ChartPayload`，但建议给 `ChartItem` 扩展几个可选字段。

推荐字段：

- `rank`
- `title`
- `artist`
- `cover`
- `album`
- `durationSec`
- `deeplink`
- `searchKeyword`
- `sourceId`
- `releaseDate`
- `lastPeriodRank`
- `periodsOnChart`
- `viewsCount`
- `rankDirection`

建议响应示例：

```json
{
  "ok": true,
  "payload": {
    "source": "youtube_music",
    "type": "songs",
    "period": "weekly",
    "region": "us",
    "title": "YouTube Music 每周热门歌曲（美国）",
    "updatedAt": "2026-03-30T00:00:00Z",
    "fromCache": false,
    "items": [
      {
        "rank": 1,
        "title": "Example Song",
        "artist": "Example Artist",
        "cover": "http://127.0.0.1:18081/api/cover?url=...",
        "album": null,
        "durationSec": null,
        "deeplink": "https://www.youtube.com/watch?v=...",
        "searchKeyword": "Example Song Example Artist",
        "sourceId": "VIDEO_ID",
        "releaseDate": null,
        "lastPeriodRank": 3,
        "periodsOnChart": 12,
        "viewsCount": 12345678,
        "rankDirection": "up"
      }
    ]
  }
}
```

## 5.5 服务端抓取实现建议

推荐在 [music_charts.py](/codes/music_worker/music_charts.py) 中扩展：

- 新增 `YOUTUBE_MUSIC_WEEKLY_CHART_REGIONS`
- 新增 `YOUTUBE_MUSIC_WEEKLY_CHART_REGION_LABELS`
- 新增 `fetch_youtube_music_weekly_top_songs(region, limit)`
- 新增 `build_youtube_music_chart_title(region)`

建议实现分两层：

### A. 技术探针层

目标：

- 用最小代价确定官方页面真实数据来源

建议步骤：

1. 使用代理访问 `https://charts.youtube.com/charts/TopSongs/us`
2. 记录页面加载过程中的 `fetch / XHR`
3. 确认最终榜单数据是否来自官方结构化接口
4. 固化请求头、客户端上下文、地区参数和周期参数

建议说明：

- 这一步可以使用 Playwright 仅做开发期探针
- 不建议把 Playwright 当长期生产依赖

### B. 正式抓取层

优先方案：

- 直接调用探针阶段确认的官方结构化响应接口

备选方案：

- 请求 `charts.youtube.com/charts/TopSongs/<region>`
- 用无头浏览器等待页面渲染完成
- 从已渲染列表中抽取榜单项

不推荐方案：

- 对首屏 HTML 做脆弱正则抓取

## 5.6 缓存策略

由于这是周榜，不需要像 Apple 日榜那样频繁刷新。

建议：

- `YouTube Music Weekly Top Songs` 缓存 `6 小时`
- 保留 `force_refresh=1`
- 当强制刷新失败时，优先回退到已有缓存
- 允许回退到过期缓存，避免前端直接看到错误

## 5.7 代理策略

这部分必须写死进实现要求：

- 所有 `charts.youtube.com` 请求必须走本地代理 `127.0.0.1:7890`
- 所有后续拿到的封面图链接也要继续走现有 `/api/cover`

原因：

- 当前机器直连 Google 系服务不可依赖
- 现有架构已经对 YouTube 系流量统一走代理

## 5.8 错误处理

建议错误处理与 Apple 榜单保持一致：

- 参数错误返回明确 `400`
- 上游抓取失败返回明确错误消息
- 强制刷新失败优先回退缓存
- 日志里打印：
  - 请求地区
  - 请求来源
  - 抓取耗时
  - 使用了代理还是缓存
  - 上游失败摘要

## 6. 数据模型改动建议

## 6.1 Python 侧

`music_charts.py` 里的 `ChartItem` 生成逻辑建议补充可选字段：

- `lastPeriodRank`
- `periodsOnChart`
- `viewsCount`
- `rankDirection`

## 6.2 shared / Android / Desktop 侧

建议同步扩展这些模型：

- [MusicApiModels.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/data/api/MusicApiModels.kt)
- [MusicApiModels.kt](/codes/music_worker/shared/src/commonMain/kotlin/com/openclaw/musicworker/shared/api/MusicApiModels.kt)

推荐新增可选字段：

```kotlin
val lastPeriodRank: Int? = null
val periodsOnChart: Int? = null
val viewsCount: Long? = null
val rankDirection: String? = null
```

这样 Apple 榜单仍可不填，YouTube 周榜可按需展示。

## 7. Android 端改动建议

当前 Android 排行榜页已经有地区切换，但还没有正式的来源切换 UI。

要接入第二个来源，建议改这些地方：

- [AppViewModel.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/ui/AppViewModel.kt)
  - 支持来源切换
  - 根据来源重算合法的 `type / period / region`
  - 来源切换后自动刷新榜单
- [ChartsScreen.kt](/codes/music_worker/android-app/app/src/main/java/com/openclaw/musicworker/ui/screens/ChartsScreen.kt)
  - 新增来源选择器
  - 标题、副标题按来源动态展示
  - YouTube 周榜可显示：
    - 上期排名
    - 在榜周数
    - 方向变化

Android UI 建议：

- 顶部新增 `来源` 胶囊：
  - `Apple Music`
  - `YouTube Music`
- 选中 `YouTube Music` 后：
  - 周期固定显示 `每周`
  - 地区列表切到 YouTube 官方支持范围
  - 如果当前地区在新来源下不合法，应自动回退到首个合法地区

## 8. 桌面端改动建议

桌面端需要和 Android 对齐。

建议改动：

- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)
  - 支持来源切换和状态重算
- [DesktopChartsPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopChartsPage.kt)
  - 新增来源切换区
  - 补充 YouTube 周榜信息展示
- [DesktopMusicApiClient.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopMusicApiClient.kt)
  - 如果共享模型有扩展字段，保持反序列化兼容

## 9. 文件改动范围建议

服务端：

- `music_charts.py`
- `music_local_api.py`

共享模型：

- `shared/src/commonMain/kotlin/com/openclaw/musicworker/shared/api/MusicApiModels.kt`

Android：

- `android-app/app/src/main/java/com/openclaw/musicworker/data/api/MusicApiModels.kt`
- `android-app/app/src/main/java/com/openclaw/musicworker/ui/AppViewModel.kt`
- `android-app/app/src/main/java/com/openclaw/musicworker/ui/screens/ChartsScreen.kt`

桌面端：

- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopChartsPage.kt`
- `desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopMusicApiClient.kt`

文档：

- `docs/ANDROID_CHARTS_FEATURE_DEVELOPMENT.md`
- 本文档

## 10. 里程碑拆分

## M0. 技术探针

目标：

- 确认 `charts.youtube.com` 的真实结构化数据获取方式

交付：

- 记录可稳定复现的官方请求路径
- 明确请求参数、客户端上下文、地区参数、周期参数
- 确认是否必须依赖无头浏览器

验收：

- `us` 至少能稳定拿到前 `10` 首结构化榜单

## M1. 后端抓取 MVP

目标：

- 跑通 `YouTube Music Weekly Top Songs`

交付：

- `source=youtube_music`
- `type=songs`
- `period=weekly`
- 至少 `global / us / jp / gb / kr` 可用

验收：

- `/api/charts/sources` 返回新来源
- `/api/charts?source=youtube_music&type=songs&period=weekly&region=us&limit=10` 返回正常
- 失败时能回退缓存

## M2. 模型扩展

目标：

- 支持展示周榜附加信息

交付：

- `ChartItem` 扩展可选字段
- Android / Desktop / shared 模型同步

验收：

- Apple 榜单兼容不回归
- YouTube 周榜字段正常解析

## M3. Android UI 接入

目标：

- Android 端可切换来源并查看 YouTube 周榜

交付：

- 来源切换 UI
- YouTube 周榜列表展示
- 点击歌曲继续走搜索链路

验收：

- 真机切换来源、地区、刷新都正常
- 封面加载正常
- 搜索跳转正常

## M4. 桌面端对齐

目标：

- 桌面端与 Android 端榜单能力对齐

交付：

- 来源切换 UI
- 周榜字段展示

验收：

- 桌面端手动验证通过

## M5. 联调与回归

目标：

- 保证双来源榜单共存稳定

验收：

- Apple Music 不回归
- YouTube Music 周榜稳定
- 缓存与强刷逻辑正常
- 代理故障时错误可诊断

## 11. 验收清单

- `GET /api/charts/sources` 包含 `youtube_music`
- `GET /api/charts?source=youtube_music&type=songs&period=weekly&region=global&limit=10`
- `GET /api/charts?source=youtube_music&type=songs&period=weekly&region=us&limit=10&force_refresh=1`
- Android 编译通过
- 桌面端编译通过
- Android 和桌面端都能切换来源
- 地区名显示为中文
- 不会错误展示 `中国`
- 封面继续通过 `/api/cover` 访问

## 12. 风险与注意事项

- YouTube Charts 页面是动态应用，页面实现可能调整
- 官方脚本或数据协议存在变更风险
- Google 相关流量必须走代理
- 中国大陆地区当前不应伪造支持
- 如果最终只能使用无头渲染，服务端资源占用会高于 Apple 榜单

## 13. 下一步建议

下一步不应该直接开始改 UI。

最合理的顺序是：

1. 先做 `M0 技术探针`
2. 确认 YouTube 官方榜单结构化数据路径
3. 再开始 `M1 后端抓取 MVP`

如果要继续推进，本轮之后的第一步应当是：

- 编写一个仅用于开发验证的探针脚本，抓取 `charts.youtube.com/charts/TopSongs/us` 的真实数据来源
