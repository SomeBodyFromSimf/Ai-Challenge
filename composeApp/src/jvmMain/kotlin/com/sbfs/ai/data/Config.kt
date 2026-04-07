package com.sbfs.ai.data

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val mcpServers: List<McpServerConfig> = emptyList()
)

@Serializable
data class McpServerConfig(
    val name: String,
    val url: String,
)