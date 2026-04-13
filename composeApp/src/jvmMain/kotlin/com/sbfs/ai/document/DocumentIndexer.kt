package com.sbfs.ai.document

import com.sbfs.ai.data.DocumentChunk
import com.sbfs.ai.data.DocumentIndex
import com.sbfs.ai.data.DocumentStatus
import com.sbfs.ai.data.IndexingStatus
import com.sbfs.ai.data.RagConfig
import com.sbfs.ai.repository.DocumentRepository
import com.sbfs.ai.repository.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class SyncResult(
    val indexed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
    val folderNotFound: Boolean = false,
)

class DocumentIndexer(
    private val documentRepository: DocumentRepository,
    private val configRepository: ConfigRepository,
) {
    private val documentsDir: File
        get() = File(System.getProperty("user.dir"), "documents")

    private val indexingEventChannel = Channel<IndexingEvent>()
    val indexingEventFlow = indexingEventChannel.receiveAsFlow()

    /**
     * Синхронизирует папку documents/ с базой данных.
     *
     * - Новые файлы → индексируются.
     * - Изменённые (по lastModified/size) → старые чанки удаляются, переиндексируются.
     * - Актуальные → пропускаются.
     * - Удалённые с диска → удаляются из БД.
     *
     * Файлы обрабатываются параллельно; внутри каждого файла эмбеддинги
     * запрашиваются последовательно, чтобы не перегружать Ollama.
     *
     */
    suspend fun syncDocumentsFolder(): SyncResult = withContext(Dispatchers.IO) {
        if (!documentsDir.exists() || !documentsDir.isDirectory) {
            return@withContext SyncResult(folderNotFound = true)
        }

        val ragConfig = configRepository.getConfig().rag

        val filesOnDisk = documentsDir.walkTopDown()
            .filter { it.isFile && TextExtractor.supports(it) }
            .toList()

        val indexedDocs = documentRepository.getAllDocuments()
        val indexedByPath = indexedDocs.associateBy { it.path }

        // Удаляем записи файлов, которых уже нет на диске
        val diskPaths = filesOnDisk.map { it.absolutePath }.toSet()
        val removed = indexedDocs.count { it.path !in diskPaths }
        indexedDocs.filter { it.path !in diskPaths }.forEach { doc ->
            documentRepository.deleteDocument(doc.id)
        }

        // Определяем, какие файлы нужно (пере)индексировать
        val toIndex = filesOnDisk.filter { file ->
            val existing = indexedByPath[file.absolutePath]
            val isUpToDate = existing != null
                && existing.lastModified == file.lastModified()
                && existing.fileSize == file.length()
                && existing.status == DocumentStatus.INDEXED
            if (isUpToDate) return@filter false
            // Удаляем устаревшее представление
            if (existing != null) documentRepository.deleteDocument(existing.id)
            true
        }

        val skipped = filesOnDisk.size - toIndex.size
        val total = toIndex.size

        if (total == 0) return@withContext SyncResult(skipped = skipped, removed = removed)

        val ollamaClient = OllamaClient(ragConfig.ollamaUrl, ragConfig.embeddingModel)
        try {
            // Параллельная обработка файлов
            val results: List<Boolean> = coroutineScope {
                toIndex.map { file ->
                    async(Dispatchers.IO) {
                        indexingEventChannel.send(IndexingEvent.FileStarted(file.name))
                        val success = indexFile(file, ragConfig, ollamaClient)
                        indexingEventChannel.send(IndexingEvent.FileFinished(file.name))
                        success
                    }
                }.awaitAll()
            }
            val indexed = results.count { it }
            val failed  = results.count { !it }
            SyncResult(indexed = indexed, skipped = skipped, failed = failed, removed = removed)
        } finally {
            ollamaClient.close()
        }
    }

    // ── Индексация одного файла ────────────────────────────────────────────────

    private suspend fun indexFile(file: File, ragConfig: RagConfig, ollama: OllamaClient): Boolean {
        val docId = UUID.randomUUID().toString()
        documentRepository.insertDocument(
            DocumentIndex(
                id           = docId,
                path         = file.absolutePath,
                filename     = file.name,
                lastModified = file.lastModified(),
                fileSize     = file.length(),
                indexedAt    = System.currentTimeMillis(),
                chunkCount   = 0,
                status       = DocumentStatus.PENDING,
            )
        )

        return try {
            val text = TextExtractor.extract(file)
            val chunks = ChunkingService.chunk(text, ragConfig)
            var embeddingsMissing = false

            // Последовательные вызовы Ollama внутри одного файла
            chunks.forEachIndexed { index, chunkText ->
                val embedding = ollama.embed(chunkText)
                if (embedding == null) embeddingsMissing = true

                documentRepository.insertChunk(
                    DocumentChunk(
                        id         = UUID.randomUUID().toString(),
                        documentId = docId,
                        chunkIndex = index,
                        content    = chunkText,
                        tokenCount = chunkText.length / 4,
                        embedding  = embedding,
                    )
                )
            }

            val finalStatus = if (embeddingsMissing) DocumentStatus.INDEXED_NO_EMBEDDINGS
                              else                   DocumentStatus.INDEXED
            documentRepository.updateDocumentStatus(docId, finalStatus, chunks.size)
            println("[DocumentIndexer] ${file.name} → ${chunks.size} чанков, статус=$finalStatus")
            true
        } catch (e: Exception) {
            println("[DocumentIndexer] Ошибка индексации ${file.name}: ${e.message}")
            documentRepository.updateDocumentStatus(docId, DocumentStatus.FAILED, 0)
            false
        }
    }
}
