package com.sbfs.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType.Companion.PrimaryNotEditable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbfs.ai.data.Model
import com.sbfs.ai.data.SessionSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    availableModels: List<Model>,
    settings: SessionSettings,
    onSettingsChange: (SessionSettings) -> Unit,
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