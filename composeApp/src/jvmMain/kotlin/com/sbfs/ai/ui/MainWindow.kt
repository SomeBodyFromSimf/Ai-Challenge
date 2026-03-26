package com.sbfs.ai.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
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

    val (messages, isLoading) = messagesPair

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Challenge") }
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
                    messages = messages,
                    settings = settings,
                    isLoading = isLoading,
                    onSendMessage = { message -> viewModel.sendMessage(message) },
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
                    )
                    ParamsPanel(
                        modifier = Modifier.weight(1f),
                        params = sessionParams,
                        onParamChange = { params -> viewModel.onParamsChanged(params) }
                    )
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
        }

        

    }
}
