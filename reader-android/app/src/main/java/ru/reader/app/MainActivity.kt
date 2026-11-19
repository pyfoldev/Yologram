package ru.reader.app

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

sealed interface Route {
    data object Main : Route
    data class Detail(val id: Int) : Route
    data class Player(val id: Int, val ordinal: Int?) : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(applicationContext)
        setContent {
            val dark = when (prefs.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            AppTheme(dark, prefs.amoled) {
                val view = LocalView.current
                SideEffect {
                    val w = (view.context as ComponentActivity).window
                    w.isNavigationBarContrastEnforced = false
                    w.statusBarColor = AColor.TRANSPARENT
                    w.navigationBarColor = AColor.TRANSPARENT
                    WindowCompat.getInsetsController(w, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(prefs) }
            }
        }
    }
}

@Composable
fun Root(prefs: Prefs) {
    val stack = remember { mutableStateListOf<Route>(Route.Main) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
    val top = stack.last()
    AnimatedContent(
        targetState = top,
        transitionSpec = { (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.94f)) togetherWith fadeOut(tween(120)) },
        label = "nav",
    ) { r ->
        when (r) {
            Route.Main -> MainScaffold(prefs, tab, { tab = it }) { stack.add(Route.Detail(it)) }
            is Route.Detail -> DetailScreen(r.id, prefs,
                onBack = { stack.removeAt(stack.lastIndex) },
                onPlay = { ord -> stack.add(Route.Player(r.id, ord)) })
            is Route.Player -> PlayerScreen(r.id, r.ordinal, prefs, onBack = { stack.removeAt(stack.lastIndex) })
        }
    }
}

@Composable
fun MainScaffold(prefs: Prefs, tab: Int, onTab: (Int) -> Unit, onOpen: (Int) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(tab == 0, { onTab(0) }, label = { Text("Каталог") },
                    icon = { Icon(if (tab == 0) Icons.Rounded.Home else Icons.Outlined.Home, null) })
                NavigationBarItem(tab == 1, { onTab(1) }, label = { Text("Избранное") },
                    icon = {
                        BadgedBox(badge = { if (prefs.favorites.isNotEmpty()) Badge { Text("${prefs.favorites.size}") } }) {
                            Icon(if (tab == 1) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder, null)
                        }
                    })
                NavigationBarItem(tab == 2, { onTab(2) }, label = { Text("Настройки") },
                    icon = { Icon(if (tab == 2) Icons.Rounded.Settings else Icons.Outlined.Settings, null) })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> CatalogScreen(prefs, onOpen)
                1 -> FavoritesScreen(prefs, onOpen)
                else -> SettingsScreen(prefs)
            }
        }
    }
}
