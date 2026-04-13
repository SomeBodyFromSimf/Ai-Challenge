package com.sbfs.ai.document

import com.sbfs.ai.data.DocumentChunk
import com.sbfs.ai.data.RagConfig
import com.sbfs.ai.repository.DocumentRepository
import kotlin.math.sqrt

class RagRetriever(
    private val documentRepository: DocumentRepository,
    private val ragConfig: RagConfig,
) {
    private val ollamaClient = OllamaClient(ragConfig.ollamaUrl, ragConfig.embeddingModel)

    /**
     * Возвращает [topK] наиболее релевантных чанков для заданного [query].
     * Если Ollama недоступна или нет чанков с эмбеддингами — возвращает пустой список.
     */
    suspend fun retrieve(query: String, topK: Int = 5): List<DocumentChunk> {
        val queryEmbedding = ollamaClient.embed(query) ?: return emptyList()
        val queryVec = queryEmbedding.toFloatArray()

        val chunks = documentRepository.getAllChunks()
            .filter { it.embedding != null }

        if (chunks.isEmpty()) return emptyList()

        return chunks
            .map { chunk -> chunk to cosineSimilarity(queryVec, chunk.embedding!!.toFloatArray()) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    fun close() = ollamaClient.close()

    // ── Cosine similarity ──────────────────────────────────────────────────────

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
