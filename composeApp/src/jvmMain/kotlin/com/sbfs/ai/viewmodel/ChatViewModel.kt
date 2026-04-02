package com.sbfs.ai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbfs.ai.OpenRouterClient
import com.sbfs.ai.data.*
import com.sbfs.ai.db.AiChallengeDb
import com.sbfs.ai.db.DatabaseDriverFactory
import com.sbfs.ai.repository.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
    private val openRouterClient = OpenRouterClient()


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
    val models = flow {
        val offlineModels = modelRepository.getModels()
        if (offlineModels.isNotEmpty()) {
            emit(offlineModels)
        } else {
            emit(openRouterClient.getAvailableModels())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

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
                val currentMessages = messages.value.first.addSystemMetaData()
                if (branchId != null) {
                    branchRepository.insertMessageBranch(branchId, userMessage)
                } else {
                    messageRepository.addMessage(userMessage)
                }

                // Получаем ответ от LLM с учетом стратегии управления контекстом
                val responseContent = openRouterClient.sendMessage(currentMessages + userMessage, _settings.value)

                // Извлекаем этап задачи из ответа LLM
                val taskContext = extractTaskContextFromResponse(responseContent.content)

                val validationResult = validateInvariants(responseContent.content)

                taskContext?.let {
                    val currentContext = taskContextState.value
                    if (currentContext != null) {
                        try {
                            currentContext.checkAvailableStep(it.taskState)
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

                val cost = "%.10f".format(responseContent.cost).dropLastWhile { it == '0' }
                val updatedSession = session.copy(totalToken = responseContent.totalUsedToken)
                sessionRepository.updateSession(updatedSession)

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
                val cost = "%.10f".format(responseContent.cost).dropLastWhile { it == '0' }
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

    suspend fun List<Message>.addSystemMetaData(): List<Message> {
        val storageContent = getStoragePrompt() ?: return this
        val taskContent = getTasksPrompt()

        // Ищем существующее системное сообщение
        val existingSystemMessageIndex = indexOfFirst { it.role == MessageRole.SYSTEM }

        return if (existingSystemMessageIndex != -1) {
            // Если системное сообщение существует, обновляем его
            val updatedList = toMutableList()
            val existingSystemMessage = updatedList[existingSystemMessageIndex]
            val updatedContent = buildString {
                append(taskContent + "\n")
                append(existingSystemMessage.content)
                if (!existingSystemMessage.content.endsWith("\n") && existingSystemMessage.content.isNotEmpty()) {
                    append("\n")
                }
                append(storageContent)
            }

            updatedList[existingSystemMessageIndex] = existingSystemMessage.copy(
                content = updatedContent.trim()
            )
            updatedList.toList()
        } else {
            val systemContent = buildString {
                append(taskContent + "\n")
                append(storageContent)
            }.trim()
            // Если системного сообщения нет, создаем новое
            val systemMessage = Message(
                id = UUID.randomUUID().toString(),
                sessionId = "",
                role = MessageRole.SYSTEM,
                content = systemContent,
                timestamp = Clock.System.now(),
                usedToken = null,
                cost = null,
                validationResult = null
            )

            // Добавляем системное сообщение в начало списка
            listOf(systemMessage) + this
        }
    }

    //Необходимо составить техническое задание для фичи Поиск. Задача реализуется для мобильных устройств.  Необходимо описать поведение поля текстового ввода. Что через каждые 5 сек после ввода мы идем на бекенд получаем ответ и отрисовываем контент
    //Поле должно иметь кнопку очистки.
    //Во время ожидания/загрузки на экране, по середине появляется лоадер.
    //Быстрый последовательный ввод не стоит обрабатывать, так как запрос мы сделаем спустя 5 секунд, после того как пользователь закончит ввод текста.
    //
    //Вывод результатов происходит в виде вертикального списка. Описывать как, что и где будет расположено на UI не нужно, так как будет приложен макет.
    //Подсказок также не будет. Сохранении истории тоже нет.

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

    private suspend fun getStoragePrompt() : String? {
        val sessionId = currentSessionId.value ?: return null
        val userProfile = userProfileRepository.getUserProfile().first()
        val sessionMemory = sessionMemoryRepository.getMemoryBySessionId(sessionId).first()
        val invariants = invariantsRepository.getInvariantsBySessionId(sessionId).first()

        // Если нет данных для добавления, возвращаем исходный список
        if (userProfile == null && sessionMemory.isEmpty() && invariants.isEmpty()) {
            return null
        }

        // Создаем контент для системного сообщения
        return buildString {
            userProfile?.let { p ->
                append("Профиль пользователя:\n")
                p.preferences.toList().joinToString { "${it.first}: ${it.second}" }
                    .takeIf { it.isNotEmpty() }
                    ?.let { append("Предпочтения:\n$it\n") }
                p.limitationsForLLM.joinToString(separator = ";")
                    .takeIf { it.isNotEmpty() }
                    ?.let { append("Нельзя ни в коем случае делать: $it\n") }
                p.additionalInfo
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { append("До информация: $it\n") }
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
                    val cost = "%.10f".format(responseContent.cost).dropLastWhile { it == '0' }
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
}
