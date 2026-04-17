package com.sbfs.ai.document

import com.sbfs.ai.data.DocumentChunk
import com.sbfs.ai.data.RagConfig
import com.sbfs.ai.repository.DocumentRepository
import kotlin.math.sqrt

/**
 * Чанк с итоговым скором релевантности (cosine similarity к запросу).
 * Скор используется в промпте как сигнал уверенности для модели.
 */
data class ScoredChunk(
    val chunk: DocumentChunk,
    /** Cosine similarity эмбеддинга чанка к эмбеддингу запроса. 0..1. */
    val score: Float,
)

class RagRetriever(
    private val documentRepository: DocumentRepository,
    private val ragConfig: RagConfig,
) {
    private val ollamaClient = OllamaClient(ragConfig.ollamaUrl, ragConfig.embeddingModel)

    /** Возвращает эмбеддинг текста как FloatArray, или null если Ollama недоступна. */
    suspend fun embedQuery(text: String): FloatArray? = ollamaClient.embed(text)?.toFloatArray()

    /**
     * Делегирует синтез запроса к OllamaClient.synthesizeRagQuery.
     * Использует titleModel из конфига как генеративную модель.
     */
    suspend fun synthesizeRagQuery(current: String, history: List<Pair<String, String?>>): SynthesisResult? =
        ollamaClient.synthesizeRagQuery(current, history, ragConfig.titleModel)

    /**
     * Возвращает наиболее релевантные чанки для [query].
     *
     * [queryEmbedding] — предвычисленный эмбеддинг запроса (экономит вызов к Ollama).
     *
     * При [useEnhanced] = false: чистый cosine similarity, topK без фильтрации.
     *
     * При [useEnhanced] = true (полный пайплайн):
     * 1. BM25 по всему корпусу (лексический сигнал)
     * 2. Cosine similarity по эмбеддингам (семантический сигнал)
     * 3. Reciprocal Rank Fusion (RRF) — объединение двух рангов
     * 4. Фильтр по minScore (по cosine similarity)
     * 5. MMR — диверсификация результатов
     */
    suspend fun retrieve(
        query: String,
        topK: Int = 5,
        useEnhanced: Boolean = true,
        queryEmbedding: FloatArray? = null,
    ): List<ScoredChunk> {
        val allChunks = documentRepository.getAllChunks()
        if (allChunks.isEmpty()) return emptyList()

        val effectiveVec: FloatArray? = queryEmbedding ?: ollamaClient.embed(query)?.toFloatArray()

        // ── Простой режим: только cosine similarity ────────────────────────────
        if (!useEnhanced) {
            val queryVec = effectiveVec ?: return emptyList()
            return allChunks
                .filter { it.embedding != null }
                .map { chunk -> ScoredChunk(chunk, cosineSimilarity(queryVec, chunk.embedding!!.toFloatArray())) }
                .sortedByDescending { it.score }
                .take(topK)
        }

        val candidates = topK * ragConfig.candidateMultiplier

        // ── BM25 ──────────────────────────────────────────────────────────────
        val bm25 = BM25Scorer(allChunks)
        val queryTokens = bm25.tokenize(query)
        val bm25Scores = FloatArray(allChunks.size) { i -> bm25.score(queryTokens, i) }

        // ── Embedding cosine similarity ────────────────────────────────────────
        val embScores = FloatArray(allChunks.size) { i ->
            if (effectiveVec != null && allChunks[i].embedding != null)
                cosineSimilarity(effectiveVec, allChunks[i].embedding!!.toFloatArray())
            else 0f
        }

        // ── RRF fusion ────────────────────────────────────────────────────────
        val bm25RankOf = IntArray(allChunks.size)
        val embRankOf  = IntArray(allChunks.size)

        allChunks.indices.sortedByDescending { bm25Scores[it] }
            .forEachIndexed { rank, idx -> bm25RankOf[idx] = rank }
        allChunks.indices.sortedByDescending { embScores[it] }
            .forEachIndexed { rank, idx -> embRankOf[idx] = rank }

        val rrfK = 60
        val rrfScores = FloatArray(allChunks.size) { i ->
            1f / (rrfK + bm25RankOf[i]) + 1f / (rrfK + embRankOf[i])
        }

        // ── Top candidates + minScore filter ──────────────────────────────────
        val topByRrf = allChunks.indices
            .sortedByDescending { rrfScores[it] }
            .take(candidates)

        val filtered = if (effectiveVec != null) {
            topByRrf.filter { embScores[it] >= ragConfig.minScore }
        } else {
            topByRrf
        }

        if (filtered.isEmpty()) return emptyList()

        // ── MMR diversification ───────────────────────────────────────────────
        val selected = mmr(
            candidates = filtered,
            allChunks  = allChunks,
            rrfScores  = rrfScores,
            lambda     = ragConfig.mmrLambda,
            topK       = topK,
        )

        return selected.map { i -> ScoredChunk(allChunks[i], embScores[i]) }
    }

    fun close() = ollamaClient.close()

    // ── MMR ───────────────────────────────────────────────────────────────────

    /**
     * Maximum Marginal Relevance: жадный отбор [topK] чанков, балансирующий
     * релевантность (RRF-скор) и разнообразие (cosine similarity к уже выбранным).
     *
     * score = λ · rrfScore(i) − (1−λ) · max_j∈selected sim(i, j)
     */
    private fun mmr(
        candidates: List<Int>,
        allChunks: List<DocumentChunk>,
        rrfScores: FloatArray,
        lambda: Float,
        topK: Int,
    ): List<Int> {
        val selected  = mutableListOf<Int>()
        val remaining = candidates.toMutableList()

        while (selected.size < topK && remaining.isNotEmpty()) {
            val next = remaining.maxByOrNull { i ->
                val relevance  = lambda * rrfScores[i]
                val redundancy = if (selected.isEmpty()) 0f else {
                    selected.maxOf { j ->
                        val vi = allChunks[i].embedding?.toFloatArray()
                        val vj = allChunks[j].embedding?.toFloatArray()
                        if (vi != null && vj != null) cosineSimilarity(vi, vj) else 0f
                    }
                }
                relevance - (1f - lambda) * redundancy
            } ?: break

            selected.add(next)
            remaining.remove(next)
        }

        return selected
    }

    // ── Cosine similarity ──────────────────────────────────────────────────────

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot   += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
