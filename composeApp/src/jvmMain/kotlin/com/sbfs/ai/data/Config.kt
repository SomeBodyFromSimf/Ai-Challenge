package com.sbfs.ai.data

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val mcpServers: List<McpServerConfig> = emptyList(),
    val rag: RagConfig = RagConfig()
)

@Serializable
data class McpServerConfig(
    val name: String,
    val url: String,
)

@Serializable
data class RagConfig(
    val chunkingStrategy: ChunkingStrategy = ChunkingStrategy.PARAGRAPH,
    val chunkSize: Int = 2000,
    val chunkOverlap: Int = 300,
    val ollamaUrl: String = "http://localhost:11434",
    val embeddingModel: String = "nomic-embed-text",
)

@Serializable
enum class ChunkingStrategy {
    /** Фиксированный размер в символах с перекрытием. */
    FIXED_SIZE,
    /** Разбивка по абзацам. */
    PARAGRAPH,
    /** Разбивка по границам предложений. */
    SENTENCE,
    /** Рекурсивная разбивка: абзацы → строки → предложения → фиксированный. */
    RECURSIVE,
}
