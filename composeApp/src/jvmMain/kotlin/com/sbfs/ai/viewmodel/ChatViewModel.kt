package com.sbfs.ai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbfs.ai.OpenRouterClient
import com.sbfs.ai.data.*
import com.sbfs.ai.db.AiChallengeDb
import com.sbfs.ai.db.DatabaseDriverFactory
import com.sbfs.ai.repository.MessageRepository
import com.sbfs.ai.repository.ModelRepository
import com.sbfs.ai.repository.ParamsRepository
import com.sbfs.ai.repository.SessionRepository
import com.sbfs.ai.repository.SummaryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    private val openRouterClient = OpenRouterClient()
    
    val sessions = sessionRepository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    
    private val currentSessionId = MutableStateFlow<String?>(null)
    val currentSession: StateFlow<Session?> = combine(
        sessions,
        currentSessionId
    ) { s, id ->
        s.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    private val isLoading = MutableStateFlow<Set<String>>(hashSetOf())

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


    val messages: StateFlow<Pair<List<Message>, Boolean>> = currentSession.flatMapLatest { session ->
        if (session != null) {
            combine(
                summaryRepository.getSummaryForSession(session.id),
                messageRepository.getMessagesFlowBySessionId(session.id),
                isLoading.map { it.contains(session.id) }
            ) { summaryMessage, messages, isLoading ->
                (listOfNotNull(summaryMessage) + messages) to isLoading
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
            sessions
                .firstOrNull { it.isNotEmpty() }
                ?.maxBy { session -> session.updatedAt }
                ?.let { session ->
                    selectSession(session)
                }
        }
    }
    
    fun selectSession(session: Session) {
        viewModelScope.launch {
            try {
                currentSessionId.value = session.id
                _settings.value = session.settings
            } catch (e: Exception) {
                _error.value = "Ошибка выбора сессии: ${e.message}"
            }
        }
    }
    
    fun createNewSession(title: String) {
        viewModelScope.launch {
            try {
                val newSession = sessionRepository.createSession(title, _settings.value)
                currentSessionId.value = newSession.id
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
                }
            } catch (e: Exception) {
                _error.value = "Ошибка очистки сессии: ${e.message}"
            }
        }
    }
    
    fun sendMessage(content: String) {
        viewModelScope.launch {
            val session = currentSession.value ?: run {
                _error.value = "Сперва создайте сессию"
                return@launch
            }
            try {
                isLoading.value += session.id
                _error.value = null
                
                // Создаем и сохраняем сообщение пользователя
                val userMessage = Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = session.id,
                    role = MessageRole.USER,
                    content = content,
                    timestamp = Clock.System.now(),
                    usedToken = null,
                    cost = null
                )

                val currentMessages = messages.value.first + userMessage

                messageRepository.addMessage(userMessage)


                // Получаем ответ от LLM
                val responseContent = openRouterClient.sendMessage(currentMessages + userMessage, _settings.value)
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
                )
                
                messageRepository.addMessage(assistantMessage)

                if (sessionParams.value.isSummaryEnabled) {
                    summarizeMessages(session)
                }
            } catch (e: Exception) {
                _error.value = "Ошибка отправки сообщения: ${e.message}"
            } finally {
                isLoading.value -= session.id
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
                cost = null
            )
            try {
                val responseContent = openRouterClient.summarizeMessages(
                    settings.value,
                    listOfNotNull(summaryMessage, summary, *messagesToSummary.toTypedArray())
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
                )

                summaryRepository.insertSummary(newSummary)
                messageRepository.removeMessages(messagesToSummary.map { it.id } )

                val updatedSession = session.copy(totalToken = responseContent.totalUsedToken)
                sessionRepository.updateSession(updatedSession)
            } catch (e: Exception) {
                _error.value = "Ошибка объединения сообщений: ${e.message}"
            }

        }
    }

    fun clearError() {
        _error.value = null
    }
}
