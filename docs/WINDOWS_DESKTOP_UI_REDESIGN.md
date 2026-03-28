# Windows 桌面端 UI 改造方案

## 0. 当前进度

截至当前仓库状态（2026-03-28）：

- 第一阶段“壳层重构”已经完成并通过桌面端 Kotlin 编译验证
- 第二阶段“搜索页重构”已经完成主要收口
- 第三阶段“代理日志页重构”已经完成
- 第四阶段“视觉与交互收口”已经开始

已落地内容：

- 已从单页长滚动结构切换为桌面工作台壳层
- 已加入顶部状态栏、左侧导航、主内容区、右侧固定任务面板
- 已接入 4 个一级页面
  - `总览`
  - `搜索下载`
  - `代理日志`
  - `更新设置`
- 已加入桌面页面枚举和壳层状态
- 已加入桌面主题基础色板
- 已在窗口层加入最小尺寸与更合理的默认窗口尺寸
- 已将“搜索下载”页的结果区改造成更高密度的桌面列表首版
- 已加入结果区工具栏、当前搜索横幅、结果列表列头
- 已将结果项从大卡片堆叠改成行式布局
  - 标题 / 频道
  - 时长
  - 任务状态
  - 操作按钮
- 已为结果行加入 hover 与选中态
- 已加入“当前选中结果”摘要区
- 已将结果区关键列宽收紧并固定
- 已将排序 / 过滤入口接成可用交互
- 已支持结果数动态反馈与过滤后空状态提示
- 已将搜索页主体拆到独立文件，开始降低 `DesktopApp.kt` 体积
- 已将搜索页通用组件继续拆到独立文件，降低 `DesktopSearchPage.kt` 复杂度
- 已把“当前搜索 / 当前选中结果”收成更紧凑的上下文条
- 已把结果行状态从双行信息块压成更紧凑的单行状态徽标
- 已继续压缩搜索侧栏、工具栏和结果列表的边距与间距
- 已继续收紧搜索侧栏宽度和结果区内边距
- 已将结果列表里的下载按钮改成“默认次级、当前项强调”的层级
- 已将“代理日志”页主体拆到独立文件
- 已将节点区改造成更紧凑的桌面切换列表
- 已将日志区改造成带过滤入口的独立滚动面板
- 已强化当前节点高亮，当前节点改成显式摘要条与列表高亮
- 已补充日志错误摘要，展示最近错误概览
- 已继续压缩日志行样式和阅读密度，更接近运维面板
- 已开始统一桌面主题的字重、字号和圆角体系
- 已开始统一壳层表面样式、边框和主内容区留白
- 已将左侧导航改成更明确的桌面工作区列表样式
- 已将顶部状态条改成按状态分色的摘要徽标
- 已将总览摘要卡进一步调整为更统一的桌面信息块样式
- 已将总览页进一步收口为 `运行概览 + 服务与任务 + 最近日志` 三块桌面面板
- 已将更新设置页进一步收口为 `桌面端更新 + 本地 API 配置 + 健康检查` 三块桌面面板
- 已补充通用的桌面提示条、内嵌信息块和键值摘要行

对应代码文件：

- [DesktopApp.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopApp.kt)
- [DesktopSearchPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchPage.kt)
- [DesktopSearchComponents.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchComponents.kt)
- [DesktopOperationsPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopOperationsPage.kt)
- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)
- [DesktopScaffold.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopScaffold.kt)
- [DesktopNavigation.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopNavigation.kt)
- [DesktopPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopPage.kt)
- [DesktopTheme.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopTheme.kt)
- [Main.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/Main.kt)

当前验证结果：

