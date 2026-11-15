package com.pyfold.ubuntucontrol

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HELPER = "/usr/local/sbin/uc-lockdown"

private sealed interface Ld {
    data object Loading : Ld
    data object Off : Ld
    data class On(val mode: String, val until: Long, val serverNow: Long, val fetchedAt: Long) : Ld
    data object NoHelper : Ld
    data class Err(val msg: String) : Ld
}

private fun parse(out: String, code: Int): Ld {
    val body = out.substringBefore("@@").trim()
    val serverNow = out.substringAfter("@@", "").trim().toLongOrNull() ?: (System.currentTimeMillis() / 1000)
    return when {
        body.startsWith("OFF") -> Ld.Off
        body.startsWith("ON") -> {
            val mode = Regex("mode=(\\w+)").find(body)?.groupValues?.get(1) ?: "?"
            val until = Regex("until=(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            Ld.On(mode, until, serverNow, System.currentTimeMillis())
        }
        "No such file" in body || "not found" in body || "interactive authentication" in body || "password is required" in body || "a terminal is required" in body ->
            Ld.NoHelper
        else -> Ld.Err(body.ifBlank { "Код $code" })
    }
}

@Composable
fun LockdownScreen(onBack: () -> Unit) {
    val conn by SshManager.state.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<Ld>(Ld.Loading) }
    var mode by remember { mutableStateOf("inbound") }
    var minutes by remember { mutableIntStateOf(60) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var installDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var tick by remember { mutableLongStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LaunchedEffect(conn, reload) {
        while (conn == Conn.Online) {
            val r = SshManager.exec("sudo -n $HELPER status 2>&1; echo @@\$(date +%s)", 15_000)
            state = parse(r.out, r.code)
            delay(5000)
        }
    }
    LaunchedEffect(state) { while (state is Ld.On) { delay(1000); tick++ } }

    fun run(cmd: String, okMsg: String) {
        scope.launch {
            busy = true
            val r = SshManager.exec("sudo -n $HELPER $cmd 2>&1", 30_000)
            busy = false
            snack.showSnackbar(if (r.code == 0) okMsg else r.out.lines().firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "Ошибка (код ${r.code})" })
            reload++
        }
    }

    val on = state as? Ld.On
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(0.55f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pa")
    val accent = when {
        on != null -> Bad
        state is Ld.Off -> Good
        else -> TextDim
    }

    AppScaffold("Локдаун сети", "Всё закрыто, кроме Tailscale", onBack, snackbar = snack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // статус
            Card(colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)).alpha(if (on != null) pulse else 1f), contentAlignment = Alignment.Center) {
                        Icon(if (on != null) Icons.Rounded.GppBad else Icons.Rounded.GppGood, null, tint = accent, modifier = Modifier.size(46.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when (state) {
                            Ld.Loading -> "Проверяю…"
                            Ld.Off -> "Сеть открыта"
                            is Ld.On -> if (on?.mode == "full") "ПОЛНЫЙ ЛОКДАУН" else "ЛОКДАУН: ВХОДЯЩИЕ"
                            Ld.NoHelper -> "Нужна установка"
                            is Ld.Err -> "Ошибка"
                        },
                        style = MaterialTheme.typography.titleLarge, color = accent, fontWeight = FontWeight.Bold,
                    )
                    val sub = when (val st = state) {
                        is Ld.On -> if (st.until == 0L) "Действует, пока вы его не отключите" else {
                            tick.hashCode()
                            val left = (st.until - (st.serverNow + (System.currentTimeMillis() - st.fetchedAt) / 1000)).coerceAtLeast(0)
                            "Автоотключение через %d:%02d".format(left / 60, left % 60)
                        }
                        Ld.Off -> "Обычный режим: бот и сервисы работают"
                        Ld.NoHelper -> "На сервере ещё нет помощника локдауна"
                        is Ld.Err -> st.msg
                        Ld.Loading -> if (conn == Conn.Online) "" else "Нет связи с сервером"
                    }
                    if (sub.isNotEmpty()) Text(sub, color = TextDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (state is Ld.NoHelper) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Установить помощник", style = MaterialTheme.typography.titleMedium)
                        Text("Небольшой скрипт /usr/local/sbin/uc-lockdown и правило sudoers только на него. Нужен пароль sudo — он используется один раз и не сохраняется.",
                            style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(vertical = 8.dp))
                        Button(onClick = { password = ""; installDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = UbuntuOrange)) {
                            Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text("Установить")
                        }
                    }
                }
            } else if (state !is Ld.Loading) {
                // режим
                Text("РЕЖИМ", style = MaterialTheme.typography.labelLarge, color = UbuntuOrange, modifier = Modifier.padding(start = 4.dp))
                ModeCard(Icons.Rounded.Login, "Только входящие", "Закрывает все входящие подключения кроме Tailscale. Бот и сервер продолжают ходить в интернет.",
                    selected = mode == "inbound", enabled = on == null) { mode = "inbound" }
                ModeCard(Icons.Rounded.Block, "Полный", "Отрезает интернет у сервера и контейнеров Docker (бот перестанет работать). Остаются только Tailscale, SSH и приложение.",
                    selected = mode == "full", enabled = on == null) { mode = "full" }

                if (on == null) {
                    Text("АВТООТКЛЮЧЕНИЕ", style = MaterialTheme.typography.labelLarge, color = UbuntuOrange, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "15 мин", 60 to "1 час", 360 to "6 часов", 0 to "Вручную").forEach { (m, label) ->
                            FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
                        }
                    }
                }

                if (on == null) Button(
                    onClick = { confirm = true }, enabled = !busy && conn == Conn.Online, modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Color.Black),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
                    else { Icon(Icons.Rounded.Lock, null); Spacer(Modifier.width(8.dp)); Text("Включить локдаун", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                } else Button(
                    onClick = { run("off", "Локдаун выключен") }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Good, contentColor = Color.Black),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
                    else { Icon(Icons.Rounded.LockOpen, null); Spacer(Modifier.width(8.dp)); Text("Выключить локдаун", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }

                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, null, tint = TextDim, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Защита от потери доступа: локдаун не включится, если Tailscale не в сети. Правила живут только в памяти — перезагрузка сервера снимает блокировку. " +
                            "Выключить можно также командой: sudo uc-lockdown off.", style = MaterialTheme.typography.bodySmall, color = TextDim)
                    }
                }
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        icon = { Icon(Icons.Rounded.Warning, null, tint = Warn) },
        title = { Text(if (mode == "full") "Полный локдаун?" else "Закрыть входящие?") },
        text = {
            Text(if (mode == "full") "Сервер и контейнеры потеряют выход в интернет: бот, обновления и всё остальное, кроме Tailscale, перестанут работать" + (if (minutes > 0) " на ${if (minutes >= 60) "${minutes / 60} ч" else "$minutes мин"}." else " до ручного отключения.")
            else "Все входящие подключения, кроме Tailscale, будут закрыты" + (if (minutes > 0) " на ${if (minutes >= 60) "${minutes / 60} ч" else "$minutes мин"}." else " до ручного отключения."))
        },
        confirmButton = { TextButton(onClick = { confirm = false; run("on $mode $minutes", "Локдаун включён") }) { Text("Включить", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
    )

    if (installDialog) AlertDialog(
        onDismissRequest = { if (!busy) installDialog = false },
        icon = { Icon(Icons.Rounded.Key, null, tint = UbuntuOrange) },
        title = { Text("Пароль sudo") },
        text = {
            OutlinedTextField(password, { password = it }, singleLine = true, label = { Text("Пароль") }, shape = MaterialTheme.shapes.medium,
                visualTransformation = PasswordVisualTransformation())
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() && !busy, onClick = {
                scope.launch {
                    busy = true
                    val script = ctx.resources.openRawResource(R.raw.uc_lockdown).bufferedReader().readText()
                    val r = SshManager.installLockdownHelper(password, script)
                    busy = false; password = ""
                    if (r.out.contains("HELPER_OK")) { installDialog = false; snack.showSnackbar("Помощник установлен"); reload++ }
                    else snack.showSnackbar(r.out.lines().firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "Не удалось установить" })
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Установить") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { installDialog = false }) { Text("Отмена") } },
    )
}

@Composable
private fun ModeCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val border = if (selected) UbuntuOrange else MaterialTheme.colorScheme.outlineVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, border), shape = MaterialTheme.shapes.large,
        modifier = Modifier.alpha(if (enabled) 1f else 0.6f).clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, if (selected) UbuntuOrange else TextDim, 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(top = 2.dp))
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = UbuntuOrange))
        }
    }
}
