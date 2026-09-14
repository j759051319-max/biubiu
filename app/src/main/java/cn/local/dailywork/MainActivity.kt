package cn.local.dailywork

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.local.dailywork.backup.BackupResult
import cn.local.dailywork.backup.ExcelBackupManager
import cn.local.dailywork.data.*
import cn.local.dailywork.health.HealthConnectStatus
import cn.local.dailywork.health.StepHealthGateway
import cn.local.dailywork.reminder.ReminderAlarm
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val Cream = Color(0xFFFFF9F2)
private val Orange = Color(0xFFE9823A)
private val OrangeLight = Color(0xFFFFE4CC)
private val Ink = Color(0xFF3F332B)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var selectedImport by mutableStateOf<Uri?>(null)
    private val chooseBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { selectedImport = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Orange,
                    onPrimary = Color.White,
                    primaryContainer = OrangeLight,
                    background = Cream,
                    surface = Color(0xFFFFFDFC),
                    onSurface = Ink
                ),
                shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))
            ) {
                val vm: WorkViewModel = viewModel()
                LaunchedEffect(selectedImport) {
                    selectedImport?.let { vm.importBackup(it); selectedImport = null }
                }
                DailyWorkApp(
                    vm = vm,
                    requestNotification = {
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    requestExactAlarm = {
                        if (Build.VERSION.SDK_INT >= 31 && !ReminderAlarm.canScheduleExact(this)) {
                            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                        }
                    },
                    uploadBackup = { vm.createBackup(::shareBackup) },
                    chooseImport = {
                        chooseBackup.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
                    }
                )
            }
        }
    }

    private fun shareBackup(result: BackupResult) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", result.file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent(intent).setPackage("com.baidu.netdisk"))
        } catch (_: Exception) {
            startActivity(Intent.createChooser(intent, "上传备份文件"))
        }
    }
}

class WorkViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.get(application)
    private val backups = ExcelBackupManager(application, db)
    private val stepGateway = StepHealthGateway(application)
    var backupStatus by mutableStateOf("尚未生成备份")
        private set
    var stepStatus by mutableStateOf("尚未同步健康连接")
        private set

    val categories = db.categoryDao().observeAll()
    val habits = db.habitDao().observeAllWithCategory()
    fun records(date: String) = db.recordDao().observeForDate(date)
    fun tasks(date: String) = db.taskDao().observeTimeline(date)
    fun note(date: String) = db.noteDao().observeForDate(date)
    fun monthCounts(start: String, end: String) = db.recordDao().observeCounts(start, end)
    fun calendarCounts(start: String, end: String) = db.recordDao().observeCalendarCounts(start, end)
    fun completedRecords(start: String, end: String) = db.recordDao().observeCompletedBetween(start, end)
    fun completedTasks(start: String, end: String) = db.taskDao().observeCompletedBetween(start, end)
    fun learning(date: String) = db.learningDao().observe(date)
    fun water(date: String) = db.waterDao().observe(date)
    fun wellness(date: String) = db.wellnessDao().observe(date)

    fun addLearning(date: String, minutes: Int) = validLearningMinutes(minutes)?.let { value ->
        viewModelScope.launch { db.learningDao().insert(LearningSession(date = date, durationMinutes = value)) }
    }

    fun deleteLearning(id: Long) = viewModelScope.launch { db.learningDao().delete(id) }

    fun addWater(date: String, milliliters: Int) = validWaterMilliliters(milliliters)?.let { value ->
        viewModelScope.launch { db.waterDao().insert(WaterEntry(date = date, milliliters = value)) }
    }

    fun deleteWater(id: Long) = viewModelScope.launch { db.waterDao().delete(id) }

    fun saveWakeTime(date: String, minute: Int) = updateWellness(date) { it.copy(wakeMinute = minute.coerceIn(0, 1439)) }

    fun saveBedTime(date: String, minute: Int) = updateWellness(date) { it.copy(bedMinute = minute.coerceIn(0, 1439)) }

    fun saveManualSteps(date: String, steps: Int) = validSteps(steps)?.let { value ->
        updateWellness(date) { it.copy(manualSteps = value) }
    }

    fun clearManualSteps(date: String) = updateWellness(date) { it.copy(manualSteps = null) }

    fun stepPermission(): String = stepGateway.permission()

    fun requestStepSync(date: String, requestPermission: (Set<String>) -> Unit) = viewModelScope.launch {
        when (stepGateway.status()) {
            HealthConnectStatus.NEEDS_INSTALL -> stepStatus = "请先安装或更新健康连接，也可手动记录步数"
            HealthConnectStatus.UNAVAILABLE -> stepStatus = "此设备暂不支持健康连接，可手动记录步数"
            HealthConnectStatus.AVAILABLE -> {
                if (stepGateway.hasPermission()) syncStepsNow(date) else requestPermission(setOf(stepGateway.permission()))
            }
        }
    }

    fun onStepPermissionResult(date: String, granted: Set<String>) {
        if (stepGateway.permission() in granted) syncSteps(date)
        else stepStatus = "未授予步数权限，可继续手动记录"
    }

    fun syncSteps(date: String) = viewModelScope.launch {
        if (stepGateway.status() == HealthConnectStatus.AVAILABLE && stepGateway.hasPermission()) {
            syncStepsNow(date)
        }
    }

    private suspend fun syncStepsNow(date: String) {
        stepStatus = "正在同步步数…"
        runCatching { stepGateway.readSteps(LocalDate.parse(date)) }
            .onSuccess { steps ->
                val current = db.wellnessDao().find(date) ?: DailyWellness(date)
                db.wellnessDao().upsert(current.copy(syncedSteps = steps, stepsSyncedAt = System.currentTimeMillis()))
                stepStatus = "已从健康连接同步"
            }
            .onFailure { stepStatus = "步数同步失败，可稍后重试或手动记录" }
    }

    private fun updateWellness(date: String, update: (DailyWellness) -> DailyWellness) = viewModelScope.launch {
        val current = db.wellnessDao().find(date) ?: DailyWellness(date)
        db.wellnessDao().upsert(update(current))
    }

    fun saveRecord(date: String, habit: Habit, checked: Boolean, note: String = "") = viewModelScope.launch {
        db.recordDao().upsert(DailyHabitRecord(date, habit.id, checked, if (checked) System.currentTimeMillis() else null, note))
    }

    fun saveTask(task: DailyTask) = viewModelScope.launch {
        val normalized = task.copy(completed = task.completedDate != null)
        if (normalized.id == 0L) {
            db.taskDao().insert(normalized.copy(sortOrder = db.taskDao().nextSortOrder()))
        } else {
            db.taskDao().update(normalized)
        }
    }

    fun setTaskCompleted(task: DailyTask, date: String, checked: Boolean) = viewModelScope.launch {
        db.taskDao().update(
            task.copy(
                completed = checked,
                completedDate = if (checked) date else null
            )
        )
    }

    fun reorderTasks(orderedVisibleIds: List<Long>) = viewModelScope.launch {
        val all = db.taskDao().allInDisplayOrder()
        val merged = mergeVisibleOrder(all.map { it.id }, orderedVisibleIds)
        merged.forEachIndexed { index, id -> db.taskDao().updateSortOrder(id, index) }
    }

    fun deleteTask(id: Long) = viewModelScope.launch { db.taskDao().delete(id) }
    fun saveNote(date: String, value: String) = viewModelScope.launch { db.noteDao().upsert(DailyNote(date, value)) }
    fun saveCategory(item: Category) = viewModelScope.launch {
        if (item.id == 0L) db.categoryDao().insert(item) else db.categoryDao().update(item)
    }
    fun deleteCategory(item: Category) = viewModelScope.launch {
        db.categoryDao().clearHabitCategory(item.id)
        db.categoryDao().delete(item.id)
    }

    fun saveHabit(item: Habit) = viewModelScope.launch {
        val saved = if (item.id == 0L) {
            val newItem = item.copy(sortOrder = db.habitDao().nextSortOrder())
            newItem.copy(id = db.habitDao().insert(newItem))
        } else {
            db.habitDao().update(item)
            item
        }
        ReminderAlarm.schedule(getApplication(), saved)
    }

    fun reorderHabits(orderedVisibleIds: List<Long>) = viewModelScope.launch {
        val all = db.habitDao().all()
        val visibleSet = orderedVisibleIds.toSet()
        var visibleIndex = 0
        val mergedIds = all.map { habit ->
            if (habit.id in visibleSet) orderedVisibleIds[visibleIndex++] else habit.id
        }
        mergedIds.forEachIndexed { index, id -> db.habitDao().updateSortOrder(id, index) }
    }

    fun deleteHabit(item: Habit) = viewModelScope.launch {
        db.habitDao().delete(item.id)
        ReminderAlarm.cancel(getApplication(), item.id)
    }

    fun createBackup(onDone: (BackupResult) -> Unit) = viewModelScope.launch {
        runCatching { backups.export() }
            .onSuccess {
                backupStatus = "备份已生成：${it.records} 条习惯、${it.tasks} 条日程、${it.learning} 条学习、${it.water} 条饮水"
                onDone(it)
            }
            .onFailure { backupStatus = "生成失败：${it.message ?: "请重试"}" }
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        backupStatus = "正在导入 Excel 备份…"
        runCatching { backups.import(uri) }
            .onSuccess { result ->
                db.habitDao().all().filter { it.reminderEnabled }.forEach { ReminderAlarm.schedule(getApplication(), it) }
                backupStatus = "导入完成：${result.records} 条习惯、${result.tasks} 条日程、${result.learning} 条学习、${result.water} 条饮水"
            }
            .onFailure { backupStatus = "导入失败：${it.message ?: "文件可能已损坏"}" }
    }
}

