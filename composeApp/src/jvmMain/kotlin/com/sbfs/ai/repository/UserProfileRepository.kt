package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.sbfs.ai.data.UserProfile
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UserProfileRepository(
    db: AiChallengeDb
) {
    private val queries = db.userProfileQueries
    private val json = Json { ignoreUnknownKeys = true }

    fun getUserProfile(): Flow<UserProfile?> {
        return queries.getCurrentUserProfile()
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { profileEntity ->
                profileEntity?.let {
                    UserProfile(
                        id = it.id,
                        name = it.name,
                        preferences = json.decodeFromString<Map<String, String>>(it.preferences),
                        limitationsForLLM = json.decodeFromString<List<String>>(it.limitationsForLLM),
                        additionalInfo = it.additionalInfo,
                    )
                }
            }
    }

    fun getAllUsers(): Flow<List<UserProfile>> {
        return queries.getAllProfiles()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map {
                    UserProfile(
                        id = it.id,
                        name = it.name,
                        isCurrent = it.isCurrent,
                        preferences = json.decodeFromString<Map<String, String>>(it.preferences),
                        limitationsForLLM = json.decodeFromString<List<String>>(it.limitationsForLLM),
                        additionalInfo = it.additionalInfo,
                    )
                }
            }
    }

    fun saveUserProfile(profile: UserProfile) {
        queries.insertOrUpdateProfile(
            com.sbfs.ai.database.User_profile(
                id = profile.id,
                name = profile.name,
                isCurrent = profile.isCurrent,
                preferences = json.encodeToString(profile.preferences),
                limitationsForLLM = json.encodeToString(profile.limitationsForLLM),
                additionalInfo = profile.additionalInfo,
            )
        )
    }

    fun removeCurrent() {
        queries.removeCurrent()
    }
}