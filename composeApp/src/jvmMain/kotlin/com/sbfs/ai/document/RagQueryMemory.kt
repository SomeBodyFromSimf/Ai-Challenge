package com.sbfs.ai.document

import kotlin.math.sqrt

data class QueryEntry(val text: String, val embedding: FloatArray, val response: String? = null)

/**
 * Память запросов в рамках одного тематического треда сессии.
 *
 * Тред — последовательность связанных Q&A пар.
 * При смене темы (тип NEW или NEGATE) тред сбрасывается.
 */
class RagQueryMemory(private val limit: Int = 20) {

    private val thread = ArrayDeque<QueryEntry>()

    fun hasHistory(): Boolean = thread.isNotEmpty()

    /** Возвращает историю как пары (вопрос, ответ?). */
    fun getThreadHistory(): List<Pair<String, String?>> = thread.map { it.text to it.response }

    fun addToThread(text: String, embedding: FloatArray) {
        if (thread.size >= limit) thread.removeFirst()
        thread.addLast(QueryEntry(text, embedding, response = null))
    }

    /** Обновляет последнюю запись треда ответом LLM (вызывается после получения ответа). */
    fun updateLastResponse(response: String) {
        val last = thread.lastOrNull() ?: return
        thread[thread.size - 1] = last.copy(response = response)
    }

    fun clearThread() = thread.clear()

    /** Возвращает записи треда, cosine similarity которых к [embedding] >= [threshold]. */
    fun findRelated(embedding: FloatArray, threshold: Float): List<QueryEntry> =
        thread.filter { cosineSimilarity(it.embedding, embedding) >= threshold }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
