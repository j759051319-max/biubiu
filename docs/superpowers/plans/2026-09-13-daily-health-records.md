# Daily Health and Learning Records Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-session learning, per-entry water, daily wake/bed times, and Health Connect-backed daily steps to the existing Android home screen, including offline persistence and Excel backup.

**Architecture:** Store learning and water as normalized dated entries and sleep/manual-step values as one dated wellness record in Room. Keep Health Connect behind a small gateway and merge its aggregate with the manual fallback in the ViewModel. Put the new Compose UI in a focused file and extend the existing Excel format with three backward-compatible sheets.

**Tech Stack:** Kotlin 1.9.25, Jetpack Compose Material 3, Room 2.6.1, AndroidX Health Connect Client 1.1.0-alpha11, JUnit 4, Android instrumentation tests.

## Global Constraints

- Continue to support Android API 26; Health Connect integration is conditional and the rest of the app remains usable without it.
- Read only daily step-count data from Health Connect; do not request location, heart-rate, or other health permissions.
- Historical dates can be viewed and manually completed from the existing date selector.
- Existing habit, task, note, reminder, and backup data must survive database migration.
- Do not add goals, trends, a live study timer, routes, or cloud sync.

---

### Task 1: Daily tracking model and calculations

**Files:**
- Create: `app/src/main/java/cn/local/dailywork/data/DailyTracking.kt`
- Create: `app/src/test/java/cn/local/dailywork/data/DailyTrackingTest.kt`

**Interfaces:**
- Produces: `LearningSession`, `WaterEntry`, `DailyWellness`, `DailyTrackingSummary`, `sleepDurationMinutes(wakeMinute: Int?, bedMinute: Int?): Int?`, and `resolveSteps(healthSteps: Long?, manualSteps: Int?): StepDisplay`.

- [ ] **Step 1: Write failing calculation tests**

```kotlin
class DailyTrackingTest {
    @Test fun `sleep crossing midnight is calculated`() {
        assertEquals(450, sleepDurationMinutes(7 * 60, 23 * 60 + 30))
    }

    @Test fun `manual steps override synced steps`() {
        assertEquals(StepDisplay(3200, StepSource.MANUAL), resolveSteps(7000, 3200))
    }

    @Test fun `health steps are used without manual value`() {
        assertEquals(StepDisplay(7000, StepSource.HEALTH_CONNECT), resolveSteps(7000, null))
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `gradle testDebugUnitTest --tests cn.local.dailywork.data.DailyTrackingTest`

Expected: FAIL because the tracking types and functions do not exist.

- [ ] **Step 3: Implement the model and pure functions**

```kotlin
@Entity(tableName = "learning_sessions", indices = [Index("date")])
data class LearningSession(@PrimaryKey(autoGenerate = true) val id: Long = 0, val date: String, val durationMinutes: Int, val createdAt: Long)

@Entity(tableName = "water_entries", indices = [Index("date")])
data class WaterEntry(@PrimaryKey(autoGenerate = true) val id: Long = 0, val date: String, val milliliters: Int, val createdAt: Long)

@Entity(tableName = "daily_wellness")
data class DailyWellness(@PrimaryKey val date: String, val wakeMinute: Int? = null, val bedMinute: Int? = null, val manualSteps: Int? = null, val syncedSteps: Long? = null, val stepsSyncedAt: Long? = null)

enum class StepSource { HEALTH_CONNECT, MANUAL, UNAVAILABLE }
data class StepDisplay(val value: Long?, val source: StepSource)
data class DailyTrackingSummary(val learningMinutes: Int, val waterMilliliters: Int, val sleepMinutes: Int?, val steps: StepDisplay)

fun sleepDurationMinutes(wakeMinute: Int?, bedMinute: Int?): Int? {
    if (wakeMinute == null || bedMinute == null) return null
    return (wakeMinute - bedMinute).mod(24 * 60)
}

