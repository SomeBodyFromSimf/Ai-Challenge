package com.sbfs.ai.data

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Session(
    val id: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val settings: SessionSettings,
    val totalToken: Long?,
)

@Serializable
data class SessionSettings(
    val model: Model? = null,
    val temperature: Double = 1.0,
    val topP: Double = 1.0,
    val topK: Int = 0,
    val minP: Double = 0.0,
    val topA: Double = 0.0,
    val frequencyPenalty: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val repetitionPenalty: Double = 1.0,
    val maxTokens: Int? = null,
    val seed: Int? = null,
    val stop: List<String> = emptyList(),
    val responseFormat: String? = null
)