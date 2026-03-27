package com.sbfs.ai.data

import kotlin.time.Instant

data class Branch(
    val id: String,
    val sessionId: String,
    val name: String,
    val createdAt: Instant
)