package com.pyfold.ubuntucontrol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POINTS = 60

private data class Proc(val pid: Int, val name: String, val cpu: Float, val mem: Float)

private const val MON_CMD =
    "head -1 /proc/stat; free -m | awk '/Mem/{print \$2, \$3}'; " +
        "awk 'NR>2{gsub(\":\",\"\",\$1); if(\$1!=\"lo\"){rx+=\$2; tx+=\$10}} END{print rx+0, tx+0}' /proc/net/dev; " +
        "cut -d' ' -f1-3 /proc/loadavg; echo @@; ps -eo pid=,comm=,pcpu=,pmem= --sort=-pcpu | head -9"

/** Мини-график: значения прижаты вправо, заливка градиентом. */
@Composable
fun Sparkline(values: List<Float>, color: Color, max: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // сетка
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(w, y), 1.dp.toPx())
        }
        if (values.size < 2) return@Canvas
        val step = w / (POINTS - 1)
        val top = max.coerceAtLeast(0.0001f)
        fun x(i: Int) = w - (values.size - 1 - i) * step
        fun y(v: Float) = h - (v / top).coerceIn(0f, 1f) * (h - 4.dp.toPx()) - 2.dp.toPx()
        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) {
                val px = x(i - 1); val py = y(values[i - 1]); val cx = x(i); val cy = y(values[i])
                cubicTo((px + cx) / 2, py, (px + cx) / 2, cy, cx, cy)
            }
        }
        val fill = Path().apply { addPath(line); lineTo(x(values.size - 1), h); lineTo(x(0), h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun MetricCard(icon: ImageVector, title: String, value: String, sub: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = TextDim)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

private fun fmtRate(bps: Float): String = when {
    bps >= 1_048_576 -> "%.1f МБ/с".format(bps / 1_048_576)
    bps >= 1024 -> "%.0f КБ/с".format(bps / 1024)
    else -> "%.0f Б/с".format(bps)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen() {
    val conn by SshManager.state.collectAsStateWithLifecycle()
    val cpu = remember { mutableStateListOf<Float>() }
    val ram = remember { mutableStateListOf<Float>() }
    val rx = remember { mutableStateListOf<Float>() }
    val tx = remember { mutableStateListOf<Float>() }
    var load by remember { mutableStateOf("—") }
    var ramText by remember { mutableStateOf("—") }
    var procs by remember { mutableStateOf<List<Proc>>(emptyList()) }
    var killTarget by remember { mutableStateOf<Proc?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val interval = (SecureStore.get("ri", "2").toLongOrNull() ?: 2L) * 1000

    fun <T> MutableList<T>.push(v: T) { add(v); while (size > POINTS) removeAt(0) }

    LaunchedEffect(conn) {
        var pTotal = 0L; var pIdle = 0L; var pRx = 0L; var pTx = 0L; var pT = 0L
        while (conn == Conn.Online) {
            val r = SshManager.exec(MON_CMD, 15_000)
            val now = System.currentTimeMillis()
            val parts = r.out.split("@@")
            val head = parts[0].lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (r.code == 0 && head.size >= 4) {
                val f = head[0].split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (f.size >= 5) {
                    val total = f.take(8).sum(); val idle = f[3] + f[4]
                    if (pTotal > 0 && total > pTotal) cpu.push((100f * (1f - (idle - pIdle).toFloat() / (total - pTotal))).coerceIn(0f, 100f))
                    pTotal = total; pIdle = idle
                }
                head[1].split(" ").mapNotNull { it.toFloatOrNull() }.let { m ->
                    if (m.size == 2 && m[0] > 0) { ram.push(100f * m[1] / m[0]); ramText = "${m[1].toInt()} / ${m[0].toInt()} МБ" }
                }
                head[2].split(" ").mapNotNull { it.toLongOrNull() }.let { n ->
                    if (n.size == 2) {
                        if (pT > 0 && now > pT) {
                            val dt = (now - pT) / 1000f
                            rx.push(((n[0] - pRx) / dt).coerceAtLeast(0f)); tx.push(((n[1] - pTx) / dt).coerceAtLeast(0f))
                        }
                        pRx = n[0]; pTx = n[1]; pT = now
                    }
                }
                load = head[3]
                procs = parts.getOrNull(1).orEmpty().lines().mapNotNull { l ->
                    val p = l.trim().split(Regex("\\s+"))
                    if (p.size >= 4) Proc(p[0].toIntOrNull() ?: return@mapNotNull null, p[1], p[2].toFloatOrNull() ?: 0f, p[3].toFloatOrNull() ?: 0f) else null
                }
            }
            delay(interval)
        }
    }

    Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Column {
                    Text("Монитор", style = MaterialTheme.typography.titleLarge)
                    Text("Обновление каждые ${interval / 1000} с", style = MaterialTheme.typography.bodySmall, color = TextDim)
                } },
                actions = { StatusChip(conn); Spacer(Modifier.width(12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val cpuNow = cpu.lastOrNull()
            MetricCard(Icons.Rounded.Speed, "Процессор", cpuNow?.let { "%.0f%%".format(it) } ?: "—", "Нагрузка: $load") {
                Sparkline(cpu, if ((cpuNow ?: 0f) > 85f) Bad else UbuntuOrange, 100f, Modifier.fillMaxWidth().height(72.dp))
            }
            MetricCard(Icons.Rounded.Memory, "Память", ram.lastOrNull()?.let { "%.0f%%".format(it) } ?: "—", ramText) {
                Sparkline(ram, if ((ram.lastOrNull() ?: 0f) > 85f) Bad else Color(0xFF8AB4F8), 100f, Modifier.fillMaxWidth().height(72.dp))
            }
            val peak = maxOf(rx.maxOrNull() ?: 0f, tx.maxOrNull() ?: 0f, 10_240f)
            MetricCard(Icons.Rounded.SwapVert, "Сеть", "↓ ${fmtRate(rx.lastOrNull() ?: 0f)}", "↑ ${fmtRate(tx.lastOrNull() ?: 0f)}") {
                Box {
                    Sparkline(rx, Good, peak, Modifier.fillMaxWidth().height(72.dp))
                    Sparkline(tx, Color(0xFFE6B800), peak, Modifier.fillMaxWidth().height(72.dp))
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("● приём", color = Good, style = MaterialTheme.typography.labelSmall)
                    Text("● отдача", color = Color(0xFFE6B800), style = MaterialTheme.typography.labelSmall)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.ListAlt, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Топ процессов", style = MaterialTheme.typography.titleMedium)
                    }
                    if (procs.isEmpty()) Text("Загрузка…", color = TextDim, modifier = Modifier.padding(16.dp))
                    procs.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(p.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("PID ${p.pid}", style = MaterialTheme.typography.labelSmall, color = TextDim)
                            }
                            Text("%.1f%% CPU".format(p.cpu), fontSize = 12.sp, color = if (p.cpu > 50) Bad else TextDim, modifier = Modifier.padding(end = 8.dp))
                            Text("%.1f%% RAM".format(p.mem), fontSize = 12.sp, color = TextDim)
                            IconButton(onClick = { killTarget = p }) { Icon(Icons.Rounded.Close, "Завершить", tint = Bad.copy(alpha = .8f)) }
                        }
                    }
                }
            }
        }
    }

    killTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            icon = { Icon(Icons.Rounded.Warning, null, tint = Warn) },
            title = { Text("Завершить процесс?") },
            text = { Text("${p.name} (PID ${p.pid}) получит сигнал TERM.") },
            confirmButton = {
                TextButton(onClick = {
                    killTarget = null
                    scope.launch {
                        val r = SshManager.exec("kill ${p.pid} 2>&1 || sudo -n kill ${p.pid} 2>&1")
                        snack.showSnackbar(if (r.code == 0) "Сигнал отправлен: ${p.name}" else r.out.lines().firstOrNull().orEmpty().ifBlank { "Не удалось" })
                    }
                }) { Text("Завершить", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Отмена") } },
        )
    }
}
