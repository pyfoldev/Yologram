package com.pyfold.ubuntucontrol

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Главная", Icons.Rounded.Home),
    Monitor("Монитор", Icons.Rounded.MonitorHeart),
    Files("Файлы", Icons.Rounded.Folder),
    Terminal("Терминал", Icons.Rounded.Terminal),
    More("Ещё", Icons.Rounded.GridView),
}

sealed interface Screen {
    data class MenuS(val menu: Target.Menu) : Screen
    data class Out(val title: String, val cmd: String) : Screen
    data class Editor(val path: String) : Screen
    data object Services : Screen
    data object Docker : Screen
    data object Snippets : Screen
    data object Settings : Screen
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
private fun LockScreen(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) onUnlock()
    }
    fun ask() {
        @Suppress("DEPRECATION")
        val i = km.createConfirmDeviceCredentialIntent("Ubuntu Control", "Подтвердите, что это вы")
        if (i == null) onUnlock() else launcher.launch(i)
    }
    LaunchedEffect(Unit) { ask() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        UbuntuLogo(88.dp)
        Spacer(Modifier.height(20.dp))
        Text("Ubuntu Control", style = MaterialTheme.typography.headlineMedium)
        Text("Приложение заблокировано", color = TextDim, modifier = Modifier.padding(top = 6.dp))
        Button(onClick = { ask() }, modifier = Modifier.padding(top = 24.dp), colors = ButtonDefaults.buttonColors(containerColor = UbuntuOrange)) {
            Icon(Icons.Rounded.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Разблокировать")
        }
    }
}

@Composable
fun App() {
    var configured by remember { mutableStateOf(SshManager.configured) }
    var tab by remember { mutableStateOf(Tab.Home) }
    val stack = remember { mutableStateListOf<Screen>() }
    var filesPath by remember { mutableStateOf<String?>(null) }
    var filesRefresh by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(SecureStore.get("lock", "0") == "1") }

    // блокировка при уходе приложения в фон
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && SecureStore.get("lock", "0") == "1") locked = true
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    if (!configured) { OnboardingScreen(onDone = { configured = true }); return }
    if (locked) { LockScreen(onUnlock = { locked = false }); return }

    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty() || tab != Tab.Home) { if (stack.isNotEmpty()) back() else tab = Tab.Home }

    fun runCmd(title: String, cmd: String) { stack.add(Screen.Out(title, cmd)) }
    fun openTarget(t: Target) = when (t) {
        Target.Terminal -> { stack.clear(); tab = Tab.Terminal }
        is Target.Menu -> { stack.add(Screen.MenuS(t)); Unit }
        is Target.Run -> runCmd(t.cmd.title, t.cmd.cmd)
        is Target.Page -> when (t.id) {
            "services" -> { stack.add(Screen.Services); Unit }
            "docker" -> { stack.add(Screen.Docker); Unit }
            "monitor" -> { stack.clear(); tab = Tab.Monitor }
            else -> Unit
        }
    }
    fun openPage(id: String) = openTarget(Target.Page(id)).also {
        when (id) { "snippets" -> stack.add(Screen.Snippets); "settings" -> stack.add(Screen.Settings) }
    }
    fun toTerminal(cmd: String) { SshManager.pendingTermInput = cmd; stack.clear(); tab = Tab.Terminal }

    // направление анимации
    var prevDepth by remember { mutableIntStateOf(0) }
    val forward = stack.size >= prevDepth
    SideEffect { prevDepth = stack.size }

    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showBar = stack.isEmpty() && !imeOpen

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
            val key: Any = stack.lastOrNull() ?: tab
            AnimatedContent(
                targetState = key,
                transitionSpec = {
                    if (initialState is Tab && targetState is Tab) fadeIn(tween(180)).togetherWith(fadeOut(tween(120)))
                    else {
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(tween(260)) { it / 4 * dir } + fadeIn(tween(260)))
                            .togetherWith(slideOutHorizontally(tween(260)) { -it / 4 * dir } + fadeOut(tween(160)))
                    }
                },
                label = "nav",
            ) { k ->
                when (k) {
                    Tab.Home -> DashboardScreen(onOpen = ::openTarget, onSettings = { stack.add(Screen.Settings) })
                    Tab.Monitor -> MonitorScreen()
                    Tab.Files -> FilesScreen(filesPath, filesRefresh, onPath = { filesPath = it }, onEdit = { stack.add(Screen.Editor(it)) })
                    Tab.Terminal -> TerminalScreen(showBack = false)
                    Tab.More -> MoreScreen(onMenu = { stack.add(Screen.MenuS(it)) }, onRun = ::runCmd, onPage = ::openPage)
                    is Screen.MenuS -> MenuScreen(k.menu, onBack = ::back, onRun = ::runCmd)
                    is Screen.Out -> OutputScreen(k.title, k.cmd, onBack = ::back)
                    is Screen.Editor -> EditorScreen(k.path, onBack = ::back, onSaved = { filesRefresh++ })
                    Screen.Services -> ServicesScreen(onBack = ::back, onRun = ::runCmd)
                    Screen.Docker -> DockerScreen(onBack = ::back, onRun = ::runCmd)
                    Screen.Snippets -> SnippetsScreen(onBack = ::back, onRun = ::runCmd, onTerminal = ::toTerminal)
                    Screen.Settings -> SettingsScreen(onBack = ::back,
                        onReset = { SshManager.reset(); stack.clear(); tab = Tab.Home; configured = false },
                        onLockChanged = { })
                }
            }
        }
        if (showBar) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White, selectedTextColor = UbuntuOrange,
                            indicatorColor = UbuntuOrange, unselectedIconColor = TextDim, unselectedTextColor = TextDim),
                    )
                }
            }
        }
    }
}
