package com.sbfs.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sbfs.ai.data.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePanel(
    profile: UserProfile,
    onProfileChange: (UserProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Профиль пользователя",
            style = MaterialTheme.typography.headlineMedium
        )

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

        Spacer(modifier = Modifier.height(8.dp))

        // Email
        OutlinedTextField(
            value = internalProfile.email ?: "",
            onValueChange = { value ->
                internalProfile = internalProfile.copy(email = value)
            },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Биография
        OutlinedTextField(
            value = internalProfile.bio ?: "",
            onValueChange = { value ->
                internalProfile = internalProfile.copy(bio = value)
            },
            label = { Text("Биография") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5
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
            text = "Навыки",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        var skillInput by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = skillInput,
                onValueChange = { skillInput = it },
                label = { Text("Навык") },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    if (skillInput.isNotBlank() && !internalProfile.skills.contains(skillInput)) {
                        internalProfile = internalProfile.copy(skills = internalProfile.skills + skillInput)
                        skillInput = ""
                    }
                },
                enabled = skillInput.isNotBlank()
            ) {
                Text("Добавить")
            }
        }

        // Список навыков
        FlowRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            internalProfile.skills.forEach { skill ->
                Chip(
                    onClick = {
                        internalProfile = internalProfile.copy(skills = internalProfile.skills - skill)
                    },
                    label = { Text(skill) },
                    modifier = Modifier.padding(4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Интересы",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        var interestInput by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = interestInput,
                onValueChange = { interestInput = it },
                label = { Text("Интерес") },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    if (interestInput.isNotBlank() && !internalProfile.interests.contains(interestInput)) {
                        internalProfile = internalProfile.copy(interests = internalProfile.interests + interestInput)
                        interestInput = ""
                    }
                },
                enabled = interestInput.isNotBlank()
            ) {
                Text("Добавить")
            }
        }

        // Список интересов
        FlowRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            internalProfile.interests.forEach { interest ->
                Chip(
                    onClick = {
                        internalProfile = internalProfile.copy(interests = internalProfile.interests - interestInput)
                    },
                    label = { Text(interest) },
                    modifier = Modifier.padding(4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Знания",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        var knowledgeInput by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = knowledgeInput,
                onValueChange = { knowledgeInput = it },
                label = { Text("Знание") },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    if (knowledgeInput.isNotBlank() && !internalProfile.knowledge.contains(knowledgeInput)) {
                        internalProfile = internalProfile.copy(knowledge = internalProfile.knowledge + knowledgeInput)
                        knowledgeInput = ""
                    }
                },
                enabled = knowledgeInput.isNotBlank()
            ) {
                Text("Добавить")
            }
        }

        // Список знаний
        FlowRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            internalProfile.knowledge.forEach { knowledge ->
                Chip(
                    onClick = {
                        internalProfile = internalProfile.copy(knowledge = internalProfile.knowledge - knowledgeInput)
                    },
                    label = { Text(knowledge) },
                    modifier = Modifier.padding(4.dp)
                )
            }
        }

        if (profile != internalProfile) {
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