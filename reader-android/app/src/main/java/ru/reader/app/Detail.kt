package ru.reader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(id: Int, prefs: Prefs, onBack: () -> Unit, onPlay: (Int?) -> Unit, onOpen: (Int) -> Unit) {
    var d by remember { mutableStateOf<Detail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        error = null
        try { d = Api.detail(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Нет сети" }
    }
    var parts by remember { mutableStateOf<List<Card>>(emptyList()) }
    LaunchedEffect(id) { parts = try { Api.franchise(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() } }
    val detail = d
    Box(Modifier.fillMaxSize()) {
        if (detail == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ошибка: $error", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton({ attempt++ }) { Text("Повторить") }
                } else CircularProgressIndicator()
            }
        } else {
            val last = prefs.lastOrdinal(id)
            val canPlay = detail.episodes.isNotEmpty()
            val fav = prefs.isFavorite(id)
            var expanded by remember { mutableStateOf(false) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().height(380.dp)) {
                        Poster(detail.card.poster, Modifier.fillMaxSize().blur(28.dp).alpha(.55f))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
                        Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                            Poster(detail.card.poster, Modifier.width(140.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.large))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(detail.card.title, style = MaterialTheme.typography.headlineSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                detail.english?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                            }
                        }
                    }
                }
                item {
                    FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoPill("${detail.card.year}"); InfoPill(detail.card.type)
                        detail.age?.let { InfoPill(it) }
                        if (detail.card.episodes > 0) InfoPill("${detail.card.episodes} эп.")
                        if (detail.avgDuration > 0) InfoPill("${detail.avgDuration} мин")
                        detail.rating?.let { InfoPill("★ %.1f".format(it), accent = true) }
                        if (detail.card.ongoing) InfoPill("Онгоинг" + (detail.publishDay?.let { " · $it" } ?: ""), accent = true)
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { onPlay(last) }, enabled = canPlay,
                            modifier = Modifier.weight(1f).height(60.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(28.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (last != null) "Продолжить: серия $last" else "Смотреть", style = MaterialTheme.typography.titleMedium)
                        }
                        FilledTonalIconButton({ prefs.toggleFavorite(detail.card) }, Modifier.size(60.dp)) {
                            Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Избранное",
                                tint = if (fav) MaterialTheme.colorScheme.primary else LocalContentColor.current, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                if (detail.blocked || !canPlay) item {
                    Text(
                        if (detail.blocked) "Релиз может быть недоступен в вашем регионе или заблокирован правообладателем." else "Серий пока нет.",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error,
                    )
                }
                if (detail.description.isNotBlank()) item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clip(MaterialTheme.shapes.large)
                        .clickable { expanded = !expanded }) {
                        Text(detail.description, style = MaterialTheme.typography.bodyLarge,
                            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
                        Text(if (expanded) "Свернуть" else "Читать полностью", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (detail.genres.isNotEmpty()) item {
                    FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        detail.genres.forEach { SuggestionChip({}, label = { Text(it) }) }
                    }
                }
                if (parts.size > 1) item {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text("Сезоны и связанное", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(parts, key = { _, c -> c.id }) { i, c ->
                                val cur = c.id == id
                                Column(Modifier.width(120.dp).clip(MaterialTheme.shapes.large).clickable(enabled = !cur) { onOpen(c.id) }) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.large)
                                        .then(if (cur) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large) else Modifier)) {
                                        Poster(c.thumb, Modifier.fillMaxSize())
                                        Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                                .background(if (cur) MaterialTheme.colorScheme.primary else Color.Black.copy(.6f), CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 3.dp))
                                    }
                                    Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (cur) FontWeight.Bold else null, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                                    Text("${c.year} · ${c.type}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
                                }
                            }
                        }
                    }
                }
                if (canPlay) item {
                    Text("Серии", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
                }
                itemsIndexed(detail.episodes) { _, ep ->
                    val p = prefs.progress(id, ep.ordinal)
                    ListItem(
                        modifier = Modifier.padding(horizontal = 12.dp).clip(MaterialTheme.shapes.large).clickable { onPlay(ep.ordinal) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Box(Modifier.size(48.dp).background(
                                if (p > .9f) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (p > .9f) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                else Text("${ep.ordinal}", style = MaterialTheme.typography.titleSmall)
                            }
                        },
                        headlineContent = { Text(ep.name ?: "Серия ${ep.ordinal}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                if (ep.duration > 0) Text("${ep.duration / 60} мин")
                                if (p in 0.01f..0.9f) LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(CircleShape))
                            }
                        },
                        trailingContent = { Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
        }
        FilledTonalIconButton(onBack, Modifier.statusBarsPadding().padding(12.dp).size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(.75f))) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
        }
    }
}

@Composable
fun InfoPill(text: String, accent: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.labelLarge,
        color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.background(
            if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape,
        ).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
