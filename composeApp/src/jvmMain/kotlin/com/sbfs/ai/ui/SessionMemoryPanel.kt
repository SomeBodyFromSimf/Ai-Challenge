package com.sbfs.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionMemoryPanel(
    facts: List<String>,
    onAddFact: (String) -> Unit,
    onRemoveFact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Память сессии",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Знания",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        var newFact by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = newFact,
                onValueChange = { newFact = it },
                label = { Text("Новое знание") },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    if (newFact.isNotBlank()) {
                        onAddFact(newFact)
                        newFact = ""
                    }
                },
                enabled = newFact.isNotBlank()
            ) {
                Text("Добавить")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Список знаний
        FlowRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            facts.forEach { fact ->
                AssistChip(
                    onClick = {
                        onRemoveFact(fact)
                    },
                    label = { Text(fact) },
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    }
}