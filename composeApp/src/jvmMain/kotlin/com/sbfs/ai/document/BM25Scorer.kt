package com.sbfs.ai.document

import com.sbfs.ai.data.DocumentChunk
import kotlin.math.ln

/**
 * BM25 scorer для лексического поиска по корпусу чанков.
 *
 * Параметры:
 * - k1 = 1.5 — контролирует насыщение TF (стандартное значение)
 * - b  = 0.75 — нормализация по длине документа
 *
 * Инициализируется один раз на корпус; затем [score] вызывается для каждой пары (query, docIndex).
 */
class BM25Scorer(chunks: List<DocumentChunk>) {

    private val k1 = 1.5f
    private val b  = 0.75f

    private val tokenizedChunks: List<List<String>> = chunks.map { tokenize(it.content) }

    private val avgDocLen: Float = if (tokenizedChunks.isEmpty()) 1f
        else tokenizedChunks.sumOf { it.size }.toFloat() / tokenizedChunks.size

    /** IDF для каждого термина корпуса. */
    private val idf: Map<String, Float>

    init {
        val n = chunks.size
        val df = mutableMapOf<String, Int>()
        tokenizedChunks.forEach { tokens ->
            tokens.toSet().forEach { token -> df[token] = (df[token] ?: 0) + 1 }
        }
        idf = df.mapValues { (_, docFreq) ->
            ln((n - docFreq + 0.5) / (docFreq + 0.5) + 1.0).toFloat()
        }
    }

    /** BM25-скор запроса (уже токенизированного) относительно документа с индексом [docIndex]. */
    fun score(queryTokens: List<String>, docIndex: Int): Float {
        val tokens = tokenizedChunks[docIndex]
        val docLen = tokens.size.toFloat()
        val tf = tokens.groupingBy { it }.eachCount()

        return queryTokens.sumOf { term ->
            val termIdf = (idf[term] ?: 0f).toDouble()
            val termTf  = (tf[term]  ?: 0).toDouble()
            val tfScore = termTf * (k1 + 1) / (termTf + k1 * (1.0 - b + b * docLen / avgDocLen))
            termIdf * tfScore
        }.toFloat()
    }

    /** Нормализованная токенизация: lowercase, только буквы/цифры, минимальная длина 2. */
    fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^а-яёa-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 }
}
