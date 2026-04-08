# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Desktop AI chat client with context management strategies, built with Kotlin Multiplatform and Compose Desktop. Communicates with LLMs via the OpenRouter API and supports MCP (Model Context Protocol) tool servers.

## Build & Run Commands

```bash
# Run the desktop app
./gradlew run

# Run with hot reload
./gradlew hotRunJvm

# Build distribution
./gradlew package

# Build MCP weather server
./gradlew :weather-mcp:build

# Run MCP weather server (default port 3000)
./gradlew :weather-mcp:run

# Full build
./gradlew build

# Clean
./gradlew clean
```

There are currently no tests in the project.

## Architecture

**Two modules:**
- `composeApp` — Desktop UI application (Kotlin Multiplatform, JVM target)
- `weather-mcp` — Standalone Ktor MCP server providing weather tools via NOAA API

**composeApp layers:**

| Layer | Location | Responsibility |
|---|---|---|
| UI | `jvmMain/.../ui/` | Compose panels: Chat, Settings, Context, Branch, Profile, SessionMemory, Params |
| ViewModel | `jvmMain/.../viewmodel/ChatViewModel.kt` | Central orchestrator; owns all state as StateFlow |
| Services | `jvmMain/.../OpenRouterClient.kt`, `McpManager.kt` | LLM API calls (with function/tool calling), MCP client lifecycle |
| Repositories | `jvmMain/.../repository/` | Data access via SQLDelight; one class per domain entity |
| DB Schema | `commonMain/sqldelight/.../database/` | `.sq` files define tables and type-safe queries |
| Data models | `commonMain/.../data/` | Shared Kotlin data classes |

**Data flow:** UI → ChatViewModel → Repository/Service → SQLite (SQLDelight) or OpenRouter API

## Key Domain Concepts

- **Sessions** — Conversations with model/temperature/strategy settings
- **Branches** — Alternative conversation paths within a session
- **Invariants** — Constraints injected into system prompt (allowed/excluded tech, architecture rules)
- **Task Context** — Tracks task state: `PLANNING → EXECUTING → VALIDATE → DONE`
- **Session Memory** — "Sticky facts" persisted across context window rotations
- **User Profile** — User preferences injected into prompts
- **Context Strategies** — `NO_STRATEGY`, `SUMMARY`, `SLIDING`, `STICKY_FACTS`, `BRANCHING`; control how the message history is trimmed when approaching token limits

## MCP Integration

MCP server configs are stored in `ai_config.json` at project root:
```json
{
  "mcpServers": [
    { "name": "weather", "url": "http://localhost:3000/mcp" }
  ]
}
```

`McpManager` connects to configured servers, discovers their tools, and makes them available as function-call tools in `OpenRouterClient`. The weather-mcp server must be running separately before tool calls will succeed.

## Tech Stack

- Kotlin 2.3.0, Compose Multiplatform 1.10.0
- Ktor Client & Server 3.0.1
- SQLDelight 2.3.2 (SQLite, type-safe queries)
- Kotlinx Serialization (JSON)
- Kotlin Coroutines with Swing dispatcher
- MCP Kotlin SDK 0.11.0
