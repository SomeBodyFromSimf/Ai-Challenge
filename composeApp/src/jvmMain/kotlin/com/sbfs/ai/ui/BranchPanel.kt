package com.sbfs.ai.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import com.sbfs.ai.Res
import com.sbfs.ai.data.Branch
import com.sbfs.ai.merge
import com.sbfs.ai.switch1
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchPanel(
    modifier: Modifier = Modifier,
    branches: List<Branch>,
    currentBranch: Branch?,
    onCreateBranch: (String) -> Unit,
    onSwitchToBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onMergeBranches: (String, String) -> Unit
) {
    var isCreatingBranch by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(16.dp)
        ) {
            Text(
                text = "Управление ветвями",
                style = MaterialTheme.typography.headlineSmall
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Кнопка создания новой ветви
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { isCreatingBranch = true }
                ) {
                    Text("Создать ветвь")
                }
            }
            
            // Форма создания новой ветви
            if (isCreatingBranch) {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("Название ветви") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            if (newBranchName.isNotBlank()) {
                                onCreateBranch(newBranchName)
                                newBranchName = ""
                                isCreatingBranch = false
                            }
                        }
                    ) {
                        Text("Создать")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { 
                            newBranchName = ""
                            isCreatingBranch = false
                        }
                    ) {
                        Text("Отмена")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Список ветвей
            Text(
                text = "Существующие ветви:",
                style = MaterialTheme.typography.titleMedium
            )
            
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                branches.forEach { branch ->
                    val isOptionsExpanded =
                        remember {
                            mutableStateOf(false)
                        }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = branch.name,
                        )
                        if (currentBranch != branch) {
                            Image(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.Gray)
                                    .clickable {
                                        onSwitchToBranch(branch.id)
                                    }
                                    .padding(4.dp),
                                painter = painterResource(Res.drawable.switch1),
                                colorFilter = ColorFilter.tint(Color.Black),
                                contentDescription = null
                            )
                        }
                        Box {
                            Image(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red)
                                    .clickable {
                                        isOptionsExpanded.value = true
                                    }
                                    .padding(4.dp),
                                painter = painterResource(Res.drawable.merge),
                                colorFilter = ColorFilter.tint(Color.White),
                                contentDescription = null
                            )
                            DropdownMenu(
                                expanded = isOptionsExpanded.value,
                                onDismissRequest = { isOptionsExpanded.value = false },
                            ) {
                                Column {
                                    Text("Выберите ветку с которой объеденить")
                                    branches.minus(branch).forEach { mergedBranch ->
                                        Box(
                                            modifier = Modifier
                                                .size(100.dp, 25.dp)
                                                .clickable {
                                                    isOptionsExpanded.value = false
                                                    onMergeBranches(branch.id, mergedBranch.id)
                                                }
                                        ) {
                                            Text(mergedBranch.name)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
