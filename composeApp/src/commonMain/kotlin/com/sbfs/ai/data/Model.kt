package com.sbfs.ai.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Model(
    val id: String,
    val name: String,
    @SerialName("context_length")
    val contextLength: Long = 0,
    val isLocal: Boolean = false,
    val baseUrl: String? = null,
)