- `desktop-app` 的 `compileKotlin` 已通过
- 第二阶段搜索页改造后，`desktop-app` 的 `compileKotlin` 再次通过
- 搜索页拆分到 [DesktopSearchPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchPage.kt) 后，`desktop-app` 的 `compileKotlin` 再次通过
- 搜索组件继续拆到 [DesktopSearchComponents.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchComponents.kt) 后，`desktop-app` 的 `compileKotlin` 再次通过
- 搜索页继续微调边距、按钮层级和结果密度后，`desktop-app` 的 `compileKotlin` 再次通过
- 代理日志页拆到 [DesktopOperationsPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopOperationsPage.kt) 后，`desktop-app` 的 `compileKotlin` 再次通过
- 代理日志页继续补强节点高亮、错误摘要和日志密度后，`desktop-app` 的 `compileKotlin` 再次通过
- 第四阶段首轮主题、导航和壳层视觉统一后，`desktop-app` 的 `compileKotlin` 再次通过
- 第四阶段继续收口总览页与更新设置页后，`desktop-app` 的 `compileKotlin` 再次通过
- 最近一次验证命令：
  - `GRADLE_USER_HOME=/codes/music_worker/.gradle-user/android ./android-app/gradlew -p /codes/music_worker/desktop-app compileKotlin`
- 当前仅保留一个非阻塞警告
  - [Main.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/Main.kt) 中的 `painterResource("desktop-icon.png")` 已被上游标记为 deprecated

## 1. 背景

当前桌面端虽然已经接通了 `health / search / download / proxy / logs / update`，但界面结构仍然基本沿用移动端思路：

- 所有模块都堆在一个纵向 `LazyColumn` 里
- 每个能力都用全宽 `Card + Button` 直接平铺
- 代理节点直接渲染为长列表按钮，桌面下信息密度过低
- 搜索、任务、日志、更新、设置没有形成稳定的桌面信息层级

从当前实现看，问题主要集中在：

- [DesktopApp.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopApp.kt)
  单文件承载整个窗口，页面结构是典型移动端长滚动页
- [DesktopAppState.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopAppState.kt)
  状态模型已经可用，但还没有被组织成桌面导航和多面板结构
- [Main.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/Main.kt)
  当前窗口壳层还很薄，没有桌面端最小尺寸、主框架和布局约束

## 2. 改造目标

本次改造目标不是改业务链路，而是把当前桌面端改造成真正适合桌面使用的 UI。

目标如下：

- 从“单列长页面”改为“桌面壳层 + 分区页面 + 多面板”
- 提升信息密度，减少无意义滚动
- 让“搜索发起下载”成为主流程，而不是和更新、日志、设置混在一起
- 让代理、日志、下载任务、更新信息具备桌面可视化状态面板
- 保持现有 API 调用和 `DesktopAppState` 主体能力不被推倒重写

非目标：

- 不改 Python 下载核心
- 不新增复杂本地数据库
- 不在第一阶段重写全部状态管理

## 3. 设计原则

### 3.1 桌面优先

桌面端默认宽屏，不应继续使用移动端“一个模块一张大卡片”的排布方式。应优先使用：

- 侧边导航
- 顶部状态栏
- 分栏布局
- 表格化或列表化结果区
- 固定任务面板

### 3.2 主流程优先

主流程应明确为：

1. 检查服务连接状态
2. 搜索歌曲
3. 发起下载
4. 观察任务进度
5. 查看日志或切代理排障

因此首页和搜索页应优先服务这条链路，而不是把更新、设置放在视觉中心。

### 3.3 状态集中可见

桌面端应让用户不滚动也能看到关键状态：

- API 是否可连接
- 当前代理节点
- 当前是否有下载任务
- 最新日志是否报错
- 是否有新版本可更新

### 3.4 不做“安卓放大版”

不再使用移动端式的大段留白、满宽按钮、纵向堆叠表单。桌面端应改成更紧凑、更稳定、更适合鼠标键盘操作的结构。

## 4. 推荐信息架构

推荐将桌面端拆成 4 个一级区域：

- `总览`
- `搜索下载`
- `代理日志`
- `更新设置`

推荐窗口壳层结构：

