package com.sbfs.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbfs.ai.data.Params

@Composable
fun ParamsPanel(
    modifier: Modifier,
    params: Params?,
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

            if (params != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Вкл. саммари")

                    Checkbox(
                        checked = params.isSummaryEnabled,
                        onCheckedChange = { onParamChange(params.copy(isSummaryEnabled = it)) }
                    )
                }
            } else {
                Text(
                    text = "Создайте сессию",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }
    }
}