fun resolveSteps(healthSteps: Long?, manualSteps: Int?): StepDisplay = when {
    manualSteps != null -> StepDisplay(manualSteps.toLong(), StepSource.MANUAL)
    healthSteps != null -> StepDisplay(healthSteps, StepSource.HEALTH_CONNECT)
    else -> StepDisplay(null, StepSource.UNAVAILABLE)
}
```

- [ ] **Step 4: Run the calculation tests**

Run: `gradle testDebugUnitTest --tests cn.local.dailywork.data.DailyTrackingTest`

Expected: PASS.

- [ ] **Step 5: Commit the model**

```bash
git add app/src/main/java/cn/local/dailywork/data/DailyTracking.kt app/src/test/java/cn/local/dailywork/data/DailyTrackingTest.kt
git commit -m "feat: add daily tracking model"
```

### Task 2: Room persistence and migration

**Files:**
- Modify: `app/src/main/java/cn/local/dailywork/data/AppDatabase.kt`
- Modify: `app/src/androidTest/java/cn/local/dailywork/data/AppDatabaseMigrationTest.kt`

**Interfaces:**
- Consumes: the three entities from Task 1.
- Produces: `LearningDao`, `WaterDao`, `WellnessDao`, database version 3, and `MIGRATION_2_3`.

- [ ] **Step 1: Add a failing migration test**

```kotlin
@Test fun migrate2To3_preservesExistingDataAndCreatesTrackingTables() {
    helper.createDatabase(DB_NAME, 2).apply {
        execSQL("INSERT INTO daily_notes(date, content) VALUES('2026-09-13', '保留')")
        close()
    }
    helper.runMigrationsAndValidate(DB_NAME, 3, true, AppDatabase.MIGRATION_2_3).use { db ->
        db.query("SELECT content FROM daily_notes WHERE date='2026-09-13'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("保留", cursor.getString(0))
        }
        db.query("SELECT COUNT(*) FROM learning_sessions").close()
        db.query("SELECT COUNT(*) FROM water_entries").close()
        db.query("SELECT COUNT(*) FROM daily_wellness").close()
    }
}
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run: `gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.local.dailywork.data.AppDatabaseMigrationTest`

Expected: FAIL because migration 2 to 3 is missing.

- [ ] **Step 3: Add DAOs and migration**

```kotlin
@Dao interface LearningDao {
    @Query("SELECT * FROM learning_sessions WHERE date = :date ORDER BY createdAt, id") fun observe(date: String): Flow<List<LearningSession>>
    @Query("SELECT * FROM learning_sessions ORDER BY date, createdAt, id") suspend fun all(): List<LearningSession>
    @Insert suspend fun insert(item: LearningSession): Long
    @Query("DELETE FROM learning_sessions WHERE id = :id") suspend fun delete(id: Long)
}

@Dao interface WaterDao {
    @Query("SELECT * FROM water_entries WHERE date = :date ORDER BY createdAt, id") fun observe(date: String): Flow<List<WaterEntry>>
    @Query("SELECT * FROM water_entries ORDER BY date, createdAt, id") suspend fun all(): List<WaterEntry>
    @Insert suspend fun insert(item: WaterEntry): Long
    @Query("DELETE FROM water_entries WHERE id = :id") suspend fun delete(id: Long)
}

@Dao interface WellnessDao {
    @Query("SELECT * FROM daily_wellness WHERE date = :date") fun observe(date: String): Flow<DailyWellness?>
    @Query("SELECT * FROM daily_wellness ORDER BY date") suspend fun all(): List<DailyWellness>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DailyWellness)
}
```

Set the database entities to include the new types, increment `version` to 3, expose the three DAO accessors, create each table and date index in `MIGRATION_2_3`, and register `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`.

- [ ] **Step 4: Run database tests**

Run: `gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.local.dailywork.data.AppDatabaseMigrationTest`

Expected: PASS.

- [ ] **Step 5: Commit persistence**

```bash
git add app/src/main/java/cn/local/dailywork/data/AppDatabase.kt app/src/androidTest/java/cn/local/dailywork/data/AppDatabaseMigrationTest.kt
git commit -m "feat: persist daily tracking data"
```

### Task 3: Health Connect step gateway

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/cn/local/dailywork/health/StepHealthGateway.kt`
- Create: `app/src/test/java/cn/local/dailywork/health/HealthConnectStatusTest.kt`

**Interfaces:**
- Produces: `HealthConnectStatus`, `StepHealthGateway.status()`, `StepHealthGateway.permission()`, and `StepHealthGateway.readSteps(date: LocalDate): Long`.

- [ ] **Step 1: Add a failing status mapping test**

```kotlin
@Test fun `sdk status is exposed as user state`() {
    assertEquals(HealthConnectStatus.AVAILABLE, mapSdkStatus(HealthConnectClient.SDK_AVAILABLE))
    assertEquals(HealthConnectStatus.NEEDS_INSTALL, mapSdkStatus(HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED))
    assertEquals(HealthConnectStatus.UNAVAILABLE, mapSdkStatus(HealthConnectClient.SDK_UNAVAILABLE))
}
```

- [ ] **Step 2: Add the dependency and verify the test initially fails**

Add `implementation("androidx.health.connect:connect-client:1.1.0-alpha11")` and run `gradle testDebugUnitTest --tests cn.local.dailywork.health.HealthConnectStatusTest`.

Expected: FAIL because the gateway is missing.

- [ ] **Step 3: Implement the gateway**

```kotlin
enum class HealthConnectStatus { AVAILABLE, NEEDS_INSTALL, UNAVAILABLE }

