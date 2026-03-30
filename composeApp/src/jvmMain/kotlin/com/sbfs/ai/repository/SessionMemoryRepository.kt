package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sbfs.ai.data.Fact
import com.sbfs.ai.data.SessionMemoryData
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SessionMemoryRepository(
    db: AiChallengeDb
) {
    private val queries = db.sessionMemoryQueries

    fun getMemoryBySessionId(sessionId: String): Flow<List<SessionMemoryData>> {
        return queries.getAll(sessionId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { factEntity ->
                    SessionMemoryData(
                        sessionId = factEntity.session_id,
                        data = factEntity.data_,
                    )
                }
            }
    }

    fun insert(data: SessionMemoryData) {
        queries.insert(
            com.sbfs.ai.database.SessionMemory(
                session_id = data.sessionId,
                data_ = data.data,
            )
        )
    }

    fun delete(sessionId: String, data: String) {
        queries.delete(sessionId, data)
    }

    fun clear(sessionId: String) {
        queries.clear(sessionId)
    }
}