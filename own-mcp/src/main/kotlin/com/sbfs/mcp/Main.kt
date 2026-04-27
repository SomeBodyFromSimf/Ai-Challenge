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
import java.io.File

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
        name = "get_git_branch",
        description = "Returns the current git branch name for the given project directory.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("project_path") {
                    put("type", "string")
                    put("description", "Absolute path to the project root directory")
                }
            },
            required = listOf("project_path"),
        ),
    ) { request ->
        val projectPath = request.arguments?.get("project_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'project_path' is required."))
            )

        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Directory not found: $projectPath"))
            )
        }

        val branch = runGit(projectDir, "branch", "--show-current")
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Failed to read git branch. Is this a git repository?"))
            )

        CallToolResult(content = listOf(TextContent(branch.trim().ifEmpty { "(detached HEAD)" })))
    }

    server.addTool(
        name = "read_project_file",
        description = "Returns the contents of a file inside the given project directory.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("project_path") {
                    put("type", "string")
                    put("description", "Absolute path to the project root directory")
                }
                putJsonObject("file_path") {
                    put("type", "string")
                    put("description", "Relative path to the file inside the project (e.g. 'src/Main.kt' or 'README.md')")
                }
            },
            required = listOf("project_path", "file_path"),
        ),
    ) { request ->
        val projectPath = request.arguments?.get("project_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'project_path' is required."))
            )
        val filePath = request.arguments?.get("file_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'file_path' is required."))
            )

        val projectDir = File(projectPath).canonicalFile
        val targetFile = File(projectDir, filePath).canonicalFile

        // Защита от path traversal
        if (!targetFile.absolutePath.startsWith(projectDir.absolutePath)) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Access denied: file is outside the project directory."))
            )
        }

        if (!targetFile.exists()) {
            return@addTool CallToolResult(
                content = listOf(TextContent("File not found: $filePath"))
            )
        }
        if (!targetFile.isFile) {
            return@addTool CallToolResult(
                content = listOf(TextContent("'$filePath' is a directory, not a file."))
            )
        }

        val content = try {
            targetFile.readText()
        } catch (e: Exception) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Failed to read file: ${e.message}"))
            )
        }

        CallToolResult(content = listOf(TextContent(content)))
    }

    server.addTool(
        name = "get_git_diff",
        description = "Returns the git diff of the project for code review. " +
                "If 'base' is provided, shows changes since that ref (e.g. 'main', 'origin/main', 'HEAD~1'). " +
                "Otherwise shows all uncommitted changes (staged + unstaged) via 'git diff HEAD'.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("project_path") {
                    put("type", "string")
                    put("description", "Absolute path to the project root directory")
                }
                putJsonObject("base") {
                    put("type", "string")
                    put("description", "Base ref to diff against (e.g. 'main', 'origin/main', 'HEAD~1'). Optional.")
                }
            },
            required = listOf("project_path"),
        ),
    ) { request ->
        val projectPath = request.arguments?.get("project_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'project_path' is required."))
            )
        val base = request.arguments?.get("base")?.jsonPrimitive?.contentOrNull

        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Directory not found: $projectPath"))
            )
        }

        val diffArgs = if (base != null) arrayOf("diff", base) else arrayOf("diff", "HEAD")
        val diff = runGit(projectDir, *diffArgs)
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("Failed to get git diff. Is this a git repository?"))
            )

        val result = diff.trim().ifEmpty { "No changes found." }
        CallToolResult(content = listOf(TextContent(result)))
    }

    return server
}

private fun runGit(workDir: File, vararg args: String): String? = try {
    val process = ProcessBuilder("git", *args)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode == 0) output else null
} catch (_: Exception) { null }
