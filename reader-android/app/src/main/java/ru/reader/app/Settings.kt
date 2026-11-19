package ru.reader.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(prefs: Prefs) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Настройки", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(top = 12.dp)) }

        item {
            Section("Внешний вид") {
                Text("Тема", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("Система", "Светлая", "Тёмная"), prefs.theme) { prefs.putTheme(it) }
                Spacer(Modifier.height(8.dp))
                SwitchRow("Чёрный фон (AMOLED)", "Глубокий чёрный в тёмной теме", prefs.amoled) { prefs.putAmoled(it) }
            }
        }
        item {
            Section("Воспроизведение") {
                Text("Качество по умолчанию", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val qs = listOf(480, 720, 1080)
                Segmented(qs.map { "${it}p" }, qs.indexOf(prefs.quality).coerceAtLeast(0)) { prefs.putQuality(qs[it]) }
                Spacer(Modifier.height(4.dp))
                Text("Если серии нет в выбранном качестве, берётся ближайшее.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(16.dp))
                Text("Масштаб видео", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("Вписать", "Заполнить", "Растянуть"), prefs.resize) { prefs.putResize(it) }

                Spacer(Modifier.height(16.dp))
                SliderRow("Скорость", "%.2f×".format(prefs.speed), prefs.speed, 0.5f..2f, 5) { prefs.putSpeed((it * 4).roundToInt() / 4f) }
                SliderRow("Шаг перемотки", "${prefs.seekStep} с", prefs.seekStep.toFloat(), 5f..30f, 4) { prefs.putSeekStep((it / 5).roundToInt() * 5) }
                SwitchRow("Автопереход", "Следующая серия включается сама", prefs.autoNext) { prefs.putAutoNext(it) }
                SwitchRow("Пропускать заставку", "Автоматически, если она отмечена", prefs.autoSkip) { prefs.putAutoSkip(it) }
            }
        }
        item {
            Section("Данные") {
                FilledTonalButton({ prefs.clearHistory() }, Modifier.fillMaxWidth().height(52.dp), enabled = prefs.history.isNotEmpty()) {
                    Text("Очистить историю просмотра")
                }
            }
        }
        item {
            Section("О приложении") {
                Text("Каталог и видео: AniLibria API. Приложение только показывает то, что отдаёт сервис.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun Segmented(labels: List<String>, selected: Int, enabled: (Int) -> Boolean = { true }, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            SegmentedButton(
                selected = i == selected, onClick = { onSelect(i) }, enabled = enabled(i),
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                icon = { SegmentedButtonDefaults.Icon(i == selected) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } },
                modifier = Modifier.height(48.dp),
            ) { Text(l, maxLines = 1) }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            thumbContent = { Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize)) },
        )
    }
}

@Composable
fun SliderRow(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(valueText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
