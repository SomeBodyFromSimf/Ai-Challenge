package com.sbfs.ai.data

import kotlinx.serialization.Serializable

@Serializable
data class TaskContext(
    val taskName: String,
    val taskState: TaskState,
    val step: Int,
    val totalSteps: Int,
)

enum class TaskState {
    PLANING,
    EXECUTING,
    VALIDATE,
    DONE
}

private val transitions = mapOf(
    TaskState.PLANING to listOf(TaskState.EXECUTING),
    TaskState.EXECUTING to listOf(TaskState.PLANING, TaskState.VALIDATE),
    TaskState.VALIDATE to listOf(TaskState.EXECUTING, TaskState.DONE),
    TaskState.DONE to emptyList(),
)

fun TaskContext.checkAvailableStep(state: TaskState) {
    val allowed = transitions[taskState]
    require(state in allowed!! || state == taskState) {
        "Переход из $taskState -> $state запрещен!!!"
    }
}