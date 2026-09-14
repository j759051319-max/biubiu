package cn.local.dailywork.backup

import android.content.Context
import android.net.Uri
import android.util.Xml
import cn.local.dailywork.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.floor

data class BackupResult(
    val file: File,
    val categories: Int,
    val habits: Int,
    val records: Int,
    val tasks: Int,
    val notes: Int,
    val learning: Int = 0,
    val water: Int = 0,
    val wellness: Int = 0
)

data class ImportResult(
    val categories: Int,
    val habits: Int,
    val records: Int,
    val tasks: Int,
    val notes: Int,
    val learning: Int = 0,
    val water: Int = 0,
    val wellness: Int = 0
)

internal val LEARNING_HEADERS = listOf("日期", "时长分钟", "记录时间")
internal val WATER_HEADERS = listOf("日期", "饮水毫升", "记录时间")
internal val WELLNESS_HEADERS = listOf("日期", "起床分钟", "睡觉分钟", "手动步数", "同步步数", "同步时间")

internal data class TaskColumns(
    val created: Int,
    val title: Int,
    val completed: Int,
    val completedDate: Int?,
    val note: Int,
    val sort: Int
)

internal fun taskColumns(header: List<String>): TaskColumns {
    fun index(vararg names: String): Int = names.firstNotNullOfOrNull { name ->
        header.indexOf(name).takeIf { it >= 0 }
    } ?: error("日程工作表缺少列：${names.joinToString("/")}")

    return TaskColumns(
        created = index("创建日期", "日期"),
        title = index("标题"),
        completed = index("已完成"),
        completedDate = header.indexOf("完成日期").takeIf { it >= 0 },
        note = index("备注"),
        sort = index("排序")
    )
}