internal fun mapSdkStatus(status: Int) = when (status) {
    HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.AVAILABLE
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectStatus.NEEDS_INSTALL
    else -> HealthConnectStatus.UNAVAILABLE
}

class StepHealthGateway(private val context: Context) {
    fun status() = mapSdkStatus(HealthConnectClient.getSdkStatus(context))
    fun permission() = HealthPermission.getReadPermission(StepsRecord::class)
    suspend fun hasPermission() = status() == HealthConnectStatus.AVAILABLE &&
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().contains(permission())

    suspend fun readSteps(date: LocalDate): Long {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val response = HealthConnectClient.getOrCreate(context).aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end))
        )
        return response[StepsRecord.COUNT_TOTAL] ?: 0L
    }
}
```

Add `<uses-permission android:name="android.permission.health.READ_STEPS" />` and the Health Connect permission rationale activity/query declarations required by AndroidX.

- [ ] **Step 4: Run unit tests and compile**

Run: `gradle testDebugUnitTest assembleDebug`

Expected: PASS and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Commit the gateway**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/cn/local/dailywork/health/StepHealthGateway.kt app/src/test/java/cn/local/dailywork/health/HealthConnectStatusTest.kt
git commit -m "feat: read daily steps from Health Connect"
```

### Task 4: ViewModel state and commands

**Files:**
- Modify: `app/src/main/java/cn/local/dailywork/MainActivity.kt`
- Create: `app/src/test/java/cn/local/dailywork/data/DailyTrackingValidationTest.kt`

**Interfaces:**
- Consumes: DAOs and `StepHealthGateway`.
- Produces: `learning(date)`, `water(date)`, `wellness(date)`, `addLearning(date, minutes)`, `deleteLearning(id)`, `addWater(date, milliliters)`, `deleteWater(id)`, `saveWakeTime(date, minute)`, `saveBedTime(date, minute)`, `saveManualSteps(date, steps)`, and `syncSteps(date)`.

- [ ] **Step 1: Test input validation**

```kotlin
@Test fun `tracking inputs accept positive bounded values`() {
    assertEquals(90, validLearningMinutes(90))
    assertNull(validLearningMinutes(0))
    assertEquals(500, validWaterMilliliters(500))
    assertNull(validWaterMilliliters(-1))
    assertEquals(12000, validSteps(12000))
    assertNull(validSteps(-2))
}
```

- [ ] **Step 2: Run the validation test and verify it fails**

Run: `gradle testDebugUnitTest --tests cn.local.dailywork.data.DailyTrackingValidationTest`

Expected: FAIL because validation functions are missing.

- [ ] **Step 3: Implement validation and ViewModel commands**

```kotlin
fun validLearningMinutes(value: Int?) = value?.takeIf { it in 1..1440 }
fun validWaterMilliliters(value: Int?) = value?.takeIf { it in 1..10000 }
fun validSteps(value: Int?) = value?.takeIf { it in 0..200000 }
```

Every command validates first, writes on `viewModelScope`, and preserves the other fields of `DailyWellness`. `syncSteps` updates only `syncedSteps` and `stepsSyncedAt`; errors are converted to a user-readable `stepStatus` state instead of throwing into Compose.

- [ ] **Step 4: Run all unit tests**

Run: `gradle testDebugUnitTest`

Expected: PASS.

- [ ] **Step 5: Commit state management**

```bash
git add app/src/main/java/cn/local/dailywork/MainActivity.kt app/src/test/java/cn/local/dailywork/data/DailyTrackingValidationTest.kt
git commit -m "feat: manage daily tracking state"
```

### Task 5: Home-screen cards and permission flow

