package com.sbfs.ai.document

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer

enum class SynthesisType { REFINE, NEGATE, NEW }

/**
 * Результат синтеза запроса LLM.
 * [query] заполнен для REFINE и NEGATE — это итоговый поисковый запрос.
 */
data class SynthesisResult(val type: SynthesisType, val query: String? = null)

private const val MAX_RESPONSE_PREVIEW = 400

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

    /**
     * Анализирует связь [current] с историей запросов [history] через локальную LLM.
     *
     * Возвращает:
     * - REFINE + query — уточнение/развитие темы, синтезированный поисковый запрос
     * - NEGATE + query — пользователь отверг направление, скорректированный запрос
     * - NEW — новая тема, история игнорируется
     *
     * Возвращает null если [model] пуст или Ollama недоступна.
     */
    /**
     * [history] — список пар (вопрос пользователя, ответ LLM или null если ещё не получен).
     * Ответы LLM обрезаются до [MAX_RESPONSE_PREVIEW] символов, чтобы не раздувать промпт.
     */
    suspend fun synthesizeRagQuery(
        current: String,
        history: List<Pair<String, String?>>,
        model: String,
    ): SynthesisResult? {
        if (model.isBlank()) return null
        val historyText = history.mapIndexed { i, (q, a) ->
            val answerLine = if (a != null) "\n   Ответ: \"${a.take(MAX_RESPONSE_PREVIEW)}\"" else ""
            "${i + 1}. Вопрос: \"$q\"$answerLine"
        }.joinToString("\n")
        val prompt = buildString {
            append("История диалога по теме:\n$historyText\n\n")
            append("Новый вопрос: \"$current\"\n\n")
            append("Ответь СТРОГО одной строкой JSON (без markdown, без пояснений):\n")
            append("{\"type\":\"refine\",\"query\":\"...\"} — уточнение/развитие темы\n")
            append("{\"type\":\"negate\",\"query\":\"...\"} — пользователь отверг направление, скорректируй запрос\n")
            append("{\"type\":\"new\"} — совершенно новая тема")
        }
        return try {
            val response = client.post("$baseUrl/api/generate") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(GenerateRequest(
                    model  = model,
                    system = "Ты анализируешь связь между новым вопросом и историей вопросов. Отвечай ТОЛЬКО JSON.",
                    prompt = prompt,
                    stream = false,
                )))
            }.bodyAsText(Charsets.UTF_8)
            val raw = json.decodeFromString<GenerateResponse>(response).response.trim()
            parseSynthesisResult(raw)
        } catch (e: Exception) {
            println("[OllamaClient] synthesizeRagQuery failed: ${e.message}")
            null
        }
    }

    private fun parseSynthesisResult(text: String): SynthesisResult? {
        val jsonStr = Regex("""\{[^{}]*\}""").find(text)?.value ?: return null
        return try {
            val obj = Json.parseToJsonElement(jsonStr).jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "refine" -> SynthesisResult(SynthesisType.REFINE, obj["query"]?.jsonPrimitive?.content)
                "negate" -> SynthesisResult(SynthesisType.NEGATE, obj["query"]?.jsonPrimitive?.content)
                "new"    -> SynthesisResult(SynthesisType.NEW)
                else     -> null
            }
        } catch (e: Exception) {
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
