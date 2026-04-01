package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.sbfs.ai.data.Branch
import com.sbfs.ai.data.Message
import com.sbfs.ai.data.MessageRole
import com.sbfs.ai.database.Message_branch
import com.sbfs.ai.db.AiChallengeDb
import jdk.internal.joptsimple.internal.Messages.message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

class BranchRepository(
    db: AiChallengeDb
) {
    private val branchQueries = db.branchQueries

    fun getBranchesBySessionId(sessionId: String): Flow<List<Branch>> {
        return branchQueries.getAll(sessionId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { branchEntity ->
                    Branch(
                        id = branchEntity.id,
                        sessionId = branchEntity.session_id,
                        name = branchEntity.name,
                        createdAt = Instant.fromEpochMilliseconds(branchEntity.created_at)
                    )
                }
            }
    }

    fun getBranchById(id: String): Flow<Branch?> {
        return branchQueries.getById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { branchEntity ->
                branchEntity?.let {
                    Branch(
                        id = it.id,
                        sessionId = it.session_id,
                        name = it.name,
                        createdAt = Instant.fromEpochMilliseconds(it.created_at)
                    )
                }
            }
    }

    fun insertBranch(branch: Branch) {
        branchQueries.insert(
            com.sbfs.ai.database.Branch(
                id = branch.id,
                session_id = branch.sessionId,
                name = branch.name,
                created_at = branch.createdAt.toEpochMilliseconds()
            )
        )
    }

    fun deleteBranch(id: String) {
        branchQueries.deleteBranch(id)
        branchQueries.removeAllMessagesFromBranch(id)
    }

    fun clearBranches(sessionId: String) {
        branchQueries.getAll(sessionId).executeAsList()
            .forEach {
                branchQueries.removeAllMessagesFromBranch(it.id)
            }
        branchQueries.deleteAllBySessionId(sessionId)
    }

    fun insertMessageBranch(branchId: String, message: Message) {
        branchQueries.insertBranchMessage(
            Message_branch(
                message_id = message.id,
                branch_id = branchId,
                role = message.role.name,
                content = message.content,
                timestamp = message.timestamp.toEpochMilliseconds(),
                usedToken = message.usedToken,
                cost = message.cost
            )
        )
    }

    fun getMessageBranches(sessionId: String, branchId: String): Flow<List<Message>> {
        return branchQueries.getBranchMessages(branchId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { entity ->
                    Message(
                        id = entity.message_id,
                        sessionId = sessionId,
                        role = MessageRole.valueOf(entity.role),
                        content = entity.content,
                        timestamp = Instant.fromEpochMilliseconds(entity.timestamp),
                        usedToken = entity.usedToken,
                        cost = entity.cost,
                    )
                }
            }
    }

    fun removeMessages(branchId: String) {
        branchQueries.removeAllMessagesFromBranch(branchId)
    }

    fun removeMessages(branchId: String, messageId: String) {
        branchQueries.removeMessage(branchId, messageId)
    }
}