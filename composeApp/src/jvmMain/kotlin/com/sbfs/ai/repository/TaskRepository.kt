package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.sbfs.ai.data.TaskContext
import com.sbfs.ai.data.TaskState
import com.sbfs.ai.database.Task_context
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TaskRepository(
    db: AiChallengeDb
) {
    private val queries = db.taskContextQueries

    fun getBySessionId(sessionId: String): Flow<TaskContext?> {
        return queries
            .getBySessionId(sessionId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { entity ->
                entity ?: return@map null
                TaskContext(
                    taskName = entity.taskName,
                    taskState = TaskState.valueOf(entity.taskState),
                    step = entity.step.toInt(),
                    totalSteps = entity.totalSteps.toInt()
                )
            }
    }

    fun insertTask(sessionId: String, task: TaskContext) {
        queries.insert(
            Task_context(
                sessionId = sessionId,
                taskName = task.taskName,
                taskState = task.taskState.name,
                step = task.step.toLong(),
                totalSteps = task.totalSteps.toLong()
            )
        )
    }

    fun deleteTask(sessionId: String) {
        queries.deleteBySessionId(sessionId)
    }
}