```text
┌──────────────────────────────────────────────────────────────┐
│ 顶部状态栏：连接状态 / 当前节点 / 刷新 / 检查更新          │
├──────────────┬──────────────────────────────┬───────────────┤
│ 左侧导航     │ 主内容区                     │ 右侧状态面板  │
│ 总览         │ 当前页面内容                 │ 当前任务      │
│ 搜索下载     │                              │ 下载进度      │
│ 代理日志     │                              │ 关键提示      │
│ 更新设置     │                              │               │
└──────────────┴──────────────────────────────┴───────────────┘
```

尺寸建议：

- 左侧导航：`220dp - 240dp`
- 顶部状态栏：`56dp - 64dp`
- 右侧状态面板：`320dp - 360dp`
- 应用最小窗口宽度：不低于 `1280dp`

## 5. 页面级改造方案

### 5.1 总览页

目标：打开应用后立即知道服务是否正常、代理是否正常、任务是否正常。

建议布局：

- 顶部 4 个摘要卡片
  - 服务状态
  - 当前代理
  - 当前任务
  - 应用更新
- 下方左右双列
  - 左侧：最近任务 / 当前任务详情
  - 右侧：最近日志摘要 / 错误提示

关键改动：

- 不再展示大段说明文案
- 状态优先用 `Chip / Badge / Label` 展示
- 重要操作集中成小型工具栏，如“刷新状态”“刷新日志”“检查更新”

### 5.2 搜索下载页

这是桌面端的核心页面，应成为默认主页面。

建议布局：

- 左侧搜索面板
  - 多行关键词输入
  - 搜索按钮
  - 最近关键词
  - 当前 API 地址简要信息
- 中间结果区
  - 使用桌面列表或表格式卡片
  - 每行展示：标题、频道、时长、状态、操作
- 右侧任务面板
  - 当前下载任务
  - 进度条
  - 下载速度
  - 预计剩余时间
  - 服务端文件路径

关键改动：

- 搜索结果不再整页纵向卡片堆叠
- 下载按钮不再满宽，而是行内主操作
- 当前任务固定在右栏，不随搜索结果滚走

### 5.3 代理日志页

目标：把“节点切换”和“排错日志”整理成桌面运维视图。

建议布局：

- 左侧代理区
  - 当前节点摘要
  - 节点分组
  - 可用性
  - 可搜索的节点列表
- 右侧日志区
  - 固定高度日志面板
  - 等宽字体
  - 支持刷新
  - 支持仅看最近错误

关键改动：

- 代理节点改为紧凑列表，而不是一串满宽按钮
- 日志必须放入滚动容器，不能直接把文本铺满页面
- 当前选中节点要有明显高亮和状态标签

### 5.4 更新设置页

目标：把低频功能集中，不占主流程空间。

建议布局：

- 左侧：桌面端更新
  - 当前版本
  - 可用版本
  - 安装包大小
  - 检查更新
  - 下载安装包
  - 下载进度
- 右侧：API 配置与诊断
  - Host
  - 端口
  - 当前地址
  - 保存
  - 健康检查摘要

关键改动：

- 更新与设置从首页迁出
- 健康检查不再独立占据主屏大面积空间
- 配置面板改成桌面设置页而不是长卡片表单

## 6. 视觉风格方向

当前界面明显带有默认 Material 风格，视觉上更像“安卓界面直接放大”。建议改成更偏桌面的轻量工作台风格。

建议方向：

- 背景：浅暖灰或浅中性色，而不是默认浅紫
- 主色：保留“音爪”品牌识别，但避免整页紫色按钮
- 文本层级：标题更强，说明文字更少
- 卡片：更薄、更紧凑、阴影更轻
- 按钮：主操作高亮，次操作弱化
- 日志区：明显区别于业务区，使用等宽字体和深浅分层

建议主题基调：

- 页面底色：`#F4F1EC`
- 卡片底色：`#FBFAF8`
- 主文字：`#1E1C1A`
- 次文字：`#6B645C`
- 主强调色：`#C65A2E`
- 成功色：`#2D8C5A`
- 警告色：`#C58A1F`
- 错误色：`#C74646`

