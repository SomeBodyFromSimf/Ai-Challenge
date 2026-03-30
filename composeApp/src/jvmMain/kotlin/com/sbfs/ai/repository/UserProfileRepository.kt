package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
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

    fun getUserProfile(id: String = "default"): Flow<UserProfile> {
        return queries.getUserProfile(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { profileEntity ->
                profileEntity?.let {
                    UserProfile(
                        id = it.id,
                        name = it.name,
                        email = it.email,
                        preferences = json.decodeFromString<Map<String, String>>(it.preferences),
                        bio = it.bio,
                        skills = json.decodeFromString<List<String>>(it.skills),
                        interests = json.decodeFromString<List<String>>(it.interests),
                        knowledge = json.decodeFromString<List<String>>(it.knowledge)
                    )
                } ?: UserProfile.DEFAULT
            }
    }

    fun saveUserProfile(profile: UserProfile) {
        queries.insertOrUpdateProfile(
            com.sbfs.ai.database.User_profile(
                id = profile.id,
                name = profile.name,
                email = profile.email,
                preferences = json.encodeToString(profile.preferences),
                bio = profile.bio,
                skills = json.encodeToString(profile.skills),
                interests = json.encodeToString(profile.interests),
                knowledge = json.encodeToString(profile.knowledge)
            )
        )
    }
}