private enum class Tab(val label: String) { TODAY("今日"), CALENDAR("日历"), MANAGE("管理") }

@Composable
private fun DailyWorkApp(
    vm: WorkViewModel,
    requestNotification: () -> Unit,
    requestExactAlarm: () -> Unit,
    uploadBackup: () -> Unit,
    chooseImport: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.TODAY) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFFFFF4E8)) {
                Tab.entries.forEach { item ->
                    val icon = when (item) {
                        Tab.TODAY -> Icons.Default.Home
                        Tab.CALENDAR -> Icons.Default.CalendarMonth
                        Tab.MANAGE -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            when (tab) {
                Tab.TODAY -> TodayScreen(vm, selectedDate, { selectedDate = it }, requestNotification, requestExactAlarm)
                Tab.CALENDAR -> CalendarScreen(vm, selectedDate, { selectedDate = it })
                Tab.MANAGE -> ManageScreen(vm, requestNotification, uploadBackup, chooseImport)
            }
        }
    }
}

@Composable
private fun TodayScreen(
    vm: WorkViewModel,
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    requestNotification: () -> Unit,
    requestExactAlarm: () -> Unit
) {
    val key = date.toString()
    val habits by vm.habits.collectAsState(emptyList())
    val records by vm.records(key).collectAsState(emptyList())
    val tasks by vm.tasks(key).collectAsState(emptyList())
    val note by vm.note(key).collectAsState(null)
    val learning by vm.learning(key).collectAsState(emptyList())
    val water by vm.water(key).collectAsState(emptyList())
    val wellness by vm.wellness(key).collectAsState(null)
    var addingTask by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<DailyTask?>(null) }
    var noteExpanded by remember { mutableStateOf(false) }
    var habitSettings by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (habitSettings) {
        HabitSettingsScreen(vm, requestNotification, requestExactAlarm) { habitSettings = false }
        return
    }

    val active = habits.filter { it.habit.enabled }
    val done = active.count { h -> records.any { it.habitId == h.habit.id && it.completed } }
    val activeIds = active.map { it.habit.id }
    val taskIds = tasks.map { it.task.id }
    val habitReorder = remember { ReorderController(activeIds) }
    val taskReorder = remember { ReorderController(taskIds) }
    LaunchedEffect(activeIds) { habitReorder.sync(activeIds) }
    LaunchedEffect(taskIds) { taskReorder.sync(taskIds) }
    val activeById = active.associateBy { it.habit.id }
    val tasksById = tasks.associateBy { it.task.id }
    val orderedHabits = habitReorder.ids.mapNotNull(activeById::get)
    val orderedTasks = taskReorder.ids.mapNotNull(tasksById::get)
    var noteText by remember(key, note) { mutableStateOf(note?.content.orEmpty()) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 18.dp)
    ) {
        item { DateHeader(date, onDate) { showDatePicker = true } }
        item { ProgressCard(done, active.size, Encouragements.forDate(date)) }
        item {
            DailyTrackingSection(
                date = key,
                learning = learning,
                water = water,
                wellness = wellness,
                stepStatus = vm.stepStatus,
                onAddLearning = { vm.addLearning(key, it) },
                onDeleteLearning = vm::deleteLearning,
                onAddWater = { vm.addWater(key, it) },
                onDeleteWater = vm::deleteWater,
                onSaveWake = { vm.saveWakeTime(key, it) },
                onSaveBed = { vm.saveBedTime(key, it) },
                onSaveManualSteps = { vm.saveManualSteps(key, it) },
                onClearManualSteps = { vm.clearManualSteps(key) },
                onRequestStepSync = { request -> vm.requestStepSync(key, request) },
                onStepPermissionResult = { vm.onStepPermissionResult(key, it) }
            )
        }
        item { SectionTitle("固定习惯", "${active.size} 项", "编辑") { habitSettings = true } }
        if (active.isEmpty()) item { GentleHint("还没有固定习惯，可点击右侧“编辑”添加。") }
        itemsIndexed(orderedHabits, key = { _, item -> "habit-${item.habit.id}" }) { _, relation ->
            val record = records.firstOrNull { it.habitId == relation.habit.id }
            val dragging = habitReorder.activeId == relation.habit.id
            HabitRow(
                modifier = Modifier.animateItem(
                    fadeInSpec = null,
                    placementSpec = if (dragging) null else spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    fadeOutSpec = null
                ),
                habit = relation.habit,
                checked = record?.completed == true,
                detail = record?.note.orEmpty(),
                canReorder = orderedHabits.size > 1,
                dragging = dragging,
                dragOffsetPx = if (dragging) habitReorder.dragOffsetPx else 0f,
                onDragStart = { habitReorder.start(relation.habit.id, it) },
                onDrag = habitReorder::dragBy,
                onDragEnd = { habitReorder.finish()?.let(vm::reorderHabits) },
                onDragCancel = habitReorder::cancel,
                onChecked = { vm.saveRecord(key, relation.habit, it, record?.note.orEmpty()) },
                onDetail = { vm.saveRecord(key, relation.habit, record?.completed == true, it) }
            )
        }
        item { SectionTitle("日程", action = "新增日程") { addingTask = true } }
        if (tasks.isEmpty()) item { GentleHint("还没有日程安排。") }
        itemsIndexed(orderedTasks, key = { _, item -> "task-${item.task.id}" }) { _, item ->
            val dragging = taskReorder.activeId == item.task.id
            TaskRow(
                modifier = Modifier.animateItem(
                    fadeInSpec = null,
                    placementSpec = if (dragging) null else spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    fadeOutSpec = null
                ),
                task = item.task,
                checked = item.completedOnDate,
                canReorder = orderedTasks.size > 1,
                dragging = dragging,
                dragOffsetPx = if (dragging) taskReorder.dragOffsetPx else 0f,
                onDragStart = { taskReorder.start(item.task.id, it) },
                onDrag = taskReorder::dragBy,
                onDragEnd = { taskReorder.finish()?.let(vm::reorderTasks) },
                onDragCancel = taskReorder::cancel,
                onChecked = { vm.setTaskCompleted(item.task, key, it) },
                onEdit = { editingTask = item.task },
                onDelete = { vm.deleteTask(item.task.id) }
            )
        }
        item { SectionTitle("当日小结", action = if (noteExpanded) "收起" else "编辑") { noteExpanded = !noteExpanded } }
        if (noteExpanded) {
            item {
                OutlinedTextField(
                    noteText,
                    { noteText = it },
                    label = { Text("记录今天的收获或待办") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
            item {
                Button(onClick = { vm.saveNote(key, noteText); noteExpanded = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("保存小结")
                }
            }
        } else if (noteText.isNotBlank()) {
            item { GentleHint(noteText) }
        }
    }

    if (addingTask) TaskDialog(key, null, { vm.saveTask(it); addingTask = false }, { addingTask = false })
    editingTask?.let { task -> TaskDialog(key, task, { vm.saveTask(it); editingTask = null }, { editingTask = null }) }
    if (showDatePicker) TodayDatePicker(date, { onDate(it); showDatePicker = false }, { showDatePicker = false })
}

@Composable
private fun DateHeader(date: LocalDate, onDate: (LocalDate) -> Unit, onCalendar: () -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onDate(date.minusDays(1)) }) { Icon(Icons.Default.ChevronLeft, "前一天") }
        Column(Modifier.clickable(onClick = onCalendar), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (date == LocalDate.now()) "今天，慢慢完成就好" else "查看历史记录", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { onDate(date.plusDays(1)) }) { Icon(Icons.Default.ChevronRight, "后一天") }
    }

@Composable
private fun ProgressCard(done: Int, total: Int, encouragement: String) =
    Card(colors = CardDefaults.cardColors(containerColor = OrangeLight), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("今日完成进度", color = Ink, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$done", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(" / $total 项", Modifier.padding(bottom = 7.dp), style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = Orange,
                trackColor = Color.White.copy(alpha = .7f)
            )
            Text(encouragement, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }

@Composable
fun SectionTitle(title: String, subtitle: String = "", action: String? = null, click: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text("  $subtitle", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        if (action != null && click != null) TextButton(onClick = click) { Text(action) }
    }

@Composable
fun GentleHint(text: String) = Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1E3)), modifier = Modifier.fillMaxWidth()) {
    Text(text, Modifier.padding(14.dp), color = Ink, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AnimatedReorderCard(
    modifier: Modifier,
    dragging: Boolean,
    dragOffsetPx: Float,
    onHeightChanged: (Float) -> Unit,
    content: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (dragging) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "reorder-scale"
    )
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = if (dragging) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "reorder-offset"
    )
    val elevation by animateDpAsState(
        targetValue = if (dragging) 10.dp else 1.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "reorder-elevation"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (dragging) 2.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "reorder-border"
    )
    val borderColor by animateColorAsState(
        targetValue = if (dragging) Orange else Color.Transparent,
        animationSpec = tween(120),
        label = "reorder-border-color"
    )
    val containerColor by animateColorAsState(
        targetValue = if (dragging) OrangeLight.copy(alpha = .72f) else MaterialTheme.colorScheme.surface,
        animationSpec = tween(120),
        label = "reorder-container"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (dragging || animatedOffset != 0f) 1f else 0f)
            .graphicsLayer {
                translationY = animatedOffset
                scaleX = scale
                scaleY = scale
            }
            .onSizeChanged { onHeightChanged(it.height.toFloat()) },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = BorderStroke(borderWidth, borderColor)
    ) {
        content()
    }
}

