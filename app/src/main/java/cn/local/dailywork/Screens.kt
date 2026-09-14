package cn.local.dailywork

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.local.dailywork.data.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun CalendarScreen(vm: WorkViewModel, selected: LocalDate, onSelected: (LocalDate) -> Unit) {
    var month by remember(selected) { mutableStateOf(YearMonth.from(selected)) }
    val start = month.atDay(1).toString()
    val end = month.atEndOfMonth().toString()
    val counts by vm.calendarCounts(start, end).collectAsState(emptyList())
    val records by vm.completedRecords(start, end).collectAsState(emptyList())
    val tasks by vm.completedTasks(start, end).collectAsState(emptyList())
    val habits by vm.habits.collectAsState(emptyList())
    val countMap = counts.associateBy { it.date }
    LazyColumn(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton({ month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "previous") }; Text(month.format(DateTimeFormatter.ofPattern("yyyy-MM")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); IconButton({ month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "next") } } }
        item { GentleHint("本月已完成：${records.size} 次固定习惯 + ${tasks.size} 项日程") }
        item { CalendarGrid(month, selected, countMap, onSelected) }
        item { Text("\u672C\u6708\u5DF2\u5B8C\u6210\u5DE5\u4F5C\u6C47\u603B", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { MonthlySummary(records, tasks, habits) }
        item { Text("\u67E5\u770B\u65E5\u671F\uFF1A$selected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item { DayReview(vm, selected) }
    }
}

@Composable
private fun CalendarGrid(
    month: YearMonth,
    selected: LocalDate,
    counts: Map<String, CalendarDayCount>,
    onSelected: (LocalDate) -> Unit
) {
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
    val leadingAndDays = List<LocalDate?>(leadingBlanks) { null } +
        (1..month.lengthOfMonth()).map { month.atDay(it) }
    val trailingBlanks = (7 - leadingAndDays.size % 7) % 7
    val calendarCells = leadingAndDays + List<LocalDate?>(trailingBlanks) { null }

    Column {
        Row(Modifier.fillMaxWidth()) {
            listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
        }
        calendarCells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(Modifier.weight(1f).height(50.dp))
                    } else {
                        val completed = (counts[day.toString()]?.total ?: 0) > 0
                        Column(
                            Modifier.weight(1f).height(50.dp).padding(3.dp)
                                .background(if (day == selected) Color(0xFFFFE4CC) else Color.Transparent, CircleShape)
                                .clickable { onSelected(day) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(day.dayOfMonth.toString())
                            Spacer(Modifier.height(3.dp))
                            if (completed) Spacer(Modifier.size(7.dp).background(Color(0xFF75A889), CircleShape))
                            else Spacer(Modifier.size(7.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlySummary(
    records: List<DailyHabitRecord>,
    tasks: List<DailyTask>,
    habits: List<HabitWithCategory>
) {
    val habitNames = habits.associate { it.habit.id to it.habit.title }
    val byDate = mutableMapOf<String, MutableList<String>>()
    records.forEach { record ->
        byDate.getOrPut(record.date) { mutableListOf() }
            .add(habitNames[record.habitId] ?: "习惯")
    }
    tasks.forEach { task ->
        task.completedDate?.let { date ->
            byDate.getOrPut(date) { mutableListOf() }.add(task.title)
        }
    }
    val habitCounts = records.groupingBy { habitNames[it.habitId] ?: "习惯" }.eachCount()

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1E3))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("合计：${records.size} 次固定习惯 + ${tasks.size} 项日程", fontWeight = FontWeight.Bold)
            Text("按日期回顾", fontWeight = FontWeight.Bold)
            if (byDate.isEmpty()) {
                Text("本月暂无已完成记录")
            } else {
                byDate.toSortedMap(compareByDescending { it }).forEach { (date, titles) ->
                    Text("$date  ${titles.joinToString("、")}")
                }
            }
            HorizontalDivider()
            Text("按工作内容统计", fontWeight = FontWeight.Bold)
            if (habitCounts.isEmpty() && tasks.isEmpty()) {
                Text("本月暂无完成工作")
            } else {
                habitCounts.forEach { (name, amount) -> Text("$name  $amount 次") }
                tasks.forEach { task ->
                    Text("${task.completedDate.orEmpty()}  ${task.title}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DayReview(vm: WorkViewModel, date: LocalDate) {
    val key = date.toString()
    val habits by vm.habits.collectAsState(emptyList())
    val records by vm.records(key).collectAsState(emptyList())
    val tasks by vm.tasks(key).collectAsState(emptyList())
    val habitNames = habits.associate { it.habit.id to it.habit.title }
    val completedRecords = records.filter { it.completed }
    val completedTasks = tasks.filter { it.completedOnDate }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (completedRecords.isEmpty() && completedTasks.isEmpty()) {
                Text("当天暂无完成记录")
            }
            completedRecords.forEach { record ->
                Text("✓  ${habitNames[record.habitId] ?: "习惯"}")
            }
            completedTasks.forEach { item ->
                Text("✓  ${item.task.title}")
            }
        }
    }
}

@Composable
fun ManageScreen(vm: WorkViewModel, requestNotification: () -> Unit, uploadBackup: () -> Unit, chooseImport: () -> Unit) { var backupPage by remember { mutableStateOf(false) }; if (backupPage) { BackupScreen(vm, { backupPage = false }, uploadBackup, chooseImport); return }; val categories by vm.categories.collectAsState(emptyList()); val habits by vm.habits.collectAsState(emptyList()); LazyColumn(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text("\u7BA1\u7406", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("\u4E60\u60EF\u3001\u63D0\u9192\u4E0E\u6570\u636E\u5907\u4EFD") }; item { Card(Modifier.fillMaxWidth().clickable { backupPage = true }, colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE4CC))) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Excel \u5907\u4EFD\u4E0E\u6062\u590D", fontWeight = FontWeight.Bold); Text("\u8DF3\u8F6C\u767E\u5EA6\u7F51\u76D8\u4E0A\u4F20\uFF0C\u6362\u624B\u673A\u53EF\u6062\u590D", style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Default.ChevronRight, null) } } }; item { SectionTitle("\u5DE5\u4F5C\u7C7B\u522B", "${categories.size}\u4E2A") }; items(categories, key = { "category-${it.id}" }) { SimpleItem(it.name) { vm.deleteCategory(it) } }; item { SectionTitle("\u56FA\u5B9A\u4E60\u60EF", "${habits.size}\u9879") }; items(habits, key = { "habit-${it.habit.id}" }) { SimpleItem(it.habit.title) { vm.deleteHabit(it.habit) } } } }
@Composable private fun BackupScreen(vm: WorkViewModel, onBack: () -> Unit, uploadBackup: () -> Unit, chooseImport: () -> Unit) { LazyColumn(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) }; Text("Excel \u5907\u4EFD\u4E0E\u6062\u590D", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } }; item { GentleHint(vm.backupStatus) }; item { Text("\u6BCF\u6B21\u4E0A\u4F20\u90FD\u4F1A\u91CD\u65B0\u751F\u6210\u300A\u6BCF\u65E5\u5DE5\u4F5C\u6253\u5361\u5907\u4EFD.xlsx\u300B\u3002\u8BF7\u5728\u767E\u5EA6\u7F51\u76D8\u4E2D\u9009\u62E9\u8986\u76D6\u540C\u540D\u6587\u4EF6\u3002", style = MaterialTheme.typography.bodySmall) }; item { Button(uploadBackup, Modifier.fillMaxWidth()) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(8.dp)); Text("\u751F\u6210\u5E76\u4E0A\u4F20\u5230\u767E\u5EA6\u7F51\u76D8") } }; item { OutlinedButton(chooseImport, Modifier.fillMaxWidth()) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("\u9009\u62E9 Excel \u5907\u4EFD\u6062\u590D") } }; item { Text("\u6062\u590D\u4F1A\u5408\u5E76\u5BFC\u5165\uFF0C\u4E0D\u4F1A\u6E05\u7A7A\u624B\u673A\u91CC\u5DF2\u6709\u7684\u8BB0\u5F55\u3002", style = MaterialTheme.typography.bodySmall) } } }
@Composable private fun SimpleItem(label: String, delete: () -> Unit) = Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); IconButton(delete) { Icon(Icons.Default.Delete, null) } } }
