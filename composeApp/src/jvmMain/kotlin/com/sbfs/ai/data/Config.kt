package com.sbfs.ai.data

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val mcpServers: List<McpServerConfig> = emptyList(),
    val localLlms: List<LocalLlmConfig> = emptyList(),
    val rag: RagConfig = RagConfig()
)

@Serializable
data class McpServerConfig(
    val name: String,
    val url: String,
)

@Serializable
data class LocalLlmConfig(
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
    /** Минимальный cosine similarity чанка к запросу; чанки ниже порога отсекаются. */
    val minScore: Float = 0.25f,
    /** Коэффициент MMR: 1.0 = только релевантность, 0.0 = только разнообразие. */
    val mmrLambda: Float = 0.7f,
    /** Бюджет токенов для RAG-контекста в промпте. */
    val tokenBudget: Int = 3000,
    /** topK * candidateMultiplier = размер пула перед MMR. */
    val candidateMultiplier: Int = 3,
    /** Модель Ollama для генерации заголовка документа. Пустая строка — заголовок из имени файла. */
    val titleModel: String = "llama3.2",
    /** Порог cosine similarity для связывания нового вопроса с историей сессии. */
    val queryMemoryThreshold: Float = 0.5f,
    /** Максимальное число запросов в памяти сессии. */
    val queryMemoryLimit: Int = 20,
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
