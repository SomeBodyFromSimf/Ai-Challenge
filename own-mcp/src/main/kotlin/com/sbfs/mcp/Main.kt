package com.sbfs.mcp

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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
        name = "write_project_file",
        description = "Writes content to a file inside the given project directory. Creates the file if it doesn't exist, or overwrites it if it does.",
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
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Content to write to the file")
                }
            },
            required = listOf("project_path", "file_path", "content"),
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
        val content = request.arguments?.get("content")?.jsonPrimitive?.content ?: ""

        val projectDir = File(projectPath).canonicalFile
        val targetFile = File(projectDir, filePath).canonicalFile

        // Защита от path traversal
        if (!targetFile.absolutePath.startsWith(projectDir.absolutePath)) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Access denied: file is outside the project directory."))
            )
        }

        try {
            // Создаем родительские директории если они не существуют
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(content)
            CallToolResult(content = listOf(TextContent("Successfully wrote to file: $filePath")))
        } catch (e: Exception) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Failed to write file: ${e.message}"))
            )
        }
    }

    server.addTool(
        name = "list_project_files",
        description = "Lists files in the given project directory. Optionally filters by extension.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("project_path") {
                    put("type", "string")
                    put("description", "Absolute path to the project root directory")
                }
                putJsonObject("file_extension") {
                    put("type", "string")
                    put("description", "Optional file extension to filter by (e.g. '.kt', '.md')")
                }
            },
            required = listOf("project_path"),
        ),
    ) { request ->
        val projectPath = request.arguments?.get("project_path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'project_path' is required."))
            )
        val fileExtension = request.arguments?.get("file_extension")?.jsonPrimitive?.contentOrNull

        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Directory not found: $projectPath"))
            )
        }

        try {
            val files = projectDir.walkTopDown()
                .filter { it.isFile }
                .let { sequence ->
                    if (fileExtension != null) {
                        sequence.filter { it.extension.equals(fileExtension.trimStart('.'), ignoreCase = true) }
                    } else {
                        sequence
                    }
                }
                .map { it.relativeTo(projectDir).toString() }
                .sorted()
                .toList()

            val result = files.joinToString("\n")
            CallToolResult(content = listOf(TextContent(result)))
        } catch (e: Exception) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Failed to list files: ${e.message}"))
            )
        }
    }

    server.addTool(
        name = "create_project_file",
        description = "Creates a new empty file inside the given project directory.",
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

        try {
            // Создаем родительские директории если они не существуют
            targetFile.parentFile?.mkdirs()
            if (targetFile.createNewFile()) {
                CallToolResult(content = listOf(TextContent("Successfully created file: $filePath")))
            } else {
                CallToolResult(content = listOf(TextContent("File already exists: $filePath")))
            }
        } catch (e: Exception) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Failed to create file: ${e.message}"))
            )
        }
    }

    server.addTool(
        name = "delete_project_file",
        description = "Deletes a file inside the given project directory.",
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

        try {
            if (targetFile.delete()) {
                CallToolResult(content = listOf(TextContent("Successfully deleted file: $filePath")))
            } else {
                CallToolResult(content = listOf(TextContent("Failed to delete file: $filePath")))
            }
        } catch (e: Exception) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Failed to delete file: ${e.message}"))
            )
        }
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
    
    server.addTool(
        name = "create_github_issue",
        description = "Creates a new issue in a GitHub repository.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("project") {
                    put("type", "string")
                    put("description", "GitHub repository in the format 'owner/repo'")
                }
                putJsonObject("token") {
                    put("type", "string")
                    put("description", "GitHub token")
                }
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "Issue title")
                }
                putJsonObject("body") {
                    put("type", "string")
                    put("description", "Issue body (description)")
                }
            },
            required = listOf("project", "title", "body"),
        ),
    ) { request ->
        val project = request.arguments?.get("project")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'project' is required."))
            )
        val token = request.arguments?.get("token")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'token' is required."))
            )
        val title = request.arguments?.get("title")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'title' is required."))
            )
        val body = request.arguments?.get("body")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("'body' is required."))
            )

        // Проверяем формат project
        val parts = project.split("/")
        if (parts.size != 2) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Invalid project format. Expected 'owner/repo'."))
            )
        }
        val owner = parts[0]
        val repo = parts[1]

        try {
            val httpClient = HttpClient(CIO) {
                install(ContentNegotiation) {
                    json()
                }
            }
            
            val response: HttpResponse = httpClient.post("https://api.github.com/repos/$owner/$repo/issues") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(
                    buildJsonObject {
                        put("title", title)
                        put("body", body)
                    }
                )
            }
            
            httpClient.close()
            
            if (response.status == HttpStatusCode.Created) {
                val responseBody = response.bodyAsText()
                val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
                val issueNumber = jsonResponse["number"]?.jsonPrimitive?.content
                
                if (issueNumber != null) {
                    CallToolResult(content = listOf(TextContent(issueNumber)))
                } else {
                    CallToolResult(content = listOf(TextContent("Failed to parse issue number from response.")))
                }
            } else {
                val errorMessage = response.bodyAsText()
                CallToolResult(content = listOf(TextContent("Failed to create issue. Status: ${response.status}, Error: $errorMessage")))
            }
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Failed to create issue: ${e.message}")))
        }
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