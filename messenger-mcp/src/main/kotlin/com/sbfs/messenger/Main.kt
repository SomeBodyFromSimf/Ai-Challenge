package com.sbfs.messenger

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import org.slf4j.event.Level

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 4000
    val server: Server = createServer()
    embeddedServer(Netty, host = "127.0.0.1", port = port) {
        install(CallLogging) {
            level = Level.INFO
        }
        mcpStreamableHttp {
            server
        }
    }.start(wait = true)
}

fun createServer(): Server {
    val info = Implementation("messenger-mcp", "1.0.0")
    val options = ServerOptions(
        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(true))
    )
    val server = Server(info, options)

    server.addTool(
        name = "send-message",
        description = "Send a message to a user by name and email.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Name of the user to send the message to")
                }
                putJsonObject("email") {
                    put("type", "string")
                    put("description", "Email address of the user")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "The message text to send")
                }
            },
            required = listOf("name", "email", "message"),
        ),
    ) { request ->
        val name = request.arguments?.get("name")?.jsonPrimitive?.content ?: "<unknown>"
        val email = request.arguments?.get("email")?.jsonPrimitive?.content ?: "<unknown>"
        val message = request.arguments?.get("message")?.jsonPrimitive?.content ?: "<empty>"

        println("--- send-message ---")
        println("Name:    $name")
        println("Email:   $email")
        println("Message: $message")
        println("--------------------")

        CallToolResult(content = listOf(TextContent("Message sent to $name <$email>.")))
    }

    return server
}
