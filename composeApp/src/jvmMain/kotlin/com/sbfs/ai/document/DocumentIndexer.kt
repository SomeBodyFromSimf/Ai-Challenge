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

    private val stateFile: File
        get() = File(System.getProperty("user.dir"), ".indexer_state")

    private val indexingEventChannel = Channel<IndexingEvent>()
    val indexingEventFlow = indexingEventChannel.receiveAsFlow()

    // ── Точка входа ───────────────────────────────────────────────────────────

    suspend fun syncAll(assistedProject: String?): Pair<SyncResult, SyncResult?> {
        val docsResult = syncDocumentsFolder()
        val projectResult = if (assistedProject != null) syncProjectFiles(assistedProject) else null
        return docsResult to projectResult
    }

    // ── Синхронизация папки documents/ ────────────────────────────────────────

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
     */
    suspend fun syncDocumentsFolder(): SyncResult = withContext(Dispatchers.IO) {
        if (!documentsDir.exists() || !documentsDir.isDirectory) {
            return@withContext SyncResult(folderNotFound = true)
        }

        val ragConfig = configRepository.getConfig().rag

        val filesOnDisk = documentsDir.walkTopDown()
            .filter { it.isFile && TextExtractor.supports(it) }
            .toList()

        val docsDirAbs = documentsDir.absolutePath
        val indexedDocs = documentRepository.getAllDocuments()
        val indexedByPath = indexedDocs.associateBy { it.path }

        // Удаляем только те записи, которые принадлежат папке documents/ и которых нет на диске
        val diskPaths = filesOnDisk.map { it.absolutePath }.toSet()
        val removed = indexedDocs.count { it.path.startsWith(docsDirAbs) && it.path !in diskPaths }
        indexedDocs.filter { it.path.startsWith(docsDirAbs) && it.path !in diskPaths }.forEach { doc ->
            documentRepository.deleteDocument(doc.id)
        }

        val toIndex = filesOnDisk.filter { file ->
            val existing = indexedByPath[file.absolutePath]
            val isUpToDate = existing != null
                && existing.lastModified == file.lastModified()
                && existing.fileSize == file.length()
                && existing.status == DocumentStatus.INDEXED
            if (isUpToDate) return@filter false
            if (existing != null) documentRepository.deleteDocument(existing.id)
            true
        }

        val skipped = filesOnDisk.size - toIndex.size
        if (toIndex.isEmpty()) return@withContext SyncResult(skipped = skipped, removed = removed)

        val ollamaClient = OllamaClient(ragConfig.ollamaUrl, ragConfig.embeddingModel)
        try {
            val results = indexFiles(toIndex, ragConfig, ollamaClient)
            SyncResult(indexed = results.count { it }, skipped = skipped, failed = results.count { !it }, removed = removed)
        } finally {
            ollamaClient.close()
        }
    }

    // ── Синхронизация файлов ассистируемого проекта ───────────────────────────

    /**
     * Синхронизирует файлы проекта (README.md, docs/, api/) с базой данных.
     *
     * - Если проект изменился с прошлого запуска — индексируем новые файлы,
     *   старые эмбеддинги НЕ удаляем.
     * - Если проект тот же — проверяем изменения файлов, переиндексируем изменённые,
     *   удаляем записи для файлов, которых больше нет на диске в рамках этого проекта.
    */
    private suspend fun syncProjectFiles(projectPath: String): SyncResult = withContext(Dispatchers.IO) {
        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return@withContext SyncResult(folderNotFound = true)
        }

        val ragConfig = configRepository.getConfig().rag
        val lastProjectPath = readLastProjectPath()
        val projectChanged = lastProjectPath != projectPath
        val projectDirAbs = projectDir.absolutePath

        val filesOnDisk = collectProjectFiles(projectDir)
        val indexedDocs = documentRepository.getAllDocuments()
        val indexedByPath = indexedDocs.associateBy { it.path }

        var removed = 0
        if (!projectChanged) {
            // Тот же проект: удаляем записи файлов, которых больше нет на диске
            val diskPaths = filesOnDisk.map { it.absolutePath }.toSet()
            indexedDocs.filter { it.path.startsWith(projectDirAbs) && it.path !in diskPaths }.forEach { doc ->
                documentRepository.deleteDocument(doc.id)
                removed++
            }
        }
        // Если проект изменился — старые эмбеддинги не трогаем

        val toIndex = filesOnDisk.filter { file ->
            val existing = indexedByPath[file.absolutePath]
            val isUpToDate = existing != null
                && existing.lastModified == file.lastModified()
                && existing.fileSize == file.length()
                && existing.status == DocumentStatus.INDEXED
            if (isUpToDate) return@filter false
            if (existing != null) documentRepository.deleteDocument(existing.id)
            true
        }

        val skipped = filesOnDisk.size - toIndex.size

        // Сохраняем новый путь проекта вне зависимости от того, есть ли что индексировать
        saveLastProjectPath(projectPath)

        if (toIndex.isEmpty()) return@withContext SyncResult(skipped = skipped, removed = removed)

        val ollamaClient = OllamaClient(ragConfig.ollamaUrl, ragConfig.embeddingModel)
        try {
            val results = indexFiles(toIndex, ragConfig, ollamaClient)
            SyncResult(indexed = results.count { it }, skipped = skipped, failed = results.count { !it }, removed = removed)
        } finally {
            ollamaClient.close()
        }
    }

    // ── Вспомогательные методы ─────────────────────────────────────────────────

    /** Собирает файлы проекта: README.md в корне + всё из docs/ и api/. */
    private fun collectProjectFiles(projectDir: File): List<File> {
        val files = mutableListOf<File>()
        File(projectDir, "README.md").takeIf { it.exists() && it.isFile }?.let { files.add(it) }
        listOf("docs", "api").forEach { dirName ->
            File(projectDir, dirName)
                .takeIf { it.exists() && it.isDirectory }
                ?.walkTopDown()
                ?.filter { it.isFile && TextExtractor.supports(it) }
                ?.forEach { files.add(it) }
        }
        return files
    }

    private fun readLastProjectPath(): String? = try {
        if (stateFile.exists()) stateFile.readText().trim().takeIf { it.isNotEmpty() } else null
    } catch (_: Exception) { null }

    private fun saveLastProjectPath(path: String) = try {
        stateFile.writeText(path)
    } catch (_: Exception) { }

    // ── Параллельная индексация списка файлов ──────────────────────────────────

    private suspend fun indexFiles(
        files: List<File>,
        ragConfig: RagConfig,
        ollamaClient: OllamaClient,
    ): List<Boolean> = coroutineScope {
        files.map { file ->
            async(Dispatchers.IO) {
                indexingEventChannel.send(IndexingEvent.FileStarted(file.name))
                val success = indexFile(file, ragConfig, ollamaClient)
                indexingEventChannel.send(IndexingEvent.FileFinished(file.name))
                success
            }
        }.awaitAll()
    }

    // ── Индексация одного файла ────────────────────────────────────────────────

    private suspend fun indexFile(file: File, ragConfig: RagConfig, ollama: OllamaClient): Boolean {
        val docId = UUID.randomUUID().toString()

        return try {
            val text = TextExtractor.extract(file)

            val title = ollama.generateTitle(text, ragConfig.titleModel)
                ?: file.nameWithoutExtension

            documentRepository.insertDocument(
                DocumentIndex(
                    id           = docId,
                    path         = file.absolutePath,
                    filename     = file.name,
                    title        = title,
                    lastModified = file.lastModified(),
                    fileSize     = file.length(),
                    indexedAt    = System.currentTimeMillis(),
                    chunkCount   = 0,
                    status       = DocumentStatus.PENDING,
                )
            )

            val chunks = ChunkingService.chunk(text, ragConfig)
            var embeddingsMissing = false

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
            println("[DocumentIndexer] ${file.name} → «$title», ${chunks.size} чанков, статус=$finalStatus")
            true
        } catch (e: Exception) {
            println("[DocumentIndexer] Ошибка индексации ${file.name}: ${e.message}")
            false
        }
    }
}