@Composable
private fun HabitRow(
    modifier: Modifier,
    habit: Habit,
    checked: Boolean,
    detail: String,
    canReorder: Boolean,
    dragging: Boolean,
    dragOffsetPx: Float,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onChecked: (Boolean) -> Unit,
    onDetail: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var edit by remember(detail) { mutableStateOf(detail) }
    var rowStepPx by remember(habit.id) { mutableFloatStateOf(1f) }
    val shade = if (checked) Color(0xFF9A9A9A) else MaterialTheme.colorScheme.onSurface
    val decoration = if (checked) TextDecoration.LineThrough else TextDecoration.None
    AnimatedReorderCard(modifier, dragging, dragOffsetPx, { rowStepPx = it }) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReorderHandle(
                    itemKey = habit.id,
                    enabled = canReorder,
                    dragging = dragging,
                    rowStepPx = rowStepPx,
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel
                )
                Checkbox(checked, onCheckedChange = onChecked)
                Column(Modifier.weight(1f)) {
                    Text(habit.title, fontWeight = FontWeight.Medium, color = shade, textDecoration = decoration)
                    if (habit.reminderEnabled) Text("每日提醒 %02d:%02d".format(habit.reminderHour, habit.reminderMinute), style = MaterialTheme.typography.labelSmall, color = shade, textDecoration = decoration)
                }
                IconButton(onClick = { expanded = !expanded }) { Icon(Icons.Default.Edit, "编辑当天记录") }
            }
            if (expanded) {
                OutlinedTextField(
                    edit,
                    { edit = it },
                    label = { Text("本项备注") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = { onDetail(edit); expanded = false }) { Text("保存") } }
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    modifier: Modifier,
    task: DailyTask,
    checked: Boolean,
    canReorder: Boolean,
    dragging: Boolean,
    dragOffsetPx: Float,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onChecked: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var rowStepPx by remember(task.id) { mutableFloatStateOf(1f) }
    val shade = if (checked) Color(0xFF9A9A9A) else MaterialTheme.colorScheme.onSurface
    val decoration = if (checked) TextDecoration.LineThrough else TextDecoration.None
    AnimatedReorderCard(modifier, dragging, dragOffsetPx, { rowStepPx = it }) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ReorderHandle(
                itemKey = task.id,
                enabled = canReorder,
                dragging = dragging,
                rowStepPx = rowStepPx,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel
            )
            Checkbox(checked, onCheckedChange = onChecked)
            Text(task.title, Modifier.weight(1f), fontWeight = FontWeight.Medium, color = shade, textDecoration = decoration)
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
        }
    }
}

