package com.sbfs.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// --- Configuration ---
const val OPENROUTER_API_KEY = ""
const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
const val MODEL = "anthropic/claude-sonnet-4-5"

data class OpenRouterRequest(
    // Model (use "provider/model-name" format)
    val model: String = MODEL,

    // ── Sampling Parameters ──
    val temperature: Double = 1.0,       // 0.0–2.0  (default: 1.0)
    val topP: Double = 1.0,              // 0.0–1.0  (default: 1.0)
    val topK: Int = 0,                   // 0+       (default: 0 = disabled)
    val minP: Double = 0.0,              // 0.0–1.0  (default: 0.0)
    val topA: Double = 0.0,              // 0.0–1.0  (default: 0.0)

    // ── Penalty Parameters ──
    val frequencyPenalty: Double = 0.0,  // -2.0–2.0 (default: 0.0)
    val presencePenalty: Double = 0.0,   // -2.0–2.0 (default: 0.0)
    val repetitionPenalty: Double = 1.0, // 0.0–2.0  (default: 1.0)

    // ── Output Control ──
    val maxTokens: Int? = null,          // 1+ (optional)
    val seed: Int? = null,               // for deterministic output
    val stop: List<String>? = null,      // stop sequences
    val stream: Boolean = false,

    // ── Response Format ──
    val responseFormat: String? = null,  // "json_object" or null

    val messagesQueue: List<MessageData>
)

@Serializable
data class MessageData(
    val role: MessageRole,
    val content: String,
)
enum class MessageRole {
    @SerialName("user")
    USER,
    @SerialName("assistant")
    ASSISTANT
}

fun buildRequestBody(req: OpenRouterRequest): String {
    val sb = StringBuilder()
    sb.append("""{"model":"${req.model}"""")
    sb.append(""","temperature":${req.temperature}""")
    sb.append(""","top_p":${req.topP}""")
    sb.append(""","top_k":${req.topK}""")
    sb.append(""","min_p":${req.minP}""")
    sb.append(""","top_a":${req.topA}""")
    sb.append(""","frequency_penalty":${req.frequencyPenalty}""")
    sb.append(""","presence_penalty":${req.presencePenalty}""")
    sb.append(""","repetition_penalty":${req.repetitionPenalty}""")
    sb.append(""","stream":${req.stream}""")

    req.maxTokens?.let   { sb.append(""","max_tokens":$it""") }
    req.seed?.let        { sb.append(""","seed":$it""") }
    req.stop?.let        { sb.append(""","stop":[${it.joinToString(",") { s -> "\"$s\""}}]""") }
    req.responseFormat?.let { sb.append(""","response_format":{"type":"$it"}""") }
    req.messagesQueue.let { messagesQueue ->
        sb.append(""","messages":[""")
        messagesQueue.forEachIndexed { index, message ->
            sb.append(appJson.encodeToString(message))
            if (index != messagesQueue.lastIndex) {
                sb.append(",")
            }
        }
        sb.append("]")
    }
    sb.append("}")

    return sb.toString()
}

@Serializable
class Response(
    val choices: List<ResponseChoice>,
)

@Serializable
class ResponseChoice(
    val message: ResponseChoiceMessage,
)

@Serializable
class ResponseChoiceMessage(
    val content: String,
)


private val appJson = Json {
    ignoreUnknownKeys = true
}

fun parseResponse(json: String): String {
    return appJson.decodeFromString<Response>(json).choices.firstOrNull()?.message?.content ?: "Empty response"
}

fun sendRequest(req: OpenRouterRequest): String {
    val client = HttpClient.newHttpClient()
    val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(OPENROUTER_URL))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $OPENROUTER_API_KEY")
        .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(req)))
        .build()

    val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200)
        throw RuntimeException("Error ${response.statusCode()}: ${response.body()}")
    return parseResponse(response.body())
}

