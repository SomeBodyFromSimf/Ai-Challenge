package com.sbfs.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType.Companion.PrimaryNotEditable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbfs.ai.data.Invariant
import com.sbfs.ai.data.Invariant.Arch.ArchType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionMemoryPanel(
    facts: List<String>,
    invariants: List<Invariant>,
    onAddInvariant: (Invariant) -> Unit,
    onRemoveInvariant: (Invariant) -> Unit,
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

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Инварианты",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        var choosenType by remember { mutableStateOf<Pair<String, String>?>(null) }


        var expanded by remember { mutableStateOf(false) }
        val currentTitle by remember {
            derivedStateOf {
                choosenType?.second ?: "Выберите тип"
            }
        }
        ExposedDropdownMenuBox(
            modifier = Modifier,
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            TextField(
                readOnly = true,
                value = currentTitle,
                onValueChange = { },
                label = { Text("Тип") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
                modifier = Modifier
                    .menuAnchor(PrimaryNotEditable, true)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                Invariant.allTypes.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.second) },
                        onClick = {
                            choosenType = type
                            expanded = false
                        }
                    )
                }
            }
        }
        when (choosenType?.first) {
            Invariant.StackOnly::class.simpleName -> {
                var chosenTypeString by remember { mutableStateOf("") }

                OutlinedTextField(
                    value = chosenTypeString,
                    label = { Text("Стэк") },
                    onValueChange = { chosenTypeString = it },
                    modifier = Modifier.widthIn(min = 150.dp)
                )

                Button(
                    onClick = {
                        val invariant = invariants.filterIsInstance<Invariant.StackOnly>().firstOrNull()?.let {
                            it.copy(allowed = it.allowed + chosenTypeString)
                        } ?: Invariant.StackOnly(setOf(chosenTypeString))
                        onAddInvariant(invariant)
                        chosenTypeString = ""
                    },
                    enabled = chosenTypeString.isNotBlank(),
                ) {
                    Text("Добавить")
                }
            }
            Invariant.Arch::class.simpleName -> {
                var expandedArch by remember { mutableStateOf(false) }
                var archType by remember { mutableStateOf<ArchType?>(null) }

                ExposedDropdownMenuBox(
                    modifier = Modifier,
                    expanded = expandedArch,
                    onExpandedChange = { expandedArch = !expandedArch }
                ) {
                    TextField(
                        readOnly = true,
                        value = archType?.name ?: "Выберите архитектуру",
                        onValueChange = { },
                        label = { Text("Архитектура") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedArch)
                        },
                        colors = ExposedDropdownMenuDefaults.textFieldColors(),
                        modifier = Modifier
                            .menuAnchor(PrimaryNotEditable, true)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedArch,
                        onDismissRequest = { expandedArch = false }
                    ) {
                        ArchType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    archType = type
                                    expandedArch = false
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        archType?.let { type ->
                            val invariant = invariants.filterIsInstance<Invariant.Arch>().firstOrNull()?.let {
                                it.copy(allowedTypes = it.allowedTypes + type)
                            } ?: Invariant.Arch(setOf(type))
                            onAddInvariant(invariant)
                            archType = null
                        }

                    },
                    enabled = archType != null,
                ) {
                    Text("Добавить")
                }
            }
            Invariant.ExcludeTechnology::class.simpleName -> {
                var chosenTypeString by remember { mutableStateOf("") }

                OutlinedTextField(
                    value = chosenTypeString,
                    label = { Text("Исключить") },
                    onValueChange = { chosenTypeString = it },
                    modifier = Modifier.widthIn(min = 150.dp)
                )

                Button(
                    onClick = {
                        val invariant = invariants.filterIsInstance<Invariant.ExcludeTechnology>().firstOrNull()?.let {
                            it.copy(excluded = it.excluded + chosenTypeString)
                        } ?: Invariant.ExcludeTechnology(setOf(chosenTypeString))
                        onAddInvariant(invariant)
                        chosenTypeString = ""
                    },
                    enabled = chosenTypeString.isNotBlank(),
                ) {
                    Text("Добавить")
                }
            }
        }

        invariants.forEach { invariant ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = invariant::class.simpleName!!,
                    style = MaterialTheme.typography.bodyMedium
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val list = when (invariant) {
                        is Invariant.Arch -> invariant.allowedTypes.map { it.name }
                        is Invariant.ExcludeTechnology -> invariant.excluded
                        is Invariant.StackOnly -> invariant.allowed
                    }
                    list.forEach { stackValue ->
                        Chip(
                            onClick = {
                                if (list.count() == 1) {
                                    onRemoveInvariant(invariant)
                                } else {
                                    val newInvariant = when (invariant) {
                                        is Invariant.Arch -> invariant.copy(allowedTypes = invariant.allowedTypes - ArchType.valueOf(stackValue))
                                        is Invariant.ExcludeTechnology -> invariant.copy(excluded = invariant.excluded - stackValue)
                                        is Invariant.StackOnly -> invariant.copy(allowed = invariant.allowed - stackValue)
                                    }
                                    onAddInvariant(newInvariant)
                                }
                            },
                            label = { Text(stackValue) },
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}