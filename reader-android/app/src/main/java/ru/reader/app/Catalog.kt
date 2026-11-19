package ru.reader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CatalogVM : ViewModel() {
    var query by mutableStateOf("")
    var sort by mutableStateOf(Sort.FRESH)
    var ongoing by mutableStateOf(false)
    var genre by mutableStateOf<Genre?>(null)
    var genres by mutableStateOf<List<Genre>>(emptyList())
    var items by mutableStateOf<List<Card>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    val grid = LazyGridState()
    private var page = 1
    private var hasMore = true
    private var job: Job? = null
    private var gen = 0
    private var lastKey: String? = null

    init { viewModelScope.launch { runCatching { genres = Api.genres() } } }

    private fun key() = "${query.trim()}|$sort|$ongoing|${genre?.id}"

    fun refreshIfNeeded() { if (key() != lastKey) reload() }

    fun reload() {
        job?.cancel(); gen++; page = 1; hasMore = true; items = emptyList(); lastKey = key()
        run()
    }

    fun loadMore() { if (!loading && hasMore && error == null) run() }
    fun retry() { error = null; run() }

    private fun run() {
        val my = gen; loading = true; error = null
        job = viewModelScope.launch {
            try {
                val q = query.trim()
                if (q.isNotEmpty()) { items = Api.search(q).distinctBy { it.id }; hasMore = false }
                else {
                    val p = Api.catalog(page, sort, ongoing, genre?.id)
                    if (my == gen) { items = (items + p.items).distinctBy { it.id }; hasMore = p.hasMore; page++ }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (my == gen) error = e.message ?: "Нет сети" }
            finally { if (my == gen) loading = false }
        }
    }
}

@Composable
fun CatalogScreen(prefs: Prefs, onOpen: (Int) -> Unit) {
    val vm: CatalogVM = viewModel()
    LaunchedEffect(vm.query, vm.sort, vm.ongoing, vm.genre) {
        if (vm.query.isNotBlank()) delay(250) // дебаунс: живой поиск без лишних запросов
        vm.refreshIfNeeded()
    }
    LaunchedEffect(vm.grid) {
        snapshotFlow {
            val l = vm.grid.layoutInfo
            (l.visibleItemsInfo.lastOrNull()?.index ?: 0) to l.totalItemsCount
        }.collect { (last, total) -> if (total > 0 && last >= total - 8) vm.loadMore() }
    }
    LazyVerticalGrid(
        state = vm.grid, columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                Text("Аниме", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(14.dp))
                SearchField(vm.query) { vm.query = it }
                Spacer(Modifier.height(12.dp))
                FilterRow(vm)
            }
        }
        if (vm.query.isBlank() && prefs.history.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            ContinueRow(prefs, onOpen)
        }
        if (vm.query.isBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(if (vm.genre != null) vm.genre!!.name else if (vm.ongoing) "Онгоинги" else vm.sort.label,
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
        }
        items(vm.items, key = { it.id }) { AnimeCard(it, onClick = { onOpen(it.id) }) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                when {
                    vm.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ошибка: ${vm.error}", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton({ vm.retry() }) { Text("Повторить") }
                    }
                    vm.loading -> CircularProgressIndicator()
                    vm.items.isEmpty() -> Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text("Поиск аниме") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Rounded.Close, "Очистить") }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterRow(vm: CatalogVM) {
    var menu by remember { mutableStateOf(false) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        item {
            Box {
                FilterChip(selected = false, onClick = { menu = true }, label = { Text(vm.sort.label) },
                    leadingIcon = { Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) })
                DropdownMenu(menu, { menu = false }) {
                    Sort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { vm.sort = s; menu = false },
                            leadingIcon = { if (s == vm.sort) Icon(Icons.Rounded.Check, null) })
                    }
                }
            }
        }
        item {
            FilterChip(selected = vm.ongoing, onClick = { vm.ongoing = !vm.ongoing }, label = { Text("Онгоинги") },
                leadingIcon = { if (vm.ongoing) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) })
        }
        items(vm.genres, key = { it.id }) { g ->
            FilterChip(selected = vm.genre?.id == g.id, onClick = { vm.genre = if (vm.genre?.id == g.id) null else g },
                label = { Text(g.name) })
        }
    }
}

@Composable
fun ContinueRow(prefs: Prefs, onOpen: (Int) -> Unit) {
    Column {
        Text("Продолжить просмотр", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(prefs.history, key = { it.card.id }) { h ->
                Box(
                    Modifier.width(260.dp).height(150.dp).clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onOpen(h.card.id) },
                ) {
                    Poster(h.card.thumb, Modifier.fillMaxSize(), Alignment.TopCenter)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                        Text(h.card.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        Text("Серия ${h.ordinal}", color = Color.White.copy(.8f), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (h.dur > 0) (h.pos.toFloat() / h.dur).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            trackColor = Color.White.copy(.25f),
                        )
                    }
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(36.dp)
                            .background(Color.Black.copy(.4f), CircleShape).padding(6.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun Poster(url: String?, modifier: Modifier = Modifier, align: Alignment = Alignment.Center) {
    val ctx = LocalContext.current
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (url != null) AsyncImage(
            ImageRequest.Builder(ctx).data(url).crossfade(180).build(), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = align,
        )
    }
}

@Composable
fun AnimeCard(c: Card, onClick: () -> Unit) {
    Column(Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.large)) {
            Poster(c.thumb, Modifier.fillMaxSize())
            if (c.ongoing) Text(
                "Онгоинг", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            if (c.episodes > 0) Text(
                "${c.episodes} эп.", color = Color.White, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color.Black.copy(.6f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
        Text("${c.year} · ${c.type}", maxLines = 1, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
    }
}

@Composable
fun FavoritesScreen(prefs: Prefs, onOpen: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Избранное", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 4.dp))
        }
        if (prefs.favorites.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Пока пусто", style = MaterialTheme.typography.titleLarge)
                Text("Нажмите ♡ на странице аниме", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(prefs.favorites, key = { it.id }) { AnimeCard(it, onClick = { onOpen(it.id) }) }
    }
}
