package com.pyfold.ubuntucontrol

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ───────────── Логотип ─────────────

/** «Круг друзей»: градиентный оранжевый круг, белое кольцо и три точки. */
@Composable
fun UbuntuLogo(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2
        drawCircle(Brush.linearGradient(listOf(Color(0xFFF47B3C), UbuntuOrange, Color(0xFFC73E0E))), r, c)
        val ring = r * 0.42f
        drawCircle(Color.White, ring, c, style = Stroke(width = r * 0.11f))
        listOf(-90f, 30f, 150f).forEach { deg ->
            val a = deg * PI.toFloat() / 180f
            val p = Offset(c.x + cos(a) * ring, c.y + sin(a) * ring)
            drawCircle(UbuntuOrange, r * 0.18f, p)
            drawCircle(Color.White, r * 0.12f, p)
        }
    }
}

/** Логотип со свечением и вращающимся пунктирным кольцом, пока соединение активно. */
@Composable
private fun HeroLogo(size: Dp, online: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "hero")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "rot")
    val pulse by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "pulse")
    Box(modifier.size(size * 1.35f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val glow = if (online) pulse else 0.25f
            drawCircle(
                Brush.radialGradient(listOf(UbuntuOrange.copy(alpha = 0.38f * glow), Color.Transparent), center, this.size.minDimension / 2),
                this.size.minDimension / 2, center,
            )
            val rr = this.size.minDimension / 2 * 0.93f
            rotate(if (online) angle else 0f, center) {
                drawCircle(
                    Color.White.copy(alpha = if (online) 0.22f else 0.08f), rr, center,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 10.dp.toPx()))),
                )
            }
        }
        UbuntuLogo(size)
    }
}

// ───────────── Общие элементы ─────────────