@Composable
private fun ReorderHandle(
    itemKey: Long,
    enabled: Boolean,
    dragging: Boolean,
    rowStepPx: Float,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    Icon(
        Icons.Default.DragHandle,
        contentDescription = if (enabled) "长按并上下拖动调整顺序" else null,
        tint = if (dragging) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(36.dp)
            .then(
                if (enabled) Modifier.pointerInput(itemKey, rowStepPx) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart(rowStepPx) },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount.y)
                        }
                    )
                } else Modifier
            )
            .padding(6.dp)
    )
}

@Composable
private fun TaskDialog(date: String, initial: DailyTask?, onSave: (DailyTask) -> Unit, onClose: () -> Unit) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var note by remember(initial) { mutableStateOf(initial?.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "新增日程" else "编辑日程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("工作内容") })
                OutlinedTextField(note, { note = it }, label = { Text("备注") })
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) {
                    val saved = if (initial == null) {
                        DailyTask(date = date, title = title.trim(), note = note.trim())
                    } else {
                        initial.copy(title = title.trim(), note = note.trim())
                    }
                    onSave(saved)
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

@Composable
private fun HabitSettingsScreen(
    vm: WorkViewModel,
    requestNotification: () -> Unit,
    requestExactAlarm: () -> Unit,
    onBack: () -> Unit
) {
    val habits by vm.habits.collectAsState(emptyList())
    var editing by remember { mutableStateOf<Habit?>(null) }
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                Text("习惯与提醒设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !ReminderAlarm.canScheduleExact(context)) {
            item { OutlinedButton(requestExactAlarm, Modifier.fillMaxWidth()) { Text("开启准时提醒权限") } }
        }
        item { SectionTitle("固定习惯", action = "新增习惯") { adding = true } }
        items(habits, key = { "habit-setting-${it.habit.id}" }) { relation ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(relation.habit.title, fontWeight = FontWeight.Medium)
                        Text(if (relation.habit.enabled) "已启用" else "已停用", style = MaterialTheme.typography.labelSmall)
                        if (relation.habit.reminderEnabled) Text("提醒：%02d:%02d".format(relation.habit.reminderHour, relation.habit.reminderMinute), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { editing = relation.habit }) { Icon(Icons.Default.Edit, "编辑") }
                    IconButton(onClick = { vm.deleteHabit(relation.habit) }) { Icon(Icons.Default.Delete, "删除") }
                }
            }
        }
    }
    if (adding) HabitEditor(null, {
        vm.saveHabit(it.copy(categoryId = null))
        if (it.reminderEnabled) { requestNotification(); requestExactAlarm() }
        adding = false
    }, { adding = false })
    editing?.let { item ->
        HabitEditor(item, {
            vm.saveHabit(it.copy(categoryId = null))
            if (it.reminderEnabled) { requestNotification(); requestExactAlarm() }
            editing = null
        }, { editing = null })
    }
}

