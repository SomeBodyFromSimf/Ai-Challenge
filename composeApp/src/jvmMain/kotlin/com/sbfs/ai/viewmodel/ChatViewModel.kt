package com.sbfs.ai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbfs.ai.McpManager
import com.sbfs.ai.OpenRouterClient
import com.sbfs.ai.SchedulerManager
import com.sbfs.ai.data.*
import com.sbfs.ai.db.AiChallengeDb
import com.sbfs.ai.db.DatabaseDriverFactory
import com.sbfs.ai.document.DocumentIndexer
import com.sbfs.ai.document.IndexingEvent
import com.sbfs.ai.repository.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.util.*
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel : ViewModel() {
    private val db = AiChallengeDb(DatabaseDriverFactory().createDriver())

    private val sessionRepository = SessionRepository(db)
    private val messageRepository = MessageRepository(db)
    private val modelRepository = ModelRepository(db)

    private val paramsRepository = ParamsRepository(db)

    private val summaryRepository = SummaryRepository(db)
    private val factRepository = FactRepository(db)
    private val branchRepository = BranchRepository(db)
    private val userProfileRepository = UserProfileRepository(db)

    private val sessionMemoryRepository = SessionMemoryRepository(db)
    private val invariantsRepository = InvariantsRepository(db)
    private val taskRepository = TaskRepository(db)
    private val configRepository = ConfigRepository()

    private val documentRepository = DocumentRepository(db)
    private val documentIndexer = DocumentIndexer(documentRepository, configRepository)
    private var ragRetriever: com.sbfs.ai.document.RagRetriever? = null
    private val ragQueryMemories = mutableMapOf<String, com.sbfs.ai.document.RagQueryMemory>()

    private val mcpManager = McpManager()
    private val openRouterClient = OpenRouterClient(mcpManager)
    private val schedulerManager = SchedulerManager(
        mcpManager = mcpManager,
        scope = viewModelScope,
        onResult = { sessionId, toolName, result, settings ->
            val message = try {
                val messageData = openRouterClient.sendSystemMessage(
                    "Преобразуй результат выполнения к человекочитаемому формату.\n" +
                    "Tool: $toolName\nРезультат: $result",
                    settings
                )
                Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = messageData.content,
                    timestamp = Clock.System.now(),
                    usedToken = messageData.outputUsedToken,
                    cost = messageData.cost.toCostString(),
                    validationResult = null
                )
            } catch (_: Exception) {
                Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = result,
                    timestamp = Clock.System.now(),
                    usedToken = null,
                    cost = null,
                    validationResult = null
                )
            }
            messageRepository.addMessage(message)
        }
    )


    // Профиль пользователя
    val userProfile = userProfileRepository.getUserProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val profiles = userProfileRepository.getAllUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val sessions = userProfile.flatMapLatest {
        if (it == null) {
            flowOf(emptyList())
        } else {
            sessionRepository.getAllSessions(it.id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val currentSessionId = MutableStateFlow<String?>(null)
    val currentSession: StateFlow<Session?> = combine(
        sessions,
        currentSessionId
    ) { s, id ->
        s.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    private val isLoading = MutableStateFlow<Set<LoadingData>>(hashSetOf())


    // Состояние выполнения задачи
    val taskContextState = currentSessionId.flatMapLatest { sessionId ->
        if (sessionId != null) {
            taskRepository.getBySessionId(sessionId)
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    // Для стратегии BRANCHING

    val branches: StateFlow<List<Branch>> = currentSessionId.flatMapLatest { sessionId ->
        if (sessionId != null) {
            branchRepository.getBranchesBySessionId(sessionId)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val sessionMemory: StateFlow<List<String>> = currentSessionId.flatMapLatest { sessionId ->
        if (sessionId != null) {
            sessionMemoryRepository.getMemoryBySessionId(sessionId)
                .map { list -> list.map { it.data } }
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val invariants: StateFlow<List<Invariant>> = currentSessionId.flatMapLatest { sessionId ->
        if (sessionId != null) {
            invariantsRepository.getInvariantsBySessionId(sessionId)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val currentBranchId = MutableStateFlow<String?>(null)
    val currentBranch = combine(
        currentBranchId, branches
    ) { id, b ->
        b.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    private val _localModels = MutableStateFlow<List<Model>>(emptyList())

    val models = combine(
        flow {
            val offlineModels = modelRepository.getModels()
            if (offlineModels.isNotEmpty()) {
                emit(offlineModels)
            } else {
                emit(openRouterClient.getAvailableModels())
            }
        },
        _localModels
    ) { remote, local -> local + remote }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val sessionParams = currentSessionId.flatMapLatest { sessionId ->
        if (sessionId != null) {
            paramsRepository.getSessionParams(sessionId)
        } else {
            flowOf(Params())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), Params())


    val systemMessage = combine(
        currentSessionId,
        sessionParams,
    ) { sessionId, param -> sessionId to param.manageContextStrategy }.flatMapLatest { (id, strategy) ->
        val sessionId = id ?: return@flatMapLatest flowOf(null)
        when (strategy) {
            ContextMinimizationStrategy.SUMMARY -> {
                summaryRepository.getSummaryForSession(sessionId)
            }

            ContextMinimizationStrategy.STICKY_FACTS -> {
                factRepository.getFactsBySessionId(sessionId)
                    .map {
                        it.takeIf { it.isNotEmpty() }
                            ?.joinToString("\n") { "${it.key}: ${it.value}" }
                            ?.let { factsContent ->
                                Message(
                                    id = "",
                                    sessionId = sessionId,
                                    role = MessageRole.SYSTEM,
                                    content = "Important facts:\n$factsContent",
                                    timestamp = Clock.System.now(),
                                    usedToken = null,
                                    cost = null,
                                    validationResult = null
                                )
                            }
                    }
            }

            ContextMinimizationStrategy.SLIDING -> flowOf(null)
            ContextMinimizationStrategy.BRANCHING -> flowOf(null)
            ContextMinimizationStrategy.NO_STRATEGY -> flowOf(null)
        }
    }

    val messages: StateFlow<Pair<List<Message>, Boolean>> = combine(
        currentSessionId,
        currentBranchId,
    ) { sessionId, branchId -> sessionId to branchId }.flatMapLatest { (sessionId, branchId) ->
        if (sessionId != null) {
            combine(
                systemMessage,
                messageRepository.getMessagesFlowBySessionId(sessionId),
                branchId?.let { branchRepository.getMessageBranches(sessionId, it) } ?: flowOf(emptyList()),
                isLoading.map { set -> set.contains(LoadingData(sessionId, branchId)) },
            ) { summaryMessage, messages, branchedMessage, isLoading ->
                listOfNotNull(summaryMessage, *messages.toTypedArray(), *branchedMessage.toTypedArray()) to isLoading
            }
        } else {
            flowOf(emptyList<Message>() to false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList<Message>() to false)

    private val _settings = MutableStateFlow(SessionSettings())
    val settings: StateFlow<SessionSettings> = _settings.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Состояние MCP серверов
    private val _mcpServers = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val mcpServers: StateFlow<Map<String, Boolean>> = _mcpServers.asStateFlow()

    val indexingStatus: StateFlow<String> = flow {
        var list = mutableListOf<String>()
        documentIndexer.indexingEventFlow.collect {
            when (it) {
                is IndexingEvent.FileStarted -> {
                    list.add(it.filename)
                }
                is IndexingEvent.FileFinished -> {
                    list.remove(it.filename)
                }
            }
            val str = when (list.size) {
                0 -> ""
                1 -> "Индексируется ${list.first()}"
                else -> "Индексация ${list.size} файлов"
            }
            emit(str)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    init {
        viewModelScope.launch {
            userProfile.filterNotNull().collect {
                sessions
                    .firstOrNull { it.isNotEmpty() }
                    ?.maxBy { session -> session.updatedAt }
                    ?.let { session ->
                        selectSession(session)
                    }
            }
        }
        // Загружаем конфигурацию MCP серверов
        loadMcpServers()
        // Индексируем документы из папки documents/
        syncDocuments()
    }
    
    private fun syncDocuments() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val config = configRepository.getConfig()
            val (docsResult, projectResult) = documentIndexer.syncAll(config.assistedProject)
            if (!docsResult.folderNotFound) {
                println(
                    "[RAG] Документы: проиндексировано=${docsResult.indexed}, " +
                    "пропущено=${docsResult.skipped}, ошибок=${docsResult.failed}, удалено=${docsResult.removed}"
                )
            }
            projectResult?.let { r ->
                if (!r.folderNotFound) {
                    println(
                        "[RAG] Проект: проиндексировано=${r.indexed}, " +
                        "пропущено=${r.skipped}, ошибок=${r.failed}, удалено=${r.removed}"
                    )
                } else {
                    println("[RAG] Папка проекта не найдена: ${config.assistedProject}")
                }
            }
        }
    }

    private fun loadMcpServers() {
        viewModelScope.launch {
            try {
                val config = configRepository.getConfig()
                mcpManager.startServers(config.mcpServers)
                val servers = config.mcpServers.associateWith { true }
                _mcpServers.value = servers.mapKeys { it.key.name }
                loadLocalModels(config.localLlms)
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки конфигурации MCP серверов: ${e.message}"
            }
        }
    }

    private fun loadLocalModels(configs: List<LocalLlmConfig>) {
        if (configs.isEmpty()) return
        viewModelScope.launch {
            val models = configs.flatMap { config ->
                openRouterClient.fetchLocalModels(config)
            }
            _localModels.value = models
        }
    }

    fun selectSession(session: Session) {
        viewModelScope.launch {
            try {
                currentSessionId.value = session.id
                _settings.value = session.settings
                currentBranchId.value = branchRepository.getBranchesBySessionId(session.id).first().firstOrNull()?.id
            } catch (e: Exception) {
                _error.value = "Ошибка выбора сессии: ${e.message}"
            }
        }
    }

    fun createNewSession(title: String) {
        viewModelScope.launch {
            try {
                val profile = userProfile.value ?: throw IllegalStateException("Сначала создайте пользователя")
                val newSession = sessionRepository.createSession(title, profile.id, _settings.value)
                currentSessionId.value = newSession.id
                currentBranchId.value = null
            } catch (e: Exception) {
                _error.value = "Ошибка создания сессии: ${e.message}"
            }
        }
    }

    fun updateSettings(newSettings: SessionSettings) {
        _settings.value = newSettings
        currentSession.value?.let { session ->
            viewModelScope.launch {
                try {
                    val updatedSession = session.copy(settings = newSettings)
                    sessionRepository.updateSession(updatedSession)
                } catch (e: Exception) {
                    _error.value = "Ошибка обновления настроек: ${e.message}"
                }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(sessionId)
                messageRepository.deleteMessagesBySessionId(sessionId)
                paramsRepository.deleteParams(sessionId)
                summaryRepository.deleteSummary(sessionId)
                branchRepository.clearBranches(sessionId)
                sessionMemoryRepository.clear(sessionId)
                invariantsRepository.clear(sessionId)
                if (currentSessionId.value == sessionId) {
                    currentSessionId.value = null
                }
            } catch (e: Exception) {
                _error.value = "Ошибка удаления сессии: ${e.message}"
            }
        }
    }


    fun onParamsChanged(params: Params) {
        viewModelScope.launch {
            currentSessionId.value?.let { paramsRepository.updateParams(it, params) }
        }
    }


fun clearCurrentSession() {
        viewModelScope.launch {
            try {
                currentSessionId.value?.let { sessionId ->
                    messageRepository.deleteMessagesBySessionId(sessionId)
                    summaryRepository.deleteSummary(sessionId)
                    sessionRepository.clearTokenInfo(sessionId)
                    branchRepository.clearBranches(sessionId)
                    taskRepository.deleteTask(sessionId)
                }
            } catch (e: Exception) {
                _error.value = "Ошибка очистки сессии: ${e.message}"
            }
        }
    }

    /**
     * Извлекает состояние задачи из ответа LLM
     * @return состояние задачи
     */
    private fun extractTaskContextFromResponse(response: String): TaskContext? {
        // Ищем строку с этапом в ответе
        val progress = response.substringAfter("<task_progress>", "").substringBefore("</task_progress>", "")
        return try {
            Json.decodeFromString<TaskContext>(progress)
        } catch (_: Exception) { null }
    }

    /**
     * Извлекает состояние задачи из ответа LLM
     * @return ответ без состояния задачи
     */
    private fun validateInvariants(response: String): ValidateInvariantsResult? {
        if (invariants.value.isEmpty()) return null
        return invariants.value.filterNot {
            it.check(response)
        }.takeIf { it.isNotEmpty() }?.let {
            ValidateInvariantsResult.Fail(it)
        } ?: ValidateInvariantsResult.Success
    }

    fun sendMessage(content: String) {
        viewModelScope.launch {
            val session = currentSession.value ?: run {
                _error.value = "Сперва создайте сессию"
                return@launch
            }
            val branchId = currentBranchId.value ?: run {
                if (sessionParams.value.manageContextStrategy == ContextMinimizationStrategy.BRANCHING && currentBranchId.value == null) {
                    _error.value = "Необходимо создать бранч"
                    return@launch
                } else {
                    null
                }
            }

            try {
                isLoading.value += LoadingData(session.id, branchId)
                _error.value = null

                // Создаем и сохраняем сообщение пользователя
                val userMessage = Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = session.id,
                    role = MessageRole.USER,
                    content = content,
                    timestamp = Clock.System.now(),
                    usedToken = null,
                    cost = null,
                    validationResult = null
                )
                if (branchId != null) {
                    branchRepository.insertMessageBranch(branchId, userMessage)
                } else {
                    messageRepository.addMessage(userMessage)
                }
                val currentMessages = messages.value.first.addSystemMetaData(query = content)

                // Получаем ответ от LLM с учетом стратегии управления контекстом
                val responseContent = openRouterClient.sendMessage(
                    currentMessages + userMessage,
                    _settings.value,
                    mcpManager.getTools(),
                    virtualToolHandler = { name, args ->
                        when (name) {
                            "schedule" -> schedulerManager.handleScheduleCall(args, session.id, _settings.value)
                            "cancel_job" -> {
                                val jobId = args["job_id"]?.jsonPrimitive?.content
                                if (jobId == null) "Error: job_id is required"
                                else if (schedulerManager.cancelJob(jobId)) "Job $jobId cancelled successfully."
                                else "Job $jobId not found."
                            }
                            else -> null
                        }
                    }
                )

                // Извлекаем этап задачи из ответа LLM
                val taskContext = extractTaskContextFromResponse(responseContent.content)

                val validationResult = validateInvariants(responseContent.content)

                taskContext?.let {
                    val currentContext = taskContextState.value
                    if (currentContext != null) {
                        try {
                            //currentContext.checkAvailableStep(it.taskState)
                            taskRepository.insertTask(session.id, it)
                        } catch (e: IllegalArgumentException) {
                            _error.value = "Ошибка перехода между состояниями: ${e.message}. Попробуйте снова"
                            if (branchId != null) {
                                branchRepository.removeMessages(branchId, userMessage.id)
                            } else {
                                messageRepository.removeMessages(listOf(userMessage.id))
                            }
                        }
                    } else {
                        taskRepository.insertTask(session.id, it)
                    }
                }

                val cost = responseContent.cost.toCostString()
                val updatedSession = session.copy(totalToken = responseContent.totalUsedToken)
                sessionRepository.updateSession(updatedSession)

                // Сохраняем ответ LLM в RAG-память треда текущей сессии
                session.id.let { sid ->
                    ragQueryMemories[sid]?.updateLastResponse(responseContent.content)
                }

                // Создаем и сохраняем сообщение ассистента
                val assistantMessage = Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = session.id,
                    role = MessageRole.ASSISTANT,
                    content = responseContent.content,
                    timestamp = Clock.System.now(),
                    usedToken = responseContent.outputUsedToken,
                    cost = cost,
                    validationResult = validationResult
                )
                if (branchId != null) {
                    branchRepository.insertMessageBranch(branchId, assistantMessage)
                } else {
                    messageRepository.addMessage(assistantMessage)
                }
                minContext(session)
            } catch (e: Exception) {
                _error.value = "Ошибка отправки сообщения: ${e.message}"
            } finally {
                isLoading.value -= LoadingData(session.id, branchId)
            }
        }
    }

    private suspend fun minContext(session: Session) {
        when (sessionParams.value.manageContextStrategy) {
            ContextMinimizationStrategy.NO_STRATEGY -> {}
            ContextMinimizationStrategy.BRANCHING -> {}
            ContextMinimizationStrategy.SUMMARY -> {
                summarizeMessages(session)
            }

            ContextMinimizationStrategy.SLIDING -> {
                slidingMessages(session)
            }

            ContextMinimizationStrategy.STICKY_FACTS -> {
                factMessages(session)
            }
        }
    }

    private suspend fun summarizeMessages(session: Session) {
        val messages = messageRepository.getMessagesFlowBySessionId(session.id).first()
        if (messages.count() >= 20) {
            val messagesToSummary = messages.dropLast(10)
            val summary = summaryRepository.getSummaryForSession(session.id).first()
                ?.copy(role = MessageRole.ASSISTANT)
            val summaryMessage = Message(
                id = "",
                sessionId = session.id,
                role = MessageRole.SYSTEM,
                content = "Please summarize the following conversation concisely. Focus on the main points and decisions made. Use the language of correspondence",
                timestamp = Clock.System.now(),
                usedToken = null,
                cost = null,
                validationResult = null
            )
            val messages = listOfNotNull(summaryMessage, summary, *messagesToSummary.toTypedArray())
            try {
                val responseContent = openRouterClient.summarizeMessages(
                    settings.value,
                    messages.addSystemMetaData()
                )
                val cost = responseContent.cost.toCostString()
                val newSummary = Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = session.id,
                    role = MessageRole.ASSISTANT,
                    content = responseContent.content,
                    timestamp = Clock.System.now(),
                    usedToken = responseContent.outputUsedToken,
                    cost = cost,
                    validationResult = null
                )

                summaryRepository.insertSummary(newSummary)
                messageRepository.removeMessages(messagesToSummary.map { it.id })

                val updatedSession = session.copy(totalToken = responseContent.totalUsedToken)
                sessionRepository.updateSession(updatedSession)
            } catch (e: Exception) {
                _error.value = "Ошибка объединения сообщений: ${e.message}"
            }

        }
    }

    private suspend fun slidingMessages(session: Session) {
        val messages = messageRepository.getMessagesFlowBySessionId(session.id).first()
        // Если сообщений больше 10, удаляем старые
        if (messages.size > 10) {
            val messagesToRemove = messages.take(messages.size - 10)
            messageRepository.removeMessages(messagesToRemove.map { it.id })
        }
    }

    private suspend fun factMessages(session: Session) {
        // Получаем текущие факты
        val facts = factRepository.getFactsBySessionId(session.id).first()
        
        // Получаем последние N сообщений (например, 10)
        val messages = messageRepository.getMessagesFlowBySessionId(session.id).first()
        val recentMessages = messages.takeLast(10)

        val storagePrompt = getStoragePrompt()
        // Создаем промпт для извлечения фактов
        val extractionPrompt = """
            Пожалуйста, извлеките важные факты из приведенного ниже разговора и обновите существующие факты.
            Существующие факты:
            ${facts.joinToString("\n") { "${it.key}: ${it.value}" }}
            
            Недавний разговор:
            ${recentMessages.joinToString("\n") { "[${it.role}] ${it.content}" }}
            
            Пожалуйста, предоставьте обновленную информацию в формате:
            ключ1: значение1
            ключ2: значение2
            ...
            
            Указывайте только те факты, которые важны для контекста, такие как цели, ограничения, предпочтения, решения и соглашения.
            Если какой-либо факт больше не имеет значения, опустите его в ответе.
            Пишите факты на языке переписки.
        """.trimIndent()
        val resPrompt = buildString {
            if (storagePrompt != null) {
                append(storagePrompt.trim())
                append("\n")
            }
            append(extractionPrompt)
        }
        try {
            // Отправляем запрос для извлечения фактов
            val extractedFactsContent = openRouterClient.sendSystemMessage(resPrompt, settings.value)
            
            // Парсим полученные факты и обновляем их в базе данных
            val extractedFacts = parseFacts(extractedFactsContent.content)
            for (fact in extractedFacts) {
                factRepository.insertFact(Fact(session.id, fact.key, fact.value))
            }
            messageRepository.removeMessages((messages - recentMessages.toSet()).map { it.id } )
        } catch (e: Exception) {
            _error.value = "Ошибка извлечения фактов: ${e.message}"
        }
    }

    private fun parseFacts(content: String): List<Fact> {
        val facts = mutableListOf<Fact>()
        val lines = content.lines()

        for (line in lines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0 && colonIndex < line.length - 1) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    facts.add(Fact("", key, value)) // sessionId будет установлен позже
                }
            }
        }

        return facts
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Добавляет факт в рабочую память сессии
     */
    fun saveSessionData(data: String) {
        viewModelScope.launch {
            try {
                currentSessionId.value?.let { sessionId ->
                    val data = SessionMemoryData(sessionId, data)
                    sessionMemoryRepository.insert(data)
                } ?: run {
                    _error.value = "Нет активной сессии для сохранения факта"
                }
            } catch (e: Exception) {
                _error.value = "Ошибка сохранения факта: ${e.message}"
            }
        }
    }

    /**
     * Удаляет факт из рабочей памяти сессии
     */
    fun removeFact(key: String) {
        viewModelScope.launch {
            try {
                currentSessionId.value?.let { sessionId ->
                    sessionMemoryRepository.delete(sessionId, key)
                } ?: run {
                    _error.value = "Нет активной сессии для удаления факта"
                }
            } catch (e: Exception) {
                _error.value = "Ошибка удаления факта: ${e.message}"
            }
        }
    }

    /**
     * Добавляет инвариант в рабочую память сессии
     */
    fun saveInvariant(invariant: Invariant) {
        viewModelScope.launch {
            try {
                currentSessionId.value?.let { sessionId ->
                    invariantsRepository.insert(sessionId, invariant)
                } ?: run {
                    _error.value = "Нет активной сессии для сохранения инварианта"
                }
            } catch (e: Exception) {
                _error.value = "Ошибка сохранения инварианта: ${e.message}"
            }
        }
    }

    /**
     * Удаляет факт из рабочей памяти сессии
     */
    fun removeInvariant(classType: String) {
        viewModelScope.launch {
            try {
                currentSessionId.value?.let { sessionId ->
                    invariantsRepository.delete(sessionId, classType)
                } ?: run {
                    _error.value = "Нет активной сессии для удаления инварианта"
                }
            } catch (e: Exception) {
                _error.value = "Ошибка удаления инварианта: ${e.message}"
            }
        }
    }

    fun saveUserProfile(profile: UserProfile) {
        viewModelScope.launch {
            try {
                userProfileRepository.saveUserProfile(profile)
            } catch (e: Exception) {
                _error.value = "Ошибка сохранения профиля: ${e.message}"
            }
        }
    }

    // Функции для работы с ветвями (BRANCHING strategy)
    fun createBranch(name: String) {
        viewModelScope.launch {
            currentSessionId.value?.let { sessionId ->
                try {
                    val newBranch = Branch(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        name = name,
                        createdAt = Clock.System.now()
                    )
                    branchRepository.insertBranch(newBranch)
                    currentBranchId.value = newBranch.id
                } catch (e: Exception) {
                    _error.value = "Ошибка создания ветви: ${e.message}"
                }
            }
        }
    }

    fun switchToBranch(branchId: String?) {
        viewModelScope.launch {
            currentBranchId.value = branchId
        }
    }

    fun deleteBranch(branchId: String) {
        viewModelScope.launch {
            try {
                branchRepository.deleteBranch(branchId)
            } catch (e: Exception) {
                _error.value = "Ошибка удаления ветви: ${e.message}"
            }
        }
    }

    suspend fun List<Message>.addSystemMetaData(query: String? = null): List<Message> {
        val useRag = _settings.value.ragMode || _settings.value.developerMode
        val ragContext = if (useRag && query != null) buildRagContext(query) else null
        val storageContent = getStoragePrompt()

        val combined = listOfNotNull(storageContent, ragContext).joinToString("\n")
        if (combined.isBlank()) return this

        // Ищем существующее системное сообщение
        val existingSystemMessageIndex = indexOfFirst { it.role == MessageRole.SYSTEM }

        return if (existingSystemMessageIndex != -1) {
            // Если системное сообщение существует, обновляем его
            val updatedList = toMutableList()
            val existingSystemMessage = updatedList[existingSystemMessageIndex]
            val updatedContent = buildString {
                append(existingSystemMessage.content)
                if (!existingSystemMessage.content.endsWith("\n") && existingSystemMessage.content.isNotEmpty()) {
                    append("\n")
                }
                append(combined)
            }
            updatedList[existingSystemMessageIndex] = existingSystemMessage.copy(
                content = updatedContent.trim()
            )
            updatedList.toList()
        } else {
            val systemMessage = Message(
                id = UUID.randomUUID().toString(),
                sessionId = "",
                role = MessageRole.SYSTEM,
                content = combined.trim(),
                timestamp = Clock.System.now(),
                usedToken = null,
                cost = null,
                validationResult = null
            )
            listOf(systemMessage) + this
        }
    }

    private fun getTasksPrompt(): String {
        return """
# Блок task_progress:
В начале сообщения должен присутствовать json в окруженный <task_progress>...</task_progress>.
В нем описываем состояние выполнения задачи. Данный блок должен быть **ОБЯЗАТЕЛЬНО** в единственном виде в сообщении.
Любая задача должна быть разбита на этапы.

## Структура json объекта внутри task_progress:
**taskName** - содержит название текущего этапа выполнения задачи. Навание должно быть понятное человеку, и отражать производимую работу. Тип - Строка. Например: "Проектирование плана." или "Реализация задания."
**taskState** - содержит текущее состояние выполнения задачи. Возможные значения: PLANING|EXECUTING|VALIDATE|DONE. Тип - Строка.
**step** - содержит номер текущего этапа выполнения. Тип - целое число.
**totalSteps** - содержит общее количество этапов выполнения. Тип - целое число.

## Детальное описание параметра taskState
Состояния указаны в соответствии их последовательности. Переход из одного состояния должен быть явно быть одобрен пользователем
**PLANING(Планирование)**.
    1. На данном этапе проектируется план выполнения. На данном этапе мы не решаем задачу.
    2. Исключительно договариваемся о плане. Задаются дополнительные вопросы в случае их возникновения. 
    3. Утверждение плана происходит **ИСКЛЮЧИТЕЛЬНО** после того как решены все вопросы.
**EXECUTING(Реализация)**
    1. На данном этапе предлагаем варианты решения задачи.
**VALIDATE(Валидация)**
    1. На данном этапе происходит ревью, написание тестов к коду(если был код). 
    2. На данном этапе необходимо переспросить все ли устраивает пользователя
**DONE(Задача выполнена)**

## Возможные переходы между состояниями
**Этапы пропускать ЗАПРЕЩЕНО**
**В одном сообщении должен быть не более ОДНОГО этапа**

Из состояния PLANING возможен переход в EXECUTING
Из состояния EXECUTING возможен переход в PLANING, VALIDATE
Из состояния VALIDATE возможен переход в EXECUTING, DONE
Состояние DONE является конечным.

## Правила
Необходимо следовать утвержденной схеме переходов между состояниями.
Нельзя описывать несколько состояний за одно сообщение.
Нельзя перепрыгивать несколько состояний за один раз.
У каждого шага(параметр step) имеется свое уникальное taskName  
        """.trimIndent()
    }

    private suspend fun getRagRetriever(): com.sbfs.ai.document.RagRetriever {
        return ragRetriever ?: run {
            val ragConfig = configRepository.getConfig().rag
            com.sbfs.ai.document.RagRetriever(documentRepository, ragConfig).also { ragRetriever = it }
        }
    }

    private suspend fun buildRagContext(query: String): String? {
        val ragConfig = configRepository.getConfig().rag
        val enhanced = _settings.value.enhancedRag
        val topK = if (enhanced) (ragConfig.tokenBudget / 400).coerceIn(3, 15) else 5
        val retriever = getRagRetriever()

        // Эмбеддинг текущего запроса — нужен для сравнения с историей
        val queryEmbedding = retriever.embedQuery(query)

        val sessionId = currentSessionId.value
        val memory = if (sessionId != null) ragQueryMemories.getOrPut(sessionId) {
            com.sbfs.ai.document.RagQueryMemory(ragConfig.queryMemoryLimit)
        } else null

        // Определяем эффективный поисковый запрос через LLM-синтез
        val (effectiveQuery, effectiveEmbedding) = resolveEffectiveQuery(
            query, queryEmbedding, memory, ragConfig.queryMemoryThreshold, retriever
        )

        val scored = retriever.retrieve(
            query = effectiveQuery,
            topK = topK,
            useEnhanced = enhanced,
            queryEmbedding = effectiveEmbedding,
        )

        if (scored.isEmpty()) return null

        // Token budget: набираем чанки пока не исчерпан бюджет
        val selected = mutableListOf<com.sbfs.ai.document.ScoredChunk>()
        var usedTokens = 0
        for (sc in scored) {
            if (usedTokens + sc.chunk.tokenCount > ragConfig.tokenBudget) break
            selected.add(sc)
            usedTokens += sc.chunk.tokenCount
        }
        if (selected.isEmpty()) return null

        // Запоминаем текущий запрос в тред сессии (эмбеддинг нужен для будущих сравнений)
        if (queryEmbedding != null && memory != null) memory.addToThread(query, queryEmbedding)

        // Группируем чанки по документу и сортируем внутри по позиции — лучше когерентность
        val grouped = selected.groupBy { it.chunk.documentId }
        val docMap  = documentRepository.getAllDocuments().associateBy { it.id }

        return buildString {
            append("## База знаний (релевантные фрагменты)\n\n")
            var i = 1
            grouped.values.forEach { chunks ->
                val doc = docMap[chunks.first().chunk.documentId]
                val sourceName = doc?.let { it.title.ifBlank { it.filename } }
                    ?: "Неизвестный источник"
                chunks.sortedBy { it.chunk.chunkIndex }.forEach { sc ->
                    val scoreStr = "%.2f".format(sc.score)
                    append("### Фрагмент $i [источник: «$sourceName», релевантность: $scoreStr]\n")
                    append(sc.chunk.content.trim())
                    append("\n\n")
                    i++
                }
            }
        }
    }

    /**
     * Определяет эффективный поисковый запрос с учётом истории треда сессии.
     *
     * Алгоритм:
     * 1. Если история пуста или Ollama недоступна → используем запрос as-is.
     * 2. Если есть похожие записи (cosine >= threshold) → спрашиваем LLM.
     *    - REFINE: синтезированный запрос + тред сохраняется
     *    - NEGATE: скорректированный запрос + тред сбрасывается
     *    - NEW или null (ошибка LLM): текущий запрос + тред сбрасывается
     * 3. Если нет похожих → новая тема, тред сбрасывается.
     *
     * Возвращает пару (effectiveQuery, effectiveEmbedding).
     * effectiveEmbedding == null означает «пересчитай эмбеддинг в retrieve».
     */
    private suspend fun resolveEffectiveQuery(
        query: String,
        queryEmbedding: FloatArray?,
        memory: com.sbfs.ai.document.RagQueryMemory?,
        threshold: Float,
        retriever: com.sbfs.ai.document.RagRetriever,
    ): Pair<String, FloatArray?> {
        if (memory == null || !memory.hasHistory() || queryEmbedding == null) {
            return query to queryEmbedding
        }

        val related = memory.findRelated(queryEmbedding, threshold)
        if (related.isEmpty()) {
            memory.clearThread()
            return query to queryEmbedding
        }

        val history = memory.getThreadHistory()
        val synthesis = retriever.synthesizeRagQuery(query, history)

        return when (synthesis?.type) {
            com.sbfs.ai.document.SynthesisType.REFINE -> {
                val refined = synthesis.query?.takeIf { it.isNotBlank() } ?: query
                println("[RAG] REFINE → \"$refined\"")
                refined to null  // эмбеддинг пересчитается в retrieve
            }
            com.sbfs.ai.document.SynthesisType.NEGATE -> {
                val corrected = synthesis.query?.takeIf { it.isNotBlank() } ?: query
                println("[RAG] NEGATE → \"$corrected\"")
                memory.clearThread()
                corrected to null
            }
            else -> {
                // NEW или ошибка парсинга — начинаем с чистого листа
                println("[RAG] NEW topic")
                memory.clearThread()
                query to queryEmbedding
            }
        }
    }

    private suspend fun getStoragePrompt() : String? {
        val sessionId = currentSessionId.value ?: return null
        val userProfile = userProfileRepository.getUserProfile().first()
        val sessionMemory = sessionMemoryRepository.getMemoryBySessionId(sessionId).first()
        val invariants = invariantsRepository.getInvariantsBySessionId(sessionId).first()
        val ragMode = _settings.value.ragMode
        val developerMode = _settings.value.developerMode
        val assistedProject = if (developerMode) configRepository.getConfig().assistedProject else null

        // Если нет данных для добавления, возвращаем null
        if ((userProfile == null || userProfile.isDefault()) && sessionMemory.isEmpty() && invariants.isEmpty() && !ragMode && !developerMode) {
            return null
        }

        // Создаем контент для системного сообщения
        return buildString {
            if (developerMode) {
                append("#Ты — ассистент разработчика.\n")
                if (assistedProject != null) {
                    append("#Путь до проекта: $assistedProject. ")
                    append("При вызове инструментов используй этот путь как project_path.\n\n")
                }
                append(
                    "Если для ответа необходимы инструменты (tools) — вызови их в первую очередь. " +
                    "Используй фрагменты из базы знаний как дополнительный контекст, если они релевантны. " +
                    "При опоре на базу знаний указывай источник в формате [источник: «<название>»]. " +
                    "Можешь также опираться на свои знания.\n"
                )
            } else if (ragMode) {
                append(
                    "Если для ответа доступны инструменты (tools) — вызови их в первую очередь. " +
                    "В остальных случаях опирайся прежде всего на предоставленные фрагменты из базы знаний. " +
                    "При использовании фрагментов ОБЯЗАТЕЛЬНО указывай источник в формате: " +
                    "[источник: «<название>»] — используй точное значение поля «источник» из заголовка фрагмента. " +
                    "Если фрагменты из базы знаний нерелевантны вопросу — отвечай на основе своих знаний.\n"
                )
            }
            userProfile?.takeIf { it.isDefault().not() }?.let { p ->
                append("Профиль пользователя:\n")
                p.preferences.toList().joinToString { "${it.first}: ${it.second}" }
                    .takeIf { it.isNotEmpty() }
                    ?.let { append("Предпочтения:\n$it\n") }
                p.limitationsForLLM.joinToString(separator = ";")
                    .takeIf { it.isNotEmpty() }
                    ?.let { append("Нельзя ни в коем случае делать: $it\n") }
                p.additionalInfo
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { append("Дополнительная информация: $it\n") }
            }

            if (sessionMemory.isNotEmpty()) {
                append("Session Memory:\n")
                sessionMemory.forEach { data ->
                    append("- ${data.data}\n")
                }
            }
            if (invariants.isNotEmpty()) {
                append("Invariants:\n")
                invariants.forEach { invariant ->
                    append("- ${invariant.description}\n")
                }
                append("НАРУШЕНИЕ ЛЮБОГО ИНВАРИАНТА ЗАПРЕЩЕНО\n")
            }
        }.takeIf { it.isNotEmpty() }
    }

    fun mergeBranches(sourceBranchId: String, targetBranchId: String) {
        viewModelScope.launch {
            try {
                val sourceBranch = branchRepository.getBranchById(sourceBranchId).first()!!
                val targetBranch = branchRepository.getBranchById(targetBranchId).first()!!
                val systemMessage = systemMessage.first()
                val generalMessages = messageRepository.getMessagesFlowBySessionId(sourceBranch.sessionId).first()
                val sourceBranchMessages =
                    branchRepository.getMessageBranches(sourceBranch.sessionId, sourceBranch.id).first()
                val targetBranchMessages =
                    branchRepository.getMessageBranches(targetBranch.sessionId, targetBranch.id).first()

                val profilePrompt = getStoragePrompt()
                val newSystemPrompt = buildString {
                    profilePrompt?.let {
                        append(it)
                        append("\n")
                    }
                    systemMessage?.let {
                        append("Previous system message:\n")
                        append(it.content)
                        append("\n")
                    }
                    append("Please merge history of two branches.\n")
                    append("The messages are in this order: System(this).\n")
                    generalMessages.count().takeIf { it > 0 }
                        ?.let { append("After $it messages from the general history\n") }
                    sourceBranchMessages.count().takeIf { it > 0 }
                        ?.let { append("After $it messages from the first branch\n") }
                    targetBranchMessages.count().takeIf { it > 0 }?.let { append("After $it from the second branch\n") }
                    append("Please write a summary exclusively about merging branches")
                }
                val newSystemMessage = Message(
                    id = "",
                    sessionId = sourceBranch.sessionId,
                    role = MessageRole.SYSTEM,
                    content = newSystemPrompt,
                    timestamp = Clock.System.now(),
                    usedToken = null,
                    cost = null,
                    validationResult = null
                )

                val messages = listOfNotNull(
                    newSystemMessage,
                    *generalMessages.toTypedArray(),
                    *sourceBranchMessages.toTypedArray(),
                    *targetBranchMessages.toTypedArray()
                )

                try {
                    val responseContent = openRouterClient.summarizeMessages(
                        settings.value,
                        messages
                    )
                    val cost = responseContent.cost.toCostString()
                    val newSummary = Message(
                        id = UUID.randomUUID().toString(),
                        sessionId = sourceBranch.sessionId,
                        role = MessageRole.ASSISTANT,
                        content = responseContent.content,
                        timestamp = Clock.System.now(),
                        usedToken = responseContent.outputUsedToken,
                        cost = cost,
                        validationResult = null
                    )
                    branchRepository.deleteBranch(targetBranch.id)
                    branchRepository.removeMessages(sourceBranch.id)
                    branchRepository.insertMessageBranch(sourceBranch.id, newSummary)
                    switchToBranch(sourceBranch.id)
                } catch (e: Exception) {
                    _error.value = "Ошибка объединения сообщений: ${e.message}"
                }
            } catch (e: Exception) {
                _error.value = "Ошибка слияния ветвей: ${e.message}"
            }
        }
    }

    fun createNewUser(name: String) {
        userProfileRepository.removeCurrent()
        userProfileRepository.saveUserProfile(
            UserProfile(
                id = UUID.randomUUID().toString(),
                name = name
            )
        )
    }

    fun setProfile(profile: UserProfile) {
        userProfileRepository.removeCurrent()
        userProfileRepository.saveUserProfile(profile.copy(isCurrent = true))
    }
    
    /**
     * Переключает состояние MCP сервера (включен/выключен)
     */
    fun toggleMcpServer(name: String, isEnabled: Boolean) {
        viewModelScope.launch {
            if (isEnabled) {
                val config = configRepository.getConfig().mcpServers.find { it.name == name } ?: return@launch
                mcpManager.addServer(config)
            } else {
                mcpManager.removeServer(name)

            }
            val currentServers = _mcpServers.value.toMutableMap()
            currentServers[name] = isEnabled
            _mcpServers.value = currentServers
        }
    }

    private fun Double.toCostString(): String {
        return when(this) {
            0.0 -> "0"
            else -> "%.10f".format(this).dropLastWhile { it == '0' }
        }

    }

}
