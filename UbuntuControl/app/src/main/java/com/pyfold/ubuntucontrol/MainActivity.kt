package com.pyfold.ubuntucontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

sealed interface Screen {
    data object Dash : Screen
    data object Term : Screen
    data class MenuS(val menu: Target.Menu) : Screen
    data class Out(val title: String, val cmd: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureStore.init(this)
        if (SshManager.configured) SshManager.startAutoConnect()
        setContent {
            UbuntuTheme {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(AubergineLight, Aubergine)))) {
                    App()
                }
            }
        }
    }
}

@Composable
fun App() {
    var configured by remember { mutableStateOf(SshManager.configured) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Dash) }
    var settings by remember { mutableStateOf(false) }

    if (!configured) {
        OnboardingScreen(onDone = { configured = true })
        return
    }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    when (val s = stack.last()) {
        Screen.Dash -> DashboardScreen(
            onOpen = { t ->
                when (t) {
                    Target.Terminal -> stack.add(Screen.Term)
                    is Target.Menu -> stack.add(Screen.MenuS(t))
                    is Target.Run -> stack.add(Screen.Out(t.cmd.title, t.cmd.cmd))
                }
            },
            onSettings = { settings = true },
        )
        Screen.Term -> TerminalScreen(onBack = ::back)
        is Screen.MenuS -> MenuScreen(s.menu, onBack = ::back, onRun = { title, cmd -> stack.add(Screen.Out(title, cmd)) })
        is Screen.Out -> OutputScreen(s.title, s.cmd, onBack = ::back)
    }

    if (settings) SettingsDialog(
        onDismiss = { settings = false },
        onReset = { settings = false; SshManager.reset(); stack.clear(); stack.add(Screen.Dash); configured = false },
    )
}
