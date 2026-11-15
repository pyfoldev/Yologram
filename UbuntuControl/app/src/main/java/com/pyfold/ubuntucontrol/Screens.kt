package com.pyfold.ubuntucontrol

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.ui.unit.min
import kotlin.math.sin

// ───────────── Логотип ─────────────

/** «Круг друзей»: оранжевый круг, белое кольцо и три точки. */
@Composable
fun UbuntuLogo(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2
        drawCircle(UbuntuOrange, r, c)
        val ring = r * 0.42f
        drawCircle(Color.White, ring, c, style = Stroke(width = r * 0.11f))
        listOf(-90f, 30f, 150f).forEach { deg ->
            val a = deg * PI.toFloat() / 180f
            val p = Offset(c.x + cos(a) * ring, c.y + sin(a) * ring)
            drawCircle(UbuntuOrange, r * 0.17f, p)       // «вырез» вокруг точки
            drawCircle(Color.White, r * 0.115f, p)
        }
    }
}

@Composable
private fun StatusDot(state: Conn) {
    val (color, text) = when (state) {
        Conn.Online -> Good to "Подключено"
        Conn.Connecting -> UbuntuOrange to "Подключаюсь…"
        Conn.Idle -> TextDim to "Нет соединения"
        is Conn.Failed -> Bad to "Не подключено"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 13.sp)
    }
}

// ───────────── Главный экран ─────────────

@Composable
fun DashboardScreen(onOpen: (Target) -> Unit, onSettings: () -> Unit) {
    val conn by SshManager.state.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pending by remember { mutableStateOf<RingItem?>(null) }

    LaunchedEffect(conn) {
        while (conn == Conn.Online) {
            val r = SshManager.exec(
                "echo \"\$(hostname)|\$(uptime -p | sed 's/up //')|\$(cut -d' ' -f1 /proc/loadavg)|" +
                    "\$(free -m | awk '/Mem/{printf \"%d%%\", \$3*100/\$2}')|\$(df -h / | awk 'NR==2{print \$5}')|" +
                    "\$(( \$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo 0) / 1000 ))°C\"", 15_000)
            val p = r.out.trim().split("|")
            if (p.size == 6) stats = listOf("Хост" to p[0], "Аптайм" to p[1], "Нагрузка" to p[2], "ОЗУ" to p[3], "Диск" to p[4], "Темп." to p[5])
            delay(6000)
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${SshManager.user}@${SshManager.host}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                StatusDot(conn)
            }
            TextButton(onClick = onSettings) { Text("Настройки", color = TextDim) }
        }
        if (conn is Conn.Failed) {
            Text((conn as Conn.Failed).msg.take(160) + "\nПроверьте, что Tailscale на телефоне включён. Переподключусь автоматически.",
                color = Bad, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val side = min(maxWidth, maxHeight)
            val btn = (side.value * 0.17f).coerceIn(44f, 62f).dp
            val radius = (side - btn - 22.dp) / 2
            Box(Modifier.align(Alignment.Center).size(side)) {
                // кольцо-направляющая
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White.copy(alpha = 0.07f), radius.toPx(), center, style = Stroke(width = 2.dp.toPx()))
                }
                UbuntuLogo(size = radius * 0.95f, modifier = Modifier.align(Alignment.Center))
                val items = Actions.ring
                items.forEachIndexed { i, item ->
                    val a = (-90.0 + i * 360.0 / items.size) * PI / 180.0
                    val dx = (cos(a) * radius.value).dp
                    val dy = (sin(a) * radius.value).dp
                    Column(
                        Modifier.align(Alignment.Center).offset(dx, dy).width(btn + 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(btn).clip(CircleShape).background(Panel)
                                .border(1.5.dp, if (item.label in listOf("Перезагр.", "Выключить")) Bad.copy(alpha = .7f) else UbuntuOrange.copy(alpha = .8f), CircleShape)
                                .clickable { if (item.target is Target.Run) pending = item else onOpen(item.target) },
                            contentAlignment = Alignment.Center,
                        ) { Text(item.emoji, fontSize = (btn.value * 0.42f).sp) }
                        Text(item.label, fontSize = 10.sp, maxLines = 1, color = TextDim, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }

        // нижняя панель метрик
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            if (stats.isEmpty()) Text(if (conn == Conn.Online) "Загружаю данные…" else "—", color = TextDim, fontSize = 12.sp)
            else stats.drop(1).forEach { (k, v) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    Text(k, color = TextDim, fontSize = 10.sp)
                }
            }
        }
        if (stats.isNotEmpty()) Text("Хост ${stats[0].second}", color = TextDim, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
    }

    pending?.let { item ->
        val run = (item.target as Target.Run).cmd
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(run.title) }, text = { Text(run.confirm ?: "Выполнить?") },
            confirmButton = { TextButton(onClick = { pending = null; onOpen(item.target) }) { Text("Да", color = Bad) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } },
        )
    }
}

