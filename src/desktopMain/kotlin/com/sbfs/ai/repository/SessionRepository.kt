package com.sbfs.ai.repository

import com.sbfs.ai.data.Session
import com.sbfs.ai.data.SessionSettings
import com.sbfs.ai.db.AiChallengeDb
import com.sbfs.ai.db.DatabaseDriverFactory
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

class SessionRepository {
    private val db = AiChallengeDb(DatabaseDriverFactory().createDriver())
    private val sessionQueries = db.sessionQueries
    private val messageQueries = db.messageQueries
    
    fun getAllSessions(): List<Session> {
        return sessionQueries.getAll().executeAsList().map { sessionEntity ->
            val settings = Json.decodeFromString<SessionSettings>(sessionEntity.settings_data)
            Session(
                id = sessionEntity.id,
                title = sessionEntity.title,
                createdAt = Instant.fromEpochMilliseconds(sessionEntity.created_at),
                updatedAt = Instant.fromEpochMilliseconds(sessionEntity.updated_at),
                settings = settings
            )
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
                settings = settings
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
            )
        )
        
        return Session(
            id = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            settings = settings
        )
    }
    
    fun updateSession(session: Session) {
        sessionQueries.updateSettings(
            id = session.id,
            title = session.title,
            update_time = session.updatedAt.toEpochMilliseconds(),
            data = Json.encodeToString(session.settings),
        )
    }
    
    fun deleteSession(id: String) {
        sessionQueries.deleteById(id)
        messageQueries.deleteByKey(id)
    }
}
