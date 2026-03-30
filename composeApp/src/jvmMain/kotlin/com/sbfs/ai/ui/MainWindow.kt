package com.sbfs.ai.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbfs.ai.data.ContextMinimizationStrategy
import com.sbfs.ai.viewmodel.ChatViewModel
import kotlin.reflect.KClass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainWindow() {
    val viewModel: ChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(
                modelClass: KClass<T>,
                extras: CreationExtras,
            ): T {
                return ChatViewModel() as T
            }
        }
    )
    val sessions by viewModel.sessions.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val messagesPair by viewModel.messages.collectAsState()
    val error by viewModel.error.collectAsState()
    val models by viewModel.models.collectAsState()
    val sessionParams by viewModel.sessionParams.collectAsState()
    val branches by viewModel.branches.collectAsState()
    val currentBranch by viewModel.currentBranch.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()

    val (messages, isLoading) = messagesPair

    // Состояние для модального окна профиля
    var showProfileModal by remember { mutableStateOf(false) }

    // Состояние для модального окна памяти сессии
    var showSessionMemoryModal by remember { mutableStateOf(false) }
    var selectedSession by remember { mutableStateOf<com.sbfs.ai.data.Session?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Challenge") },
                actions = {
                    // Кнопка профиля
                    Button(onClick = { showProfileModal = true }) {
                        Text("Профиль")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Левая панель - настройки параметров запроса
                SettingsPanel(
                    settings = settings,
                    availableModels = models,
                    onSettingsChange = { newSettings -> viewModel.updateSettings(newSettings) },
                    modifier = Modifier
                        .weight(0.7f)
                        .fillMaxHeight()
                        .border(2.dp, MaterialTheme.colorScheme.inverseOnSurface)
                )

                // Центральная панель - чат
                ChatPanel(
                    session = currentSession,
                    branch = currentBranch,
                    messages = messages,
                    settings = settings,
                    isLoading = isLoading,
                    onSendMessage = { message -> viewModel.sendMessage(message) },
                    onSaveFacts = { message -> viewModel.saveSessionData(message) },
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight()
                )

                Column(
                    modifier = Modifier
                        .weight(0.7f)
                        .fillMaxHeight()
                        .border(2.dp, MaterialTheme.colorScheme.inverseOnSurface),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ContextPanel(
                        modifier = Modifier.weight(1f),
                        sessions = sessions,
                        currentSession = currentSession,
                        onSessionSelected = { session -> viewModel.selectSession(session) },
                        onCreateNewSession = { title -> viewModel.createNewSession(title) },
                        onDeleteSession = { sessionId -> viewModel.deleteSession(sessionId) },
                        onClearSession = { viewModel.clearCurrentSession() },
                        onShowSessionMemory = { session ->
                            selectedSession = session
                            showSessionMemoryModal = true
                        }
                    )
                    ParamsPanel(
                        modifier = Modifier.weight(1f),
                        params = sessionParams,
                        onParamChange = { params -> viewModel.onParamsChanged(params) }
                    )
                    
                    // Панель управления ветвями для стратегии BRANCHING
                    if (sessionParams.manageContextStrategy == ContextMinimizationStrategy.BRANCHING) {
                        BranchPanel(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            branches = branches,
                            currentBranch = currentBranch,
                            onCreateBranch = { name -> viewModel.createBranch(name) },
                            onSwitchToBranch = { branchId -> viewModel.switchToBranch(branchId) },
                            onDeleteBranch = { branchId -> viewModel.deleteBranch(branchId) },
                            onMergeBranches = { sourceId, targetId -> viewModel.mergeBranches(sourceId, targetId) }
                        )
                    }
                }

                // Правая панель - управление контекстом и памятью

            }

            // Отображение ошибок
            error?.let { errorMessage ->
                Snackbar(
                    modifier = Modifier.align(Alignment.Center),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Закрыть")
                        }
                    }
                ) {
                    Text(errorMessage)
                }
            }

            // Модальное окно профиля
            if (showProfileModal) {
                AlertDialog(
                    onDismissRequest = { showProfileModal = false },
                    confirmButton = {
                        TextButton(onClick = { showProfileModal = false }) {
                            Text("Закрыть")
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    text = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            userProfile?.let { profile ->
                                ProfilePanel(
                                    profile = profile,
                                    onProfileChange = { updatedProfile ->
                                        viewModel.saveUserProfile(updatedProfile)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } ?: CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                )
            }

            // Модальное окно памяти сессии
            if (showSessionMemoryModal && selectedSession != null) {
                val sessionMemoryData by viewModel.sessionMemory.collectAsState()
                AlertDialog(
                    onDismissRequest = { showSessionMemoryModal = false },
                    confirmButton = {
                        TextButton(onClick = { showSessionMemoryModal = false }) {
                            Text("Закрыть")
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    text = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            SessionMemoryPanel(
                                facts = sessionMemoryData,
                                onAddFact = { fact -> viewModel.saveSessionData(fact) },
                                onRemoveFact = { fact -> viewModel.removeFact(fact) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                )
            }
        }


    }
}
