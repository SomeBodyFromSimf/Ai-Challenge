package com.sbfs.ai.data

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String, // ID профиля (по умолчанию один профиль)
    val name: String? = null, // Имя пользователя
    val isCurrent: Boolean = true,
    val preferences: Map<String, String> = emptyMap(), // Предпочтения пользователя
    val limitationsForLLM: List<String> = emptyList(), // Ограничения это делать нельзя
    val additionalInfo: String? = null, // Доп инфа
) {
    fun isDefault(): Boolean {
        return preferences.isEmpty() && limitationsForLLM.isEmpty() && additionalInfo == null
    }
}