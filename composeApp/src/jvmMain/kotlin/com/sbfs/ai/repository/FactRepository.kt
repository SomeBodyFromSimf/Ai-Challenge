package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sbfs.ai.data.Fact
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FactRepository(
    db: AiChallengeDb
) {
    private val queries = db.factQueries

    fun getFactsBySessionId(sessionId: String): Flow<List<Fact>> {
        return queries.getAll(sessionId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { factEntity ->
                    Fact(
                        sessionId = factEntity.session_id,
                        key = factEntity.key,
                        value = factEntity.value_
                    )
                }
            }
    }

    fun insertFact(fact: Fact) {
        queries.insert(
            com.sbfs.ai.database.Fact(
                session_id = fact.sessionId,
                key = fact.key,
                value_ = fact.value
            )
        )
    }

    fun deleteFact(sessionId: String, key: String) {
        queries.deleteByKey(sessionId, key)
    }

    fun clearFacts(sessionId: String) {
        queries.clear(sessionId)
    }
}