package com.sbfs.ai

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sbfs.ai.ui.MainWindow

fun main() = application {
    Window(
        state = rememberWindowState(
            size = DpSize(1500.dp, 1000.dp)
        ),
        onCloseRequest = ::exitApplication,
        title = "AI Challenge"
    ) {
        MainWindow()
    }
}