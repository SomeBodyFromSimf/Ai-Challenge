package com.sbfs.ai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbfs.ai.OpenRouterClient
import com.sbfs.ai.data.Message
import com.sbfs.ai.data.MessageRole
import com.sbfs.ai.data.Session
import com.sbfs.ai.data.SessionSettings
import com.sbfs.ai.db.AiChallengeDb
import com.sbfs.ai.db.DatabaseDriverFactory
import com.sbfs.ai.repository.MessageRepository
import com.sbfs.ai.repository.SessionRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import kotlin.time.Clock

class ChatViewModel : ViewModel() {
    private val db = AiChallengeDb(DatabaseDriverFactory().createDriver())

    private val sessionRepository = SessionRepository(db)
    private val messageRepository = MessageRepository(db)
    private val openRouterClient = OpenRouterClient()
    
    val sessions = sessionRepository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    
    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()
    
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    
    private val _settings = MutableStateFlow(SessionSettings())
    val settings: StateFlow<SessionSettings> = _settings.asStateFlow()
    
    private val _isLoading = MutableStateFlow<Set<String>>(hashSetOf())
    val isLoading: StateFlow<Set<String>> = _isLoading.asStateFlow()
    
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
                _currentSession.value = session
                _settings.value = session.settings
                loadMessages(session.id)
            } catch (e: Exception) {
                _error.value = "Ошибка выбора сессии: ${e.message}"
            }
        }
    }
    
    fun createNewSession(title: String) {
        viewModelScope.launch {
            try {
                val newSession = sessionRepository.createSession(title, _settings.value)
                _currentSession.value = newSession
                _messages.value = emptyList()
            } catch (e: Exception) {
                _error.value = "Ошибка создания сессии: ${e.message}"
            }
        }
    }
    
    fun updateSettings(newSettings: SessionSettings) {
        _settings.value = newSettings
        _currentSession.value?.let { session ->
            viewModelScope.launch {
                try {
                    val updatedSession = session.copy(settings = newSettings)
                    sessionRepository.updateSession(updatedSession)
                    _currentSession.value = updatedSession
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
                if (_currentSession.value?.id == sessionId) {
                    _currentSession.value = null
                    _messages.value = emptyList()
                }
            } catch (e: Exception) {
                _error.value = "Ошибка удаления сессии: ${e.message}"
            }
        }
    }
    
    fun clearCurrentSession() {
        viewModelScope.launch {
            try {
                _currentSession.value?.let { session ->
                    messageRepository.deleteMessagesBySessionId(session.id)
                    _messages.value = emptyList()
                }
            } catch (e: Exception) {
                _error.value = "Ошибка очистки сессии: ${e.message}"
            }
        }
    }
    
    private fun loadMessages(sessionId: String) {
        viewModelScope.launch {
            try {
                _messages.value = messageRepository.getMessagesBySessionId(sessionId)
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки сообщений: ${e.message}"
            }
        }
    }
    
    fun sendMessage(content: String) {
        viewModelScope.launch {
            val sessionId = _currentSession.value?.id ?: run {
                _error.value = "Сперва создайте сессию"
                return@launch
            }
            try {
                _isLoading.value += sessionId
                _error.value = null
                
                // Создаем и сохраняем сообщение пользователя
                val userMessage = Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = MessageRole.USER,
                    content = content,
                    timestamp = Clock.System.now()
                )
                
                messageRepository.addMessage(userMessage)
                val updatedMessages = _messages.value + userMessage
                _messages.value = updatedMessages
                
                // Получаем ответ от LLM
                val responseContent = openRouterClient.sendMessage(updatedMessages, _settings.value)
                
                // Создаем и сохраняем сообщение ассистента
                val assistantMessage = Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = _currentSession.value?.id ?: return@launch,
                    role = MessageRole.ASSISTANT,
                    content = responseContent,
                    timestamp = Clock.System.now()
                )
                
                messageRepository.addMessage(assistantMessage)
                _messages.value = updatedMessages + assistantMessage
            } catch (e: Exception) {
                _error.value = "Ошибка отправки сообщения: ${e.message}"
            } finally {
                _isLoading.value -= sessionId
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}