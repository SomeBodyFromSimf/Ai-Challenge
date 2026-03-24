package com.sbfs.ai.repository

import com.sbfs.ai.data.Message
import com.sbfs.ai.data.MessageRole
import com.sbfs.ai.db.AiChallengeDb
import com.sbfs.ai.db.DatabaseDriverFactory
import kotlinx.datetime.Instant

class MessageRepository {
    private val db = AiChallengeDb(DatabaseDriverFactory().createDriver())
    private val messageQueries = db.messageQueries
    
    fun getMessagesBySessionId(sessionId: String): List<Message> {
        return messageQueries.getByKey(sessionId).executeAsList().map { messageEntity ->
            Message(
                id = messageEntity.id,
                sessionId = messageEntity.session_id,
                role = MessageRole.valueOf(messageEntity.role),
                content = messageEntity.content,
                timestamp = Instant.fromEpochMilliseconds(messageEntity.timestamp)
            )
        }
    }
    
    fun addMessage(message: Message) {
        messageQueries.insert(
            com.sbfs.ai.database.Message(
                id = message.id,
                session_id = message.sessionId,
                role = message.role.name,
                content = message.content,
                timestamp = message.timestamp.toEpochMilliseconds()
            )
        )
    }
    
    fun deleteMessagesBySessionId(sessionId: String) {
        messageQueries.deleteByKey(sessionId)
    }
}