class ExcelBackupManager(private val context: Context, private val db: AppDatabase) {
    suspend fun export(): BackupResult = withContext(Dispatchers.IO) {
        val categories = db.categoryDao().all()
        val habits = db.habitDao().all()
        val records = db.recordDao().all()
        val tasks = db.taskDao().allInDisplayOrder()
        val notes = db.noteDao().all()
        val learning = db.learningDao().all()
        val water = db.waterDao().all()
        val wellness = db.wellnessDao().all()
        val categoryNames = categories.associate { it.id to it.name }
        val habitMap = habits.associateBy { it.id }
        val overview = mutableMapOf<String, MutableList<String>>()

        records.forEach { record ->
            habitMap[record.habitId]?.let { habit ->
                val state = if (record.completed) "✓" else "○"
                overview.getOrPut(record.date) { mutableListOf() }.add("$state 习惯：${habit.title}")
            }
        }
        tasks.forEach { task ->
            val state = if (task.completed) "✓" else "○"
            val overviewDate = task.completedDate ?: task.date
            overview.getOrPut(overviewDate) { mutableListOf() }.add("$state 日程：${task.title}")
        }
        notes.forEach { overview.getOrPut(it.date) { mutableListOf() } }

        val sheets = listOf(
            "日程总览" to (listOf(listOf("日期", "全部工作（✓已完成，○未完成）", "当天小结")) +
                overview.keys.sortedDescending().map { date ->
                    listOf(date, overview[date]!!.joinToString("；"), notes.firstOrNull { it.date == date }?.content.orEmpty())
                }),
            "类别" to (listOf(listOf("名称", "排序", "启用")) +
                categories.map { listOf(it.name, it.sortOrder.toString(), it.enabled.cnBoolean()) }),
            "固定习惯" to (listOf(listOf("名称", "类别", "排序", "启用", "提醒", "提醒时", "提醒分")) +
                habits.map { habit ->
                    listOf(
                        habit.title,
                        categoryNames[habit.categoryId].orEmpty(),
                        habit.sortOrder.toString(),
                        habit.enabled.cnBoolean(),
                        habit.reminderEnabled.cnBoolean(),
                        habit.reminderHour.toString(),
                        habit.reminderMinute.toString()
                    )
                }),
            "习惯记录" to (listOf(listOf("日期", "习惯名称", "类别", "已完成", "完成时间", "备注")) +
                records.mapNotNull { record ->
                    habitMap[record.habitId]?.let { habit ->
                        listOf(
                            record.date,
                            habit.title,
                            categoryNames[habit.categoryId].orEmpty(),
                            record.completed.cnBoolean(),
                            record.completedAt?.toString().orEmpty(),
                            record.note
                        )
                    }
                }),
            "日程" to (listOf(listOf("创建日期", "标题", "已完成", "完成日期", "备注", "排序")) +
                tasks.map {
                    listOf(
                        it.date,
                        it.title,
                        it.completed.cnBoolean(),
                        it.completedDate.orEmpty(),
                        it.note,
                        it.sortOrder.toString()
                    )
                }),
            "每日小结" to (listOf(listOf("日期", "内容")) + notes.map { listOf(it.date, it.content) }),
            "学习记录" to (listOf(LEARNING_HEADERS) + learning.map {
                listOf(it.date, it.durationMinutes.toString(), it.createdAt.toString())
            }),
            "饮水记录" to (listOf(WATER_HEADERS) + water.map {
                listOf(it.date, it.milliliters.toString(), it.createdAt.toString())
            }),
            "每日健康" to (listOf(WELLNESS_HEADERS) + wellness.map {
                listOf(
                    it.date,
                    it.wakeMinute?.toString().orEmpty(),
                    it.bedMinute?.toString().orEmpty(),
                    it.manualSteps?.toString().orEmpty(),
                    it.syncedSteps?.toString().orEmpty(),
                    it.stepsSyncedAt?.toString().orEmpty()
                )
            }),
            "备份说明" to listOf(
                listOf("项目", "内容"),
                listOf("导出时间", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))),
                listOf("可编辑工作表", "类别、固定习惯、习惯记录、日程、每日小结、学习记录、饮水记录、每日健康；日程总览仅用于查看"),
                listOf("日期格式", "推荐使用 yyyy-MM-dd；也兼容 Excel/WPS 日期单元格"),
                listOf("完成状态", "填写“是/否”“完成/未完成”或 true/false"),
                listOf("恢复方式", "合并导入；同日期同项目以备份内容更新"),
                listOf("版本", "4")
            )
        )
        val dir = File(context.cacheDir, "backups").apply { mkdirs() }
        val file = File(dir, "每日工作打卡备份.xlsx")
        Xlsx.write(file, sheets)
        BackupResult(
            file,
            categories.size,
            habits.size,
            records.size,
            tasks.size,
            notes.size,
            learning.size,
            water.size,
            wellness.size
        )
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("daily_work_import", ".xlsx", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")

            val categoryRows = Xlsx.read(temp, listOf("类别"), 2).dropHeader()
            val habitRows = Xlsx.read(temp, listOf("固定习惯"), 3).dropHeader()
            val recordRows = normalizeDateRows(Xlsx.read(temp, listOf("习惯记录"), 4).dropHeader(), "习惯记录")
            val taskSheet = Xlsx.read(temp, listOf("日程", "临时工作"), 5)
            val columns = taskSheet.firstOrNull()?.let(::taskColumns)
            val taskRows = if (columns == null) emptyList() else taskSheet.drop(1)
            val noteRows = normalizeDateRows(Xlsx.read(temp, listOf("每日小结"), 6).dropHeader(), "每日小结")
            val learningRows = normalizeDateRows(Xlsx.readOptional(temp, listOf("学习记录")).dropHeader(), "学习记录")
            val waterRows = normalizeDateRows(Xlsx.readOptional(temp, listOf("饮水记录")).dropHeader(), "饮水记录")
            val wellnessRows = normalizeDateRows(Xlsx.readOptional(temp, listOf("每日健康")).dropHeader(), "每日健康")

            if (
                categoryRows.isEmpty() && habitRows.isEmpty() && recordRows.isEmpty() && taskRows.isEmpty() &&
                noteRows.isEmpty() && learningRows.isEmpty() && waterRows.isEmpty() && wellnessRows.isEmpty()
            ) {
                error("不是有效的每日工作打卡备份文件，或可编辑工作表中没有数据")
            }

            var categoryCount = 0
            var habitCount = 0
            var recordCount = 0
            var taskCount = 0
            var noteCount = 0
            var learningCount = 0
            var waterCount = 0
            var wellnessCount = 0
            val categoryIds = mutableMapOf<String, Long>()

            categoryRows.forEach { row ->
                val name = row.getOrNull(0).orEmpty().trim()
                if (name.isNotBlank()) {
                    val old = db.categoryDao().findByName(name)
                    val item = (old ?: Category(name = name)).copy(
                        name = name,
                        sortOrder = row.getOrNull(1).orEmpty().toNumberInt() ?: 0,
                        enabled = row.getOrNull(2).toBooleanSafe(true)
                    )
                    val id = if (old == null) db.categoryDao().insert(item) else {
                        db.categoryDao().update(item)
                        item.id
                    }
                    categoryIds[name] = id
                    categoryCount++
                }
            }

            val importedHabits = mutableMapOf<String, Habit>()
            habitRows.forEach { row ->
                val title = row.getOrNull(0).orEmpty().trim()
                if (title.isNotBlank()) {
                    val categoryName = row.getOrNull(1).orEmpty().trim()
                    val categoryId = categoryName.takeIf { it.isNotBlank() }?.let { name ->
                        categoryIds[name] ?: db.categoryDao().findByName(name)?.id
                    }
                    val old = db.habitDao().find(title, categoryId)
                    val item = (old ?: Habit(title = title)).copy(
                        title = title,
                        categoryId = categoryId,
                        sortOrder = row.getOrNull(2).orEmpty().toNumberInt() ?: (old?.sortOrder ?: db.habitDao().nextSortOrder()),
                        enabled = row.getOrNull(3).toBooleanSafe(true),
                        reminderEnabled = row.getOrNull(4).toBooleanSafe(false),
                        reminderHour = row.getOrNull(5).orEmpty().toNumberInt()?.coerceIn(0, 23) ?: 20,
                        reminderMinute = row.getOrNull(6).orEmpty().toNumberInt()?.coerceIn(0, 59) ?: 0
                    )
                    val saved = if (old == null) item.copy(id = db.habitDao().insert(item)) else {
                        db.habitDao().update(item)
                        item
                    }
                    importedHabits[habitKey(title, categoryName)] = saved
                    habitCount++
                }
            }

            recordRows.forEach { row ->
                val date = row.getOrNull(0).orEmpty()
                val title = row.getOrNull(1).orEmpty().trim()
                val categoryName = row.getOrNull(2).orEmpty().trim()
                if (date.isNotBlank() && title.isNotBlank()) {
                    val categoryId = categoryName.takeIf { it.isNotBlank() }?.let { name ->
                        categoryIds[name] ?: db.categoryDao().findByName(name)?.id
                    }
                    var habit = importedHabits[habitKey(title, categoryName)] ?: db.habitDao().find(title, categoryId)
                    if (habit == null) {
                        val newHabit = Habit(title = title, categoryId = categoryId, sortOrder = db.habitDao().nextSortOrder())
                        habit = newHabit.copy(id = db.habitDao().insert(newHabit))
                    }
                    importedHabits[habitKey(title, categoryName)] = habit
                    db.recordDao().upsert(
                        DailyHabitRecord(
                            date = date,
                            habitId = habit.id,
                            completed = row.getOrNull(3).toBooleanSafe(false),
                            completedAt = row.getOrNull(4).orEmpty().toNumberLong(),
                            note = row.getOrNull(5).orEmpty()
                        )
                    )
                    recordCount++
                }
            }

            columns?.let { mapped ->
                taskRows.forEachIndexed { index, row ->
                    val rowNumber = index + 2
                    val createdDate = normalizedDate(
                        row.getOrNull(mapped.created).orEmpty(),
                        "日程",
                        rowNumber
                    )
                    val title = row.getOrNull(mapped.title).orEmpty().trim()
                    if (title.isNotBlank()) {
                        val explicitCompletedDate = mapped.completedDate
                            ?.let { row.getOrNull(it).orEmpty().trim() }
                            ?.takeIf { it.isNotBlank() }
                            ?.let { normalizedDate(it, "日程", rowNumber) }
                        val completedFlag = row.getOrNull(mapped.completed).toBooleanSafe(false)
                        val completedDate = explicitCompletedDate ?: createdDate.takeIf { completedFlag }
                        val old = db.taskDao().find(createdDate, title)
                        val item = (old ?: DailyTask(date = createdDate, title = title)).copy(
                            date = createdDate,
                            title = title,
                            completed = completedDate != null,
                            completedDate = completedDate,
                            note = row.getOrNull(mapped.note).orEmpty(),
                            sortOrder = row.getOrNull(mapped.sort).orEmpty().toNumberInt()
                                ?: (old?.sortOrder ?: db.taskDao().nextSortOrder())
                        )
                        if (old == null) db.taskDao().insert(item) else db.taskDao().update(item)
                        taskCount++
                    }
                }

                db.taskDao().allInDisplayOrder().forEachIndexed { index, task ->
                    db.taskDao().updateSortOrder(task.id, index)
                }
            }

            noteRows.forEach { row ->
                val date = row.getOrNull(0).orEmpty()
                if (date.isNotBlank()) {
                    db.noteDao().upsert(DailyNote(date, row.getOrNull(1).orEmpty()))
                    noteCount++
                }
            }

            learningRows.forEach { row ->
                val date = row.getOrNull(0).orEmpty()
                val minutes = row.getOrNull(1).orEmpty().toNumberInt()
                val createdAt = row.getOrNull(2).orEmpty().toNumberLong()
                if (date.isNotBlank() && minutes != null && minutes > 0 && createdAt != null) {
                    if (db.learningDao().find(date, minutes, createdAt) == null) {
                        db.learningDao().insert(LearningSession(date = date, durationMinutes = minutes, createdAt = createdAt))
                    }
                    learningCount++
                }
            }

            waterRows.forEach { row ->
                val date = row.getOrNull(0).orEmpty()
                val milliliters = row.getOrNull(1).orEmpty().toNumberInt()
                val createdAt = row.getOrNull(2).orEmpty().toNumberLong()
                if (date.isNotBlank() && milliliters != null && milliliters > 0 && createdAt != null) {
                    if (db.waterDao().find(date, milliliters, createdAt) == null) {
                        db.waterDao().insert(WaterEntry(date = date, milliliters = milliliters, createdAt = createdAt))
                    }
                    waterCount++
                }
            }

            wellnessRows.forEach { row ->
                val date = row.getOrNull(0).orEmpty()
                if (date.isNotBlank()) {
                    db.wellnessDao().upsert(
                        DailyWellness(
                            date = date,
                            wakeMinute = row.getOrNull(1).orEmpty().toNumberInt()?.coerceIn(0, 1439),
                            bedMinute = row.getOrNull(2).orEmpty().toNumberInt()?.coerceIn(0, 1439),
                            manualSteps = row.getOrNull(3).orEmpty().toNumberInt()?.coerceIn(0, 200000),
                            syncedSteps = row.getOrNull(4).orEmpty().toNumberLong()?.coerceAtLeast(0),
                            stepsSyncedAt = row.getOrNull(5).orEmpty().toNumberLong()
                        )
                    )
                    wellnessCount++
                }
            }

            ImportResult(
                categoryCount,
                habitCount,
                recordCount,
                taskCount,
                noteCount,
                learningCount,
                waterCount,
                wellnessCount
            )
        } finally {
            temp.delete()
        }
    }

    private fun normalizeDateRows(rows: List<List<String>>, sheetName: String): List<List<String>> = rows.mapIndexedNotNull { index, row ->
        val raw = row.getOrNull(0).orEmpty().trim()
        if (raw.isBlank()) return@mapIndexedNotNull null
        val normalized = normalizeDate(raw) ?: error("$sheetName 第 ${index + 2} 行日期无法识别：$raw")
        row.toMutableList().also {
            if (it.isEmpty()) it.add(normalized) else it[0] = normalized
        }
    }

    private fun normalizedDate(value: String, sheetName: String, rowNumber: Int): String =
        normalizeDate(value.trim()) ?: error("$sheetName 第 $rowNumber 行日期无法识别：$value")

    private fun normalizeDate(value: String): String? {
        val text = value.trim()
        val patterns = listOf("yyyy-M-d", "yyyy/M/d", "yyyy年M月d日", "M/d/yyyy")
        patterns.forEach { pattern ->
            try {
                return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern)).toString()
            } catch (_: DateTimeParseException) {
                // 尝试下一种格式。
            }
        }
        val serial = text.toDoubleOrNull()
        if (serial != null && serial in 1.0..2958465.0) {
            return LocalDate.of(1899, 12, 30).plusDays(floor(serial).toLong()).toString()
        }
        return null
    }

    private fun List<List<String>>.dropHeader(): List<List<String>> = if (isEmpty()) emptyList() else drop(1)
    private fun habitKey(title: String, category: String) = "${title.trim()}\u0000${category.trim()}"
    private fun Boolean.cnBoolean() = if (this) "是" else "否"
    private fun String?.toBooleanSafe(default: Boolean) = when (this?.trim()?.lowercase()) {
        "true", "1", "是", "完成", "已完成", "启用", "已启用" -> true
        "false", "0", "否", "未完成", "未启用", "停用", "已停用" -> false
        else -> default
    }
    private fun String.toNumberInt(): Int? = toDoubleOrNull()?.toInt()
    private fun String.toNumberLong(): Long? = toDoubleOrNull()?.toLong()
}

