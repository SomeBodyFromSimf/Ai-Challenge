package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sbfs.ai.data.Invariant
import com.sbfs.ai.database.Invariants
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class InvariantsRepository(
    db: AiChallengeDb
) {
    private val queries = db.invariantsQueries

    fun getInvariantsBySessionId(sessionId: String): Flow<List<Invariant>> {
        return queries.getAll(sessionId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { entity ->
                    Json.decodeFromString<Invariant>(entity.data_)
                }
            }
    }

    fun insert(sessionId: String, invariant: Invariant) {
        queries.insert(
            Invariants(
                session_id = sessionId,
                classType = invariant::class.simpleName!!,
                data_ = Json.encodeToString<Invariant>(invariant),
            )
        )
    }

    fun delete(sessionId: String, classType: String) {
        queries.delete(sessionId, classType)
    }

    fun clear(sessionId: String) {
        queries.clear(sessionId)
    }
}