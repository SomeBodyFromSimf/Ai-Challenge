package com.sbfs.ai

import com.sbfs.ai.data.LocalLlmConfig
import com.sbfs.ai.data.Message
import com.sbfs.ai.data.MessageRole
import com.sbfs.ai.data.Model
import com.sbfs.ai.data.SessionSettings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.collections.plus

class OpenRouterClient(
    private val mcpManager: McpManager
) {
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

    suspend fun fetchLocalModels(config: LocalLlmConfig): List<Model> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${config.url.trimEnd('/')}/v1/models"
                val response: HttpResponse = client.get(url) {
                    contentType(ContentType.Application.Json)
                }
                if (response.status == HttpStatusCode.OK) {
                    val body = response.body<LocalModelsResponse>()
                    body.data.map { dto ->
                        Model(
                            id = dto.id,
                            name = "Local(${config.name}-${dto.id}",
                            contextLength = dto.contextLength ?: 0,
                            isLocal = true,
                            baseUrl = config.url,
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun completionUrl(model: Model?): String =
        if (model?.isLocal == true && model.baseUrl != null)
            "${model.baseUrl.trimEnd('/')}/v1/chat/completions"
        else
            OPENROUTER_COMPLETIONS_URL

    private fun HttpRequestBuilder.applyAuth(model: Model?) {
        if (model?.isLocal != true) {
            header("Authorization", "Bearer $OPENROUTER_API_KEY")
        }
    }

    suspend fun generateResponse(prompt: String, model: Model): String {
        val settings = SessionSettings(model = model)
        return sendSystemMessage(prompt, settings).content
    }
    
    suspend fun sendMessage(
        messages: List<Message>,
        settings: SessionSettings,
        tools: List<JsonObject>,
        virtualToolHandler: (suspend (name: String, args: Map<String, JsonElement>) -> String?)? = null
    ): SendMessageData {
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
                    },
                    tools = tools,
                )

                val model = settings.model
                val response: HttpResponse = client.post(completionUrl(model)) {
                    contentType(ContentType.Application.Json)
                    applyAuth(model)
                    setBody(request)
                }

                if (response.status == HttpStatusCode.OK) {
                    val responseBody = response.body<OpenRouterResponse>()
                    val contentBuilder = StringBuilder()
                    contentBuilder.manageWithToolMessages(request, responseBody, model, virtualToolHandler)
                    SendMessageData(
                        content = contentBuilder.toString(),
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

    suspend fun summarizeMessages(settings: SessionSettings, messages: List<Message>): SendMessageData {
        return withContext(Dispatchers.IO) {
            try {
                val request = OpenRouterRequest(
                    model = settings.model?.id ?: throw Exception("Не выбрана модель"),
                    temperature = 0.5,
                    maxTokens = 500,
                    messages = messages.map { message ->
                        MessageData(
                            role = message.role,
                            content = message.content
                        )
                    },
                    topP = 1.0,
                    topK = 0,
                    minP = 0.0,
                    topA = 0.0,
                    frequencyPenalty = 0.0,
                    presencePenalty = 0.0,
                    repetitionPenalty = 1.0,
                    seed = null,
                    stop = null,
                    responseFormat = null,
                )

                val model = settings.model
                val response: HttpResponse = client.post(completionUrl(model)) {
                    contentType(ContentType.Application.Json)
                    applyAuth(model)
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
                throw Exception("Failed to summarize messages: ${e.message}", e)
            }
        }
    }
    
    suspend fun sendSystemMessage(content: String, settings: SessionSettings): SendMessageData {
        return withContext(Dispatchers.IO) {
            try {
                val systemMessage = MessageData(
                    role = MessageRole.USER,
                    content = content
                )
                
                val request = OpenRouterRequest(
                    model = settings.model?.id ?: throw Exception("Не выбрана модель"),
                    temperature = 0.7,
                    maxTokens = 500,
                    messages = listOf(systemMessage),
                    topP = 1.0,
                    topK = 0,
                    minP = 0.0,
                    topA = 0.0,
                    frequencyPenalty = 0.0,
                    presencePenalty = 0.0,
                    repetitionPenalty = 1.0,
                    seed = null,
                    stop = null,
                    responseFormat = null,
                )

                val model = settings.model
                val response: HttpResponse = client.post(completionUrl(model)) {
                    contentType(ContentType.Application.Json)
                    applyAuth(model)
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
                throw Exception("Failed to send system message: ${e.message}", e)
            }
        }
    }


    private suspend fun StringBuilder.manageWithToolMessages(
        request: OpenRouterRequest,
        responseBody: OpenRouterResponse,
        model: Model?,
        virtualToolHandler: (suspend (name: String, args: Map<String, JsonElement>) -> String?)? = null
    ) {
        val choice = responseBody.choices.firstOrNull()
        val toolCalls = choice?.message?.toolCalls

        if (choice?.reason == "tool_calls" && toolCalls?.isNotEmpty() == true) {
            val toolMessages = toolCalls.map { toolCall ->
                val parsedArgs = Json.decodeFromString<Map<String, JsonElement>>(toolCall.function.arguments)
                val toolText = virtualToolHandler?.invoke(toolCall.function.name, parsedArgs)
                    ?: mcpManager.callMcpServer(toolCall.function.name, parsedArgs)
                MessageData(
                    role = MessageRole.TOOL,
                    content = toolText,
                    toolCallId = toolCall.id
                )
            }
            val newRequest = request.copy(
                messages = request.messages + MessageData(
                    role = MessageRole.ASSISTANT,
                    content = choice.message.content,
                    toolCalls = choice.message.toolCalls
                ) + toolMessages
            )
            val toolResponse: HttpResponse = client.post(completionUrl(model)) {
                contentType(ContentType.Application.Json)
                applyAuth(model)
                setBody(newRequest)
            }
            if (toolResponse.status == HttpStatusCode.OK) {
                manageWithToolMessages(newRequest, toolResponse.body(), model, virtualToolHandler)
            } else {
                throw Exception("Error ${toolResponse.status}: ${toolResponse.bodyAsText()}")
            }
        } else {
            // Final response — only this goes into the chat message
            append(choice?.message?.content ?: "")
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
    val tools: List<JsonObject> = emptyList(),
    val stream: Boolean = false
)

@Serializable
data class MessageData(
    val role: MessageRole,
    val content: String?,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ResponseToolCall>? = null,
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
    val cost: Double = 0.0,
)

@Serializable
data class ResponseChoice(
    val message: ResponseChoiceMessage,
    @SerialName("finish_reason")
    val reason: String? = null,
)

@Serializable
data class ResponseToolCall(
    val id: String,
    val function: ResponseToolCallFun
)

@Serializable
data class ResponseToolCallFun(
    val name: String,
    val arguments: String,
)

@Serializable
data class ResponseChoiceMessage(
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ResponseToolCall>? = null,
)


data class SendMessageData(
    val content: String,
    val inputUsedToken: Long,
    val outputUsedToken: Long,
    val totalUsedToken: Long,
    val cost: Double
)

@Serializable
data class LocalModelsResponse(
    val data: List<LocalModelDto> = emptyList(),
)

@Serializable
data class LocalModelDto(
    val id: String,
    @SerialName("context_length")
    val contextLength: Long? = null,
)