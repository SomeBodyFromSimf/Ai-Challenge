package com.sbfs.ai.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.sbfs.ai.data.DocumentChunk
import com.sbfs.ai.data.DocumentIndex
import com.sbfs.ai.data.DocumentStatus
import com.sbfs.ai.db.AiChallengeDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DocumentRepository(db: AiChallengeDb) {

    private val indexQueries = db.documentIndexQueries
    private val chunkQueries = db.documentChunkQueries

    // ── DocumentIndex ──────────────────────────────────────────────────────────

    fun getAllDocumentsFlow(): Flow<List<DocumentIndex>> =
        indexQueries.getAll()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toDomain() } }

    suspend fun getAllDocuments(): List<DocumentIndex> = withContext(Dispatchers.IO) {
        indexQueries.getAll().executeAsList().map { it.toDomain() }
    }

    suspend fun getDocumentByPath(path: String): DocumentIndex? = withContext(Dispatchers.IO) {
        indexQueries.getByPath(path).executeAsOneOrNull()?.toDomain()
    }

    fun insertDocument(doc: DocumentIndex) {
        indexQueries.insert(
            com.sbfs.ai.database.Document_index(
                id           = doc.id,
                path         = doc.path,
                filename     = doc.filename,
                lastModified = doc.lastModified,
                fileSize     = doc.fileSize,
                indexedAt    = doc.indexedAt,
                chunkCount   = doc.chunkCount.toLong(),
                status       = doc.status.name,
            )
        )
    }

    fun updateDocumentStatus(id: String, status: DocumentStatus, chunkCount: Int) {
        indexQueries.updateStatus(
            id         = id,
            status     = status.name,
            chunkCount = chunkCount.toLong(),
            indexedAt  = System.currentTimeMillis(),
        )
    }

    fun deleteDocument(id: String) {
        chunkQueries.deleteByDocumentId(id)
        indexQueries.deleteById(id)
    }

    // ── DocumentChunk ──────────────────────────────────────────────────────────

    fun insertChunk(chunk: DocumentChunk) {
        chunkQueries.insert(
            com.sbfs.ai.database.Document_chunk(
                id         = chunk.id,
                documentId = chunk.documentId,
                chunkIndex = chunk.chunkIndex.toLong(),
                content    = chunk.content,
                tokenCount = chunk.tokenCount.toLong(),
                embedding  = chunk.embedding,
            )
        )
    }

    suspend fun getChunksByDocumentId(documentId: String): List<DocumentChunk> =
        withContext(Dispatchers.IO) {
            chunkQueries.getByDocumentId(documentId).executeAsList().map { it.toDomain() }
        }

    suspend fun getAllChunks(): List<DocumentChunk> = withContext(Dispatchers.IO) {
        chunkQueries.getAll().executeAsList().map { it.toDomain() }
    }

    // ── Mappers ────────────────────────────────────────────────────────────────

    private fun com.sbfs.ai.database.Document_index.toDomain() = DocumentIndex(
        id           = id,
        path         = path,
        filename     = filename,
        lastModified = lastModified,
        fileSize     = fileSize,
        indexedAt    = indexedAt,
        chunkCount   = chunkCount.toInt(),
        status       = DocumentStatus.valueOf(status),
    )

    private fun com.sbfs.ai.database.Document_chunk.toDomain() = DocumentChunk(
        id         = id,
        documentId = documentId,
        chunkIndex = chunkIndex.toInt(),
        content    = content,
        tokenCount = tokenCount.toInt(),
        embedding  = embedding,
    )
}
