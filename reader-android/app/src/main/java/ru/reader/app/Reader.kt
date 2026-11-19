package ru.reader.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(item: Item, prefs: Prefs, starred: Boolean, onToggleSave: () -> Unit, onBack: () -> Unit) {
    var fontSize by remember { mutableIntStateOf(prefs.fontSize) }
    val isWiki = item.target is Target.Wiki
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (isWiki) {
                        TextButton({ fontSize = (fontSize - 2).coerceAtLeast(12); prefs.fontSize = fontSize }) { Text("A−") }
                        TextButton({ fontSize = (fontSize + 2).coerceAtMost(32); prefs.fontSize = fontSize }) { Text("A+") }
                    }
                    IconButton(onToggleSave) {
                        Icon(if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder, "Закладка")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val t = item.target) {
                is Target.Anime -> AnimeScreen(t.id, prefs)
                is Target.Wiki -> WikiScreen(t.title, fontSize)
                is Target.Web -> WebScreen(t.url)
                is Target.Link -> LinkScreen(t.url)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimeScreen(id: Int, prefs: Prefs) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<AnimeData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableIntStateOf(-1) }
    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(id) {
        try { data = Api.anime(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    val eps = data?.episodes.orEmpty()

    fun play(i: Int) {
        val ep = eps.getOrNull(i) ?: return
        val url = ep.url ?: return
        current = i
        prefs.setEpisode(id, ep.ordinal)
        player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        player.prepare()
        player.playWhenReady = true
    }
    DisposableEffect(eps) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED && current + 1 < eps.size) play(current + 1) }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(eps) {
        if (eps.isNotEmpty() && current < 0) {
            val last = prefs.episode(id)
            play(eps.indexOfFirst { it.ordinal == last }.takeIf { it >= 0 } ?: 0)
            player.playWhenReady = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        error?.let { Text("Ошибка: $it", Modifier.padding(16.dp)) }
        if (data == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        data?.let { d ->
            if (eps.isEmpty()) Text("Серий нет (релиз мог быть заблокирован правообладателем).", Modifier.padding(16.dp))
            FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                eps.forEachIndexed { i, ep ->
                    FilterChip(selected = i == current, onClick = { play(i) }, label = { Text("${ep.ordinal}") })
                }
            }
            Text(d.description, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun WikiScreen(title: String, fontSize: Int) {
    var paragraphs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(title) {
        try {
            val html = Api.wikiText(title)
                .replace(Regex("<(style|script)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), "")
            val text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace("[править]", "")
            paragraphs = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    when {
        error != null -> Text("Ошибка: $error", Modifier.padding(16.dp))
        paragraphs == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(paragraphs!!) { Text(it, fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}

@Composable
fun LinkScreen(url: String) {
    val ctx = LocalContext.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Для этого издания нет встроенного просмотра.")
        Button({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Открыть на сайте источника") }
    }
}
