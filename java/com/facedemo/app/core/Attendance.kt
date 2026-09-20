package com.facedemo.app.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** 一条打卡记录。字段与 Python 版 CSV 完全一致。 */
data class AttendanceRecord(
    val id: Int,
    val time: String,
    val klass: String,
    val name: String,
    val kind: String,
    val similarity: Float?,
    val threshold: Float?,
    val source: String,
    val snapshot: String,
    val note: String
) {
    val clock: String get() = if (time.length >= 19) time.substring(11, 19) else time
}

data class AttendanceResult(
    val ok: Boolean,
    val reason: String,
    val record: AttendanceRecord?,
    val message: String
)

/**
 * 一对「签到-签退」（v1.0.2 新增）。
 *
 * 一天可以打多次卡，所以一个人的记录会被配成若干段：
 *   * 正常一段：签到 09:00:00 → 签退 09:05:00，`text` = `09:00:00-09:05:00`
 *   * 只签到没签退：`text` = `09:00:00-`（后面留空，一眼能看出"还没签退"）
 *   * 只有签退没签到（手工补签退）：`text` = `-09:05:00`
 */
data class AttendanceSession(
    val checkin: AttendanceRecord?,
    val checkout: AttendanceRecord?
) {
    /** 界面上显示的那一串：签到时间-签退时间 */
    val text: String get() = (checkin?.clock ?: "") + "-" + (checkout?.clock ?: "")
    val isOpen: Boolean get() = checkin != null && checkout == null
    val startClock: String get() = checkin?.clock ?: checkout?.clock ?: ""
}

/**
 * 打卡 (签到 / 签退)。对应 Python 版 facedemo/attendance.py。
 *
 * 规则（v1.0.3 起）:
 *   * 只有“识别通过 + 达到阈值”的人才会打卡 (连续命中判定在 Speaker.Announcer 里完成)
 *   * **一天可以打任意多次卡**（不再有「一天一次」的限制）
 *   * 唯一的速度限制是 cfg.attendanceIntervalHours **小时**，而且**只在同一类型连着来时才生效**：
 *       - 签到 → 签退 → 签到：后一个不受限（签退把这一段闭合了，可以马上再签到）
 *       - 签到 → 签到（中间没签退）：要隔这么久才记第二条，防"站那儿不动被连续记"
 *       - 签退 → 签退 同理
 *   * 类型可运行时切换: 签到 / 签退（不新增按钮，就在签到页顶部切一下）
 *
 * 存储: logs/attendance_YYYYMMDD.csv, 追加写入, 重启不丢也不重复计
 */
class AttendanceStore(private val cfg: Config, private val log: (String) -> Unit = {}) {

