package com.sbfs.ai.document

import com.sbfs.ai.data.DocumentChunk
import com.sbfs.ai.data.DocumentIndex
import com.sbfs.ai.data.DocumentSource
import com.sbfs.ai.data.DocumentStatus
import com.sbfs.ai.data.IndexingStatus
import com.sbfs.ai.data.RagConfig
import com.sbfs.ai.repository.DocumentRepository
import com.sbfs.ai.repository.ConfigRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class SyncResult(
    val indexed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
    val folderNotFound: Boolean = false,
)

/** Представление скачанного файла из GitHub репозитория */
private data class DownloadedFile(
    val relativePath: String,
    val content: String,
    val lastModified: Long,
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

    suspend fun syncAll(assistedDevProject: String?, assistedSupportProject: String?): Triple<SyncResult, SyncResult?, SyncResult?> {
        val docsResult = syncDocumentsFolder()
        val devProjectResult = if (assistedDevProject != null) syncProjectFiles(assistedDevProject) else null
        val supportProjectResult = if (assistedSupportProject != null) syncSupportProjectFiles(assistedSupportProject) else null
        return Triple(docsResult, devProjectResult, supportProjectResult)
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
            val results = indexFiles(toIndex, ragConfig, ollamaClient, DocumentSource.DOCUMENTS)
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
            val results = indexFiles(toIndex, ragConfig, ollamaClient, DocumentSource.DEV_PROJECT)
            SyncResult(indexed = results.count { it }, skipped = skipped, failed = results.count { !it }, removed = removed)
        } finally {
            ollamaClient.close()
        }
    }

