package com.pyfold.ubuntucontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        enableEdgeToEdge()
        SecureStore.init(this)
        if (SshManager.configured) SshManager.startAutoConnect()
        setContent {
            UbuntuTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) { App() }
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

    // направление анимации: вперёд — справа, назад — слева
    var prevSize by remember { mutableIntStateOf(1) }
    val forward = stack.size >= prevSize
    SideEffect { prevSize = stack.size }

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = {
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(260)) { it / 4 * dir } + fadeIn(tween(260)))
                .togetherWith(slideOutHorizontally(tween(260)) { -it / 4 * dir } + fadeOut(tween(160)))
        },
        label = "nav",
    ) { s ->
        when (s) {
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
    }

    if (settings) SettingsDialog(
        onDismiss = { settings = false },
        onReset = { settings = false; SshManager.reset(); stack.clear(); stack.add(Screen.Dash); configured = false },
    )
}
