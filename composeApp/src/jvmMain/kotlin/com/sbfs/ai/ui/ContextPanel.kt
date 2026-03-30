package com.sbfs.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbfs.ai.Res
import com.sbfs.ai.data.Session
import com.sbfs.ai.memory
import com.sbfs.ai.ui.images.Delete
import com.sbfs.ai.ui.images.Edit
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextPanel(
    sessions: List<Session>,
    currentSession: Session?,
    onSessionSelected: (Session) -> Unit,
    onCreateNewSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onClearSession: () -> Unit,
    onShowSessionMemory: (Session) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Управление сессиями",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Кнопка создания новой сессии
            var showCreateDialog by remember { mutableStateOf(false) }
            var newSessionTitle by remember { mutableStateOf("") }

            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Создать новую сессию")
            }

            if (showCreateDialog) {
                AlertDialog(
                    onDismissRequest = { showCreateDialog = false },
                    title = { Text("Создать новую сессию") },
                    text = {
                        OutlinedTextField(
                            value = newSessionTitle,
                            onValueChange = { newSessionTitle = it },
                            label = { Text("Название сессии") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (newSessionTitle.isNotBlank()) {
                                    onCreateNewSession(newSessionTitle)
                                    newSessionTitle = ""
                                    showCreateDialog = false
                                }
                            },
                            enabled = newSessionTitle.isNotBlank()
                        ) {
                            Text("Создать")
                        }
                    },
                    dismissButton = {
                        Button(onClick = { showCreateDialog = false }) {
                            Text("Отмена")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Список сессий
            Text(
                text = "Сохраненные сессии:",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(sessions) { session ->
                    SessionItem(
                        session = session,
                        isSelected = session.id == currentSession?.id,
                        onSelect = { onSessionSelected(session) },
                        onDelete = { onDeleteSession(session.id) },
                        onShowMemory = { onShowSessionMemory(session) }
                    )
                }
            }

            // Кнопка очистки сессии
            if (currentSession != null) {
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onClearSession,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Очистить текущую сессию")
                }
            }
        }
    }
}

@Composable
fun SessionItem(
    session: Session,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onShowMemory: () -> Unit
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Обновлено: ${session.updatedAt}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row {
                IconButton(onClick = onSelect) {
                    Icon(
                        imageVector = Edit,
                        contentDescription = "Выбрать"
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Delete,
                        contentDescription = "Удалить"
                    )
                }
                IconButton(onClick = onShowMemory) {
                    Icon(
                        painter = painterResource(Res.drawable.memory),
                        contentDescription = "Знания"
                    )
                }
            }
        }
    }
}