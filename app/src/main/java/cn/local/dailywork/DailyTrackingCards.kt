package cn.local.dailywork

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import cn.local.dailywork.data.DailyWellness
import cn.local.dailywork.data.LearningSession
import cn.local.dailywork.data.StepSource
import cn.local.dailywork.data.WaterEntry
import cn.local.dailywork.data.resolveSteps
import cn.local.dailywork.data.sleepDurationMinutes

@Composable
internal fun DailyTrackingSection(
    date: String,
    learning: List<LearningSession>,
    water: List<WaterEntry>,
    wellness: DailyWellness?,
    stepStatus: String,
    onAddLearning: (Int) -> Unit,
    onDeleteLearning: (Long) -> Unit,
    onAddWater: (Int) -> Unit,
    onDeleteWater: (Long) -> Unit,
    onSaveWake: (Int) -> Unit,
    onSaveBed: (Int) -> Unit,
    onSaveManualSteps: (Int) -> Unit,
    onClearManualSteps: () -> Unit,
    onRequestStepSync: ((Set<String>) -> Unit) -> Unit,
    onStepPermissionResult: (Set<String>) -> Unit
) {
    var showLearning by remember(date) { mutableStateOf(false) }
    var showWater by remember(date) { mutableStateOf(false) }
    var showWake by remember(date) { mutableStateOf(false) }
    var showBed by remember(date) { mutableStateOf(false) }
    var showSteps by remember(date) { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
        onStepPermissionResult
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("每日记录")
        TrackingCard(
            icon = Icons.Default.School,
            title = "学习时长",
            value = formatDuration(learning.sumOf { it.durationMinutes }),
            action = "新增",
            onAction = { showLearning = true }
        ) {
            learning.asReversed().forEach { item ->
                EntryRow(formatDuration(item.durationMinutes)) { onDeleteLearning(item.id) }
            }
        }
        TrackingCard(
            icon = Icons.Default.WaterDrop,
            title = "每日饮水量",
            value = "${water.sumOf { it.milliliters }} 毫升",
            action = "记录饮水",
            onAction = { showWater = true }
        ) {
            water.asReversed().forEach { item ->
                EntryRow("${item.milliliters} 毫升") { onDeleteWater(item.id) }
            }
        }
        TrackingCard(
            icon = Icons.Default.Bedtime,
            title = "作息时间",
            value = sleepDurationMinutes(wellness?.wakeMinute, wellness?.bedMinute)?.let(::formatDuration)
                ?: "尚未完整记录",
            action = null,
            onAction = {}
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ showWake = true }, Modifier.weight(1f)) {
                    Text("起床 ${formatClock(wellness?.wakeMinute)}")
                }
                OutlinedButton({ showBed = true }, Modifier.weight(1f)) {
                    Text("睡觉 ${formatClock(wellness?.bedMinute)}")
                }
            }
        }
        val steps = resolveSteps(wellness?.syncedSteps, wellness?.manualSteps)
        TrackingCard(
            icon = Icons.Default.DirectionsWalk,
            title = "每日步数",
            value = steps.value?.let { "$it 步" } ?: "尚无数据",
            action = "手动记录",
            onAction = { showSteps = true }
        ) {
            Text(
                when (steps.source) {
                    StepSource.HEALTH_CONNECT -> "$stepStatus · 健康连接"
                    StepSource.MANUAL -> "手动记录 · $stepStatus"
                    StepSource.UNAVAILABLE -> stepStatus
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onRequestStepSync { permissionLauncher.launch(it) }
                }) { Text("同步手机步数") }
                if (wellness?.manualSteps != null) {
                    TextButton(onClick = onClearManualSteps) { Text("改用自动数据") }
                }
            }
        }
    }

    if (showLearning) {
        LearningDialog(
            onSave = { onAddLearning(it); showLearning = false },
            onClose = { showLearning = false }
        )
    }
    if (showWater) {
        NumberDialog(
            title = "记录饮水量",
            label = "本次饮水（毫升）",
            maxDigits = 5,
            onSave = { onAddWater(it); showWater = false },
            onClose = { showWater = false }
        )
    }
    if (showSteps) {
        NumberDialog(
            title = "手动记录步数",
            label = "当天步数",
            maxDigits = 6,
            allowZero = true,
            onSave = { onSaveManualSteps(it); showSteps = false },
            onClose = { showSteps = false }
        )
    }
    if (showWake) {
        ClockDialog("选择起床时间", wellness?.wakeMinute ?: 7 * 60, {
            onSaveWake(it); showWake = false
        }, { showWake = false })
    }
    if (showBed) {
        ClockDialog("选择睡觉时间", wellness?.bedMinute ?: 23 * 60, {
            onSaveBed(it); showBed = false
        }, { showBed = false })
    }
}

@Composable
private fun TrackingCard(
    icon: ImageVector,
    title: String,
    value: String,
    action: String?,
    onAction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (action != null) TextButton(onClick = onAction) { Text(action) }
            }
            content()
        }
    }
}

@Composable
private fun EntryRow(text: String, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除此条记录") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LearningDialog(onSave: (Int) -> Unit, onClose: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("分钟") }
    var expanded by remember { mutableStateOf(false) }
    val minutes = amount.toIntOrNull()?.let { if (unit == "小时") it * 60 else it }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("新增学习时长") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(4) },
                    label = { Text("时长") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = unit,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("单位") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("分钟", "小时").forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = { unit = value; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { minutes?.takeIf { it in 1..1440 }?.let(onSave) }, enabled = minutes in 1..1440) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

@Composable
private fun NumberDialog(
    title: String,
    label: String,
    maxDigits: Int,
    allowZero: Boolean = false,
    onSave: (Int) -> Unit,
    onClose: () -> Unit
) {
    var value by remember { mutableStateOf("") }
    val number = value.toIntOrNull()
    val valid = number != null && if (allowZero) number >= 0 else number > 0
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(maxDigits) },
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        confirmButton = { Button(onClick = { number?.let(onSave) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(title: String, initialMinute: Int, onSave: (Int) -> Unit, onClose: () -> Unit) {
    val state = rememberTimePickerState(initialMinute / 60, initialMinute % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = { Button(onClick = { onSave(state.hour * 60 + state.minute) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

private fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
}

private fun formatClock(minute: Int?): String = minute?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "未记录"
