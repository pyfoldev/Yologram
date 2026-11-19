package ru.reader.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun buildPlayer(ctx: Context): ExoPlayer {
    val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(8000).setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(true)
    // Маленькие пороги буфера — быстрый старт и быстрая перемотка
    val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 800, 1500).build()
    return ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(HlsMediaSource.Factory(http).setAllowChunklessPreparation(true))
        .setLoadControl(load)
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .build()
}

private fun pickQuality(ep: Episode, want: Int): Int {
    val q = ep.urls.keys.sorted()
    return q.lastOrNull { it <= want } ?: q.first()
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

@Composable
fun PlayerScreen(id: Int, startOrdinal: Int?, prefs: Prefs, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<Detail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        error = null
        try { detail = Api.detail(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Нет сети" }
    }
    val d = detail
    if (d == null || d.episodes.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    error != null -> { Text("Ошибка: $error", color = Color.White); FilledTonalButton({ attempt++ }) { Text("Повторить") } }
                    d != null -> Text("Серий нет", color = Color.White)
                    else -> CircularProgressIndicator()
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onBack) { Text("Назад") }
            }
        }
        BackHandler(onBack = onBack)
        return
    }
    PlayerContent(d, startOrdinal, prefs, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(d: Detail, startOrdinal: Int?, prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val scope = rememberCoroutineScope()
    val eps = d.episodes
    val player = remember { buildPlayer(ctx) }

    var index by remember { mutableIntStateOf(eps.indexOfFirst { it.ordinal == startOrdinal }.takeIf { it >= 0 } ?: 0) }
    var quality by remember { mutableIntStateOf(pickQuality(eps[index], prefs.quality)) }
    var speed by remember { mutableFloatStateOf(prefs.speed) }
    var resize by remember { mutableIntStateOf(prefs.resize) }
    var controls by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }
    var touch by remember { mutableIntStateOf(0) }
    var playError by remember { mutableStateOf<String?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var fullscreen by remember { mutableStateOf(landscape) }
    var skipped by remember { mutableStateOf(setOf<Int>()) }

    val ep = eps[index]

    fun showHud(t: String) { hud = t; hudTick++ }

    fun load(i: Int, q: Int, startMs: Long = prefs.position(d.card.id, eps[i].ordinal), play: Boolean = true) {
        val e = eps[i]
        val use = pickQuality(e, q)
        index = i; quality = use; playError = null
        player.setMediaItem(
            MediaItem.Builder().setUri(e.urls.getValue(use)).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
            if (startMs > 3000) startMs else 0L,
        )
        player.prepare(); player.setPlaybackSpeed(speed); player.playWhenReady = play
    }

    fun seekBy(sec: Int) {
        val t = (player.currentPosition + sec * 1000L).coerceIn(0L, if (player.duration > 0) player.duration else Long.MAX_VALUE)
        player.seekTo(t); showHud(if (sec > 0) "+$sec с" else "−${-sec} с")
    }

    fun setFullscreen(on: Boolean) {
        fullscreen = on
        activity.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        if (!on) scope.launch { delay(1200); activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // старт
    LaunchedEffect(Unit) { load(index, prefs.quality) }
    LaunchedEffect(landscape) { fullscreen = landscape }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    prefs.saveProgress(d.card, eps[index].ordinal, player.duration, player.duration)
                    if (prefs.autoNext && index + 1 < eps.size) load(index + 1, quality, 0) else controls = true
                }
            }
            override fun onPlayerError(error: PlaybackException) { playError = "Не удалось воспроизвести (${error.errorCodeName})" }
        }
        player.addListener(l)
        onDispose {
            prefs.saveProgress(d.card, eps[index].ordinal, player.currentPosition, player.duration.coerceAtLeast(0))
            player.removeListener(l); player.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // позиция, автопропуск заставки, сохранение
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            if (seeking == null) pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0); buffered = player.bufferedPosition
            val e = eps[index]
            if (prefs.autoSkip && e.openStart != null && e.openStop != null && index !in skipped) {
                val s = pos / 1000
                if (s in e.openStart until e.openStop) { player.seekTo(e.openStop * 1000L); skipped = skipped + index; showHud("Заставка пропущена") }
            }
            if (++tick % 20 == 0 && dur > 0) prefs.saveProgress(d.card, e.ordinal, pos, dur)
            delay(250)
        }
    }

    // авто-скрытие панели
    LaunchedEffect(controls, playing, touch, sheet) {
        if (controls && playing && !sheet) { delay(3500); controls = false }
    }
    LaunchedEffect(hudTick) { if (hud != null) { delay(900); hud = null } }

    // системные панели, экран не гаснет
    DisposableEffect(fullscreen) {
        val w = activity.window
        val c = WindowCompat.getInsetsController(w, w.decorView)
        if (fullscreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            w.attributes = w.attributes.also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        } else c.show(WindowInsetsCompat.Type.systemBars())
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(Unit) {
        val w = activity.window
        val c = WindowCompat.getInsetsController(w, w.decorView)
        val prevS = c.isAppearanceLightStatusBars; val prevN = c.isAppearanceLightNavigationBars
        c.isAppearanceLightStatusBars = false; c.isAppearanceLightNavigationBars = false
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            c.isAppearanceLightStatusBars = prevS; c.isAppearanceLightNavigationBars = prevN
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    BackHandler { if (fullscreen) setFullscreen(false) else onBack() }

    val inOpening = ep.openStart != null && ep.openStop != null && (pos / 1000) in ep.openStart until ep.openStop

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.background(Color.Black).then(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth())) {
            Box(
                Modifier.then(if (fullscreen) Modifier.fillMaxSize() else Modifier.statusBarsPadding().fillMaxWidth().aspectRatio(16f / 9f)),
            ) {
                AndroidView(
                    factory = { c -> PlayerView(c).apply { useController = false; this.player = player; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
                    update = {
                        it.resizeMode = when (resize) {
                            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // жесты: тап — панель, двойной тап — перемотка, свайп по краям — яркость/громкость
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(prefs.seekStep) {
                            detectTapGestures(
                                onTap = { controls = !controls; touch++ },
                                onDoubleTap = { o -> seekBy(if (o.x < size.width / 2) -prefs.seekStep else prefs.seekStep) },
                            )
                        }
                        .pointerInput(fullscreen) {
                            var left = true
                            var acc = 0f
                            detectVerticalDragGestures(
                                onDragStart = { o -> left = o.x < size.width / 2; acc = 0f },
                                onVerticalDrag = { _, dy ->
                                    acc += -dy / size.height
                                    if (kotlin.math.abs(acc) < 0.02f) return@detectVerticalDragGestures
                                    if (left) {
                                        val w = activity.window; val lp = w.attributes
                                        val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                        lp.screenBrightness = (cur + acc).coerceIn(0.02f, 1f); w.attributes = lp
                                        showHud("Яркость ${(lp.screenBrightness * 100).toInt()}%")
                                    } else {
                                        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        val v = (am.getStreamVolume(AudioManager.STREAM_MUSIC) + acc * max).toInt().coerceIn(0, max)
                                        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                        showHud("Громкость ${v * 100 / max}%")
                                    }
                                    acc = 0f
                                },
                            )
                        },
                )

                if (buffering && playError == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White)

                hud?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                            .background(Color.Black.copy(.65f), CircleShape).padding(horizontal = 18.dp, vertical = 8.dp))
                }
                playError?.let {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(it, color = Color.White, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton({ load(index, quality, player.currentPosition) }) { Text("Повторить") }
                    }
                }

                Fade(controls) {
                    Controls(
                        title = d.card.title, subtitle = "Серия ${ep.ordinal}", fullscreen = fullscreen,
                        playing = playing, pos = seeking?.toLong() ?: pos, dur = dur, buffered = buffered,
                        quality = quality, speed = speed, resize = resize, step = prefs.seekStep,
                        hasPrev = index > 0, hasNext = index + 1 < eps.size, inOpening = inOpening,
                        onBack = { if (fullscreen) setFullscreen(false) else onBack() },
                        onPlayPause = { if (player.isPlaying) player.pause() else player.play(); touch++ },
                        onPrev = { load(index - 1, quality, 0) }, onNext = { load(index + 1, quality, 0) },
                        onSeekBy = { seekBy(it); touch++ },
                        onSeekChange = { seeking = it; touch++ },
                        onSeekDone = { seeking?.let { s -> player.seekTo(s.toLong()) }; seeking = null },
                        onSettings = { sheet = true },
                        onScale = { resize = (resize + 1) % 3; prefs.putResize(resize); showHud(listOf("Вписать", "Заполнить", "Растянуть")[resize]) },
                        onFullscreen = { setFullscreen(!fullscreen) },
                        onSkipOpening = { ep.openStop?.let { player.seekTo(it * 1000L) } },
                    )
                }
            }
        }

        if (!fullscreen) {
            LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(d.card.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Серия ${ep.ordinal}${ep.name?.let { " · $it" } ?: ""} · ${quality}p",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(eps) { i, e ->
                    val cur = i == index
                    ListItem(
                        modifier = Modifier.padding(horizontal = 12.dp).clip(MaterialTheme.shapes.large).clickable { if (!cur) load(i, quality) },
                        colors = ListItemDefaults.colors(containerColor = if (cur) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                        leadingContent = {
                            Box(Modifier.size(44.dp).background(
                                if (cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (cur) Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
                                else Text("${e.ordinal}", style = MaterialTheme.typography.titleSmall)
                            }
                        },
                        headlineContent = { Text(e.name ?: "Серия ${e.ordinal}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { if (e.duration > 0) Text("${e.duration / 60} мин") },
                    )
                }
            }
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Параметры воспроизведения", style = MaterialTheme.typography.titleLarge)
            Text("Качество", style = MaterialTheme.typography.titleMedium)
            val qs = listOf(480, 720, 1080)
            Segmented(qs.map { "${it}p" }, qs.indexOf(quality).coerceAtLeast(0), enabled = { qs[it] in ep.urls }) {
                val t = player.currentPosition; val was = player.playWhenReady
                prefs.putQuality(qs[it]); load(index, qs[it], t, was)
            }
            Text("Масштаб", style = MaterialTheme.typography.titleMedium)
            Segmented(listOf("Вписать", "Заполнить", "Растянуть"), resize) { resize = it; prefs.putResize(it) }
            SliderRow("Скорость", "%.2f×".format(speed), speed, 0.5f..2f, 5) {
                speed = (it * 4).let { v -> kotlin.math.round(v) } / 4f; player.setPlaybackSpeed(speed)
            }
            SwitchRow("Автопереход", null, prefs.autoNext) { prefs.putAutoNext(it) }
            SwitchRow("Пропускать заставку", null, prefs.autoSkip) { prefs.putAutoSkip(it) }
        }
    }
}

@Composable
private fun Controls(
    title: String, subtitle: String, fullscreen: Boolean, playing: Boolean, pos: Long, dur: Long, buffered: Long,
    quality: Int, speed: Float, resize: Int, step: Int, hasPrev: Boolean, hasNext: Boolean, inOpening: Boolean,
    onBack: () -> Unit, onPlayPause: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onSeekBy: (Int) -> Unit,
    onSeekChange: (Float) -> Unit, onSeekDone: () -> Unit, onSettings: () -> Unit, onScale: () -> Unit,
    onFullscreen: () -> Unit, onSkipOpening: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color(0x33000000), Color(0xCC000000))))
            .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier),
    ) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = Color.White.copy(.75f), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onSettings) { Icon(Icons.Rounded.Tune, "Параметры", tint = Color.White) }
        }

        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 32.dp else 14.dp)) {
            IconButton(onPrev, enabled = hasPrev) { Icon(Icons.Rounded.SkipPrevious, "Пред.", tint = Color.White.copy(if (hasPrev) 1f else .3f), modifier = Modifier.size(36.dp)) }
            SeekIcon(step, false) { onSeekBy(-step) }
            FilledIconButton(onPlayPause, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            SeekIcon(step, true) { onSeekBy(step) }
            IconButton(onNext, enabled = hasNext) { Icon(Icons.Rounded.SkipNext, "След.", tint = Color.White.copy(if (hasNext) 1f else .3f), modifier = Modifier.size(36.dp)) }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (inOpening) Button(onSkipOpening, Modifier.align(Alignment.End), colors = ButtonDefaults.buttonColors(
                containerColor = Color.White, contentColor = Color.Black)) {
                Icon(Icons.Rounded.FastForward, null); Spacer(Modifier.width(6.dp)); Text("Пропустить заставку")
            }
            Slider(
                value = pos.toFloat().coerceIn(0f, dur.coerceAtLeast(1).toFloat()),
                onValueChange = onSeekChange, onValueChangeFinished = onSeekDone,
                valueRange = 0f..dur.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(.3f), thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${fmt(pos)} / ${fmt(dur)}", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                Pill("${quality}p", onClick = onSettings)
                Spacer(Modifier.width(8.dp))
                Pill("%s×".format(if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()), onClick = onSettings)
                Spacer(Modifier.width(8.dp))
                Pill(icon = if (resize == 0) Icons.Rounded.AspectRatio else Icons.Rounded.ZoomOutMap, onClick = onScale)
                Spacer(Modifier.width(8.dp))
                Pill(icon = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, onClick = onFullscreen)
            }
        }
    }
}

@Composable
private fun SeekIcon(step: Int, forward: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Replay, null, tint = Color.White, modifier = Modifier.size(40.dp).graphicsLayer { scaleX = if (forward) -1f else 1f })
            Text("$step", color = Color.White, fontSize = 11.sp, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Pill(text: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(.18f)).clickable(onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = if (text != null) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (text != null) Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Fade(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { content() }
}
