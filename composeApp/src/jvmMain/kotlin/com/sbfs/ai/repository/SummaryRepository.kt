package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.sbfs.ai.data.Message
import com.sbfs.ai.data.MessageRole
import com.sbfs.ai.database.Summary
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

class SummaryRepository(
    db: AiChallengeDb
) {
    private val queries = db.summaryQueries

    fun getSummaryForSession(sessionId: String): Flow<Message?> {
        return queries.getByKey(sessionId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { summaryEntity ->
                summaryEntity?.let { summary ->
                    Message(
                        id = "",
                        sessionId = sessionId,
                        role = MessageRole.SYSTEM,
                        content = summary.content,
                        timestamp = Instant.fromEpochMilliseconds(summary.created_at),
                        usedToken = summary.tokens,
                        cost = null,
                        validationResult = null
                    )
                }
            }
    }

    fun insertSummary(message: Message) {
        queries.insert(
                Summary(
                    session_id = message.sessionId,
                    content = message.content,
                    created_at = message.timestamp.toEpochMilliseconds(),
                    tokens = message.usedToken!!
                )
        )
    }

    fun deleteSummary(sessionId: String) {
        queries.deleteByKey(sessionId)
    }
}