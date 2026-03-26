package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sbfs.ai.data.Session
import com.sbfs.ai.data.SessionSettings
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

class SessionRepository(
    db: AiChallengeDb,
) {
    private val sessionQueries = db.sessionQueries

    fun getAllSessions(): Flow<List<Session>> {
        return sessionQueries.getAll()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { sessionEntity ->
                    val settings = Json.decodeFromString<SessionSettings>(sessionEntity.settings_data)
                    Session(
                        id = sessionEntity.id,
                        title = sessionEntity.title,
                        createdAt = Instant.fromEpochMilliseconds(sessionEntity.created_at),
                        updatedAt = Instant.fromEpochMilliseconds(sessionEntity.updated_at),
                        settings = settings,
                        totalToken = sessionEntity.totalToken,
                    )
                }
        }
    }
    
    fun getSessionById(id: String): Session? {
        val sessionEntity = sessionQueries.getByKey(id).executeAsOneOrNull()
        return sessionEntity?.let { entity ->
            val settings = Json.decodeFromString<SessionSettings>(sessionEntity.settings_data)
            Session(
                id = entity.id,
                title = entity.title,
                createdAt = Instant.fromEpochMilliseconds(entity.created_at),
                updatedAt = Instant.fromEpochMilliseconds(entity.updated_at),
                settings = settings,
                totalToken = sessionEntity.totalToken,
            )
        }
    }
    
    fun createSession(title: String, settings: SessionSettings): Session {
        val now = Clock.System.now()
        val id = java.util.UUID.randomUUID().toString()
        
        sessionQueries.insert(
            com.sbfs.ai.database.Session(
                id = id,
                title = title,
                created_at = now.toEpochMilliseconds(),
                updated_at = now.toEpochMilliseconds(),
                settings_data = Json.encodeToString(settings),
                totalToken = null
            )
        )
        
        return Session(
            id = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            settings = settings,
            totalToken = null
        )
    }
    
    fun updateSession(session: Session) {
        sessionQueries.updateSettings(
            id = session.id,
            title = session.title,
            update_time = session.updatedAt.toEpochMilliseconds(),
            data = Json.encodeToString(session.settings),
            totalToken = session.totalToken,
        )
    }
    
    fun deleteSession(id: String) {
        sessionQueries.deleteById(id)
    }

    fun clearTokenInfo(id: String) {
        sessionQueries.deleteTokenInfo(
            id = id,
        )
    }
}
