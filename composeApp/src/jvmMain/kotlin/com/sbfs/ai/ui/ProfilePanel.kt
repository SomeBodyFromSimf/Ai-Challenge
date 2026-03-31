package com.sbfs.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sbfs.ai.Res
import com.sbfs.ai.change_user
import com.sbfs.ai.data.UserProfile
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePanel(
    profile: UserProfile,
    onProfileChange: (UserProfile) -> Unit,
    onChooseAnotherAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Профиль пользователя",
                style = MaterialTheme.typography.headlineMedium
            )
            IconButton(onClick = onChooseAnotherAccount) {
                Icon(
                    painter = painterResource(Res.drawable.change_user),
                    contentDescription = "Изменить"
                )
            }
        }


        var internalProfile by remember(profile) {
            mutableStateOf(profile)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Имя пользователя
        OutlinedTextField(
            value = internalProfile.name ?: "",
            onValueChange = { value ->
                internalProfile = internalProfile.copy(name = value)
            },
            label = { Text("Имя") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Предпочтения",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        var preferenceKey by remember { mutableStateOf("") }
        var preferenceValue by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = preferenceKey,
                onValueChange = { preferenceKey = it },
                label = { Text("Ключ") },
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = preferenceValue,
                onValueChange = { preferenceValue = it },
                label = { Text("Значение") },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    if (preferenceKey.isNotBlank() && preferenceValue.isNotBlank()) {
                        val newPreferences = internalProfile.preferences + (preferenceKey to preferenceValue)
                        internalProfile = internalProfile.copy(preferences = newPreferences)
                        preferenceKey = ""
                        preferenceValue = ""
                    }
                },
                enabled = preferenceKey.isNotBlank() && preferenceValue.isNotBlank()
            ) {
                Text("Добавить")
            }
        }

        // Список предпочтений
        internalProfile.preferences.forEach { (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("$key: $value")
                Button(
                    onClick = {
                        val newPreferences = internalProfile.preferences - key
                        internalProfile = internalProfile.copy(preferences = newPreferences)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red
                    )
                ) {
                    Text("Удалить")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Ограничения",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        var limitationInput by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = limitationInput,
                onValueChange = { limitationInput = it },
                label = { Text("Ограничения") },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    if (limitationInput.isNotBlank() && !internalProfile.limitationsForLLM.contains(limitationInput)) {
                        internalProfile = internalProfile.copy(limitationsForLLM = internalProfile.limitationsForLLM + limitationInput)
                        limitationInput = ""
                    }
                },
                enabled = limitationInput.isNotBlank()
            ) {
                Text("Добавить")
            }
        }

        // Список ограничений
        FlowRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            internalProfile.limitationsForLLM.forEach { limitation ->
                Chip(
                    onClick = {
                        internalProfile = internalProfile.copy(limitationsForLLM = internalProfile.limitationsForLLM - limitation)
                    },
                    label = { Text(limitation) },
                    modifier = Modifier.padding(4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Доп информация для LLM",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = internalProfile.additionalInfo.orEmpty(),
                onValueChange = { internalProfile = internalProfile.copy(additionalInfo = it) },
                label = { Text("Инфо") },
                placeholder = { Text("Укажите любую важную информацию которую посчитаете нужной") },
                minLines = 5,
                modifier = Modifier.weight(1f)
            )
        }

        if (profile != internalProfile && internalProfile.name.isNullOrEmpty().not()) {
            Button(onClick = {
                onProfileChange(internalProfile)
            }) {
                Text("Сохранить")
            }
        }
    }
}

@Composable
fun Chip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier
    )
}