package com.sbfs.ai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbfs.ai.OpenRouterClient
import com.sbfs.ai.data.*
import com.sbfs.ai.db.AiChallengeDb
import com.sbfs.ai.db.DatabaseDriverFactory
import com.sbfs.ai.repository.MessageRepository
import com.sbfs.ai.repository.ModelRepository
import com.sbfs.ai.repository.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import kotlin.time.Clock

class ChatViewModel : ViewModel() {
    private val db = AiChallengeDb(DatabaseDriverFactory().createDriver())

    private val sessionRepository = SessionRepository(db)
    private val messageRepository = MessageRepository(db)
    private val modelRepository = ModelRepository(db)
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


    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<Pair<List<Message>, Boolean>> = currentSession.flatMapLatest { session ->
        if (session != null) {
            combine(
                messageRepository.getMessagesFlowBySessionId(session.id),
                isLoading.map { it.contains(session.id) }
            ) { messages, isLoading ->
                messages to isLoading
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
                if (currentSessionId.value == sessionId) {
                    currentSessionId.value = null
                }
            } catch (e: Exception) {
                _error.value = "Ошибка удаления сессии: ${e.message}"
            }
        }
    }
    
    fun clearCurrentSession() {
        viewModelScope.launch {
            try {
                currentSessionId.value?.let { sessionId ->
                    messageRepository.deleteMessagesBySessionId(sessionId)
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
                    cost
                )
                
                messageRepository.addMessage(assistantMessage)
            } catch (e: Exception) {
                _error.value = "Ошибка отправки сообщения: ${e.message}"
            } finally {
                isLoading.value -= session.id
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
