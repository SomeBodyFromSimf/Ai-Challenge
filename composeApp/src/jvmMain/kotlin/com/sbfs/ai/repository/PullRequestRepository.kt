package com.sbfs.ai.repository

import com.sbfs.ai.database.PullRequest
import com.sbfs.ai.db.AiChallengeDb
import java.util.UUID

class PullRequestRepository(db: AiChallengeDb) {

    private val queries = db.pullRequestQueries

    fun insert(number: Int, state: String) {
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        queries.insertPullRequest(id, number.toLong(), state, false, createdAt)
    }

    fun getUnreviewed(): List<PullRequest> {
        return queries.getAllUnreviewed().executeAsList()
    }

    fun markReviewed(id: String) {
        queries.markReviewed(id)
    }

    fun getAllNumbers(): List<Long> {
        return queries.getAllNumbers().executeAsList()
    }
}