@Composable
fun StatusChip(state: Conn) {
    val (color, text) = when (state) {
        Conn.Online -> Good to "В сети"
        Conn.Connecting -> Warn to "Подключение…"
        Conn.Idle -> TextDim to "Не подключено"
        is Conn.Failed -> Bad to "Нет связи"
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
            Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun IconBubble(icon: ImageVector, tint: Color = UbuntuOrange, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String, subtitle: String? = null, onBack: () -> Unit, showBack: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = { if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        content = content,
    )
}

// ───────────── Главный экран ─────────────

private data class Stats(val host: String, val uptime: String, val load: String, val cpu: Float, val ram: Int, val disk: Int, val temp: Int?)

@Composable
fun DashboardScreen(onOpen: (Target) -> Unit, onSettings: () -> Unit) {
    val conn by SshManager.state.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf<Stats?>(null) }
    var pending by remember { mutableStateOf<RingItem?>(null) }
    var latency by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(conn) {
        while (conn == Conn.Online) {
            latency = SshManager.latencyMs().takeIf { it >= 0 }
            val r = SshManager.exec(
                "echo \"\$(hostname)|\$(uptime -p | sed 's/up //')|\$(cut -d' ' -f1 /proc/loadavg)|\$(nproc)|" +
                    "\$(free | awk '/Mem/{printf \"%d\", \$3*100/\$2}')|\$(df / | awk 'NR==2{gsub(\"%\",\"\",\$5); print \$5}')|" +
                    "\$(( \$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo 0) / 1000 ))\"", 15_000)
            val p = r.out.trim().split("|")
            if (p.size == 7) {
                val load = p[2].toFloatOrNull() ?: 0f
                val cores = p[3].toFloatOrNull()?.coerceAtLeast(1f) ?: 1f
                stats = Stats(p[0], p[1], p[2], (load / cores).coerceIn(0f, 1f), p[4].toIntOrNull() ?: 0,
                    p[5].toIntOrNull() ?: 0, p[6].toIntOrNull()?.takeIf { it > 0 })
            }
            delay(6000)
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stats?.host ?: SshManager.host, style = MaterialTheme.typography.titleLarge)
                Text("${SshManager.user}@${SshManager.host}" + (latency?.let { " · $it мс" } ?: ""), style = MaterialTheme.typography.bodySmall, color = TextDim)
            }
            StatusChip(conn)
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Настройки", tint = TextDim) }
        }
        if (conn is Conn.Failed) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WifiOff, null, tint = Bad)
                    Spacer(Modifier.width(10.dp))
                    Text("${(conn as Conn.Failed).msg.take(120)}\nВключите Tailscale на телефоне — переподключусь сам.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val side = min(maxWidth, maxHeight)
            val btn = (side.value * 0.165f).coerceIn(44f, 58f).dp
            val radius = (side - btn - 26.dp) / 2
            Box(Modifier.align(Alignment.Center).size(side)) {
                val items = Actions.ring
                HeroLogo(size = radius * 0.78f, online = conn == Conn.Online, modifier = Modifier.align(Alignment.Center))
                items.forEachIndexed { i, item ->
                    val a = (-90.0 + i * 360.0 / items.size) * PI / 180.0
                    val tint = if (item.danger) Bad else UbuntuOrange
                    Column(
                        Modifier.align(Alignment.Center)
                            .offset((cos(a) * radius.value).dp, (sin(a) * radius.value).dp).width(btn + 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            onClick = { if (item.target is Target.Run) pending = item else onOpen(item.target) },
                            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, Brush.linearGradient(listOf(tint.copy(alpha = .9f), tint.copy(alpha = .25f)))),
                            shadowElevation = 4.dp, modifier = Modifier.size(btn),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(btn * 0.46f)) }
                        }
                        Text(item.label, style = MaterialTheme.typography.labelSmall, color = TextDim, maxLines = 1,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }

        // метрики
        val s = stats
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(Icons.Rounded.Speed, "CPU", s?.let { "${(it.cpu * 100).toInt()}%" } ?: "—", s?.cpu, Modifier.weight(1f))
            StatCard(Icons.Rounded.Memory, "ОЗУ", s?.let { "${it.ram}%" } ?: "—", s?.let { it.ram / 100f }, Modifier.weight(1f))
            StatCard(Icons.Rounded.Storage, "Диск", s?.let { "${it.disk}%" } ?: "—", s?.let { it.disk / 100f }, Modifier.weight(1f))
            StatCard(Icons.Rounded.Thermostat, "Темп.", s?.temp?.let { "$it°" } ?: "—", s?.temp?.let { (it / 90f).coerceIn(0f, 1f) }, Modifier.weight(1f))
        }
        s?.let {
            Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, null, tint = TextDim, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Работает ${it.uptime} · нагрузка ${it.load}", style = MaterialTheme.typography.bodySmall, color = TextDim)
            }
        }
    }

    pending?.let { item ->
        val run = (item.target as Target.Run).cmd
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(item.icon, null, tint = Bad) },
            title = { Text(run.title) }, text = { Text(run.confirm ?: "Выполнить?") },
            confirmButton = { TextButton(onClick = { pending = null; onOpen(item.target) }) { Text(run.title, color = Bad) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun StatCard(icon: ImageVector, label: String, value: String, progress: Float?, modifier: Modifier = Modifier) {
    val color = when {
        progress == null -> TextDim
        progress > 0.85f -> Bad
        progress > 0.65f -> Warn
        else -> UbuntuOrange
    }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextDim, maxLines = 1)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp), maxLines = 1)
            LinearProgressIndicator(
                progress = { progress ?: 0f }, color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

// ───────────── Меню команд ─────────────

private fun iconFor(c: Cmd): ImageVector {
    val t = c.title.lowercase()
    return when {
        "перезапуст" in t || "перезагруз" in t -> Icons.Rounded.RestartAlt
        "остановить" in t || "завершить" in t -> Icons.Rounded.StopCircle
        "запустить" in t -> Icons.Rounded.PlayCircle
        "лог" in t || "журнал" in t || "dmesg" in t || "ядро" in t -> Icons.Rounded.Article
        "пинг" in t || "netcheck" in t -> Icons.Rounded.NetworkCheck
        "обновл" in t || "установить" in t || "проверить" in t -> Icons.Rounded.SystemUpdateAlt
        "очистка" in t -> Icons.Rounded.CleaningServices
        "wi" in t -> Icons.Rounded.Wifi
        "dns" in t || "адрес" in t || "ip" in t || "маршрут" in t -> Icons.Rounded.Dns
        "порт" in t || "файрвол" in t -> Icons.Rounded.Security
        "диск" in t || "место" in t || "память" in t || "файл" in t || "образ" in t -> Icons.Rounded.Storage
        "топ" in t || "нагруз" in t || "процесс" in t -> Icons.Rounded.Speed
        "температур" in t -> Icons.Rounded.Thermostat
        "бэкап" in t -> Icons.Rounded.Backup
        "статус" in t || "состояние" in t -> Icons.Rounded.MonitorHeart
        "служб" in t || "контейнер" in t || "таймер" in t || "автозапуск" in t -> Icons.Rounded.Tune
        "вход" in t || "кто" in t -> Icons.Rounded.Person
        else -> Icons.Rounded.Terminal
    }
}

@Composable
fun MenuScreen(menu: Target.Menu, onBack: () -> Unit, onRun: (String, String) -> Unit) {
    var dialog by remember { mutableStateOf<Cmd?>(null) }
    var arg by remember { mutableStateOf("") }

    fun fire(c: Cmd, a: String?) {
        val cmd = if (a != null) c.cmd.replace("%s", SshManager.shq(a)) else c.cmd
        onRun(c.title.removeSuffix("…"), cmd)
    }

    AppScaffold(menu.title, onBack = onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(menu.items) { c ->
                val danger = c.confirm != null
                Card(
                    onClick = { arg = ""; if (c.arg != null || c.confirm != null) dialog = c else fire(c, null) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(iconFor(c), if (danger) Warn else UbuntuOrange)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title.removeSuffix("…"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            val sub = when {
                                c.arg != null && danger -> "Нужен ввод и подтверждение"
                                c.arg != null -> "Нужен ввод: ${c.arg.lowercase()}"
                                danger -> "Потребуется подтверждение"
                                else -> null
                            }
                            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (danger) Warn else TextDim) }
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = TextDim)
                    }
                }
            }
        }
    }

    dialog?.let { c ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(iconFor(c), null, tint = if (c.confirm != null) Warn else UbuntuOrange) },
            title = { Text(c.title.removeSuffix("…")) },
            text = {
                Column {
                    if (c.confirm != null) Text(c.confirm)
                    if (c.arg != null) {
                        OutlinedTextField(arg, { arg = it }, singleLine = true, label = { Text(c.arg) },
                            placeholder = { c.hint?.let { Text(it) } }, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = c.arg == null || arg.isNotBlank(), onClick = {
                    val a = if (c.arg != null) arg.trim() else null
                    dialog = null; fire(c, a)
                }) { Text("Выполнить") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
        )
    }
}

// ───────────── Вывод команды ─────────────

private fun colorize(text: String): AnnotatedString = buildAnnotatedString {
    val red = Regex("(?i)\\b(error|failed|fail|ошибка|denied|not found|no such|inactive|dead|critical)\\b")
    val green = Regex("(?i)\\b(active \\(running\\)|running|ok|up|healthy|enabled|success)\\b")
    text.lines().forEachIndexed { i, line ->
        if (i > 0) append("\n")
        val color = when {
            red.containsMatchIn(line) -> Bad.copy(alpha = 0.95f)
            green.containsMatchIn(line) && line.length < 90 -> Good
            else -> Color(0xFFE6E6EA)
        }
        withStyle(SpanStyle(color = color)) { append(line) }
    }
}

@Composable
fun OutputScreen(title: String, command: String, onBack: () -> Unit) {
    var out by remember { mutableStateOf<ExecResult?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val clip = LocalClipboardManager.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tick) {
        out = null
        val isPower = Regex("systemctl (reboot|poweroff)").containsMatchIn(command)
        val r = SshManager.exec(command, 120_000)
        out = if (isPower) ExecResult(0, "Команда отправлена.\nСервер станет недоступен — приложение переподключится само, когда он вернётся.") else r
    }
    AppScaffold(title, onBack = onBack, snackbar = snack, actions = {
        IconButton(onClick = { tick++ }) { Icon(Icons.Rounded.Refresh, "Обновить") }
        IconButton(enabled = out != null, onClick = {
            out?.let { clip.setText(AnnotatedString(it.out)); scope.launch { snack.showSnackbar("Скопировано") } }
        }) { Icon(Icons.Rounded.ContentCopy, "Копировать") }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            val o = out
            if (o == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = UbuntuOrange)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = UbuntuOrange)
                        Text("Выполняю…", color = TextDim, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            } else {
                val ok = o.code == 0
                Surface(shape = CircleShape, color = (if (ok) Good else Bad).copy(alpha = 0.14f), modifier = Modifier.padding(bottom = 10.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) Good else Bad, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ok) "Успешно" else "Код выхода ${o.code}", color = if (ok) Good else Bad, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Surface(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                        SelectionContainer {
                            Text(colorize(o.out.ifBlank { "(пустой вывод)" }), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

// ───────────── Терминал ─────────────

@Composable
fun TerminalScreen(onBack: () -> Unit = {}, showBack: Boolean = true) {
    val scope = rememberCoroutineScope()
    var term by remember { mutableStateOf<TermSession?>(null) }
    var input by remember { mutableStateOf("") }
    var fail by remember { mutableStateOf<String?>(null) }
    val conn by SshManager.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

    LaunchedEffect(conn) {
        if (conn == Conn.Online && (term == null || term?.closed?.value == true)) {
            fail = null
            term = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SshManager.ensureTerm() } }
            catch (e: Exception) { fail = "Не удалось открыть сессию: ${e.message}"; null }
        }
        if (conn != Conn.Online) term = null
    }
    // команда, присланная из других экранов («Мои команды», «Файлы»), вставляется в поле ввода
    LaunchedEffect(term) {
        SshManager.pendingTermInput?.let { input = it; SshManager.pendingTermInput = null }
    }

    val text by (term?.text ?: remember { MutableStateFlow("") }).collectAsStateWithLifecycle()
    val closed by (term?.closed ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    val fontSize = (SecureStore.get("fs", "13").toIntOrNull() ?: 13).sp

    fun sendLine() { term?.send(input + "\n"); input = "" }

    AppScaffold("Терминал", "${SshManager.user}@${SshManager.host}", onBack = onBack, showBack = showBack, actions = {
        IconButton(onClick = { SshManager.resetTerm(); term = null; fail = null
            scope.launch { term = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SshManager.ensureTerm() } } catch (e: Exception) { fail = "Не удалось открыть сессию: ${e.message}"; null } }
        }) { Icon(Icons.Rounded.Refresh, "Новая сессия") }
        IconButton(onClick = { term?.clear() }) { Icon(Icons.Rounded.DeleteSweep, "Очистить") }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            Surface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), shape = MaterialTheme.shapes.large,
                color = Color(0xFF050506), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.verticalScroll(scroll).padding(12.dp)) {
                    SelectionContainer {
                        Text(
                            when {
                                term == null && conn != Conn.Online -> "Нет подключения к серверу…"
                                term == null -> fail ?: "Открываю сессию…"
                                else -> text + if (closed) "\n[сессия завершена — выйдите и откройте заново]" else ""
                            },
                            fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize * 1.3f, color = Color(0xFFE8E8EC),
                        )
                    }
                }
            }
            LazyRow(Modifier.padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(
                    "Tab" to "\t", "Esc" to "\u001b", "Ctrl+C" to "\u0003", "Ctrl+D" to "\u0004", "Ctrl+L" to "\u000c",
                    "Ctrl+Z" to "\u001a", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
                    "~" to "~", "/" to "/", "|" to "|", "-" to "-",
                )) { (label, seq) ->
                    AssistChip(onClick = { term?.send(seq) }, label = { Text(label, fontFamily = FontFamily.Monospace) },
                        shape = CircleShape, colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = null)
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    input, { input = it }, Modifier.weight(1f), singleLine = true, shape = CircleShape,
                    placeholder = { Text("Введите команду…") },
                    leadingIcon = { Icon(Icons.Rounded.ChevronRight, null, tint = UbuntuOrange) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { sendLine() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { sendLine() }, modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = UbuntuOrange)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Отправить", tint = Color.White)
                }
            }
        }
    }
}

// ───────────── Первая настройка ─────────────

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var host by remember { mutableStateOf(SshManager.host) }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf(SshManager.user) }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var sudo by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        HeroLogo(size = 84.dp, online = busy)
        Text("Ubuntu Control", style = MaterialTheme.typography.headlineMedium)
        Text("Войдите один раз по паролю — приложение создаст свой SSH‑ключ, установит его на сервер и дальше подключается автоматически. Пароль не сохраняется.",
            color = TextDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Адрес сервера (Tailscale IP)") },
                    leadingIcon = { Icon(Icons.Rounded.Dns, null) }, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(user, { user = it }, Modifier.weight(1f), singleLine = true, label = { Text("Пользователь") },
                        leadingIcon = { Icon(Icons.Rounded.Person, null) }, shape = MaterialTheme.shapes.medium)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.width(92.dp), singleLine = true, label = { Text("Порт") },
                        shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пароль") },
                    leadingIcon = { Icon(Icons.Rounded.Key, null) }, shape = MaterialTheme.shapes.medium,
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Показать пароль")
                        }
                    },
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Управление без пароля", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("Ограниченное правило sudoers: systemctl, apt, docker, tailscale, ufw — для перезагрузки и служб.",
                            style = MaterialTheme.typography.bodySmall, color = TextDim)
                    }
                    Switch(sudo, { sudo = it }, colors = SwitchDefaults.colors(checkedTrackColor = UbuntuOrange))
                }
            }
        }
        error?.let {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = Bad)
                    Spacer(Modifier.width(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        status?.let { Text(it, color = OrangeSoft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp)) }
        Button(
            enabled = !busy && host.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(),
            onClick = {
                busy = true; error = null
                scope.launch {
                    val script = ctx.resources.openRawResource(R.raw.uc_lockdown).bufferedReader().readText()
                    val r = SshManager.setup(host.trim(), port.toIntOrNull() ?: 22, user.trim(), pass, sudo, script) { status = it }
                    busy = false
                    r.onSuccess { SshManager.startAutoConnect(); onDone() }
                        .onFailure { error = "Ошибка: ${it.message}"; status = null }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = UbuntuOrange),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(54.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else { Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(8.dp)); Text("Подключиться", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VpnLock, null, tint = TextDim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("На телефоне должен быть включён Tailscale", color = TextDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit, onReset: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Settings, null, tint = UbuntuOrange) },
        title = { Text("Настройки") },
        text = {
            Text("Сервер: ${SshManager.user}@${SshManager.host}\n\nСброс удалит ключ из приложения. На сервере ключ останется в ~/.ssh/authorized_keys — при необходимости удалите строку «ubuntu-control@android».")
        },
        confirmButton = { TextButton(onClick = onReset) { Text("Сбросить и войти заново", color = Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
