package com.sbfs.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType.Companion.PrimaryNotEditable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbfs.ai.data.Model
import com.sbfs.ai.data.SessionSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    availableModels: List<Model>,
    settings: SessionSettings,
    mcpServers: Map<String, Boolean>,
    onSettingsChange: (SessionSettings) -> Unit,
    toggleMcpServer: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "Настройки запроса",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            var expanded by remember { mutableStateOf(false) }
            val currentTitle = remember(settings.model) {
                if (settings.model != null) {
                    "${settings.model.name} (${settings.model.contextLength})"
                } else {
                    "Выберите модель"
                }
            }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                TextField(
                    readOnly = true,
                    value = currentTitle,
                    onValueChange = { },
                    label = { Text("Модель") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                    modifier = Modifier
                        .menuAnchor(PrimaryNotEditable, true)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text("${model.name} (${model.contextLength})") },
                            onClick = {
                                onSettingsChange(settings.copy(model = model))
                                expanded = false
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SliderWithLabel(
                value = settings.temperature.toFloat(),
                onValueChange = { value ->
                    onSettingsChange(settings.copy(temperature = value.toDouble()))
                },
                label = "Temperature",
                valueRange = 0f..2f,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SliderWithLabel(
                value = settings.topP.toFloat(),
                onValueChange = { value ->
                    onSettingsChange(settings.copy(topP = value.toDouble()))
                },
                label = "Top P",
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            TextField(
                value = settings.topK.toString(),
                onValueChange = { value ->
                    val intValue = value.toIntOrNull() ?: settings.topK
                    onSettingsChange(settings.copy(topK = intValue))
                },
                label = { Text("Top K") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SliderWithLabel(
                value = settings.minP.toFloat(),
                onValueChange = { value ->
                    onSettingsChange(settings.copy(minP = value.toDouble()))
                },
                label = "Min P",
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SliderWithLabel(
                value = settings.topA.toFloat(),
                onValueChange = { value ->
                    onSettingsChange(settings.copy(topA = value.toDouble()))
                },
                label = "Top A",
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SliderWithLabel(
                value = settings.frequencyPenalty.toFloat(),
                onValueChange = { value ->
                    onSettingsChange(settings.copy(frequencyPenalty = value.toDouble()))
                },
                label = "Frequency Penalty",
                valueRange = -2f..2f,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SliderWithLabel(
                value = settings.presencePenalty.toFloat(),
                onValueChange = { value ->
                    onSettingsChange(settings.copy(presencePenalty = value.toDouble()))
                },
                label = "Presence Penalty",
                valueRange = -2f..2f,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SliderWithLabel(
                value = settings.repetitionPenalty.toFloat(),
                onValueChange = { value ->
                    onSettingsChange(settings.copy(repetitionPenalty = value.toDouble()))
                },
                label = "Repetition Penalty",
                valueRange = 0f..2f,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            TextField(
                value = settings.maxTokens?.toString() ?: "",
                onValueChange = { value ->
                    val intValue = value.toIntOrNull()
                    onSettingsChange(settings.copy(maxTokens = intValue))
                },
                label = { Text("Max Tokens") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RAG-режим")
                Switch(
                    checked = settings.ragMode,
                    onCheckedChange = { onSettingsChange(settings.copy(ragMode = it)) }
                )
            }

            if (settings.ragMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Улучшенный RAG",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = settings.enhancedRag,
                        onCheckedChange = { onSettingsChange(settings.copy(enhancedRag = it)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Режим разработчика")
                Switch(
                    checked = settings.developerMode,
                    onCheckedChange = { onSettingsChange(settings.copy(developerMode = it)) }
                )
            }

            var connectorsExpanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = connectorsExpanded,
                onExpandedChange = { connectorsExpanded = !connectorsExpanded }
            ) {
                Button(
                    onClick = {
                        connectorsExpanded = !connectorsExpanded
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connectors")
                }
                ExposedDropdownMenu(
                    expanded = connectorsExpanded,
                    onDismissRequest = { connectorsExpanded = false }
                ) {
                    Text("MCP Серверы")
                    if (mcpServers.isEmpty()) {
                        Text(
                            text = "Нет настроенных MCP серверов",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        mcpServers.forEach { (name, isEnabled) ->
                            McpServerItem(
                                name = name,
                                isEnabled = isEnabled,
                                onToggle = { enabled ->
                                    toggleMcpServer(name, enabled)
                                },
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SliderWithLabel(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label)
            Text("%.2f".format(value))
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
fun McpServerItem(
    name: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle
        )
    }
}