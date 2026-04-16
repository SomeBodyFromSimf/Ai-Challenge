package com.sbfs.ai.data

data class DocumentIndex(
    val id: String,
    val path: String,
    val filename: String,
    val title: String,
    val lastModified: Long,
    val fileSize: Long,
    val indexedAt: Long,
    val chunkCount: Int,
    val status: DocumentStatus,
)

data class DocumentChunk(
    val id: String,
    val documentId: String,
    val chunkIndex: Int,
    val content: String,
    val tokenCount: Int,
    val embedding: ByteArray? = null,
)

enum class DocumentStatus {
    PENDING,
    INDEXED,
    /** Чанки созданы, но Ollama была недоступна — эмбеддинги не сгенерированы. */
    INDEXED_NO_EMBEDDINGS,
    FAILED,
}
