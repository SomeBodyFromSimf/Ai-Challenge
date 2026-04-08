package com.sbfs.mcp


import com.sbfs.mcp.client.WeatherClient
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
import kotlin.random.Random

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3000
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
    val info = Implementation(
        "weather",
        "1.0.0"
    )
    val options = ServerOptions(
        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(true))
    )
    val server = Server(info, options)
    val weatherClient = WeatherClient()
    server.addTool(
        name = "get_alerts",
        description = "Get weather alerts for a US state. Input is a two-letter US state code (e.g. CA, NY)",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("state") {
                    put("type", "string")
                    put("description", "Two-letter US state code (e.g. CA, NY)")
                }
            },
            required = listOf("state"),
        ),
    ) { request ->
        val state = request.arguments?.get("state")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("The 'state' parameter is required.")),
            )

        val alerts = weatherClient.getAlerts(state)
        CallToolResult(content = alerts.map { TextContent(it) })
    }

    server.addTool(
        name = "get_forecast",
        description = "Get weather forecast for a location. Note: only US locations are supported by the NWS API.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("latitude") {
                    put("type", "number")
                    put("description", "Latitude of the location")
                }
                putJsonObject("longitude") {
                    put("type", "number")
                    put("description", "Longitude of the location")
                }
            },
            required = listOf("latitude", "longitude"),
        ),
    ) { request ->
        val latitude = request.arguments?.get("latitude")?.jsonPrimitive?.doubleOrNull
        val longitude = request.arguments?.get("longitude")?.jsonPrimitive?.doubleOrNull
        if (latitude == null || longitude == null) {
            return@addTool CallToolResult(
                content = listOf(TextContent("The 'latitude' and 'longitude' parameters are required.")),
            )
        }

        val forecast = weatherClient.getForecast(latitude, longitude)
        CallToolResult(content = forecast.map { TextContent(it) })
    }

    server.addTool(
        name = "call_taxi",
        description = "Order a taxi to the specified address. Returns estimated arrival time.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("address") {
                    put("type", "string")
                    put("description", "Pickup address")
                }
            },
            required = listOf("address"),
        ),
    ) { _ ->
        val minutes = Random.nextInt(5, 16)
        CallToolResult(content = listOf(TextContent("The car will arrive in $minutes minutes.")))
    }

    server.addTool(
        name = "get_connection_quality",
        description = "Returns server current internet connection quality metrics.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList(),
        ),
    ) { _ ->
        val speed = Random.nextInt(60, 201)
        val latency = Random.nextInt(5, 50)
        val packetLoss = if (Random.nextInt(10) == 0) Random.nextInt(1, 5) else 0
        CallToolResult(
            content = listOf(
                TextContent("Internet quality: $speed Mbps, ${latency} ms delay, ${packetLoss}% packet loss")
            )
        )
    }

    return server
}