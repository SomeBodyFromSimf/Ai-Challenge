package com.sbfs.ai.data

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String = "default", // ID профиля (по умолчанию один профиль)
    val name: String? = null, // Имя пользователя
    val email: String? = null, // Email пользователя
    val preferences: Map<String, String> = emptyMap(), // Предпочтения пользователя
    val bio: String? = null, // Биография или описание пользователя
    val skills: List<String> = emptyList(), // Навыки пользователя
    val interests: List<String> = emptyList(), // Интересы пользователя
    val knowledge: List<String> = emptyList() // Общие знания пользователя
) {
    companion object {
        val DEFAULT = UserProfile()

    }
}