/**
     * Синхронизирует файлы проекта поддержки (README.md, docs/, api/) с базой данных.
     *
     * - Если проект изменился с прошлого запуска — индексируем новые файлы,
     *   старые эмбеддинги НЕ удаляем.
     * - Если проект тот же — проверяем изменения файлов, переиндексируем изменённые,
     *   удаляем записи для файлов, которых больше нет на диске в рамках этого проекта.
     *
     * @param projectPath путь к проекту в формате "owner/repo" для GitHub репозитория
     */
    private suspend fun syncSupportProjectFiles(projectPath: String): SyncResult = withContext(Dispatchers.IO) {
        // Проверяем, является ли projectPath GitHub репозиторием (формат owner/repo)
        if (!projectPath.contains("/")) {
            // Если это не GitHub репозиторий, обрабатываем как локальный путь (для обратной совместимости)
            val projectDir = File(projectPath)
            if (!projectDir.exists() || !projectDir.isDirectory) {
                return@withContext SyncResult(folderNotFound = true)
            }
            
            return@withContext syncSupportProjectFilesLocal(projectDir, projectPath)
        }
        
        // Обработка GitHub репозитория
        val parts = projectPath.split("/", limit = 2)
        if (parts.size != 2) {
            println("[DocumentIndexer] Неверный формат GitHub репозитория: $projectPath")
            return@withContext SyncResult(folderNotFound = true)
        }
        val owner = parts[0]
        val repo = parts[1]
        
        val ragConfig = configRepository.getConfig().rag
        // Для проекта поддержки используем отдельный файл состояния
        val supportStateFile = File(stateFile.parentFile, ".indexer_state_support")
        val lastProjectPath = try {
            if (supportStateFile.exists()) supportStateFile.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) { null }
        val projectChanged = lastProjectPath != projectPath

        // Создаем временную директорию для скачанных файлов
        val tempDir = try {
            createTempDirectory("support_project_")
        } catch (e: Exception) {
            println("[DocumentIndexer] Ошибка создания временной директории: ${e.message}")
            return@withContext SyncResult(folderNotFound = true)
        }
        
        try {
            // Скачиваем файлы из GitHub репозитория
            val downloadedFiles = downloadGitHubProjectFiles(owner, repo, tempDir)
            if (downloadedFiles.isEmpty()) {
                return@withContext SyncResult(folderNotFound = true)
            }
            
            val indexedDocs = documentRepository.getAllDocuments()
            // Для GitHub репозитория используем projectPath как префикс для путей
            val projectPrefix = "github:$projectPath"
            val indexedByPath = indexedDocs.associateBy { it.path }

            var removed = 0
            if (!projectChanged) {
                // Тот же проект: удаляем записи файлов, которых больше нет
                val diskPaths = downloadedFiles.map { "$projectPrefix/${it.relativePath}" }.toSet()
                indexedDocs.filter { it.path.startsWith(projectPrefix) && it.path !in diskPaths }.forEach { doc ->
                    documentRepository.deleteDocument(doc.id)
                    removed++
                }
            }
            // Если проект изменился — старые эмбеддинги не трогаем

            val toIndex = downloadedFiles.filter { file ->
                val filePath = "$projectPrefix/${file.relativePath}"
                val existing = indexedByPath[filePath]
                val isUpToDate = existing != null
                    && existing.lastModified == file.lastModified
                    && existing.fileSize == file.content.length.toLong()
                    && existing.status == DocumentStatus.INDEXED
                if (isUpToDate) return@filter false
                if (existing != null) documentRepository.deleteDocument(existing.id)
                true
            }

            val skipped = downloadedFiles.size - toIndex.size

            // Сохраняем новый путь проекта вне зависимости от того, есть ли что индексировать
            try {
                supportStateFile.writeText(projectPath)
            } catch (_: Exception) { }

            if (toIndex.isEmpty()) return@withContext SyncResult(skipped = skipped, removed = removed)

            val ollamaClient = OllamaClient(ragConfig.ollamaUrl, ragConfig.embeddingModel)
            try {
                val results = indexDownloadedFiles(toIndex, ragConfig, ollamaClient, projectPrefix)
                SyncResult(indexed = results.count { it }, skipped = skipped, failed = results.count { !it }, removed = removed)
            } finally {
                ollamaClient.close()
            }
        } finally {
            // Удаляем временную директорию
            try {
                tempDir.deleteRecursively()
            } catch (e: Exception) {
                println("[DocumentIndexer] Ошибка удаления временной директории: ${e.message}")
            }
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
    
    /** Создает временную директорию */
    private fun createTempDirectory(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile()
    }
    
    /**
     * Синхронизирует файлы локального проекта поддержки (для обратной совместимости)
     */
    private suspend fun syncSupportProjectFilesLocal(projectDir: File, projectPath: String): SyncResult = withContext(Dispatchers.IO) {
        val ragConfig = configRepository.getConfig().rag
        // Для проекта поддержки используем отдельный файл состояния
        val supportStateFile = File(stateFile.parentFile, ".indexer_state_support")
        val lastProjectPath = try {
            if (supportStateFile.exists()) supportStateFile.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) { null }
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
        try {
            supportStateFile.writeText(projectPath)
        } catch (_: Exception) { }

        if (toIndex.isEmpty()) return@withContext SyncResult(skipped = skipped, removed = removed)

        val ollamaClient = OllamaClient(ragConfig.ollamaUrl, ragConfig.embeddingModel)
        try {
            val results = indexFiles(toIndex, ragConfig, ollamaClient, DocumentSource.SUPPORT_PROJECT)
            SyncResult(indexed = results.count { it }, skipped = skipped, failed = results.count { !it }, removed = removed)
        } finally {
            ollamaClient.close()
        }
    }
    
    /**
     * Скачивает файлы из GitHub репозитория
     */
    private suspend fun downloadGitHubProjectFiles(owner: String, repo: String, tempDir: File): List<DownloadedFile> = withContext(Dispatchers.IO) {
        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
            }
        }
        
        val downloadedFiles = mutableListOf<DownloadedFile>()
        val pathsToCheck = listOf("README.md", "docs/", "api/")
        
        try {
            for (path in pathsToCheck) {
                if (path.endsWith("/")) {
                    // Это директория, получаем её содержимое
                    val dirPath = path.dropLast(1) // Убираем слэш в конце
                    downloadDirectoryContents(httpClient, owner, repo, dirPath, tempDir, downloadedFiles)
                } else {
                    // Это файл
                    downloadFile(httpClient, owner, repo, path, tempDir, downloadedFiles)
                }
            }
        } catch (e: Exception) {
            println("[DocumentIndexer] Ошибка при скачивании файлов из GitHub: ${e.message}")
        } finally {
            httpClient.close()
        }
        
        return@withContext downloadedFiles
    }
    
    /**
     * Скачивает содержимое директории из GitHub репозитория
     */
    private suspend fun downloadDirectoryContents(
        httpClient: HttpClient,
        owner: String,
        repo: String,
        dirPath: String,
        tempDir: File,
        downloadedFiles: MutableList<DownloadedFile>
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$owner/$repo/contents/$dirPath"
            val response: HttpResponse = httpClient.get(url)
            
            if (response.status == HttpStatusCode.OK) {
                val content = response.bodyAsText()
                val jsonArray = Json.parseToJsonElement(content).jsonArray
                
                for (item in jsonArray) {
                    val itemType = item.jsonObject["type"]?.jsonPrimitive?.content
                    val itemName = item.jsonObject["name"]?.jsonPrimitive?.content
                    val itemPath = item.jsonObject["path"]?.jsonPrimitive?.content
                    
                    if (itemType == "file" && itemName != null && itemPath != null) {
                        // Проверяем, поддерживается ли тип файла
                        if (TextExtractor.SUPPORTED_EXTENSIONS.any { itemName.endsWith(it, ignoreCase = true) }) {
                            downloadFile(httpClient, owner, repo, itemPath, tempDir, downloadedFiles)
                        }
                    } else if (itemType == "dir" && itemPath != null) {
                        // Рекурсивно скачиваем содержимое поддиректории
                        downloadDirectoryContents(httpClient, owner, repo, itemPath, tempDir, downloadedFiles)
                    }
                }
            }
        } catch (e: Exception) {
            println("[DocumentIndexer] Ошибка при получении содержимого директории $dirPath: ${e.message}")
        }
    }
    
    /**
     * Скачивает файл из GitHub репозитория
     */
    private suspend fun downloadFile(
        httpClient: HttpClient,
        owner: String,
        repo: String,
        filePath: String,
        tempDir: File,
        downloadedFiles: MutableList<DownloadedFile>
    ) = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$owner/$repo/contents/$filePath"
            val response: HttpResponse = httpClient.get(url) {
                headers {
                    append("Accept", "application/vnd.github.v3.raw")
                }
            }
            
            if (response.status == HttpStatusCode.OK) {
                val content = response.bodyAsText()
                val lastModified = System.currentTimeMillis()
                
                // Создаем структуру директорий
                val fileRelativePath = Paths.get(filePath)
                val fileDir = tempDir.toPath().resolve(fileRelativePath.parent ?: Paths.get(""))
                Files.createDirectories(fileDir)
                
                // Сохраняем файл
                val tempFile = tempDir.toPath().resolve(fileRelativePath).toFile()
                tempFile.writeText(content)
                
                // Добавляем в список скачанных файлов
                downloadedFiles.add(DownloadedFile(filePath, content, lastModified))
                println("[DocumentIndexer] Скачан файл: $filePath")
            }
        } catch (e: Exception) {
            println("[DocumentIndexer] Ошибка при скачивании файла $filePath: ${e.message}")
        }
    }
    
    /**
     * Индексирует скачанные файлы из GitHub репозитория
     */
    private suspend fun indexDownloadedFiles(
        files: List<DownloadedFile>,
        ragConfig: RagConfig,
        ollamaClient: OllamaClient,
        projectPrefix: String,
    ): List<Boolean> = coroutineScope {
        files.map { file ->
            async(Dispatchers.IO) {
                indexingEventChannel.send(IndexingEvent.FileStarted(file.relativePath))
                val success = indexDownloadedFile(file, ragConfig, ollamaClient, projectPrefix)
                indexingEventChannel.send(IndexingEvent.FileFinished(file.relativePath))
                success
            }
        }.awaitAll()
    }
    
    /**
     * Индексирует один скачанный файл из GitHub репозитория
     */
    private suspend fun indexDownloadedFile(
        file: DownloadedFile,
        ragConfig: RagConfig,
        ollama: OllamaClient,
        projectPrefix: String,
    ): Boolean {
        val docId = UUID.randomUUID().toString()
        val filePath = "$projectPrefix/${file.relativePath}"

        return try {
            val text = file.content

            val title = ollama.generateTitle(text, ragConfig.titleModel)
                ?: file.relativePath.substringAfterLast("/", file.relativePath)

            documentRepository.insertDocument(
                DocumentIndex(
                    id           = docId,
                    path         = filePath,
                    filename     = file.relativePath.substringAfterLast("/"),
                    title        = title,
                    lastModified = file.lastModified,
                    fileSize     = file.content.length.toLong(),
                    indexedAt    = System.currentTimeMillis(),
                    chunkCount   = 0,
                    status       = DocumentStatus.PENDING,
                    source       = DocumentSource.SUPPORT_PROJECT,
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
            println("[DocumentIndexer] ${file.relativePath} → «$title», ${chunks.size} чанков, статус=$finalStatus, источник=SUPPORT_PROJECT")
            true
        } catch (e: Exception) {
            println("[DocumentIndexer] Ошибка индексации ${file.relativePath}: ${e.message}")
            false
        }
    }

// ── Параллельная индексация списка файлов ──────────────────────────────────

    private suspend fun indexFiles(
        files: List<File>,
        ragConfig: RagConfig,
        ollamaClient: OllamaClient,
        source: DocumentSource,
    ): List<Boolean> = coroutineScope {
        files.map { file ->
            async(Dispatchers.IO) {
                indexingEventChannel.send(IndexingEvent.FileStarted(file.name))
                val success = indexFile(file, ragConfig, ollamaClient, source)
                indexingEventChannel.send(IndexingEvent.FileFinished(file.name))
                success
            }
        }.awaitAll()
    }

    // ── Индексация одного файла ────────────────────────────────────────────────

    private suspend fun indexFile(file: File, ragConfig: RagConfig, ollama: OllamaClient, source: DocumentSource): Boolean {
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
                    source       = source,
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
            println("[DocumentIndexer] ${file.name} → «$title», ${chunks.size} чанков, статус=$finalStatus, источник=$source")
            true
        } catch (e: Exception) {
            println("[DocumentIndexer] Ошибка индексации ${file.name}: ${e.message}")
            false
        }
    }
}