fun printUsage() {
    println("""
        Usage: java -jar AiChallenge.jar

        Options:
          --model              <string>     Model to use (default: anthropic/claude-sonnet-4-5)
          --temperature        <0.0-2.0>    Randomness (default: 1.0)
          --top-p              <0.0-1.0>    Nucleus sampling (default: 1.0)
          --top-k              <int>        Top-K sampling (default: 0 = off)
          --min-p              <0.0-1.0>    Min probability threshold (default: 0.0)
          --top-a              <0.0-1.0>    Dynamic top-A sampling (default: 0.0)
          --frequency-penalty  <-2.0-2.0>  Penalize frequent tokens (default: 0.0)
          --presence-penalty   <-2.0-2.0>  Penalize repeated tokens (default: 0.0)
          --repetition-penalty <0.0-2.0>   Reduce repetition (default: 1.0)
          --max-tokens         <int>        Max tokens to generate
          --seed               <int>        Seed for deterministic output
          --stop               <string>     Stop sequence (repeatable)
          --json                            Enable JSON response format
          --help                            Show this help

        Models (provider/model format):
          anthropic/claude-sonnet-4-5
          openai/gpt-4o
          google/gemini-pro
          meta-llama/llama-3-70b-instruct
          mistralai/mixtral-8x7b-instruct
    """.trimIndent())
}

//java -jar AiChallenge.jar --max-tokens 128 "What green objects do you know? Describe it with the xml object"
//java -jar AiChallenge.jar --max-tokens 128 --stop /object "What green objects do you know? Describe it with the xml object"

fun main(args: Array<String>) {
    if (args.contains("--help")) { printUsage(); return }

    var model              = "anthropic/claude-sonnet-4-5"
    var temperature        = 1.0
    var topP               = 1.0
    var topK               = 0
    var minP               = 0.0
    var topA               = 0.0
    var frequencyPenalty   = 0.0
    var presencePenalty    = 0.0
    var repetitionPenalty  = 1.0
    var maxTokens: Int?    = null
    var seed: Int?         = null
    var jsonMode           = false
    val stopSequences      = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--model"              -> { model             = args[++i] }
            "--temperature"        -> { temperature       = args[++i].toDouble() }
            "--top-p"              -> { topP              = args[++i].toDouble() }
            "--top-k"              -> { topK              = args[++i].toInt() }
            "--min-p"              -> { minP              = args[++i].toDouble() }
            "--top-a"              -> { topA              = args[++i].toDouble() }
            "--frequency-penalty"  -> { frequencyPenalty  = args[++i].toDouble() }
            "--presence-penalty"   -> { presencePenalty   = args[++i].toDouble() }
            "--repetition-penalty" -> { repetitionPenalty = args[++i].toDouble() }
            "--max-tokens"         -> { maxTokens         = args[++i].toInt() }
            "--seed"               -> { seed              = args[++i].toInt() }
            "--stop"               -> { stopSequences.add(args[++i]) }
            "--json"               -> { jsonMode          = true }
            else                   -> { }
        }
        i++
    }

    var request = OpenRouterRequest(
        model             = model,
        temperature       = temperature,
        topP              = topP,
        topK              = topK,
        minP              = minP,
        topA              = topA,
        frequencyPenalty  = frequencyPenalty,
        presencePenalty   = presencePenalty,
        repetitionPenalty = repetitionPenalty,
        maxTokens         = maxTokens,
        seed              = seed,
        stop              = stopSequences.ifEmpty { null },
        responseFormat    = if (jsonMode) "json_object" else null,
        messagesQueue = listOf(),
    )

    println("🚀 Start connection with OpenRouter...")
    println("   Model              : $model")
    println("   Temperature        : $temperature")
    println("   Top-P              : $topP")
    println("   Top-K              : $topK")
    println("   Min-P              : $minP")
    println("   Top-A              : $topA")
    println("   Frequency Penalty  : $frequencyPenalty")
    println("   Presence Penalty   : $presencePenalty")
    println("   Repetition Penalty : $repetitionPenalty")
    maxTokens?.let { println("   Max Tokens         : $it") }
    seed?.let      { println("   Seed               : $it") }
    if (jsonMode)  println("   Response Format    : json_object")
    println("-".repeat(50))



    try {
        while (true) {
            println("\nWrite your request:")
            val prompt = readln()
            request = request.copy(
                messagesQueue = request.messagesQueue + prompt.messageDataByRole(MessageRole.USER)
            )
            val response = sendRequest(request)
            println("\nResponse:\n")
            println(response)
            request = request.copy(
                messagesQueue = request.messagesQueue + response.messageDataByRole(MessageRole.ASSISTANT)
            )
        }
    } catch (e: Exception) {
        println("❌ Error: ${e.message}")
    }
}

private fun String.messageDataByRole(role: MessageRole): MessageData {
    return MessageData(
        role = role,
        content = this.replace("\"", "\\\"").replace("\n", "\\n")
    )
}