// ───────────── Меню команд ─────────────

@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = UbuntuOrange, fontSize = 15.sp) }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            actions()
        }
        content()
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

    ScreenFrame(menu.title, onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(menu.items) { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                        .clickable { arg = ""; if (c.arg != null || c.confirm != null) dialog = c else fire(c, null) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.title, Modifier.weight(1f), fontSize = 15.sp)
                    Text(if (c.confirm != null) "⚠" else "›", color = if (c.confirm != null) UbuntuOrange else TextDim)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    dialog?.let { c ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(c.title.removeSuffix("…")) },
            text = {
                Column {
                    if (c.confirm != null) Text(c.confirm)
                    if (c.arg != null) {
                        OutlinedTextField(arg, { arg = it }, singleLine = true, label = { Text(c.arg) },
                            placeholder = { c.hint?.let { Text(it) } }, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = c.arg == null || arg.isNotBlank(), onClick = {
                    val a = if (c.arg != null) arg.trim() else null
                    dialog = null; fire(c, a)
                }) { Text("Выполнить", color = UbuntuOrange) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
        )
    }
}

// ───────────── Вывод команды ─────────────

@Composable
fun OutputScreen(title: String, command: String, onBack: () -> Unit) {
    var out by remember { mutableStateOf<ExecResult?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val clip = LocalClipboardManager.current
    LaunchedEffect(tick) {
        out = null
        val isPower = Regex("systemctl (reboot|poweroff)").containsMatchIn(command)
        val r = SshManager.exec(command, 120_000)
        out = if (isPower) ExecResult(0, "Команда отправлена. Сервер недоступен — приложение переподключится само, когда он вернётся.") else r
    }
    ScreenFrame(title, onBack, actions = {
        TextButton(onClick = { tick++ }) { Text("Обновить", color = UbuntuOrange) }
        TextButton(onClick = { out?.let { clip.setText(AnnotatedString(it.out)) } }) { Text("Копировать", color = TextDim) }
    }) {
        val o = out
        if (o == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = UbuntuOrange) }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B0013)).padding(10.dp)) {
                if (o.code != 0) Text("Код выхода: ${o.code}", color = Bad, fontSize = 12.sp)
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(o.out.ifBlank { "(пустой вывод)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ───────────── Терминал ─────────────

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    var term by remember { mutableStateOf<TermSession?>(null) }
    var input by remember { mutableStateOf("") }
    var fail by remember { mutableStateOf<String?>(null) }
    val conn by SshManager.state.collectAsStateWithLifecycle()
    val history = remember { mutableStateListOf<String>() }
    var histPos by remember { mutableIntStateOf(-1) }
    val scroll = rememberScrollState()

    LaunchedEffect(conn) {
        if (conn == Conn.Online && (term == null || term?.closed?.value == true)) {
            term?.close()
            term = try { SshManager.openShell() } catch (e: Exception) { fail = e.message; null }
        }
    }
    DisposableEffect(Unit) { onDispose { term?.close() } }

    val text by (term?.text ?: remember { kotlinx.coroutines.flow.MutableStateFlow("") }).collectAsStateWithLifecycle()
    val closed by (term?.closed ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }

    fun sendLine() {
        val t = term ?: return
        if (input.isNotEmpty()) { history.add(input); histPos = -1 }
        t.send(input + "\n"); input = ""
    }

    ScreenFrame("Терминал", onBack, actions = {
        TextButton(onClick = { term?.clear() }) { Text("Очистить", color = TextDim) }
    }) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B0013)).padding(8.dp)) {
            Box(Modifier.verticalScroll(scroll)) {
                SelectionContainer {
                    Text(
                        when {
                            term == null && conn != Conn.Online -> "Нет подключения к серверу…"
                            term == null -> fail ?: "Открываю сессию…"
                            else -> text + if (closed) "\n[сессия завершена — вернитесь и откройте заново]" else ""
                        },
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp, color = Color(0xFFEDEDED),
                    )
                }
            }
        }
        // панель спецклавиш
        Row(Modifier.fillMaxWidth().horizontalScroll2().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Tab" to "\t", "Esc" to "\u001b", "Ctrl+C" to "\u0003", "Ctrl+D" to "\u0004", "Ctrl+L" to "\u000c",
                "Ctrl+Z" to "\u001a", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
                "~" to "~", "/" to "/", "|" to "|", "-" to "-",
            ).forEach { (label, seq) ->
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Panel).clickable { term?.send(seq) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)) { Text(label, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("команда…") },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { sendLine() }),
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { sendLine() }, colors = ButtonDefaults.buttonColors(containerColor = UbuntuOrange)) { Text("⏎") }
        }
    }
}

