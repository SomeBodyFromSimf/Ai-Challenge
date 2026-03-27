package com.sbfs.ai.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sbfs.ai.data.ContextMinimizationStrategy
import com.sbfs.ai.data.Params

@Composable
fun ParamsPanel(
    modifier: Modifier,
    params: Params,
    onParamChange: (Params) -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "Параметры сессии",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Black, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Управление контекстом",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                ContextMinimizationStrategy.entries.forEach { entry ->
                    val text = entry.toText()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelMedium
                        )
                        RadioButton(
                            selected = params.manageContextStrategy == entry,
                            onClick = { onParamChange(params.copy(manageContextStrategy = entry)) },
                        )
                    }
                }
            }
        }
    }
}

private fun ContextMinimizationStrategy.toText(): String {
    return when (this) {
        ContextMinimizationStrategy.NO_STRATEGY -> "Без стратегии"
        ContextMinimizationStrategy.SUMMARY -> "Саммари"
        ContextMinimizationStrategy.SLIDING -> "Sliding"
        ContextMinimizationStrategy.STICKY_FACTS -> "Sticky Facts"
        ContextMinimizationStrategy.BRANCHING -> "Branching"
    }
}
