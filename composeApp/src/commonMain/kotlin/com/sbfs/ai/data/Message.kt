package com.sbfs.ai.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Instant,
    val usedToken: Long?,
    val cost: String?
)

@Serializable
enum class MessageRole {
    @SerialName("system")
    SYSTEM,
    @SerialName("user")
    USER,
    @SerialName("assistant")
    ASSISTANT
}