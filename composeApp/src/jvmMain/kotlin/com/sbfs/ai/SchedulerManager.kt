package com.sbfs.ai

import com.sbfs.ai.data.SessionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages deferred and periodic MCP tool executions.
 * The agent (ChatViewModel) owns this — MCP tools stay stateless.
 *
 * Supports two execution types:
 * - deferred: run once after delay_seconds
 * - periodic: run every interval_seconds until cancelled
 *
 * Session ID and settings are captured at scheduling time, not at execution time.
 */
class SchedulerManager(
    private val mcpManager: McpManager,
    private val scope: CoroutineScope,
    private val onResult: suspend (sessionId: String, toolName: String, result: String, settings: SessionSettings) -> Unit
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Parses a "schedule" tool call from the LLM and starts the appropriate job.
     * sessionId and settings are captured here so the job always targets the right session.
     */
    suspend fun handleScheduleCall(args: Map<String, JsonElement>, sessionId: String, settings: SessionSettings): String {
        val toolName = args["tool_name"]?.jsonPrimitive?.content
            ?: return "Error: tool_name is required"
        val toolArgs: Map<String, JsonElement> = args["tool_arguments"]?.jsonObject?.toMap() ?: emptyMap()
        val executionType = args["execution_type"]?.jsonPrimitive?.content
            ?: return "Error: execution_type is required"

        return when (executionType) {
            "deferred" -> {
                val delaySeconds = args["delay_seconds"]?.jsonPrimitive?.long ?: 60L
                val jobId = scheduleDeferred(sessionId, settings, toolName, toolArgs, delaySeconds)
                "Scheduled '$toolName' to run in $delaySeconds seconds. Job ID: $jobId"
            }
            "periodic" -> {
                val intervalSeconds = args["interval_seconds"]?.jsonPrimitive?.long ?: 60L
                val jobId = schedulePeriodic(sessionId, settings, toolName, toolArgs, intervalSeconds)
                "Started periodic execution of '$toolName' every $intervalSeconds seconds. Job ID: $jobId"
            }
            else -> "Unknown execution_type: '$executionType'. Use 'deferred' or 'periodic'."
        }
    }

    fun cancelJob(jobId: String): Boolean {
        return activeJobs.remove(jobId)?.also { it.cancel() } != null
    }

    fun cancelAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    private fun scheduleDeferred(
        sessionId: String,
        settings: SessionSettings,
        toolName: String,
        toolArgs: Map<String, JsonElement>,
        delaySeconds: Long
    ): String {
        val jobId = UUID.randomUUID().toString()
        activeJobs[jobId] = scope.launch {
            delay(delaySeconds * 1000L)
            val result = mcpManager.callMcpServer(toolName, toolArgs)
            onResult(sessionId, toolName, result, settings)
            activeJobs.remove(jobId)
        }
        return jobId
    }

    private fun schedulePeriodic(
        sessionId: String,
        settings: SessionSettings,
        toolName: String,
        toolArgs: Map<String, JsonElement>,
        intervalSeconds: Long
    ): String {
        val jobId = UUID.randomUUID().toString()
        activeJobs[jobId] = scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                val result = mcpManager.callMcpServer(toolName, toolArgs)
                onResult(sessionId, toolName, result, settings)
            }
        }
        return jobId
    }
}