@Composable
private fun Modifier.horizontalScroll2(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

// ───────────── Первая настройка ─────────────

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var host by remember { mutableStateOf(SshManager.host) }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf(SshManager.user) }
    var pass by remember { mutableStateOf("") }
    var sudo by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        UbuntuLogo(96.dp)
        Spacer(Modifier.height(12.dp))
        Text("Ubuntu Control", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Войдите один раз по паролю. Приложение создаст свой SSH‑ключ, установит его на сервер и дальше подключается само. Пароль не сохраняется.",
            color = TextDim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
        OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Адрес сервера (Tailscale IP)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(user, { user = it }, Modifier.weight(1f), singleLine = true, label = { Text("Пользователь") })
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.width(90.dp), singleLine = true, label = { Text("Порт") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { sudo = !sudo }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(sudo, { sudo = it })
            Text("Разрешить управление без пароля (перезагрузка, службы, apt, docker). Создаст ограниченное правило sudoers.", fontSize = 12.sp, color = TextDim)
        }
        error?.let { Text(it, color = Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        status?.let { Text(it, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Button(
            enabled = !busy && host.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(),
            onClick = {
                busy = true; error = null
                scope.launch {
                    val r = SshManager.setup(host.trim(), port.toIntOrNull() ?: 22, user.trim(), pass, sudo) { status = it }
                    busy = false
                    r.onSuccess { SshManager.startAutoConnect(); onDone() }
                        .onFailure { error = "Ошибка: ${it.message}"; status = null }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = UbuntuOrange),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp),
        ) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) else Text("Подключиться и сохранить") }
        Text("Нужен включённый Tailscale на телефоне.", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit, onReset: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = { Text("Сервер: ${SshManager.user}@${SshManager.host}\n\nСброс удалит ключ из приложения. На сервере ключ останется в ~/.ssh/authorized_keys — при необходимости удалите строку «ubuntu-control@android».") },
        confirmButton = { TextButton(onClick = onReset) { Text("Сбросить и войти заново", color = Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
