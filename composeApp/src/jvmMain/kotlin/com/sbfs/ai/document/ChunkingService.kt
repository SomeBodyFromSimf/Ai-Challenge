package com.sbfs.ai.document

import com.sbfs.ai.data.ChunkingStrategy
import com.sbfs.ai.data.RagConfig

object ChunkingService {

    fun chunk(text: String, config: RagConfig): List<String> {
        val normalized = text.trim()
        if (normalized.isBlank()) return emptyList()

        return when (config.chunkingStrategy) {
            ChunkingStrategy.FIXED_SIZE -> chunkFixed(normalized, config.chunkSize, config.chunkOverlap)
            ChunkingStrategy.PARAGRAPH  -> chunkBySeparator(normalized, "\n", config.chunkSize, config.chunkOverlap)
            ChunkingStrategy.SENTENCE   -> chunkBySentences(normalized, config.chunkSize, config.chunkOverlap)
            ChunkingStrategy.RECURSIVE  -> chunkRecursive(normalized, config.chunkSize, config.chunkOverlap)
        }
    }

    // ── FIXED_SIZE ─────────────────────────────────────────────────────────────

    private fun chunkFixed(text: String, size: Int, overlap: Int): List<String> {
        val effectiveOverlap = overlap.coerceAtMost(size - 1)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + size, text.length)
            chunks.add(text.substring(start, end))
            start += size - effectiveOverlap
        }
        return chunks
    }

    // ── PARAGRAPH / general separator ─────────────────────────────────────────

    private fun chunkBySeparator(text: String, separator: String, size: Int, overlap: Int): List<String> {
        val pieces = text.split(separator).map { it.trim() }.filter { it.isNotBlank() }
        return buildChunks(pieces, separator, size, overlap)
    }

    // ── SENTENCE ───────────────────────────────────────────────────────────────

    private fun chunkBySentences(text: String, size: Int, overlap: Int): List<String> {
        // Разбиваем по границам предложений, сохраняя разделитель
        val pieces = text
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return buildChunks(pieces, " ", size, overlap)
    }

    // ── RECURSIVE ─────────────────────────────────────────────────────────────

    private fun chunkRecursive(text: String, size: Int, overlap: Int): List<String> {
        if (text.length <= size) return listOf(text).filter { it.isNotBlank() }

        val separators = listOf("\n\n" to "\n\n", "\n" to "\n", ". " to " ", " " to " ")
        for ((sep, joinSep) in separators) {
            val pieces = text.split(sep).map { it.trim() }.filter { it.isNotBlank() }
            if (pieces.size > 1) {
                val merged = buildChunks(pieces, joinSep, size, overlap)
                // Рекурсивно дробим чанки, которые всё ещё превышают size
                return merged.flatMap { chunk ->
                    if (chunk.length > size) chunkRecursive(chunk, size, overlap) else listOf(chunk)
                }
            }
        }
        return chunkFixed(text, size, overlap)
    }

    // ── Общая сборка чанков из кусочков с учётом перекрытия ───────────────────

    /**
     * Собирает чанки из атомарных [pieces], не превышая [size] символов.
     * При переполнении чанка часть его конца (до [overlap] символов) переносится
     * в начало следующего чанка.
     */
    private fun buildChunks(pieces: List<String>, joinSep: String, size: Int, overlap: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        var currentLen = 0

        for (piece in pieces) {
            val addLen = piece.length + if (current.isEmpty()) 0 else joinSep.length
            if (currentLen + addLen > size && current.isNotEmpty()) {
                chunks.add(current.joinToString(joinSep))

                // Переносим хвост предыдущего чанка как перекрытие
                val carry = mutableListOf<String>()
                var carryLen = 0
                for (p in current.reversed()) {
                    val pAdd = p.length + if (carry.isEmpty()) 0 else joinSep.length
                    if (carryLen + pAdd > overlap) break
                    carry.add(0, p)
                    carryLen += pAdd
                }
                current.clear()
                current.addAll(carry)
                currentLen = carryLen
            }
            current.add(piece)
            currentLen += addLen
        }

        if (current.isNotEmpty()) {
            chunks.add(current.joinToString(joinSep))
        }
        return chunks
    }
}
