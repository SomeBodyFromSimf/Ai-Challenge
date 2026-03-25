package com.sbfs.ai

import com.sbfs.ai.data.Message
import com.sbfs.ai.data.MessageRole
import com.sbfs.ai.data.Model
import com.sbfs.ai.data.SessionSettings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.collections.ifEmpty

class OpenRouterClient {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 600000
            connectTimeoutMillis = 600000
            socketTimeoutMillis = 600000
        }
    }
    
    companion object {
        private const val OPENROUTER_API_KEY = ""
        private const val OPENROUTER_COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"
    }
    
    suspend fun sendMessage(messages: List<Message>, settings: SessionSettings): SendMessageData {
        return withContext(Dispatchers.IO) {
            try {
                val request = OpenRouterRequest(
                    model = settings.model?.id ?: throw Exception("Не выбрана модель"),
                    temperature = settings.temperature,
                    topP = settings.topP,
                    topK = settings.topK,
                    minP = settings.minP,
                    topA = settings.topA,
                    frequencyPenalty = settings.frequencyPenalty,
                    presencePenalty = settings.presencePenalty,
                    repetitionPenalty = settings.repetitionPenalty,
                    maxTokens = settings.maxTokens,
                    seed = settings.seed,
                    stop = settings.stop.ifEmpty { null },
                    responseFormat = settings.responseFormat,
                    messages = messages.map { message ->
                        MessageData(
                            role = message.role,
                            content = message.content
                        )
                    }
                )
                
                val response: HttpResponse = client.post(OPENROUTER_COMPLETIONS_URL) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $OPENROUTER_API_KEY")
                    setBody(request)
                }
                
                if (response.status == HttpStatusCode.OK) {
                    val responseBody = response.body<OpenRouterResponse>()

                    SendMessageData(
                        content = responseBody.choices.firstOrNull()?.message?.content ?: "Empty response",
                        inputUsedToken = responseBody.usage.promptTokens,
                        outputUsedToken = responseBody.usage.completionTokens,
                        totalUsedToken = responseBody.usage.totalTokens,
                        cost = responseBody.usage.cost,
                    )
                } else {
                    throw Exception("Error ${response.status}: ${response.bodyAsText()}")
                }
            } catch (e: Exception) {
                throw Exception("Failed to send message: ${e.message}", e)
            }
        }
    }

    suspend fun getAvailableModels(): List<Model> {
        return withContext(Dispatchers.IO) {
            try {
                val response: HttpResponse = client.get(OPENROUTER_MODELS_URL) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $OPENROUTER_API_KEY")
                }

                if (response.status == HttpStatusCode.OK) {
                    val responseBody = response.body<ModelsResponse>()
                    responseBody.data.sortedBy { it.name }
                } else {
                    throw Exception("Error ${response.status}: ${response.bodyAsText()}")
                }
            } catch (e: Exception) {
                throw Exception("Failed to send message: ${e.message}", e)
            }
        }
    }
}

@Serializable
data class OpenRouterRequest(
    val model: String,
    val temperature: Double,
    @SerialName("top_p")
    val topP: Double,
    @SerialName("top_k")
    val topK: Int,
    @SerialName("min_p")
    val minP: Double,
    @SerialName("top_a")
    val topA: Double,
    val frequencyPenalty: Double,
    val presencePenalty: Double,
    val repetitionPenalty: Double,
    @SerialName("max_completion_tokens")
    val maxTokens: Int?,
    val seed: Int?,
    val stop: List<String>?,
    val responseFormat: String?,
    val messages: List<MessageData>,
    val stream: Boolean = false
)

@Serializable
data class MessageData(
    val role: MessageRole,
    val content: String
)

@Serializable
data class OpenRouterResponse(
    val choices: List<ResponseChoice>,
    val usage: ResponseUsage
)

@Serializable
data class ModelsResponse(
    val data: List<Model>,
)

@Serializable
data class ResponseUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Long,
    @SerialName("completion_tokens")
    val completionTokens: Long,
    @SerialName("total_tokens")
    val totalTokens: Long,
    val cost: Double
)

@Serializable
data class ResponseChoice(
    val message: ResponseChoiceMessage
)

@Serializable
data class ResponseChoiceMessage(
    val content: String
)


data class SendMessageData(
    val content: String,
    val inputUsedToken: Long,
    val outputUsedToken: Long,
    val totalUsedToken: Long,
    val cost: Double
)