package com.sbfs.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbfs.ai.data.ContextMinimizationStrategy
import com.sbfs.ai.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
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
    val mcpServers by viewModel.mcpServers.collectAsState()
    val invariants by viewModel.invariants.collectAsState()
    val taskContext by viewModel.taskContextState.collectAsState()
    val sessionParams by viewModel.sessionParams.collectAsState()
    val branches by viewModel.branches.collectAsState()
    val currentBranch by viewModel.currentBranch.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val indexingStatus by viewModel.indexingStatus.collectAsState()

    val (messages, isLoading) = messagesPair

    // Состояние для модального окна профиля
    var showProfileModal by remember { mutableStateOf(false) }
    var showProfileCreateModal by remember { mutableStateOf(false) }
    var showProfileChangeModal by remember { mutableStateOf(false) }

    // Состояние для модального окна памяти сессии
    var showSessionMemoryModal by remember { mutableStateOf(false) }
    var selectedSession by remember { mutableStateOf<com.sbfs.ai.data.Session?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Challenge. Пользователь: ${userProfile?.name}") },
                actions = {
                    // Кнопка профиля
                    Button(onClick = {
                        if (userProfile != null) {
                            showProfileModal = true
                        } else {
                            showProfileCreateModal = true
                        }
                    }) {
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
                    mcpServers = mcpServers,
                    onSettingsChange = { newSettings -> viewModel.updateSettings(newSettings) },
                    toggleMcpServer = { name, isEnabled -> viewModel.toggleMcpServer(name, isEnabled) },
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
                    taskContext = taskContext,
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
            }
            if (indexingStatus.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = indexingStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                    )
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.width(70.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
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
                                    onChooseAnotherAccount = {
                                        showProfileModal = false
                                        showProfileChangeModal = true
                                    },
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

            val scope = rememberCoroutineScope()

            if (showProfileChangeModal) {
                val users by viewModel.profiles.collectAsState()

                AlertDialog(
                    onDismissRequest = { showProfileChangeModal = false },
                    confirmButton = {
                        TextButton(onClick = {
                            showProfileChangeModal = false
                            showProfileCreateModal = true
                        }) {
                            Text("Создать")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showProfileChangeModal = false }) {
                            Text("Закрыть")
                        }
                    },
                    text = {
                        Column {
                            users.forEach { user ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                    .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(user.name.orEmpty())
                                    Button(
                                        onClick = {
                                            viewModel.setProfile(user)
                                            showProfileChangeModal = false
                                        },
                                        content = {
                                            Text("Выбрать")
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }

            if (showProfileCreateModal) {
                var name by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showProfileCreateModal = false },
                    confirmButton = {
                        if (name.isNotEmpty()) {
                            TextButton(onClick = {
                                showProfileCreateModal = false
                                scope.launch {
                                    viewModel.createNewUser(name)
                                    showProfileModal = true
                                }
                            }
                            ) {
                                Text("Сохранить")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showProfileCreateModal = false }) {
                            Text("Закрыть")
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Введите Ваше имя")
                            OutlinedTextField(
                                value = name,
                                onValueChange = { value ->
                                    name = value
                                },
                                label = { Text("Имя") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                )
            }

            // Модальное окно памяти сессии
            if (showSessionMemoryModal && selectedSession != null) {
                val sessionMemoryData by viewModel.sessionMemory.collectAsState()
                Dialog(
                    onDismissRequest = { showSessionMemoryModal = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .fillMaxHeight()
                                .padding(16.dp)
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            SessionMemoryPanel(
                                facts = sessionMemoryData,
                                invariants = invariants,
                                onAddInvariant = { invariant -> viewModel.saveInvariant(invariant) },
                                onRemoveInvariant = { invariant -> viewModel.removeInvariant(invariant::class.simpleName!!) },
                                onAddFact = { fact -> viewModel.saveSessionData(fact) },
                                onRemoveFact = { fact -> viewModel.removeFact(fact) },
                                modifier = Modifier.fillMaxSize()
                            )
                            TextButton(onClick = { showSessionMemoryModal = false }) {
                                Text("Закрыть")
                            }
                        }
                    }
                )
            }
        }


    }
}