## 7. 组件层拆分方案

当前 [DesktopApp.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopApp.kt) 过于集中，建议拆分为以下结构：

```text
desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/
├── Main.kt
├── DesktopApp.kt
├── DesktopTheme.kt
├── DesktopNavigation.kt
├── DesktopScaffold.kt
├── screens/
│   ├── OverviewScreen.kt
│   ├── SearchScreen.kt
│   ├── OperationsScreen.kt
│   └── SettingsScreen.kt
└── components/
    ├── StatusCard.kt
    ├── TaskPanel.kt
    ├── ProxyList.kt
    ├── LogPanel.kt
    ├── SearchResultRow.kt
    └── UpdateCard.kt
```

状态层建议最小改动：

- 保留现有 `DesktopAppState`
- 新增页面枚举，如 `DesktopPage`
- 新增少量 UI 层状态
  - 当前页面
  - 当前选中搜索结果
  - 代理节点筛选关键字
  - 日志过滤方式

## 8. 与现有能力的映射关系

本次 UI 改造不改底层能力，只调整展示与交互组织方式。

能力映射如下：

- `refreshHealth()` 进入总览页和设置页摘要卡片
- `search()` 进入搜索下载页主流程
- `startDownload()` 与 `refreshLatestTask()` 进入右侧固定任务面板
- `refreshOperations()` 与 `selectProxy()` 进入代理日志页
- `checkAppUpdate()` 与 `downloadOrOpenAppUpdate()` 进入更新设置页

这意味着：

- 业务逻辑可以大部分保留
- 主要改造成本集中在 UI 壳层、组件拆分和布局重组

## 9. 实施阶段建议

### 第一阶段：壳层重构

目标：

- 完成顶部状态栏
- 完成左侧导航
- 完成主内容区与右侧任务面板

输出：

- 应用不再是单页长滚动
- 具备 4 个一级页面

当前状态：

- 已完成
- 已编译通过
- 当前主界面已具备桌面壳层和固定任务侧栏

### 第二阶段：搜索页重构

目标：

- 将搜索页改成“搜索面板 + 结果区 + 任务面板”
- 将结果卡片改成桌面列表行

输出：

- 搜索和下载成为主工作流

当前状态：

- 已开始
- 已完成第一版高密度桌面结果列表改造
- 当前已将搜索结果区从“桌面卡片列表”收紧为“工具栏 + 列头 + 行式结果列表”
- 已补充结果行 hover / 选中态
- 已补充当前选中结果摘要
- 已完成当前一轮列宽收口
- 已将排序 / 过滤从占位入口改成可用交互
- 已补充过滤后空状态与结果数动态反馈
- 已将搜索页主要 UI 与 helper 从 `DesktopApp.kt` 提取到独立的 [DesktopSearchPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchPage.kt)
- 已将搜索页列表、上下文条、工具栏控件等通用组件继续拆到 [DesktopSearchComponents.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchComponents.kt)
- 已将“当前搜索”横幅和“当前选中结果”摘要合并为更紧凑的上下文条
- 已将结果行状态展示压缩为更适合桌面列表的单行徽标
- [DesktopApp.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopApp.kt) 当前已回到“应用入口 + 页面路由”职责
- [DesktopSearchPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopSearchPage.kt) 当前已回到“页面布局组合”职责
- 已继续收紧搜索侧栏宽度、页面内边距和结果行垂直密度
- 已将结果区下载按钮改成“当前项强调、默认项次级”的桌面层级
- 当前已落地的搜索交互细节
  - 排序：`默认 / 标题 A-Z / 时长短到长 / 时长长到短`
  - 过滤：`全部 / 短于 4 分钟 / 4-10 分钟 / 长于 10 分钟`
  - 筛选或排序后，会尽量保留当前选中结果；若结果被过滤掉，则自动选中首条可见结果
