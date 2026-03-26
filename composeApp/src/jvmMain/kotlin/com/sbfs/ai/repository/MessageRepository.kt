package com.sbfs.ai.repository

import androidx.compose.ui.layout.SubcomposeSlotReusePolicy
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sbfs.ai.data.Message
import com.sbfs.ai.data.MessageRole
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

class MessageRepository(
    db: AiChallengeDb,
) {
    private val messageQueries = db.messageQueries
    
    fun getMessagesFlowBySessionId(sessionId: String): Flow<List<Message>> {
        return messageQueries.getByKey(sessionId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { messageEntity ->
                    Message(
                        id = messageEntity.id,
                        sessionId = messageEntity.session_id,
                        role = MessageRole.valueOf(messageEntity.role),
                        content = messageEntity.content,
                        timestamp = Instant.fromEpochMilliseconds(messageEntity.timestamp),
                        usedToken = messageEntity.usedToken,
                        cost = messageEntity.cost,
                    )
                }
        }
    }
    
    fun addMessage(message: Message) {
        messageQueries.insert(
            com.sbfs.ai.database.Message(
                id = message.id,
                session_id = message.sessionId,
                role = message.role.name,
                content = message.content,
                timestamp = message.timestamp.toEpochMilliseconds(),
                usedToken = message.usedToken,
                cost = message.cost,
            )
        )
    }

    fun updateMessage(message: Message, usedToken: Long) {
        messageQueries.update(
            id = message.id,
            usedToken = usedToken,
        )
    }
    
    fun deleteMessagesBySessionId(sessionId: String) {
        messageQueries.deleteBySessionId(sessionId)
    }

    fun removeMessages(listIds: List<String>) {
        messageQueries.transaction {
            listIds.forEach {
                messageQueries.deleteById(it)
            }
        }
    }
}