package com.pyfold.ubuntucontrol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ───────────── общие куски ─────────────

@Composable
private fun StateChip(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = tint ?: MaterialTheme.colorScheme.onSurface) },
        leadingContent = { Icon(icon, null, tint = tint ?: TextDim) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun firstLine(r: ExecResult) = r.out.lines().firstOrNull { it.isNotBlank() }.orEmpty()

// ───────────── Службы ─────────────

private data class Svc(val name: String, val active: String, val sub: String, val desc: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(onBack: () -> Unit, onRun: (String, String) -> Unit) {
    val conn by SshManager.state.collectAsStateWithLifecycle()
    var all by remember { mutableStateOf<List<Svc>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf<Svc?>(null) }
    var confirm by remember { mutableStateOf<Pair<Svc, String>?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(conn, reload) {
        if (conn != Conn.Online) return@LaunchedEffect
        loading = true; error = null
        val r = SshManager.exec("systemctl list-units --type=service --all --no-legend --plain --no-pager", 30_000)
        if (r.code == 0) {
            all = r.out.lines().mapNotNull { l ->
                val p = l.trim().split(Regex("\\s+"), limit = 5)
                if (p.size >= 4) Svc(p[0].removeSuffix(".service"), p[2], p[3], p.getOrElse(4) { "" }) else null
            }.sortedWith(compareBy({ it.sub != "running" }, { it.name }))
        } else error = r.out
        loading = false
    }

    fun act(s: Svc, verb: String) {
        scope.launch {
            val r = SshManager.exec("sudo -n systemctl $verb ${SshManager.shq(s.name)} 2>&1", 60_000)
            snack.showSnackbar(if (r.code == 0) "Готово: $verb ${s.name}" else firstLine(r).ifBlank { "Ошибка (код ${r.code})" })
            reload++
        }
    }

    val shown = all.filter { s ->
        (query.isBlank() || s.name.contains(query, true) || s.desc.contains(query, true)) && when (filter) {
            1 -> s.sub == "running"; 2 -> s.active == "failed"; 3 -> s.active == "inactive"; else -> true
        }
    }

    AppScaffold("Службы", "${all.count { it.sub == "running" }} работают · ${all.count { it.active == "failed" }} с ошибками", onBack, snackbar = snack,
        actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "Обновить") } }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                placeholder = { Text("Поиск службы") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = CircleShape,
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистить") } })
            LazyRow(Modifier.padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Все", "Работают", "Ошибки", "Остановлены").withIndex().toList()) { (i, label) ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = UbuntuOrange)
            error?.let { Text(it, color = Bad, modifier = Modifier.padding(16.dp)) }
            LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.name }) { s ->
                    val (c, label) = when {
                        s.active == "failed" -> Bad to "ошибка"
                        s.sub == "running" -> Good to "работает"
                        s.active == "activating" -> Warn to "запуск"
                        s.sub == "exited" -> TextDim to "выполнена"
                        else -> TextDim to s.sub
                    }
                    Card(onClick = { sel = s }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBubble(Icons.Rounded.Settings, c, 38.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.desc, style = MaterialTheme.typography.bodySmall, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StateChip(label, c)
                        }
                    }
                }
            }
        }
    }

    sel?.let { s ->
        ModalBottomSheet(onDismissRequest = { sel = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                Text(s.desc, style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
                Spacer(Modifier.height(8.dp))
                SheetAction(Icons.Rounded.PlayArrow, "Запустить") { sel = null; act(s, "start") }
                SheetAction(Icons.Rounded.RestartAlt, "Перезапустить") { sel = null; confirm = s to "restart" }
                SheetAction(Icons.Rounded.Stop, "Остановить", Bad) { sel = null; confirm = s to "stop" }
                SheetAction(Icons.Rounded.CheckCircle, "Включить автозапуск") { sel = null; act(s, "enable") }
                SheetAction(Icons.Rounded.Block, "Отключить автозапуск") { sel = null; act(s, "disable") }
                SheetAction(Icons.Rounded.Article, "Логи") { sel = null; onRun("Логи ${s.name}", "journalctl -u ${SshManager.shq(s.name)} -n 150 --no-pager") }
                SheetAction(Icons.Rounded.MonitorHeart, "Статус") { sel = null; onRun("Статус ${s.name}", "systemctl status ${SshManager.shq(s.name)} --no-pager -l") }
            }
        }
    }

    confirm?.let { (s, verb) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.Warning, null, tint = Warn) },
            title = { Text(if (verb == "stop") "Остановить службу?" else "Перезапустить службу?") },
            text = { Text(s.name + if (s.name in listOf("ssh", "sshd", "tailscaled")) "\n\nВнимание: это может оборвать ваше подключение." else "") },
            confirmButton = { TextButton(onClick = { confirm = null; act(s, verb) }) { Text("Да", color = if (verb == "stop") Bad else UbuntuOrange) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

// ───────────── Docker ─────────────

private data class Ctr(val name: String, val state: String, val status: String, val image: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerScreen(onBack: () -> Unit, onRun: (String, String) -> Unit) {
    val conn by SshManager.state.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<List<Ctr>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf<Ctr?>(null) }
    var confirm by remember { mutableStateOf<Pair<Ctr, String>?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(conn, reload) {
        if (conn != Conn.Online) return@LaunchedEffect
        loading = true; error = null
        val r = SshManager.exec("docker ps -a --format '{{.Names}}|{{.State}}|{{.Status}}|{{.Image}}' 2>&1", 30_000)
        if (r.code == 0) list = r.out.lines().mapNotNull { l ->
            val p = l.split("|"); if (p.size >= 4) Ctr(p[0], p[1], p[2], p[3]) else null
        }.sortedWith(compareBy({ it.state != "running" }, { it.name }))
        else error = "Docker недоступен: ${firstLine(r)}"
        loading = false
    }

    fun act(c: Ctr, verb: String, cmd: String = "docker $verb ${SshManager.shq(c.name)}") {
        scope.launch {
            val r = SshManager.exec("$cmd 2>&1", 90_000)
            snack.showSnackbar(if (r.code == 0) "Готово: $verb ${c.name}" else firstLine(r).ifBlank { "Ошибка (код ${r.code})" })
            reload++
        }
    }

    AppScaffold("Docker", "${list.count { it.state == "running" }} из ${list.size} запущено", onBack, snackbar = snack,
        actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "Обновить") } }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = UbuntuOrange)
            error?.let { Text(it, color = Bad, modifier = Modifier.padding(16.dp)) }
            if (!loading && error == null && list.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Контейнеров нет", color = TextDim)
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.name }) { c ->
                    val color = when (c.state) { "running" -> Good; "restarting" -> Warn; "exited", "dead" -> Bad; else -> TextDim }
                    Card(onClick = { sel = c }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBubble(Icons.Rounded.ViewInAr, color, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.image, style = MaterialTheme.typography.bodySmall, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.status, style = MaterialTheme.typography.labelSmall, color = color)
                            }
                            StateChip(c.state, color)
                        }
                    }
                }
            }
        }
    }

    sel?.let { c ->
        ModalBottomSheet(onDismissRequest = { sel = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                Text(c.image, style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
                if (c.state != "running") SheetAction(Icons.Rounded.PlayArrow, "Запустить") { sel = null; act(c, "start") }
                SheetAction(Icons.Rounded.RestartAlt, "Перезапустить") { sel = null; confirm = c to "restart" }
                if (c.state == "running") SheetAction(Icons.Rounded.Stop, "Остановить", Bad) { sel = null; confirm = c to "stop" }
                SheetAction(Icons.Rounded.Article, "Логи (150 строк)") { sel = null; onRun("Логи ${c.name}", "docker logs --tail 150 ${SshManager.shq(c.name)} 2>&1 | cut -c1-220") }
                SheetAction(Icons.Rounded.Speed, "Нагрузка") { sel = null; onRun("Нагрузка ${c.name}", "docker stats --no-stream ${SshManager.shq(c.name)}") }
                SheetAction(Icons.Rounded.Info, "Подробности") { sel = null; onRun("Inspect ${c.name}", "docker inspect ${SshManager.shq(c.name)} | head -120") }
                SheetAction(Icons.Rounded.Delete, "Удалить контейнер", Bad) { sel = null; confirm = c to "rm" }
            }
        }
    }

    confirm?.let { (c, verb) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.Warning, null, tint = Warn) },
            title = { Text(when (verb) { "stop" -> "Остановить контейнер?"; "rm" -> "Удалить контейнер?"; else -> "Перезапустить контейнер?" }) },
            text = { Text(c.name + if (verb == "rm") "\n\nКонтейнер будет удалён (тома с данными сохранятся)." else "") },
            confirmButton = { TextButton(onClick = {
                confirm = null
                if (verb == "rm") act(c, "rm", "docker rm -f ${SshManager.shq(c.name)}") else act(c, verb)
            }) { Text("Да", color = if (verb == "restart") UbuntuOrange else Bad) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

// ───────────── Мои команды ─────────────

data class Snippet(val title: String, val cmd: String)

private fun loadSnippets(): List<Snippet> = try {
    val a = JSONArray(SecureStore.get("snips", "[]"))
    (0 until a.length()).map { Snippet(a.getJSONObject(it).getString("t"), a.getJSONObject(it).getString("c")) }
} catch (_: Exception) { emptyList() }

private fun saveSnippets(l: List<Snippet>) =
    SecureStore.put("snips", JSONArray(l.map { JSONObject().put("t", it.title).put("c", it.cmd) }).toString())

@Composable
fun SnippetsScreen(onBack: () -> Unit, onRun: (String, String) -> Unit, onTerminal: (String) -> Unit) {
    val items = remember { mutableStateListOf<Snippet>().also { it.addAll(loadSnippets()) } }
    var edit by remember { mutableStateOf<Pair<Int, Snippet>?>(null) } // -1 = новая
    var title by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }

    AppScaffold("Мои команды", "Свои сценарии одним нажатием", onBack,
        actions = { IconButton(onClick = { title = ""; cmd = ""; edit = -1 to Snippet("", "") }) { Icon(Icons.Rounded.Add, "Добавить") } }) { pad ->
        if (items.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Rounded.Bookmarks, null, tint = TextDim, modifier = Modifier.size(56.dp))
                Text("Пока пусто", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text("Сохраните часто используемые команды — они будут запускаться одним нажатием.", color = TextDim, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { title = ""; cmd = ""; edit = -1 to Snippet("", "") }, modifier = Modifier.padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UbuntuOrange)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить команду") }
            }
        }
        else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.withIndex().toList()) { (i, s) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Text(s.cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(onClick = { onRun(s.title, s.cmd) }) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Запустить") }
                            IconButton(onClick = { onTerminal(s.cmd) }) { Icon(Icons.Rounded.Terminal, "В терминал", tint = TextDim) }
                            IconButton(onClick = { title = s.title; cmd = s.cmd; edit = i to s }) { Icon(Icons.Rounded.Edit, "Изменить", tint = TextDim) }
                            IconButton(onClick = { items.removeAt(i); saveSnippets(items) }) { Icon(Icons.Rounded.Delete, "Удалить", tint = Bad.copy(alpha = .8f)) }
                        }
                    }
                }
            }
        }
    }

    edit?.let { (idx, _) ->
        AlertDialog(
            onDismissRequest = { edit = null },
            icon = { Icon(Icons.Rounded.Bookmarks, null, tint = UbuntuOrange) },
            title = { Text(if (idx < 0) "Новая команда" else "Изменить команду") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Название") }, shape = MaterialTheme.shapes.medium)
                    OutlinedTextField(cmd, { cmd = it }, label = { Text("Команда") }, shape = MaterialTheme.shapes.medium, minLines = 2,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                }
            },
            confirmButton = { TextButton(enabled = title.isNotBlank() && cmd.isNotBlank(), onClick = {
                val s = Snippet(title.trim(), cmd.trim())
                if (idx < 0) items.add(s) else items[idx] = s
                saveSnippets(items); edit = null
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Отмена") } },
        )
    }
}

// ───────────── Ещё ─────────────

private data class MoreItem(val icon: ImageVector, val title: String, val sub: String, val action: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(onMenu: (Target.Menu) -> Unit, onRun: (String, String) -> Unit, onPage: (String) -> Unit) {
    val sys = "hostnamectl 2>/dev/null | head -12; echo; lscpu | grep -E 'Model name|^CPU\\(s\\)|MHz' ; echo; free -h; echo; df -h / ; echo; ip -br a"
    val items = listOf(
        MoreItem(Icons.Rounded.Apps, "Мои сервисы", "Nextcloud, Immich, Gitea и др.") { onPage("apps") },
        MoreItem(Icons.Rounded.Tune, "Службы", "systemd: запуск, остановка, логи") { onPage("services") },
        MoreItem(Icons.Rounded.ViewInAr, "Docker", "Контейнеры и их логи") { onPage("docker") },
        MoreItem(Icons.Rounded.Shield, "Локдаун сети", "Закрыть всё, кроме Tailscale") { onPage("lockdown") },
        MoreItem(Icons.Rounded.Bookmarks, "Мои команды", "Свои сценарии") { onPage("snippets") },
        MoreItem(Icons.Rounded.Lan, "Сеть", "Адреса, порты, DNS, пинг") { onMenu(Actions.network) },
        MoreItem(Icons.Rounded.Storage, "Диск и память", "Место, разделы, RAM") { onMenu(Actions.disk) },
        MoreItem(Icons.Rounded.Article, "Журналы", "Система, ошибки, SSH") { onMenu(Actions.logs) },
        MoreItem(Icons.Rounded.SystemUpdateAlt, "Обновления", "apt, перезагрузка") { onMenu(Actions.updates) },
        MoreItem(Icons.Rounded.VpnLock, "Tailscale", "Статус, Funnel, netcheck") { onMenu(Actions.tailscale) },
        MoreItem(Icons.Rounded.SmartToy, "Боты", "chat-helper, nullgram") { onMenu(Actions.bots) },
        MoreItem(Icons.Rounded.Info, "О системе", "ОС, процессор, диски") { onRun("О системе", sys) },
        MoreItem(Icons.Rounded.Security, "Безопасность", "ufw, входы, обновления") {
            onRun("Безопасность", "echo '== Файрвол'; sudo -n ufw status 2>&1 | head -12; echo; echo '== Последние входы SSH'; journalctl -u ssh --no-pager -n 400 | grep -iE 'accepted|failed' | tail -12; echo; echo '== Нужна перезагрузка?'; [ -f /var/run/reboot-required ] && echo да || echo нет")
        },
        MoreItem(Icons.Rounded.Settings, "Настройки", "Блокировка, шрифт, сброс") { onPage("settings") },
    )
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("Ещё", style = MaterialTheme.typography.titleLarge) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { pad ->
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            gridItems(items) { m ->
                Card(onClick = m.action, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        IconBubble(m.icon, size = 42.dp)
                        Text(m.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        Text(m.sub, style = MaterialTheme.typography.bodySmall, color = TextDim, minLines = 2, maxLines = 2)
                    }
                }
            }
        }
    }
}

// ───────────── Настройки ─────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onReset: () -> Unit, onLockChanged: (Boolean) -> Unit) {
    var lock by remember { mutableStateOf(SecureStore.get("lock", "0") == "1") }
    var fs by remember { mutableFloatStateOf((SecureStore.get("fs", "13").toFloatOrNull() ?: 13f)) }
    var ri by remember { mutableStateOf(SecureStore.get("ri", "2")) }
    var confirmReset by remember { mutableStateOf(false) }
    val conn by SshManager.state.collectAsStateWithLifecycle()
    var ping by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val secure = remember { (ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure }

    @Composable fun section(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = UbuntuOrange, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
    @Composable fun card(content: @Composable ColumnScope.() -> Unit) = Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), content = content)
    }

    AppScaffold("Настройки", onBack = onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            section("СЕРВЕР")
            card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Rounded.Dns, size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${SshManager.user}@${SshManager.host}", style = MaterialTheme.typography.titleMedium)
                        Text(when (conn) { Conn.Online -> "Подключено"; Conn.Connecting -> "Подключение…"; is Conn.Failed -> "Нет связи"; Conn.Idle -> "Не подключено" },
                            style = MaterialTheme.typography.bodySmall, color = TextDim)
                    }
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { scope.launch { ping = "…"; val ms = SshManager.latencyMs(); ping = if (ms >= 0) "$ms мс" else "нет ответа" } }) {
                        Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Проверить задержку")
                    }
                    ping?.let { Text(it, modifier = Modifier.padding(start = 12.dp), color = if (it.endsWith("мс")) Good else Bad, fontWeight = FontWeight.SemiBold) }
                }
            }
            section("БЕЗОПАСНОСТЬ")
            card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Блокировка приложения", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(if (secure) "Запрашивать отпечаток или PIN телефона при открытии" else "Сначала установите блокировку экрана в настройках телефона",
                            style = MaterialTheme.typography.bodySmall, color = TextDim)
                    }
                    Switch(lock, enabled = secure, onCheckedChange = { lock = it; SecureStore.put("lock", if (it) "1" else "0"); onLockChanged(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = UbuntuOrange))
                }
            }
            section("ВНЕШНИЙ ВИД И РАБОТА")
            card {
                Text("Размер шрифта терминала и редактора: ${fs.toInt()} sp", style = MaterialTheme.typography.bodyLarge)
                Slider(fs, { fs = it }, valueRange = 10f..20f, steps = 9, onValueChangeFinished = { SecureStore.put("fs", fs.toInt().toString()) },
                    colors = SliderDefaults.colors(thumbColor = UbuntuOrange, activeTrackColor = UbuntuOrange))
                Spacer(Modifier.height(6.dp))
                Text("Обновление монитора", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    listOf("1", "2", "5", "10").forEachIndexed { i, v ->
                        SegmentedButton(selected = ri == v, onClick = { ri = v; SecureStore.put("ri", v) },
                            shape = SegmentedButtonDefaults.itemShape(i, 4)) { Text("$v с") }
                    }
                }
            }
            section("ПРИЛОЖЕНИЕ")
            card {
                Text("Ubuntu Control ${BuildConfigVersion.NAME}", style = MaterialTheme.typography.bodyLarge)
                Text("Ключ SSH хранится зашифрованным в Android Keystore. Отпечаток сервера запоминается при первом входе.",
                    style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(top = 4.dp))
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.padding(top = 12.dp), border = BorderStroke(1.dp, Bad.copy(alpha = .6f))) {
                    Icon(Icons.Rounded.Logout, null, tint = Bad, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Сбросить и войти заново", color = Bad)
                }
            }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        icon = { Icon(Icons.Rounded.Warning, null, tint = Warn) }, title = { Text("Сбросить настройки?") },
        text = { Text("Ключ будет удалён из приложения. На сервере он останется в ~/.ssh/authorized_keys — при необходимости удалите строку «ubuntu-control@android».") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Сбросить", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
    )
}

object BuildConfigVersion { const val NAME = "2.4" }