@Composable
private fun HabitEditor(initial: Habit?, onSave: (Habit) -> Unit, onClose: () -> Unit) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    var reminderEnabled by remember(initial) { mutableStateOf(initial?.reminderEnabled ?: false) }
    var hour by remember(initial) { mutableStateOf((initial?.reminderHour ?: 20).toString()) }
    var minute by remember(initial) { mutableStateOf((initial?.reminderMinute ?: 0).toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "新增习惯" else "编辑习惯") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("习惯名称") })
                Row(verticalAlignment = Alignment.CenterVertically) { Text("启用习惯", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("每日提醒", Modifier.weight(1f)); Switch(reminderEnabled, { reminderEnabled = it }) }
                if (reminderEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, label = { Text("时") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, label = { Text("分") }, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) {
                    val saved = (initial ?: Habit(title = "")).copy(
                        title = title.trim(),
                        enabled = enabled,
                        reminderEnabled = reminderEnabled,
                        reminderHour = hour.toIntOrNull()?.coerceIn(0, 23) ?: 20,
                        reminderMinute = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    )
                    onSave(saved)
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

@Composable
private fun TodayDatePicker(initial: LocalDate, onSelected: (LocalDate) -> Unit, onClose: () -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "上月") }
                Text(month.format(DateTimeFormatter.ofPattern("yyyy年M月")))
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "下月") }
            }
        },
        text = { TodayMonthGrid(month, initial, onSelected) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

@Composable
private fun TodayMonthGrid(month: YearMonth, selected: LocalDate, onSelected: (LocalDate) -> Unit) {
    val leading = month.atDay(1).dayOfWeek.value - 1
    val raw = List(leading) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    val cells = raw + List((7 - raw.size % 7) % 7) { null }
    Column {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(
                        Modifier.weight(1f).height(40.dp).padding(2.dp)
                            .background(if (day == selected) OrangeLight else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(enabled = day != null) { day?.let(onSelected) },
                        contentAlignment = Alignment.Center
                    ) { if (day != null) Text(day.dayOfMonth.toString()) }
                }
            }
        }
    }
}

private object Encouragements {
    private val values = listOf("今天也在稳稳向前。", "完成一件，就离目标更近一点。", "慢慢来，持续记录就是进步。", "把今天过扎实，就是最好的积累。")
    fun forDate(date: LocalDate): String = values[(date.toEpochDay().mod(values.size.toLong())).toInt()]
}
