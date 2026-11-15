package com.pyfold.ubuntucontrol

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f ГБ".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f МБ".format(b / 1048576.0)
    b >= 1024 -> "%.0f КБ".format(b / 1024.0)
    else -> "$b Б"
}

private fun fileIcon(f: RemoteFile): ImageVector {
    if (f.dir) return Icons.Rounded.Folder
    return when (f.name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "svg" -> Icons.Rounded.Image
        "mp4", "mkv", "avi", "mov" -> Icons.Rounded.Movie
        "mp3", "wav", "flac", "ogg" -> Icons.Rounded.AudioFile
        "zip", "gz", "tar", "xz", "7z", "bz2", "deb" -> Icons.Rounded.FolderZip
        "sh", "py", "js", "kt", "json", "yml", "yaml", "conf", "cfg", "ini", "service", "env", "toml" -> Icons.Rounded.Code
        "log", "txt", "md" -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
}

private fun join(dir: String, name: String) = if (dir == "/") "/$name" else "${dir.trimEnd('/')}/$name"

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "upload.bin"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(path: String?, refresh: Int, onPath: (String) -> Unit, onEdit: (String) -> Unit) {
    val conn by SshManager.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var cur by remember { mutableStateOf(path ?: "") }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<RemoteFile?>(null) }
    var renameFor by remember { mutableStateOf<RemoteFile?>(null) }
    var deleteFor by remember { mutableStateOf<RemoteFile?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var downloadFor by remember { mutableStateOf<RemoteFile?>(null) }
    var showHidden by remember { mutableStateOf(false) }

    LaunchedEffect(conn, cur, reload, refresh) {
        if (conn != Conn.Online) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        val start = cur.ifEmpty { SshManager.home().getOrNull() ?: "/" }
        SshManager.listDir(start).onSuccess { (real, list) -> cur = real; files = list; onPath(real) }
            .onFailure { error = it.message ?: "Ошибка" }
        loading = false
    }

    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = displayName(ctx, uri)
            val r = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)!!.use { SshManager.upload(it, join(cur, name)) } }.getOrElse { Result.failure(it) }
            }
            snack.showSnackbar(if (r.isSuccess) "Загружено: $name" else "Ошибка: ${r.exceptionOrNull()?.message}")
            reload++
        }
    }
    val downloader = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = downloadFor; downloadFor = null
        if (uri == null || f == null) return@rememberLauncherForActivityResult
        scope.launch {
            snack.showSnackbar("Скачиваю ${f.name}…")
            val r = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { SshManager.download(join(cur, f.name), it) } }.getOrElse { Result.failure(it) }
            }
            snack.showSnackbar(if (r.isSuccess) "Сохранено: ${f.name}" else "Ошибка: ${r.exceptionOrNull()?.message}")
        }
    }

    Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Column {
                    Text("Файлы", style = MaterialTheme.typography.titleLarge)
                    Text(cur.ifEmpty { "…" }, style = MaterialTheme.typography.bodySmall, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } },
                actions = {
                    IconButton(onClick = { showHidden = !showHidden }) {
                        Icon(if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Скрытые файлы", tint = if (showHidden) UbuntuOrange else TextDim)
                    }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "Обновить") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallFloatingActionButton(onClick = { text = ""; newFolder = true }, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Icon(Icons.Rounded.CreateNewFolder, "Новая папка")
                }
                FloatingActionButton(onClick = { uploader.launch("*/*") }, containerColor = UbuntuOrange, contentColor = Color.White) {
                    Icon(Icons.Rounded.UploadFile, "Загрузить")
                }
            }
        }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // хлебные крошки
            val segs = remember(cur) {
                val parts = cur.split('/').filter { it.isNotEmpty() }
                listOf("/" to "/") + parts.indices.map { i -> parts[i] to "/" + parts.take(i + 1).joinToString("/") }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(segs) { (label, p) ->
                    AssistChip(onClick = { cur = p }, label = { Text(if (label == "/") "корень" else label) },
                        leadingIcon = { Icon(if (label == "/") Icons.Rounded.Dns else Icons.Rounded.Folder, null, Modifier.size(16.dp)) },
                        shape = CircleShape, border = null,
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = UbuntuOrange)
            error?.let {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
            val shown = files.filter { showHidden || !it.name.startsWith(".") }
            if (!loading && error == null && shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.FolderOff, null, tint = TextDim, modifier = Modifier.size(48.dp))
                        Text("Папка пуста", color = TextDim, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (cur != "/" && cur.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().clickable { cur = cur.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.ArrowUpward, TextDim, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Наверх", color = TextDim)
                    }
                }
                items(shown, key = { it.name }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (f.dir) cur = join(cur, f.name)
                            else if (f.size <= 512 * 1024) onEdit(join(cur, f.name)) else menuFor = f
                        }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBubble(fileIcon(f), if (f.dir) UbuntuOrange else Color(0xFF8AB4F8), 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (f.dir) "" else fmtSize(f.size) + " · ") + SimpleDateFormat("d MMM yyyy HH:mm", Locale("ru")).format(Date(f.mtime)) + " · " + f.perms,
                                style = MaterialTheme.typography.bodySmall, color = TextDim, maxLines = 1,
                            )
                        }
                        IconButton(onClick = { menuFor = f }) { Icon(Icons.Rounded.MoreVert, "Действия", tint = TextDim) }
                    }
                }
            }
        }
    }

    // меню действий
    menuFor?.let { f ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(fileIcon(f), size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                @Composable fun act(icon: ImageVector, label: String, tint: Color = Color.Unspecified, onClick: () -> Unit) =
                    ListItem(headlineContent = { Text(label, color = tint.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface) },
                        leadingContent = { Icon(icon, null, tint = tint.takeIf { it != Color.Unspecified } ?: TextDim) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(onClick = onClick))
                if (f.dir) act(Icons.Rounded.FolderOpen, "Открыть") { cur = join(cur, f.name); menuFor = null }
                else {
                    if (f.size <= 512 * 1024) act(Icons.Rounded.Edit, "Открыть в редакторе") { menuFor = null; onEdit(join(cur, f.name)) }
                    act(Icons.Rounded.Download, "Скачать на телефон") { downloadFor = f; menuFor = null; downloader.launch(f.name) }
                }
                act(Icons.Rounded.DriveFileRenameOutline, "Переименовать") { text = f.name; renameFor = f; menuFor = null }
                act(Icons.Rounded.Terminal, "Путь в терминал") {
                    SshManager.pendingTermInput = SshManager.shq(join(cur, f.name)); menuFor = null
                    scope.launch { snack.showSnackbar("Путь отправится в терминал при открытии вкладки") }
                }
                act(Icons.Rounded.Delete, "Удалить", Bad) { deleteFor = f; menuFor = null }
            }
        }
    }

    if (newFolder) AlertDialog(
        onDismissRequest = { newFolder = false },
        icon = { Icon(Icons.Rounded.CreateNewFolder, null, tint = UbuntuOrange) }, title = { Text("Новая папка") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Имя") }, shape = MaterialTheme.shapes.medium) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = {
            newFolder = false
            scope.launch {
                val r = SshManager.mkdir(join(cur, text.trim()))
                snack.showSnackbar(if (r.isSuccess) "Папка создана" else "Ошибка: ${r.exceptionOrNull()?.message}"); reload++
            }
        }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Отмена") } },
    )

    renameFor?.let { f ->
        AlertDialog(
            onDismissRequest = { renameFor = null },
            icon = { Icon(Icons.Rounded.DriveFileRenameOutline, null, tint = UbuntuOrange) }, title = { Text("Переименовать") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Новое имя") }, shape = MaterialTheme.shapes.medium) },
            confirmButton = { TextButton(enabled = text.isNotBlank() && text != f.name, onClick = {
                renameFor = null
                scope.launch {
                    val r = SshManager.rename(join(cur, f.name), join(cur, text.trim()))
                    snack.showSnackbar(if (r.isSuccess) "Переименовано" else "Ошибка: ${r.exceptionOrNull()?.message}"); reload++
                }
            }) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Отмена") } },
        )
    }

    deleteFor?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            icon = { Icon(Icons.Rounded.Delete, null, tint = Bad) }, title = { Text("Удалить «${f.name}»?") },
            text = { Text(if (f.dir) "Папка будет удалена вместе со всем содержимым. Отменить нельзя." else "Файл будет удалён безвозвратно.") },
            confirmButton = { TextButton(onClick = {
                deleteFor = null
                scope.launch {
                    val r = SshManager.remove(join(cur, f.name), f.dir)
                    snack.showSnackbar(if (r.isSuccess) "Удалено" else "Ошибка: ${r.exceptionOrNull()?.message}"); reload++
                }
            }) { Text("Удалить", color = Bad) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Отмена") } },
        )
    }
}

// ───────────── Редактор ─────────────

@Composable
fun EditorScreen(path: String, onBack: () -> Unit, onSaved: () -> Unit) {
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val fontSize = (SecureStore.get("fs", "13").toIntOrNull() ?: 13).sp
    val dirty = original != null && text != original

    LaunchedEffect(path) {
        SshManager.readText(path).onSuccess { original = it; text = it }.onFailure { error = it.message ?: "Не удалось открыть" }
    }
    androidx.activity.compose.BackHandler(enabled = dirty) { askDiscard = true }

    AppScaffold(path.substringAfterLast('/'), subtitle = path.substringBeforeLast('/').ifEmpty { "/" }, onBack = { if (dirty) askDiscard = true else onBack() },
        snackbar = snack,
        actions = {
            if (dirty) Text("изменено", color = Warn, style = MaterialTheme.typography.labelMedium)
            IconButton(enabled = dirty && !saving, onClick = {
                saving = true
                scope.launch {
                    val r = SshManager.writeText(path, text)
                    saving = false
                    if (r.isSuccess) { original = text; onSaved(); snack.showSnackbar("Сохранено") }
                    else snack.showSnackbar("Ошибка: ${r.exceptionOrNull()?.message}")
                }
            }) {
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = UbuntuOrange)
                else Icon(Icons.Rounded.Save, "Сохранить", tint = if (dirty) UbuntuOrange else TextDim)
            }
        }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            when {
                error != null -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                    Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
                original == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = UbuntuOrange)
                else -> Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large, color = Color(0xFF050506),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    TextField(
                        text, { text = it }, Modifier.fillMaxSize(),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize * 1.35f),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    )
                }
            }
        }
    }

    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false },
        icon = { Icon(Icons.Rounded.Warning, null, tint = Warn) }, title = { Text("Есть несохранённые изменения") },
        text = { Text("Закрыть редактор без сохранения?") },
        confirmButton = { TextButton(onClick = { askDiscard = false; onBack() }) { Text("Закрыть", color = Bad) } },
        dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Остаться") } },
    )
}