**Files:**
- Create: `app/src/main/java/cn/local/dailywork/DailyTrackingCards.kt`
- Modify: `app/src/main/java/cn/local/dailywork/MainActivity.kt`

**Interfaces:**
- Consumes: ViewModel flows and commands from Task 4.
- Produces: `DailyTrackingSection` and four cards/dialogs shown for the selected date.

- [ ] **Step 1: Add the Health Connect permission launcher to `MainActivity`**

```kotlin
private val requestHealthPermissions = registerForActivityResult(
    PermissionController.createRequestPermissionResultContract()
) { granted -> healthPermissionResult.value = granted.isNotEmpty() }
```

Pass a lambda that launches `setOf(stepGateway.permission())` into the root Compose function. After a successful callback, call `vm.syncSteps(selectedDate)`.

- [ ] **Step 2: Implement the cards and validated dialogs**

`DailyTrackingSection` renders in this order: learning, water, sleep, steps. Learning uses a numeric amount plus an `小时/分钟` dropdown and converts hours to minutes. Water accepts digits only. Wake and bed use Material time pickers. Step card shows `健康连接同步`, `手动记录`, or `暂不可用`, and requests permission only after the user taps sync.

- [ ] **Step 3: Wire the section into `TodayScreen`**

Collect the selected date's three flows with `collectAsState`, compute totals with `sumOf`, and insert `DailyTrackingSection` immediately after `ProgressCard` so all four metrics remain on the home screen.

- [ ] **Step 4: Compile and manually verify the UI**

Run: `gradle assembleDebug`

Expected: BUILD SUCCESSFUL. On an emulator/device, verify adding and deleting learning/water entries, changing dates, entering wake/bed times, manual steps, permission refusal, and Health Connect synchronization.

- [ ] **Step 5: Commit the UI**

```bash
git add app/src/main/java/cn/local/dailywork/DailyTrackingCards.kt app/src/main/java/cn/local/dailywork/MainActivity.kt
git commit -m "feat: add daily tracking cards to home"
```

### Task 6: Excel backup, compatibility, and release verification

**Files:**
- Modify: `app/src/main/java/cn/local/dailywork/backup/ExcelBackupManager.kt`
- Create: `app/src/test/java/cn/local/dailywork/backup/DailyTrackingBackupTest.kt`
- Modify: `app/build.gradle.kts`
- Modify: `README.md`

**Interfaces:**
- Consumes: all tracking DAOs and entities.
- Produces: backup sheets `学习记录`, `饮水记录`, and `每日健康`; backup format version 4; app version 1.5.0.

- [ ] **Step 1: Add backup column tests**

```kotlin
@Test fun `tracking sheet headers remain stable`() {
    assertEquals(listOf("日期", "时长分钟", "记录时间"), LEARNING_HEADERS)
    assertEquals(listOf("日期", "饮水毫升", "记录时间"), WATER_HEADERS)
    assertEquals(listOf("日期", "起床分钟", "睡觉分钟", "手动步数", "同步步数", "同步时间"), WELLNESS_HEADERS)
}
```

- [ ] **Step 2: Run the backup test and verify it fails**

Run: `gradle testDebugUnitTest --tests cn.local.dailywork.backup.DailyTrackingBackupTest`

Expected: FAIL because the header constants do not exist.

- [ ] **Step 3: Extend export and import**

Export the three sheets using the exact headers above. Import them by name with fallback indexes after existing sheets, normalize the date column, reject non-positive learning/water values, and upsert `DailyWellness` by date. If all three sheets are absent, return empty tracking counts and continue importing the old backup normally. Extend `BackupResult` and `ImportResult` with `learning`, `water`, and `wellness` counts and include them in the status copy.

- [ ] **Step 4: Bump version and document features**

Set `versionCode = 13`, `versionName = "1.5.0"`, update README features/build output, and describe Health Connect permission plus manual fallback.

- [ ] **Step 5: Run the complete verification suite**

Run: `gradle testDebugUnitTest assembleDebug`

Expected: BUILD SUCCESSFUL and all unit tests pass. If an Android test device is available, also run `gradle connectedDebugAndroidTest` and expect all migration/database tests to pass.

- [ ] **Step 6: Commit and push**

```bash
git add app/src/main/java/cn/local/dailywork/backup/ExcelBackupManager.kt app/src/test/java/cn/local/dailywork/backup/DailyTrackingBackupTest.kt app/build.gradle.kts README.md
git commit -m "feat: back up daily health records"
git push -u origin main
```
