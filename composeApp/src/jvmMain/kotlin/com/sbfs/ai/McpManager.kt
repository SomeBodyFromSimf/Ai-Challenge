package com.sbfs.ai

import com.sbfs.ai.data.McpServerConfig
import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import io.ktor.server.application.serverConfig
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

    suspend fun getTools(): List<JsonObject> {
        return tools.values.flatten().map { tool ->
            // List available tools
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", Json.encodeToJsonElement(tool.inputSchema))

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
