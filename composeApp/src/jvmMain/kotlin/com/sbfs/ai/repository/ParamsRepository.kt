package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.sbfs.ai.data.ContextMinimizationStrategy
import com.sbfs.ai.data.Params
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ParamsRepository(
    db: AiChallengeDb
) {
    private val queries = db.paramsQueries

    fun getSessionParams(sessionId: String): Flow<Params> {
        return queries.get(sessionId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { params ->
                params?.let {
                    Params(
                        manageContextStrategy = ContextMinimizationStrategy.valueOf(it.contextStrategy),
                    )
                } ?: Params()
            }
    }

    fun updateParams(sessionId: String, params: Params) {
        queries.update(
            params = com.sbfs.ai.database.Params(
                session_id = sessionId,
                contextStrategy = params.manageContextStrategy.name
            )
        )
    }

    fun deleteParams(sessionId: String) {
        queries.deleteByKey(sessionId)
    }
}