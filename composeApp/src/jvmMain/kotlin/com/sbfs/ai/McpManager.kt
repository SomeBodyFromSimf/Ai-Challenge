package com.sbfs.ai

import com.sbfs.ai.data.McpServerConfig
import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Менеджер для взаимодействия с MCP сервером
 */
class McpManager {
    private val httpClient = HttpClient { install(SSE) }

    private val clients = ConcurrentHashMap<String, Client>()
    private val tools = ConcurrentHashMap<String, List<Tool>>()


    /**
     * Инициализирует MCP клиенты и подключается к серверам
     * @return имеющиеся тулзы
     */
    suspend fun startServers(serverConfigs: List<McpServerConfig>): Unit = coroutineScope {
        serverConfigs.map { serverConfig ->
            async {
                connectToServer(serverConfig)
            }
        }.awaitAll()
    }

    suspend fun addServer(serverConfig: McpServerConfig): Boolean {
        return connectToServer(serverConfig)
    }

    suspend fun removeServer(serverName: String) {
        tools.remove(serverName)
        val client = clients.remove(serverName) ?: return
        client.close()
    }

    suspend fun callMcpServer(toolName: String, args: Map<String, Any?>): String {
        val clientName = tools.firstNotNullOfOrNull { (clientName, tools) ->
            tools.find { it.name == toolName }?.let { clientName }
        } ?: return "Не нашли тулзу $toolName "
        return clients[clientName]?.callTool(
            name = toolName,
            arguments = args
        )?.content?.filterIsInstance<TextContent>()?.joinToString("\n") { it.text }.orEmpty()
    }

    fun getTools(): List<JsonObject> {
        val mcpTools = tools.values.flatten().map { tool ->
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", Json.encodeToJsonElement(tool.inputSchema))
                }
            }
        }
        return mcpTools + scheduleTool + cancelJobTool
    }

    companion object {
        val cancelJobTool: JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "cancel_job")
                put("description", "Cancel a previously scheduled job (deferred or periodic) by its ID.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("job_id") {
                            put("type", "string")
                            put("description", "The job ID that was returned when the job was scheduled")
                        }
                    }
                    putJsonArray("required") { add("job_id") }
                }
            }
        }

        val scheduleTool: JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "schedule")
                put(
                    "description",
                    "Schedule an MCP tool for deferred or periodic execution. " +
                    "Use 'deferred' when the user asks to run something after a delay (e.g. 'call a taxi in 20 minutes'). " +
                    "Use 'periodic' when the user asks to run something repeatedly (e.g. 'check connection every 30 seconds')."
                )
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("tool_name") {
                            put("type", "string")
                            put("description", "Name of the MCP tool to execute")
                        }
                        putJsonObject("tool_arguments") {
                            put("type", "object")
                            put("description", "Arguments to pass to the tool (can be empty)")
                        }
                        putJsonObject("execution_type") {
                            put("type", "string")
                            putJsonArray("enum") { add("deferred"); add("periodic") }
                            put("description", "deferred: run once after delay_seconds. periodic: run every interval_seconds.")
                        }
                        putJsonObject("delay_seconds") {
                            put("type", "integer")
                            put("description", "Seconds to wait before execution. Required for deferred type.")
                        }
                        putJsonObject("interval_seconds") {
                            put("type", "integer")
                            put("description", "Seconds between executions. Required for periodic type.")
                        }
                    }
                    putJsonArray("required") { add("tool_name"); add("execution_type") }
                }
            }
        }
    }

    private suspend fun connectToServer(serverConfig: McpServerConfig): Boolean {
        return try {
            val client = Client(
                clientInfo = Implementation(
                    name = "ai-challenge-app",
                    version = "1.0.0"
                )
            )

            val transport = StreamableHttpClientTransport(
                client = httpClient,
                url = serverConfig.url
            )

            // Connect to server
            client.connect(transport)
            clients[serverConfig.name] = client
            tools[serverConfig.name] = client.listTools().tools
            true
        } catch (_: Exception) {
            println("[${serverConfig.name}] Server ${serverConfig.url} not connected")
            false
        }
    }
}
