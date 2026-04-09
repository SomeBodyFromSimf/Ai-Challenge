package com.sbfs.mcp

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
    val info = Implementation("own-mcp", "1.0.0")
    val options = ServerOptions(
        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(true))
    )
    val server = Server(info, options)

    server.addTool(
        name = "get_github_diff",
        description = "Fetch the code diff for a GitHub pull request / merge request by its number. Returns a fake diff for testing purposes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("mr_number") {
                    put("type", "integer")
                    put("description", "The pull request / merge request number")
                }
            },
            required = listOf("mr_number"),
        ),
    ) { request ->
        val mrNumber = request.arguments?.get("mr_number")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'mr_number' is required and must be an integer."))
            )

        val fakeDiff = """
            PR #$mrNumber — Add user authentication
            Author: john.doe | Base: main ← feature/auth

            diff --git a/src/auth/AuthService.kt b/src/auth/AuthService.kt
            new file mode 100644
            --- /dev/null
            +++ b/src/auth/AuthService.kt
            @@ -0,0 +1,20 @@
            +class AuthService(private val db: Database) {
            +
            +    fun login(username: String, password: String): String? {
            +        val user = db.findUser(username) ?: return null
            +        if (user.password != password) return null
            +        return generateToken(user.id)
            +    }
            +
            +    private fun generateToken(userId: String): String {
            +        return Base64.encode(userId + System.currentTimeMillis())
            +    }
            +}

            diff --git a/src/api/UserController.kt b/src/api/UserController.kt
            --- a/src/api/UserController.kt
            +++ b/src/api/UserController.kt
            @@ -10,6 +10,12 @@
             class UserController {
            +    private val authService = AuthService(Database.instance)
            +
            +    fun login(ctx: Context) {
            +        val token = authService.login(ctx.body("username"), ctx.body("password"))
            +        if (token == null) ctx.status(401) else ctx.json(mapOf("token" to token))
            +    }
            +
                 fun getUser(ctx: Context) {
        """.trimIndent()

        CallToolResult(content = listOf(TextContent(fakeDiff)))
    }

    server.addTool(
        name = "write_mr_review",
        description = "Post a review comment on a GitHub pull request / merge request.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("mr_number") {
                    put("type", "integer")
                    put("description", "The pull request / merge request number")
                }
                putJsonObject("review") {
                    put("type", "string")
                    put("description", "The review text to post as a comment on the MR")
                }
            },
            required = listOf("mr_number", "review"),
        ),
    ) { request ->
        val mrNumber = request.arguments?.get("mr_number")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'mr_number' is required and must be an integer."))
            )
        val review = request.arguments?.get("review")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'review' is required."))
            )

        println("--- Review posted on PR #$mrNumber ---\n$review\n------")
        CallToolResult(
            content = listOf(TextContent("Review successfully posted on PR #$mrNumber."))
        )
    }

    return server
}