    companion object {
        private const val TAG = "Attendance"
        val KINDS = listOf("签到", "签退")
        val HEADER = listOf("时间", "班级", "姓名", "类型", "相似度", "阈值", "来源", "快照", "备注")
    }

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.CHINA)

    var enabled: Boolean = true
    var kind: String = if (cfg.attendanceKind in KINDS) cfg.attendanceKind else "签到"
        private set

    val records = ArrayList<AttendanceRecord>()
    private var counter = 0
    private var fieldNames: List<String> = HEADER
    var todayKey: String = dayFormat.format(Date())
        private set
    var csvFile: File = pathFor(todayKey)
        private set

    init {
        loadToday()
    }

    private fun pathFor(day: String) = File(cfg.logsDir, "attendance_$day.csv")

    /** 把今天的记录读进内存, 保证重启后不会重复打卡。 */
    private fun loadToday() {
        if (!csvFile.exists()) return
        try {
            val (header, rows) = TextCodec.readCsv(csvFile)
            if (header.isNotEmpty()) fieldNames = header
            val index = HashMap<String, Int>()
            for ((column, field) in header.withIndex()) index[field.trim()] = column
            fun pick(row: List<String>, key: String): String {
                val column = index[key] ?: return ""
                return row.getOrNull(column)?.trim() ?: ""
            }
            for (row in rows) {
                val name = pick(row, "姓名")
                if (name.isEmpty()) continue
                counter++
                val record = AttendanceRecord(
                    id = counter,
                    time = pick(row, "时间"),
                    klass = pick(row, "班级"),
                    name = name,
                    kind = pick(row, "类型"),
                    similarity = pick(row, "相似度").toFloatOrNull()?.let { round4(it) },
                    threshold = pick(row, "阈值").toFloatOrNull()?.let { round4(it) },
                    source = pick(row, "来源"),
                    snapshot = pick(row, "快照"),
                    note = pick(row, "备注")
                )
                records.add(record)
            }
        } catch (e: Throwable) {
            log("[打卡] 读取今日记录失败: ${e.message}")
        }
        if (records.isNotEmpty()) {
            log("[打卡] 今日已有 ${records.size} 条记录 (${csvFile.name})")
        }
    }

    private fun parseTime(text: String): Long = try {
        stampFormat.parse(text)?.time ?: 0L
    } catch (e: Throwable) {
        0L
    }

    private fun round4(value: Float): Float = (value * 10000f).roundToLong() / 10000f

    /** 把秒数说成人话：90 → "1 分钟"，5400 → "1 小时 30 分钟"（打卡间隔提示用） */
    private fun humanSpan(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分钟"
            hours > 0 -> "$hours 小时"
            minutes > 0 -> "$minutes 分钟"
            else -> "$total 秒"
        }
    }

    /** 小时数说成人话：1.0 → "1 小时"，0.5 → "0.5 小时" */
    private fun humanHours(hours: Double): String =
        if (hours == hours.toLong().toDouble()) "${hours.toLong()} 小时" else "$hours 小时"

    /**
     * 某个人今天**最后一条**有效记录（跳过「撤销」的）。
     *
     * 打卡间隔判定就用它：记录是按时间顺序追加的，所以最后一条就是"刚刚发生过什么"。
     * 从内存记录里取而不是另外维护一张表，跨天/重启都不会错位。
     */
    private fun lastRecordOf(name: String): AttendanceRecord? =
        records.lastOrNull { it.name == name && !it.note.startsWith("撤销") }

    // ------------------------------------------------------------------
    // 签到-签退配对（v1.0.2）：一天多次打卡时，把记录按时间顺序配成若干段
    // ------------------------------------------------------------------

    /**
     * 把一串记录配成「签到-签退」段。规则：
     *   * 遇到「签到」：若上一段还开着（没签退），先把上一段落成"只签到"一段，再开新段
     *   * 遇到「签退」：收掉最近一个开着的那段；没有开着的段就单独成段（只有签退时间）
     *   * 备注以「撤销」开头的记录不参与配对
     */
    fun pair(records: List<AttendanceRecord>): List<AttendanceSession> {
        val out = ArrayList<AttendanceSession>()
        var open: AttendanceRecord? = null
        for (item in records) {
            if (item.note.startsWith("撤销")) continue
            when (item.kind) {
                "签退" -> {
                    out.add(AttendanceSession(open, item))
                    open = null
                }
                else -> {   // 签到（以及老日志里缺类型的记录，一律当签到）
                    if (open != null) out.add(AttendanceSession(open, null))
                    open = item
                }
            }
        }
        if (open != null) out.add(AttendanceSession(open, null))
        return out
    }

    /** 某个人今天的打卡段（按时间顺序）。 */
    fun sessions(name: String): List<AttendanceSession> {
        rollDay()
        return pair(records.filter { it.name == name })
    }

    /** 今天某个人的原始记录（时间顺序）—— 签到页的「管理」对话框用它列当天记录。 */
    fun recordsOf(name: String): List<AttendanceRecord> {
        rollDay()
        return records.filter { it.name == name }
    }

    /** 这个人现在有没有"开着"的一段（签到之后还没签退）—— 签退的前置条件。 */
    fun hasOpenSession(name: String): Boolean = sessions(name).lastOrNull()?.isOpen == true

    /** 今天这个人有没有过这类记录（用来把"不能签退"的原因说清楚）。 */
    private fun hasTodayKind(name: String, kindValue: String): Boolean =
        records.any { it.name == name && it.kind == kindValue && !it.note.startsWith("撤销") }

    // v1.0.6 删除：sessionText()（看板自己用 sessions() 拼 span，没人调它）

    /** 所有打卡日（yyyyMMdd），按日期升序。 */
    fun attendanceDays(): List<Pair<String, File>> {
        val files = cfg.logsDir.listFiles { f ->
            f.isFile && f.name.startsWith("attendance_") && f.name.endsWith(".csv")
        } ?: return emptyList()
        return files
            .map { it.name.removePrefix("attendance_").removeSuffix(".csv") to it }
            .sortedBy { it.first }
    }

    /** 读任意一天的打卡 CSV 成记录列表（表头顺序按文件里的列名找，老日志缺列也不怕）。 */
    fun readDay(file: File): List<AttendanceRecord> {
        val out = ArrayList<AttendanceRecord>()
        if (!file.isFile) return out
        try {
            val (header, rows) = TextCodec.readCsv(file)
            val index = HashMap<String, Int>()
            for ((column, field) in header.withIndex()) index[field.trim()] = column
            fun pick(row: List<String>, name: String): String {
                val column = index[name] ?: return ""
                return row.getOrNull(column)?.trim() ?: ""
            }
            var seq = 0
            for (row in rows) {
                val name = pick(row, "姓名")
                if (name.isEmpty()) continue
                out.add(
                    AttendanceRecord(
                        id = ++seq,
                        time = pick(row, "时间"),
                        klass = pick(row, "班级"),
                        name = name,
                        kind = pick(row, "类型").ifEmpty { "签到" },
                        similarity = pick(row, "相似度").toFloatOrNull()?.let { round4(it) },
                        threshold = pick(row, "阈值").toFloatOrNull()?.let { round4(it) },
                        source = pick(row, "来源"),
                        snapshot = pick(row, "快照"),
                        note = pick(row, "备注")
                    )
                )
            }
        } catch (e: Throwable) {
            log("[打卡] 读取 ${file.name} 失败: ${e.message}")
        }
        return out
    }

    private fun prettyDay(day: String): String =
        if (day.length == 8) "${day.substring(0, 4)}-${day.substring(4, 6)}-${day.substring(6, 8)}" else day

    private fun num(value: Float?): String =
        if (value == null) "" else String.format(Locale.US, "%.4f", value)

    /**
     * 导出**所有日期**的原始打卡记录（v1.0.2 新增）。
     *
     * 每天一个 CSV 文件，这里按日期顺序合并成一张表，最前面补一列「日期」，
     * Excel 打开就能直接按日期/姓名筛选。
     */
    fun allRecordsCsv(): String {
        val sb = StringBuilder("\uFEFF")
        sb.append(TextCodec.joinCsv(listOf("日期") + HEADER)).append("\r\n")
        var rows = 0
        var days = 0
        for ((day, file) in attendanceDays()) {
            val list = readDay(file)
            if (list.isEmpty()) continue
            days++
            for (r in list) {
                sb.append(
                    TextCodec.joinCsv(
                        listOf(
                            prettyDay(day), r.time, r.klass, r.name, r.kind,
                            num(r.similarity), num(r.threshold), r.source, r.snapshot, r.note
                        )
                    )
                ).append("\r\n")
                rows++
            }
        }
        log("[打卡] 导出全部记录: $days 天 / $rows 条")
        return sb.toString()
    }

    /**
     * 导出**所有日期**的「签到-签退」配对明细（v1.0.2 新增）。
     *
     * 一行 = 一个人某天的一段打卡：`09:00:00-09:05:00`、只签到就是 `09:00:00-`。
     * 一天打多次就有多行（「时段」列是第几段），比原始流水更好核对。
     */
    fun allSessionsCsv(): String {
        val sb = StringBuilder("\uFEFF")
        sb.append(TextCodec.joinCsv(listOf("日期", "班级", "姓名", "时段", "签到时间", "签退时间", "是否已签退", "签到相似度")))
            .append("\r\n")
        var rows = 0
        for ((day, file) in attendanceDays()) {
            val list = readDay(file)
            if (list.isEmpty()) continue
            // 一天里按人分组（保持出现顺序），每人再配对
            val byName = LinkedHashMap<String, MutableList<AttendanceRecord>>()
            for (r in list) byName.getOrPut(r.name) { ArrayList() }.add(r)
            for ((name, mine) in byName) {
                val klass = mine.firstOrNull { it.klass.isNotEmpty() }?.klass ?: ""
                val segs = pair(mine)
                for ((i, seg) in segs.withIndex()) {
                    sb.append(
                        TextCodec.joinCsv(
                            listOf(
                                prettyDay(day), klass, name, (i + 1).toString(),
                                seg.checkin?.clock ?: "", seg.checkout?.clock ?: "",
                                if (seg.checkout != null) "已签退" else "未签退",
                                num(seg.checkin?.similarity)
                            )
                        )
                    ).append("\r\n")
                    rows++
                }
            }
        }
        log("[打卡] 导出配对明细: $rows 段")
        return sb.toString()
    }

    private fun rollDay() {
        val day = dayFormat.format(Date())
        if (day != todayKey) {
            log("[打卡] 跨天, 切换到 $day 的记录文件")
            todayKey = day
            csvFile = pathFor(day)
            records.clear()
            counter = 0
            fieldNames = HEADER
        }
    }

    // ------------------------------------------------------------------
    fun setKind(value: String): String {
        if (value in KINDS) {
            kind = value
            log("[打卡] 模式切换为: $value")
        }
        return kind
    }

    // v1.0.6 删除：todayCount（没人读）、toggleKind()（界面用的是 setKind("签到"/"签退")）、
    //              hasToday()（v1.0.3 拿掉「一天一次」之后就没人调了；看板自己建映射表）

    // ------------------------------------------------------------------
    /** 记一条打卡。 */
    @Synchronized
    fun record(
        name: String,
        similarity: Float?,
        threshold: Float?,
        source: String = "",
        snapshot: String = "",
        note: String = "",
        kindValue: String? = null,
        force: Boolean = false,
        klass: String = ""
    ): AttendanceResult {
        rollDay()
        val target = kindValue ?: kind
        if (!enabled) return AttendanceResult(false, "disabled", null, "打卡功能已关闭")
        if (name.isBlank()) return AttendanceResult(false, "noname", null, "没有识别到人名")

        // v1.0.7 规则：没签到不许签退 —— 签退必须收掉"开着的那一段签到"。
        // 这条与 force 无关（手工补签也要守），否则会出现"只有签退、没有签到"的孤立记录：
        // 看板的"已到"是按有没有签到算的，补签出一条签退后行上什么都不变，
        // 用户看到的就是"提示补签成功、但没有任何记录"。
        if (target == "签退" && !hasOpenSession(name)) {
            return AttendanceResult(
                false, "no-checkin", null,
                if (hasTodayKind(name, "签退")) "$name 今天已经签退过了（要先签到才能再签退）"
                else "$name 还没有签到，不能签退"
            )
        }

        // 间隔限制（v1.0.4 起按小时算）：只在「同一类型连着来」时生效。
        // 签到→签退→签到 里最后的签到不受限（签退已经把这一段时间闭合了，可以马上再签到）；
        // 签到→签到（中间没签退）才需要等 cfg.attendanceIntervalHours 小时，防"站那儿不动被连续记"。
        val intervalHours = cfg.attendanceIntervalHours.toDouble()
        val intervalSeconds = intervalHours * 3600.0
        if (!force && intervalSeconds > 0) {
            val last = lastRecordOf(name)
            if (last != null && last.kind == target) {
                val elapsed = (System.currentTimeMillis() - parseTime(last.time)) / 1000.0
                if (elapsed < intervalSeconds) {
                    return AttendanceResult(
                        false, "cooldown", null,
                        "$name 距上次${target}只过了 ${humanSpan(elapsed)}，" +
                            "间隔设置 ${humanHours(intervalHours)}，还要等 ${humanSpan(intervalSeconds - elapsed)}"
                    )
                }
            }
        }

        counter++
        val created = AttendanceRecord(
            id = counter,
            time = stampFormat.format(Date()),
            klass = klass,
            name = name,
            kind = target,
            similarity = similarity?.let { round4(it) },
            threshold = threshold?.let { round4(it) },
            source = source,
            snapshot = snapshot,
            note = note
        )
        records.add(created)
        appendRow(created)
        log(
            "[打卡] ${created.clock}  " + (if (created.klass.isNotEmpty()) "[${created.klass}] " else "") +
                "$name  $target 成功  (相似度 " +
                (if (similarity != null) String.format(Locale.US, "%.3f", similarity) else "-") +
                ", 来源 ${source.ifEmpty { "-" }})"
        )
        return AttendanceResult(true, "ok", created, "$name $target 成功")
    }

    /** 追加一行; 列顺序跟随文件已有表头, 老日志 (没有班级列) 也能继续写。 */
    private fun appendRow(record: AttendanceRecord) {
        try {
            cfg.logsDir.mkdirs()
            val newFile = !csvFile.exists() || csvFile.length() == 0L
            if (newFile) fieldNames = HEADER
            val values = mapOf(
                "时间" to record.time,
                "班级" to record.klass,
                "姓名" to record.name,
                "类型" to record.kind,
                "相似度" to (record.similarity?.let { String.format(Locale.US, "%.4f", it) } ?: ""),
                "阈值" to (record.threshold?.let { String.format(Locale.US, "%.4f", it) } ?: ""),
                "来源" to record.source,
                "快照" to record.snapshot,
                "备注" to record.note
            )
            if (newFile) TextCodec.appendCsvLines(csvFile, HEADER, listOf(HEADER.map { values[it] ?: "" }))
            else TextCodec.appendCsvLine(csvFile, fieldNames, fieldNames.map { values[it] ?: "" })
        } catch (e: Throwable) {
            log("[打卡] 写入失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    fun recent(limit: Int = 50): List<AttendanceRecord> {
        rollDay()
        return records.takeLast(limit).reversed()
    }

    /**
     * v1.0.9：删掉今天的**一条**记录（签到页「管理」对话框里点某条记录 → 确认后调用）。
     *
     * 和 v1.0.6 删掉的 `cancel()` 不同：那个是"这个人的这类记录**全部**删掉"，
     * 现在这个是**按 id 精确删一条**（用户要的就是这个：误识别、点错补签的，删那一条就够）。
     * 删完把当天的 CSV 按内存里的记录整份重写（打卡文件是追加写的，删行只能重写）。
     */
    @Synchronized
    fun deleteRecord(id: Int): Pair<Boolean, String> {
        rollDay()
        val target = records.firstOrNull { it.id == id } ?: return false to "这条记录已经不在了"
        records.removeAll { it.id == id }
        val ok = rewriteToday()
        log(
            "[打卡] 删除记录: ${target.time} ${target.name} ${target.kind}" +
                (if (ok) "" else "（写回 CSV 失败！）")
        )
        return if (ok) true to "已删除 ${target.name} ${target.clock} 的${target.kind}记录"
        else false to "已从界面删除，但写回 CSV 失败（重启后会回来）"
    }

    /** 按当前内存记录重写今天的 CSV（删除单条记录后用）。 */
    private fun rewriteToday(): Boolean {
        rollDay()
        return try {
            val rows = records.map { item ->
                listOf(
                    item.time, item.klass, item.name, item.kind,
                    item.similarity?.let { String.format(Locale.US, "%.4f", it) } ?: "",
                    item.threshold?.let { String.format(Locale.US, "%.4f", it) } ?: "",
                    item.source, item.snapshot, item.note
                )
            }
            TextCodec.writeCsv(csvFile, HEADER, rows)
            fieldNames = HEADER
            true
        } catch (e: Throwable) {
            log("[打卡] 重写日志失败: ${e.message}")
            false
        }
    }

    /** 手动补签（v1.0.6 起只剩补签：撤销那条路径删掉了，理由见 ui/AttendancePage.kt 里的注释）。 */
    @Synchronized
    fun manual(name: String, klass: String = "", kindValue: String? = null): AttendanceResult =
        record(name, 1.0f, null, source = "手动补签", note = "手动补签",
            kindValue = kindValue ?: kind, force = true, klass = klass)

    // v1.0.6 删除：cancel(name, kind) + rewriteToday()。
    // 它们实现的是"把这个人的这类记录全部删掉"（filterNot 后整份重写 CSV），
    // 一天多次打卡时点一下撤销会把当天所有签到清空；而且界面按钮是按"有没有签到"显示的，
    // 跟当前模式对不上（签到完切签退还显示撤销）。用户明确说这个按钮不需要，所以整条路径移除。
    // 注意：下面读记录时仍然跳过备注以「撤销」开头的行 —— 那是为了兼容历史上已经写进 CSV 的老数据。

    /** 全员到场看板: 按班级列出每个人“已签到 / 未到”。 */
    fun board(roster: Roster?, galleryNames: List<String> = emptyList(), klass: String = ""): Map<String, Any> {
        rollDay()
        val checkin = HashMap<String, AttendanceRecord>()
        val checkout = HashMap<String, AttendanceRecord>()
        for (item in records) {
            if (item.note.startsWith("撤销")) continue
            if (item.kind == "签到" && !checkin.containsKey(item.name)) checkin[item.name] = item
            else if (item.kind == "签退") checkout[item.name] = item
        }

        data class Person(val name: String, val klass: String, val studentId: String, val note: String)

        val people = ArrayList<Person>()
        if (roster != null) {
            for (student in roster.sortedStudents()) {
                people.add(Person(student.name, student.klass, student.studentId, student.note))
            }
        }
        val known = people.map { it.name }.toMutableSet()
        for (name in galleryNames) {
            if (known.contains(name)) continue
            val cls = roster?.classOf(name).orEmpty()
            people.add(Person(name, cls.ifEmpty { Roster.UNASSIGNED }, "", "（不在名单里）"))
            known.add(name)
        }
        for (item in checkin.values + checkout.values) {
            if (known.contains(item.name)) continue
            people.add(Person(item.name, item.klass.ifEmpty { Roster.UNASSIGNED }, "", "（不在名单里）"))
            known.add(item.name)
        }

        val groups = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (person in people) {
            if (klass.isNotEmpty() && person.klass != klass) continue
            val sign = checkin[person.name]
            val leave = checkout[person.name]
            // v1.0.2：一天多次打卡 → 把这个人的记录配成若干段，界面/导出都用这段文字
            val segs = sessions(person.name)
            val members = groups.getOrPut(person.klass) { ArrayList() }
            members.add(
                mapOf(
                    "name" to person.name,
                    "studentId" to person.studentId,
                    "note" to person.note,
                    "present" to (sign != null),
                    "checkin" to (sign?.clock ?: ""),
                    "checkout" to (leave?.clock ?: ""),
                    "span" to segs.joinToString(" · ") { it.text },
                    "sessionCount" to segs.size,
                    "openSessions" to segs.count { it.isOpen },
                    "similarity" to sign?.similarity,
                    "manual" to (sign != null && sign.note.startsWith("手动"))
                )
            )
        }

        val outGroups = ArrayList<Map<String, Any?>>()
        for (name in groups.keys.sortedBy { RosterParser.classSortKey(it) }) {
            val members = groups[name]!!.sortedWith(compareBy(
                { (it["studentId"] as String).ifEmpty { "~" } },
                { it["name"] as String }
            ))
            val present = members.count { it["present"] == true }
            outGroups.add(
                mapOf(
                    "klass" to name,
                    "total" to members.size,
                    "present" to present,
                    "absent" to members.size - present,
                    "rate" to if (members.isEmpty()) 0.0 else present.toDouble() / members.size,
                    "students" to members
                )
            )
        }

        val total = outGroups.sumOf { it["total"] as Int }
        val present = outGroups.sumOf { it["present"] as Int }
        val absent = ArrayList<Map<String, String>>()
        var checkedOut = 0
        var sessions = 0
        for (group in outGroups) {
            @Suppress("UNCHECKED_CAST")
            val members = group["students"] as List<Map<String, Any?>>
            val klassName = group["klass"] as String
            for (member in members) {
                if (member["present"] != true) {
                    absent.add(mapOf("name" to (member["name"] as String), "klass" to klassName))
                }
                if ((member["checkout"] as String).isNotEmpty()) checkedOut++
                sessions += (member["sessionCount"] as? Int) ?: 0
            }
        }
        return mapOf(
            "ok" to true,
            "date" to todayKey,
            "kind" to kind,
            "klass" to klass,
            "classes" to outGroups.map { it["klass"] as String },
            "groups" to outGroups,
            "absent" to absent,
            "summary" to mapOf(
                "total" to total,
                "present" to present,
                "absent" to total - present,
                "rate" to if (total == 0) 0.0 else present.toDouble() / total,
                "checkedOut" to checkedOut,
                "records" to records.size,
                "sessions" to sessions
            )
        )
    }

    fun summary(): Map<String, Any> {
        rollDay()
        val classes = HashMap<String, Int>()
        for (item in records) {
            if (item.kind == "签到") {
                val cls = item.klass.ifEmpty { Roster.UNASSIGNED }
                classes[cls] = (classes[cls] ?: 0) + 1
            }
        }
        val last = records.lastOrNull()
        return mapOf(
            "date" to todayKey,
            "kind" to kind,
            "total" to records.size,
            "checkin" to records.count { it.kind == "签到" },
            "checkout" to records.count { it.kind == "签退" },
            "peopleSigned" to records.filter { it.kind == "签到" }.map { it.name }.distinct().sorted(),
            "peopleOut" to records.filter { it.kind == "签退" }.map { it.name }.distinct().sorted(),
            "byClass" to classes,
            "csv" to csvFile.absolutePath,
            "last" to (last?.let { "${it.name} ${it.clock}" } ?: "")
        )
    }

    fun missing(names: List<String>): List<String> {
        rollDay()
        val signed = records.filter { it.kind == "签到" }.map { it.name }.toSet()
        return names.filterNot { signed.contains(it) }
    }

    fun csvText(): String {
        rollDay()
        return if (!csvFile.exists()) HEADER.joinToString(",") + "\n"
        else TextCodec.readSmart(csvFile).text
    }

    // v1.0.6 删除：rewriteToday()（整份重写今天的 CSV）—— 它只被上面删掉的 cancel() 用。

    /** 到场点名表 CSV 文本 (可直接分享/导出)。 */
    fun boardCsv(roster: Roster?, galleryNames: List<String>, klass: String = ""): String {
        val board = board(roster, galleryNames, klass)
        val lines = ArrayList<List<String>>()
        lines.add(listOf("到场点名表 ${board["date"]} (类型 ${board["kind"]})"))
        lines.add(listOf("班级", "姓名", "学号", "状态", "签到时间", "签退时间", "打卡明细", "备注"))
        @Suppress("UNCHECKED_CAST")
        val groups = board["groups"] as List<Map<String, Any?>>
        for (group in groups) {
            @Suppress("UNCHECKED_CAST")
            val members = group["students"] as List<Map<String, Any?>>
            for (item in members) {
                lines.add(
                    listOf(
                        group["klass"] as String,
                        item["name"] as String,
                        item["studentId"] as String,
                        if (item["present"] == true) "已到" else "未到",
                        item["checkin"] as String,
                        item["checkout"] as String,
                        // 一天多次打卡时这里就是 "09:00:00-09:05:00 · 12:00:00-"
                        (item["span"] as? String).orEmpty(),
                        (if (item["manual"] == true) "手动补签" else "") + (item["note"] as String)
                    )
                )
            }
            lines.add(listOf("${group["klass"]} 小计", "${group["present"]}/${group["total"]}", "", "", "", "", "", ""))
        }
        @Suppress("UNCHECKED_CAST")
        val summary = board["summary"] as Map<String, Any>
        val rate = (summary["rate"] as Double) * 100
        val sessions = (summary["sessions"] as? Int) ?: 0
        lines.add(
            listOf(
                "合计", "${summary["present"]}/${summary["total"]}",
                String.format(Locale.US, "出席率 %.1f%%", rate),
                "打卡 $sessions 段", "", "", "", ""
            )
        )
        val sb = StringBuilder("\uFEFF")
        for (row in lines) sb.append(TextCodec.joinCsv(row)).append("\r\n")
        return sb.toString()
    }
}