- 右侧任务面板已完成，因此当前第二阶段主要聚焦搜索主工作流进一步收口
- 第三阶段已完成，当前已经具备这些桌面运维能力
  - 节点摘要区已经收成桌面运维卡片
  - 节点切换区已经改成可筛选的紧凑列表
  - 日志区已经改成独立滚动面板，并支持 `全部日志 / 仅错误` 过滤
  - 当前节点高亮已经强化到摘要条和列表双重层级
  - 日志区已经补充最近错误摘要
- 第四阶段已开始，当前这一轮已落地这些内容
  - 已统一基础主题色板、字号、字重和圆角层级
  - 已统一导航、主内容区、侧栏面板的边框与表面样式
  - 已将左侧导航从默认按钮列表改造成更稳定的桌面工作区导航
  - 已将顶部状态摘要改成按状态分色的桌面徽标
  - 已进一步统一总览摘要卡的视觉语言
  - 已将总览页收成更明确的桌面摘要面板与日志面板
  - 已将更新设置页收成更明确的更新面板、配置面板与健康面板
  - 已补充桌面端可复用的提示条、内嵌信息块和事实行组件
- 下一步建议继续补这些细节
  - 继续补齐更多 hover / disabled / selected 视觉状态
  - 做一轮桌面端整体视觉回归验证
  - 如有必要，再继续拆分 `DesktopApp.kt` 中新增的通用桌面组件

### 第三阶段：代理日志页重构

目标：

- 节点列表改成紧凑桌面列表
- 日志切入独立滚动面板

输出：

- 排障效率明显高于当前版本

当前状态：

- 已完成
- 已将页面主体拆到 [DesktopOperationsPage.kt](/codes/music_worker/desktop-app/src/main/kotlin/com/openclaw/musicworker/desktop/DesktopOperationsPage.kt)
- 已将节点区改成“摘要卡片 + 可筛选节点列表”
- 已将节点切换按钮从整行满宽按钮收成行内小按钮
- 已将日志区改成独立滚动面板
- 已加入 `全部日志 / 仅错误` 过滤入口
- 已强化当前节点高亮与当前节点摘要条
- 已补充最近错误摘要
- 已压缩日志行样式和阅读密度
- 当前可以进入第四阶段继续统一整体视觉与交互细节

### 第四阶段：视觉与交互收口

目标：

- 自定义主题配色
- 收敛按钮层级
- 补齐 hover / selected / disabled 状态
- 调整窗口最小尺寸与边距体系

输出：

- 摆脱默认移动端放大感

当前状态：

- 已开始
- 已统一基础主题色板、字号、字重与圆角体系
- 已统一导航、主内容区和右侧固定面板的边框与表面样式
- 已将左侧导航改成更接近桌面工作台的信息导航
- 已将顶部状态摘要改成按状态分层的桌面徽标
- 已将总览页收口为更明确的摘要区、服务任务区和日志区
- 已将更新设置页收口为更明确的更新区、配置区和健康区
- 已补充提示条、内嵌信息块和事实行等通用桌面组件
- 下一步继续补齐 hover / selected / disabled 状态，并补一轮整体视觉校验

## 10. 验收标准

改造完成后，至少应满足以下标准：

- 应用首屏不再是单列堆叠大卡片
- 搜索、代理、更新、设置分离为清晰页面
- 宽屏下无需大量滚动即可看到关键状态
- 搜索结果区域可高密度展示至少 8 条结果
- 当前下载任务始终可见
- 日志区使用独立滚动容器和等宽字体
- 代理节点列表不再使用整屏满宽按钮堆叠
- 配色和组件风格不再表现为默认移动端 Material 放大版

## 11. 推荐下一步

推荐按以下顺序落地：

1. 继续第四阶段，补齐 hover / disabled / selected 状态
2. 做一轮整体桌面端页面回归验证
3. 如有必要，继续拆分 `DesktopApp.kt` 中新增的通用桌面组件
4. 最后再看是否需要继续做更细的发布前视觉收口

这样风险最低，也最符合当前代码结构。
