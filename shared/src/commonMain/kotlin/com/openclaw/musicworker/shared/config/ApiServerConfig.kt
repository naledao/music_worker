package com.openclaw.musicworker.shared.config

import kotlinx.serialization.Serializable

@Serializable
data class ApiServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 18081,
) {
    val baseUrl: String
        get() = "http://$host:$port"
}
