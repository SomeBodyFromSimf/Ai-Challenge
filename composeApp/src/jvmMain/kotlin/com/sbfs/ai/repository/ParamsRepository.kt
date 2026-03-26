package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.sbfs.ai.data.Message
import com.sbfs.ai.data.MessageRole
import com.sbfs.ai.data.Params
import com.sbfs.ai.database.Summary
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

class ParamsRepository(
    db: AiChallengeDb
) {
    private val queries = db.paramsQueries

    fun getSessionParams(sessionId: String): Flow<Params> {
        return queries.get(sessionId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { params ->
                params?.is_summary_enabled?.let {
                    Params(
                        isSummaryEnabled = it,
                    )
                } ?: Params()
            }
    }

    fun updateParams(sessionId: String, params: Params) {
        queries.update(
            params = com.sbfs.ai.database.Params(
                session_id = sessionId,
                is_summary_enabled = params.isSummaryEnabled
            )
        )
    }

    fun deleteParams(sessionId: String) {
        queries.deleteByKey(sessionId)
    }
}