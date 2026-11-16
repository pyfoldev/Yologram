package com.pyfold.ubuntucontrol

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HOST = "pyfold.tail8919f2.ts.net"

/** Описание самохостируемого сервиса: где открыть, какие контейнеры/службы проверять, как перезапустить. */
private data class AppDef(
    val name: String, val desc: String, val icon: ImageVector,
    val url: String?, val containers: List<String>, val restart: String, val logs: String,
)

private val APPS = listOf(
    AppDef("Nextcloud", "Файлы, календарь, контакты", Icons.Rounded.Cloud, "https://$HOST:10000",
        listOf("nextcloud-app-1", "nextcloud-db-1", "nextcloud-redis-1", "nextcloud-cron-1"),
        "cd /opt/nextcloud && docker compose restart", "docker logs --tail 100 nextcloud-app-1 2>&1 | cut -c1-200"),
    AppDef("Immich", "Фото и видео", Icons.Rounded.PhotoLibrary, "https://$HOST:2283",
        listOf("immich_server", "immich_machine_learning", "immich_postgres", "immich_redis"),
        "cd /opt/immich && docker compose restart", "docker logs --tail 100 immich_server 2>&1 | cut -c1-200"),
    AppDef("Vaultwarden", "Менеджер паролей", Icons.Rounded.Key, "https://$HOST:8222",
        listOf("vaultwarden"), "cd /opt/vaultwarden && docker compose restart", "docker logs --tail 100 vaultwarden 2>&1 | cut -c1-200"),
    AppDef("AdGuard Home", "DNS и блокировка рекламы", Icons.Rounded.Shield, "https://$HOST:3000",
        listOf("adguardhome"), "cd /opt/adguard && docker compose restart", "docker logs --tail 100 adguardhome 2>&1 | cut -c1-200"),
    AppDef("SearXNG", "Приватный поиск", Icons.Rounded.Search, "https://$HOST:8888",
        listOf("searxng"), "cd /opt/searxng && docker compose restart", "docker logs --tail 100 searxng 2>&1 | cut -c1-200"),
    AppDef("Gitea", "Git-репозитории", Icons.Rounded.Code, "https://$HOST:3200",
        listOf("gitea"), "cd /opt/gitea && docker compose restart", "docker logs --tail 100 gitea 2>&1 | cut -c1-200"),
    AppDef("Homepage", "Стартовая страница", Icons.Rounded.Dashboard, "https://$HOST:3100",
        listOf("homepage", "homepage-dockerproxy"), "cd /opt/homepage && docker compose restart", "docker logs --tail 100 homepage 2>&1 | cut -c1-200"),
    AppDef("Uptime Kuma", "Мониторинг и оповещения", Icons.Rounded.MonitorHeart, "https://$HOST:3001",
        listOf("uptime-kuma"), "cd /opt/uptime-kuma && docker compose restart", "docker logs --tail 100 uptime-kuma 2>&1 | cut -c1-200"),
    AppDef("Dozzle", "Логи контейнеров", Icons.Rounded.Article, "https://$HOST:8090",
        listOf("dozzle", "dozzle-dockerproxy"), "cd /opt/dozzle && docker compose restart", "docker logs --tail 100 dozzle 2>&1 | cut -c1-200"),
    AppDef("Бот chat-helper", "Telegram-бот Nori", Icons.Rounded.SmartToy, null,
        listOf("chat-helper-bot-1", "chat-helper-caddy-1", "chat-helper-postgres-1"),
        "cd /opt/chat-helper && docker compose --profile pg restart bot", "cd /opt/chat-helper && docker compose logs --tail 80 bot 2>&1 | cut -c1-200"),
)

private enum class St { Up, Partial, Down, Unknown }