private object Xlsx {
    private data class SheetRef(val name: String, val relationId: String)

    fun write(file: File, sheets: List<Pair<String, List<List<String>>>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            put(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>${sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}</Types>""")
            put(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put(zip, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets.mapIndexed { index, sheet -> "<sheet name=\"${esc(sheet.first)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>" }.joinToString("")}</sheets></workbook>""")
            put(zip, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }}</Relationships>""")
            sheets.forEachIndexed { index, sheet -> put(zip, "xl/worksheets/sheet${index + 1}.xml", worksheet(sheet.second)) }
        }
    }

    fun read(file: File, sheetNames: List<String>, fallbackIndex: Int): List<List<String>> = ZipFile(file).use { zip ->
        val sharedStrings = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            zip.getInputStream(entry).use(::parseSharedStrings)
        }.orEmpty()
        val sheetPath = resolveSheetPath(zip, sheetNames) ?: "xl/worksheets/sheet$fallbackIndex.xml"
        val entry = zip.getEntry(sheetPath) ?: return emptyList()
        zip.getInputStream(entry).use { parseRows(it, sharedStrings) }
    }

    fun readOptional(file: File, sheetNames: List<String>): List<List<String>> = ZipFile(file).use { zip ->
        val sharedStrings = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            zip.getInputStream(entry).use(::parseSharedStrings)
        }.orEmpty()
        val sheetPath = resolveSheetPath(zip, sheetNames) ?: return emptyList()
        val entry = zip.getEntry(sheetPath) ?: return emptyList()
        zip.getInputStream(entry).use { parseRows(it, sharedStrings) }
    }

    private fun resolveSheetPath(zip: ZipFile, names: List<String>): String? {
        val workbookEntry = zip.getEntry("xl/workbook.xml") ?: return null
        val sheets = zip.getInputStream(workbookEntry).use(::parseWorkbookSheets)
        val selected = names.firstNotNullOfOrNull { wanted -> sheets.firstOrNull { it.name == wanted } }
            ?: return null
        val relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return null
        val target = zip.getInputStream(relsEntry).use { parseRelationships(it)[selected.relationId] } ?: return null
        return when {
            target.startsWith("/") -> target.removePrefix("/")
            target.startsWith("xl/") -> target
            else -> "xl/${target.removePrefix("../")}".replace("\\", "/")
        }
    }

    private fun parseWorkbookSheets(input: InputStream): List<SheetRef> {
        val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val result = mutableListOf<SheetRef>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.getAttributeValue(null, "name").orEmpty()
                val relationId = (0 until parser.attributeCount)
                    .firstOrNull { parser.getAttributeName(it) == "id" }
                    ?.let(parser::getAttributeValue)
                    .orEmpty()
                if (name.isNotBlank() && relationId.isNotBlank()) result += SheetRef(name, relationId)
            }
            parser.next()
        }
        return result
    }

    private fun parseRelationships(input: InputStream): Map<String, String> {
        val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val result = mutableMapOf<String, String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id").orEmpty()
                val target = parser.getAttributeValue(null, "Target").orEmpty()
                if (id.isNotBlank() && target.isNotBlank()) result[id] = target
            }
            parser.next()
        }
        return result
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val result = mutableListOf<String>()
        var current: StringBuilder? = null
        var inText = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inText) current?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> result += current?.toString().orEmpty()
                }
            }
            parser.next()
        }
        return result
    }

    private fun parseRows(input: InputStream, sharedStrings: List<String>): List<List<String>> {
        val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val rows = mutableListOf<List<String>>()
        var row: MutableMap<Int, String>? = null
        var column = 0
        var cellType = ""
        var cellValue = StringBuilder()
        var readingValue = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        column = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        cellValue = StringBuilder()
                    }
                    "v", "t" -> readingValue = true
                }
                XmlPullParser.TEXT -> if (readingValue) cellValue.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> readingValue = false
                    "c" -> {
                        val raw = cellValue.toString()
                        val value = when (cellType) {
                            "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            "b" -> if (raw == "1") "true" else "false"
                            else -> raw
                        }
                        row?.set(column, value)
                    }
                    "row" -> {
                        val current = row.orEmpty()
                        val maxColumn = current.keys.maxOrNull() ?: -1
                        rows += if (maxColumn < 0) emptyList() else List(maxColumn + 1) { current[it].orEmpty() }
                    }
                }
            }
            parser.next()
        }
        return rows
    }

    private fun worksheet(rows: List<List<String>>) =
        """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${rows.mapIndexed { rowIndex, row -> "<row r=\"${rowIndex + 1}\">${row.mapIndexed { columnIndex, value -> "<c r=\"${col(columnIndex)}${rowIndex + 1}\" t=\"inlineStr\"><is><t>${esc(value)}</t></is></c>" }.joinToString("")}</row>" }.joinToString("")}</sheetData></worksheet>"""

    private fun put(zip: ZipOutputStream, path: String, body: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun esc(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile(Char::isLetter)
        if (letters.isEmpty()) return 0
        var result = 0
        letters.uppercase().forEach { result = result * 26 + (it - 'A' + 1) }
        return result - 1
    }

    private fun col(index: Int): String {
        var number = index + 1
        var result = ""
        while (number > 0) {
            val remainder = (number - 1) % 26
            result = ('A' + remainder) + result
            number = (number - 1) / 26
        }
        return result
    }
}
