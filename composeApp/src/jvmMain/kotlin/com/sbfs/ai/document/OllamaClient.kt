package com.sbfs.ai.document

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

class OllamaClient(
    private val baseUrl: String,
    private val model: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis  = 30_000
        }
    }

    /**
     * Возвращает эмбеддинг для [text] в виде [ByteArray] (packed FloatArray, little-endian).
     * Возвращает null если Ollama недоступна или вернула ошибку.
     */
    suspend fun embed(text: String): ByteArray? = try {
        val response = client.post("$baseUrl/api/embeddings") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(EmbeddingRequest(model = model, prompt = text)))
        }.bodyAsText(Charsets.UTF_8)
        json.decodeFromString<EmbeddingResponse>(response).embedding.toByteArray()
    } catch (e: Exception) {
        println("[OllamaClient] Недоступен (${e::class.simpleName}: ${e.message})")
        null
    }

    /**
     * Генерирует короткий заголовок для документа на основе [text] (первые ~1000 символов).
     * Использует [model] (не embedding-модель, а генеративную).
     * Возвращает null, если Ollama недоступна или [model] пустой.
     */
    suspend fun generateTitle(text: String, model: String): String? {
        if (model.isBlank()) return null
        val snippet = text.take(1000).trim()
        return try {
            val response = client.post("$baseUrl/api/generate") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(GenerateRequest(
                    model  = model,
                    system = "Придумай короткое название документа (3–8 слов) на основе его содержимого. " +
                             "Отвечай ТОЛЬКО названием, без кавычек и лишних слов.",
                    prompt = snippet,
                    stream = false,
                )))
            }.bodyAsText(Charsets.UTF_8)
            json.decodeFromString<GenerateResponse>(response).response
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            println("[OllamaClient] generateTitle failed (${e::class.simpleName}: ${e.message})")
            null
        }
    }

    fun close() = client.close()

    // ── Serialization models ───────────────────────────────────────────────────

    @Serializable
    private data class EmbeddingRequest(
        val model: String,
        val prompt: String,
    )

    @Serializable
    private data class EmbeddingResponse(
        val embedding: List<Float>,
    )

    @Serializable
    private data class GenerateRequest(
        val model: String,
        val system: String,
        val prompt: String,
        val stream: Boolean,
    )

    @Serializable
    private data class GenerateResponse(
        val response: String,
    )
}

// ── FloatArray ↔ ByteArray ─────────────────────────────────────────────────────

fun List<Float>.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES)
    forEach { buffer.putFloat(it) }
    return buffer.array()
}

fun ByteArray.toFloatArray(): FloatArray {
    val buffer = ByteBuffer.wrap(this)
    return FloatArray(size / Float.SIZE_BYTES) { buffer.getFloat() }
}