@Composable
fun AppsScreen(onBack: () -> Unit, onRun: (String, String) -> Unit) {
    val conn by SshManager.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var states by remember { mutableStateOf<Map<String, String>>(emptyMap()) }   // container -> state
    var nullgram by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<AppDef?>(null) }

    LaunchedEffect(conn, reload) {
        while (conn == Conn.Online) {
            val r = SshManager.exec("docker ps -a --format '{{.Names}}|{{.State}}' 2>&1; echo @@; systemctl is-active nullgram 2>&1", 20_000)
            if (r.code == 0 || r.out.contains("@@")) {
                val (a, b) = r.out.split("@@").let { it[0] to it.getOrElse(1) { "" } }
                states = a.lines().mapNotNull { l -> l.split("|").takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                nullgram = b.trim().ifBlank { null }
            }
            loading = false
            delay(10_000)
        }
    }

    fun stateOf(a: AppDef): St {
        if (states.isEmpty()) return St.Unknown
        val s = a.containers.map { states[it] }
        return when {
            s.all { it == "running" } -> St.Up
            s.any { it == "running" } -> St.Partial
            s.all { it == null } -> St.Unknown
            else -> St.Down
        }
    }

    val upCount = APPS.count { stateOf(it) == St.Up } + if (nullgram == "active") 1 else 0

    AppScaffold("Мои сервисы", if (states.isEmpty()) "Проверяю…" else "Работает $upCount из ${APPS.size + 1}", onBack, snackbar = snack,
        actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "Обновить") } }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (loading && conn == Conn.Online) LinearProgressIndicator(Modifier.fillMaxWidth(), color = UbuntuOrange)
            if (conn != Conn.Online) Text("Нет связи с сервером. Включите Tailscale на телефоне.", color = Bad, modifier = Modifier.padding(16.dp))
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(APPS, key = { it.name }) { a ->
                    val st = stateOf(a)
                    val (color, label) = when (st) {
                        St.Up -> Good to "работает"
                        St.Partial -> Warn to "частично"
                        St.Down -> Bad to "остановлен"
                        St.Unknown -> TextDim to "—"
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBubble(a.icon, color.takeIf { st != St.Unknown } ?: UbuntuOrange, 44.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(a.desc, style = MaterialTheme.typography.bodySmall, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(7.dp).padding(0.dp)) { Surface(shape = CircleShape, color = color, modifier = Modifier.fillMaxSize()) {} }
                                        Spacer(Modifier.width(6.dp))
                                        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (a.url != null) FilledTonalButton(onClick = {
                                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) }
                                    catch (e: Exception) { scope.launch { snack.showSnackbar("Не удалось открыть ссылку") } }
                                }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Открыть")
                                }
                                OutlinedButton(onClick = { onRun("Логи: ${a.name}", a.logs) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                    Icon(Icons.Rounded.Article, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Логи")
                                }
                                OutlinedButton(onClick = { confirm = a }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                // nullgram — systemd-служба, не контейнер
                item {
                    val up = nullgram == "active"
                    val color = if (nullgram == null) TextDim else if (up) Good else Bad
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBubble(Icons.Rounded.Sync, color.takeIf { nullgram != null } ?: UbuntuOrange, 44.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Nullgram сервер", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Синхронизация профилей (systemd)", style = MaterialTheme.typography.bodySmall, color = TextDim)
                                }
                                Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) {
                                    Text(when { nullgram == null -> "—"; up -> "работает"; else -> nullgram!! }, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        color = color, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onRun("Логи: nullgram", "journalctl -u nullgram -n 100 --no-pager") }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                    Icon(Icons.Rounded.Article, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Логи")
                                }
                                OutlinedButton(onClick = { onRun("Перезапуск nullgram", "sudo -n systemctl restart nullgram && echo Перезапущено") }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.Warning, null, tint = Warn) },
            title = { Text("Перезапустить «${a.name}»?") },
            text = { Text("Сервис будет недоступен несколько секунд.") },
            confirmButton = { TextButton(onClick = { confirm = null; onRun("Перезапуск: ${a.name}", a.restart + " 2>&1 | tail -15") }) { Text("Перезапустить", color = UbuntuOrange) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}
