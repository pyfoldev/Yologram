package ru.reader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(applicationContext)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(prefs) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(prefs: Prefs) {
    var tab by rememberSaveable { mutableStateOf(Tab.ANIME) }
    var source by rememberSaveable { mutableStateOf(Source.ANILIBRIA) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(prefs.saved()) }
    var open by remember { mutableStateOf<Item?>(null) }

    LaunchedEffect(tab, source, submitted) {
        if (tab == Tab.SAVED) return@LaunchedEffect
        status = "Загрузка…"; items = emptyList()
        try {
            items = source.list(submitted.trim(), tab == Tab.COMICS)
            status = if (items.isEmpty()) "Ничего не найдено" else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Ошибка: ${e.message}. Попробуйте другой источник."
        }
    }

    val current = open
    if (current != null) {
        BackHandler { open = null }
        ReaderScreen(current, prefs, saved.any { it.key == current.key },
            onToggleSave = { saved = prefs.toggle(current) }, onBack = { open = null })
        return
    }

    val shown = if (tab == Tab.SAVED) saved else items
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Tab.entries.toList()) { t ->
                FilterChip(selected = tab == t, onClick = {
                    tab = t; query = ""; submitted = ""
                    if (t.sources.isNotEmpty()) source = t.sources.first()
                }, label = { Text(t.label) })
            }
        }
        if (tab != Tab.SAVED) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Поиск…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = query }),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            if (tab.sources.size > 1) LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tab.sources) { s -> FilterChip(selected = source == s, onClick = { source = s }, label = { Text(s.label) }) }
            }
        }
        val msg = if (tab == Tab.SAVED && saved.isEmpty()) "Закладок пока нет: удерживайте карточку, чтобы добавить" else status
        if (msg != null) Text(msg, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(shown, key = { it.key }) { it -> Card(it, saved.any { s -> s.key == it.key }, onOpen = { open = it }, onLong = { saved = prefs.toggle(it) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Card(item: Item, starred: Boolean, onOpen: () -> Unit, onLong: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onOpen, onLongClick = onLong)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.outlineVariant)) {
            if (item.cover != null) AsyncImage(item.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.padding(8.dp)) {
            Text((if (starred) "★ " else "") + item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (item.sub.isNotEmpty()) Text(item.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
