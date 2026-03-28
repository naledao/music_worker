package com.openclaw.musicworker.desktop

enum class DesktopPage(
    val title: String,
    val summary: String,
) {
    OVERVIEW(
        title = "总览",
        summary = "服务状态与关键摘要",
    ),
    SEARCH(
        title = "搜索下载",
        summary = "搜索歌曲并发起下载",
    ),
    OPERATIONS(
        title = "代理日志",
        summary = "节点切换与日志排障",
    ),
    SETTINGS(
        title = "更新设置",
        summary = "安装包更新与本地配置",
    ),
}
