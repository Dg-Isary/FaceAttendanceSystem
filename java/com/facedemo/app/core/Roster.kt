package com.facedemo.app.core

import android.util.Log
import java.io.File

/**
 * 名单 (班级 + 学生)。对应 Python 版 facedemo/roster.py, CSV 列完全一致:
 *
 *     班级,姓名,学号,备注
 *     一班,张三,2026001,
 *
 * 约定:
 *   * 姓名唯一 (照片文件名就是姓名)
 *   * 班级可以不写, 程序会把照片目录的子目录名当作班级
 *   * 名单里没有但有人脸库照片的人会自动补进名单 (班级记“未分班”)
 *   * 名单里有、没有照片的人照样出现在到场看板 (状态未到, 可手动补签)
 */
data class Student(
    val name: String,
    var klass: String = Roster.UNASSIGNED,
    var studentId: String = "",
    var note: String = ""
) {
    fun toRow(): List<String> = listOf(klass, name, studentId, note)
}

object RosterParser {

    val HEADER = listOf("班级", "姓名", "学号", "备注")
    const val UNASSIGNED = "未分班"

    private val CLASS_PATTERN = Regex("^第?[一二两三四五六七八九十\\d]{1,3}(班|年级|组|队)$")
    private val CLASS_SUFFIX = Regex("(班|年级|级|组|队)$")
    private val ID_PATTERN = Regex("^[A-Za-z0-9_\\-]+$")
    private val SEQ_PATTERN = Regex("^\\d{1,4}[.、)）]?$")
    private val SPLIT_PATTERN = Regex("[\\t,，;；、]+|\\s{2,}")

    private val HEADER_WORDS = listOf("班级", "姓名", "名字", "学生", "学号", "序号", "备注", "性别",
        "name", "class", "id", "no", "note", "student")

    /** 表头列名 -> 内部字段。顺序有意义: 先匹配“学号”, 免得“学生学号”被判成姓名列。 */
    private val COLUMN_ALIASES: List<Pair<String, List<String>>> = listOf(
        "学号" to listOf("学号", "学籍号", "考号", "考生号", "编号", "id", "no", "number"),
        "班级" to listOf("班级", "所属班级", "行政班", "所在班", "班", "年级", "class", "grade"),
        "姓名" to listOf("姓名", "名字", "学生姓名", "学生", "name", "student"),
        "备注" to listOf("备注", "说明", "note", "remark", "memo")
    )

    private val CHINESE_DIGITS = mapOf(
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )

    /**
     * 班级排序键: 阿拉伯数字 > 中文数字 > 其它, 未分班永远最后。
     * (Kotlin 的 Triple 不实现 Comparable, 所以自定义一个可比较的键)
     */
    data class ClassKey(val group: Int, val number: Int, val label: String) : Comparable<ClassKey> {
        override fun compareTo(other: ClassKey): Int {
            if (group != other.group) return group - other.group
            if (number != other.number) return number - other.number
            return label.compareTo(other.label)
        }
    }

    fun classSortKey(klass: String): ClassKey {
        val text = klass.trim()
        if (text.isEmpty() || text == UNASSIGNED) return ClassKey(3, 0, "")
        val match = Regex("(\\d+)").find(text)
        if (match != null) return ClassKey(0, match.groupValues[1].toInt(), text)
        val value = chineseToInt(text)
        if (value != null) return ClassKey(1, value, text)
        return ClassKey(2, 0, text)
    }

    fun chineseToInt(text: String): Int? {
        val match = Regex("^第?([一二两三四五六七八九十]+)(班|年级|组)?$").find(text.trim()) ?: return null
        val digits = match.groupValues[1]
        if (digits == "十") return 10
        if (digits.startsWith("十")) return 10 + (CHINESE_DIGITS[digits.getOrNull(1)] ?: 0)
        if (digits.endsWith("十")) return (CHINESE_DIGITS[digits[0]] ?: 0) * 10
        val pos = digits.indexOf('十')
        if (pos > 0) {
            val head = digits.substring(0, pos)
            val tail = digits.substring(pos + 1)
            val h = if (head.isEmpty()) 0 else (CHINESE_DIGITS[head[0]] ?: 0)
            val t = if (tail.isEmpty()) 0 else (CHINESE_DIGITS[tail[0]] ?: 0)
            return h * 10 + t
        }
        return CHINESE_DIGITS[digits[0]]
    }

    /** 像不像学号: 纯 ASCII 字母数字/横线下划线, 且含数字, 长度 2~20。 */
    fun looksLikeId(text: String): Boolean {
        val value = text.trim()
        if (value.length < 2 || value.length > 20) return false
        return ID_PATTERN.matches(value) && value.any { it.isDigit() }
    }

    /**
     * 像不像班级名。故意放宽: 一班 / 3班 / 三年级 / 高一(3)班 / 计算机1班 / 2026级1班
     * 都要认出来, 否则整列会错位 (班级被当成姓名)。
     */
    fun looksLikeClass(text: String, known: Collection<String> = emptyList()): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        if (known.contains(value)) return true
        if (CLASS_PATTERN.matches(value)) return true
        return CLASS_SUFFIX.containsMatchIn(value) && value.length <= 16
    }

    /** 把表头单元格映射成内部字段名 (班级/姓名/学号/备注)。 */
    fun columnKey(field: String): String? {
        val text = field.trim().lowercase().replace(" ", "").replace("　", "")
        if (text.isEmpty()) return null
        for ((key, aliases) in COLUMN_ALIASES) {
            for (alias in aliases) if (text.contains(alias)) return key
        }
        return null
    }

    fun isHeader(parts: List<String>): Boolean {
        val joined = parts.joinToString("").lowercase().trim()
        if (joined.isEmpty()) return false
        if (parts.size == 1) return columnKey(parts[0]) == "姓名"
        var hits = 0
        for (word in HEADER_WORDS) {
            if (joined.contains(word)) hits++
        }
        return hits >= 2 || (parts.size >= 3 && joined.contains("姓名"))
    }

    fun splitFields(line: String): List<String> {
        val text = line.trim().trim('\uFEFF')
        if (text.isEmpty()) return emptyList()
        var parts = SPLIT_PATTERN.split(text).map { it.trim().trim('"').trim('\'').trim() }
            .filter { it.isNotEmpty() }
        if (parts.size == 1) {
            parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        return parts
    }

    /** 在前几行里找表头, 返回 (行号, {字段: 列号})。 */
    fun findHeader(lines: List<String>): Pair<Int, Map<String, Int>>? {
        for (index in 0 until minOf(8, lines.size)) {
            val parts = splitFields(lines[index])
            if (parts.isEmpty()) continue
            val mapping = HashMap<String, Int>()
            for ((column, field) in parts.withIndex()) {
                val key = columnKey(field) ?: continue
                if (!mapping.containsKey(key)) mapping[key] = column
            }
            if (mapping.containsKey("姓名")) return index to mapping
        }
        return null
    }

    /**
     * 把名单文本解析成 [{name, studentId, klass, note}]。
     * 有表头就按表头映射列, 没有表头才按位置猜。
     */
    fun parse(text: String, defaultClass: String = "", knownClasses: Collection<String> = emptyList()): List<Map<String, String>> {
        val lines = text.split("\n").map { it.trimEnd('\r') }
        val header = findHeader(lines)
        if (header != null) {
            val (index, mapping) = header
            val rows = ArrayList<Map<String, String>>()
            for (raw in lines.drop(index + 1)) {
                val parts = splitFields(raw)
                if (parts.isEmpty() || isHeader(parts)) continue
                fun pick(key: String): String {
                    val column = mapping[key] ?: return ""
                    if (column >= parts.size) return ""
                    return parts[column].trim()
                }
                val name = pick("姓名")
                if (name.isEmpty()) continue
                rows.add(mapOf(
                    "name" to name,
                    "studentId" to pick("学号"),
                    "klass" to pick("班级").ifEmpty { defaultClass },
                    "note" to pick("备注")
                ))
            }
            if (rows.isNotEmpty()) return rows
        }

        val rows = ArrayList<Map<String, String>>()
        for (raw in lines) {
            var parts = splitFields(raw)
            if (parts.isEmpty() || isHeader(parts)) continue
            if (parts.size >= 2 && SEQ_PATTERN.matches(parts[0])) parts = parts.drop(1)
            if (parts.isEmpty()) continue
            var klass = ""
            // 第一列算不算“班级/备注”标签?
            //   1) 长得像班名 (一班 / 高一(3)班 / …) 或已经在用过的班级里 —— 认;
            //   2) 一行有三列以上、且第二列不是学号 —— 那第一列基本就是标签。
            //      因为“姓名,学号,备注”这种写法第二列必定是学号, 不会误判。
            // 这样老师的自由文字 (科任老师、代课、教研组…) 也能原样当标签/备注用。
            val firstIsLabel = parts.size >= 3 && !looksLikeId(parts[0]) && !looksLikeId(parts[1])
            if (parts.size >= 2 && (looksLikeClass(parts[0], knownClasses) || firstIsLabel)) {
                klass = parts[0]
                parts = parts.drop(1)
            }
            // 学号在前、姓名在后? (2026001,张三)
            if (parts.size >= 2 && looksLikeId(parts[0]) && !looksLikeId(parts[1])) {
                parts = listOf(parts[1], parts[0]) + parts.drop(2)
            }
            val name = parts[0]
            var studentId = ""
            val notes = ArrayList<String>()
            for (field in parts.drop(1)) {
                if (looksLikeClass(field, knownClasses) && klass.isEmpty()) klass = field
                else if (looksLikeId(field) && studentId.isEmpty()) studentId = field
                else notes.add(field)          // 剩下的都留作备注, 不丢内容
            }
            rows.add(mapOf(
                "name" to name,
                "studentId" to studentId,
                "klass" to klass.ifEmpty { defaultClass },
                "note" to notes.joinToString(" ")
            ))
        }
        return rows
    }
}

class Roster(private val cfg: Config, private val log: (String) -> Unit = {}) {

    companion object {
        const val UNASSIGNED = RosterParser.UNASSIGNED
        private const val TAG = "Roster"
    }

    val path: File = cfg.rosterFile
    val students = LinkedHashMap<String, Student>()
    private val order = ArrayList<String>()
    var warnings: List<String> = emptyList()
        private set
    var encoding: String = "utf-8"
        private set

    /**
     * 手动删掉、并且**不希望再被自动入册**的人。
     *
     * 程序有个便利逻辑：人脸库里有照片、名单里没有的人会自动补进名单。
     * 这样一来“从名单删除”就会显得毫无作用 —— 刚删掉，重载人脸库又自动加回来了。
     * 所以把删掉的名字记在这个小文件里，自动入册时跳过；只要显式再加一次
     * （导入名单 / 手动加人 / 重新录入）就自动解除。
     */
    private val removedFile: File = File(cfg.root, "roster_removed.txt")
    private val removed = LinkedHashSet<String>()

    init {
        load()
    }

    // ------------------------------------------------------------------
    fun load(): Int {
        students.clear()
        order.clear()
        loadRemoved()
        val problems = ArrayList<String>()
        if (!path.exists()) {
            log("[名单] 还没有名单文件, 稍后会自动生成: ${path.name}")
            warnings = problems
            return 0
        }
        try {
            val decoded = TextCodec.readSmart(path)
            encoding = decoded.encoding
            if (encoding != "utf-8" && encoding != "utf-8-sig") {
                log("[名单] 文件编码是 $encoding (Excel 另存为 CSV 常见), 已自动识别")
            }
            val (header, rows) = TextCodec.readCsv(path)
            val mapping = HashMap<String, Int>()
            for ((column, field) in header.withIndex()) {
                val key = RosterParser.columnKey(field) ?: continue
                if (!mapping.containsKey(key)) mapping[key] = column
            }
            if (!mapping.containsKey("姓名")) mapping["姓名"] = 0
            for (row in rows) {
                fun pick(key: String): String {
                    val column = mapping[key] ?: return ""
                    return row.getOrNull(column)?.trim() ?: ""
                }
                val name = pick("姓名")
                if (name.isEmpty()) continue
                if (students.containsKey(name)) {
                    problems.add("名单里 $name 重复, 只保留第一条")
                    continue
                }
                students[name] = Student(
                    name = name,
                    klass = pick("班级").ifEmpty { UNASSIGNED },
                    studentId = pick("学号"),
                    note = pick("备注")
                )
                order.add(name)
            }
        } catch (e: Throwable) {
            problems.add("读取名单失败: ${e.message}")
        }
        warnings = problems
        log("[名单] ${path.name} -> ${students.size} 人 / ${classes().size} 个班")
        for (line in problems) log("[名单] ! $line")
        return students.size
    }

    fun save(): Boolean {
        return try {
            val rows = sortedNames().map { students[it]!!.toRow() }
            TextCodec.writeCsv(path, RosterParser.HEADER, rows)
            true
        } catch (e: Throwable) {
            log("[名单] 写入失败: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // 已删除名单（防止被自动重新入册）
    // ------------------------------------------------------------------
    private fun loadRemoved() {
        removed.clear()
        if (!removedFile.exists()) return
        try {
            TextCodec.readSmart(removedFile).text.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { removed.add(it) }
        } catch (e: Throwable) {
            log("[名单] 读取删除记录失败: ${e.message}")
        }
    }

    private fun saveRemoved() {
        try {
            removedFile.writeText(removed.joinToString("\n"), Charsets.UTF_8)
        } catch (e: Throwable) {
            log("[名单] 写入删除记录失败: ${e.message}")
        }
    }

    /** 只记“别再自动入册”，不动名单（用于人脸库里有人、名单里本来就没有的情况）。 */
    fun blockAutoEnroll(name: String) {
        if (name.isBlank()) return
        if (removed.add(name)) {
            saveRemoved()
            log("[名单] $name 已加入“不自动入册”")
        }
    }

    /** 被手动删除、当前不会自动入册的人。 */
    fun removedNames(): List<String> = removed.filter { !students.containsKey(it) }.sorted()

    /** 清掉“已删除”记录，让有人脸照片的人重新自动入册。 */
    fun clearRemoved(save: Boolean = true): Int {
        val count = removed.size
        removed.clear()
        if (save) saveRemoved()
        if (count > 0) log("[名单] 已恢复自动入册 ($count 人)")
        return count
    }

    /** 按班级 + 原始顺序输出 (同一个班内保持 CSV 里的顺序)。 */
    private fun sortedNames(): List<String> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (name in order) {
            val student = students[name] ?: continue
            groups.getOrPut(student.klass) { ArrayList() }.add(name)
        }
        val result = ArrayList<String>()
        for (klass in groups.keys.sortedBy { RosterParser.classSortKey(it) }) {
            result.addAll(groups[klass]!!)
        }
        return result
    }

    // ------------------------------------------------------------------
    fun sortedStudents(klass: String = ""): List<Student> {
        var items = students.values.toList()
        if (klass.isNotEmpty()) items = items.filter { it.klass == klass }
        return items.sortedWith(compareBy(
            { RosterParser.classSortKey(it.klass) },
            { it.studentId.ifEmpty { "~" } },
            { it.name }
        ))
    }

    fun classes(): List<String> = students.values.map { it.klass }.distinct()
        .sortedBy { RosterParser.classSortKey(it) }

    fun classOf(name: String): String = students[name]?.klass ?: ""

    fun get(name: String): Student? = students[name]

    fun exists(name: String): Boolean = students.containsKey(name)

    // ------------------------------------------------------------------
    fun add(name: String, klass: String = UNASSIGNED, studentId: String = "", note: String = "", save: Boolean = true): Student? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val existing = students[clean]
        val student = if (existing == null) {
            val created = Student(
                name = clean,
                klass = klass.trim().ifEmpty { UNASSIGNED },
                studentId = studentId.trim(),
                note = note.trim()
            )
            students[clean] = created
            order.add(clean)
            created
        } else {
            if (klass.isNotBlank()) existing.klass = klass.trim().ifEmpty { UNASSIGNED }
            if (studentId.isNotBlank()) existing.studentId = studentId.trim()
            if (note.isNotBlank()) existing.note = note.trim()
            existing
        }
        if (save) save()
        // 显式加人 = 解除“别再自动入册”
        if (removed.remove(clean)) saveRemoved()
        log("[名单] ${student.name} -> ${student.klass}" + if (student.studentId.isNotEmpty()) " (学号 ${student.studentId})" else "")
        return student
    }

    fun remove(name: String, save: Boolean = true): Boolean {
        if (!students.containsKey(name)) return false
        students.remove(name)
        order.remove(name)
        // 记一笔“别再自动入册”，否则重载人脸库时他又会被自动加回来
        removed.add(name)
        if (save) {
            save()
            saveRemoved()
        }
        log("[名单] 已从名单移除 $name (以后不会被自动入册; 重新导入/录入他会自动恢复)")
        return true
    }

    fun setClass(name: String, klass: String, save: Boolean = true): Boolean {
        val student = students[name] ?: return false
        student.klass = klass.trim().ifEmpty { UNASSIGNED }
        if (save) save()
        return true
    }

    fun setStudentId(name: String, studentId: String, save: Boolean = true): Boolean {
        val student = students[name] ?: return false
        student.studentId = studentId.trim()
        if (save) save()
        return true
    }

    /** 把人脸库里出现、但名单里没有的人补进名单。 */
    fun syncFromGallery(names: List<String>, classOf: ((String) -> String)? = null): Int {
        var added = 0
        for (name in names) {
            if (students.containsKey(name)) continue
            if (removed.contains(name)) continue     // 手动删过的人不自动入册
            val klass = classOf?.invoke(name)?.trim().orEmpty()
            students[name] = Student(name = name, klass = klass.ifEmpty { UNASSIGNED })
            order.add(name)
            added++
            log("[名单] 新人入册: $name (${klass.ifEmpty { UNASSIGNED }})")
        }
        if (added > 0) {
            save()
            log("[名单] 已把 $added 个人补进 ${path.name}")
        }
        return added
    }

    // ------------------------------------------------------------------
    /** 导入名单文本 (粘贴 / Excel 复制 / CSV 文件)。返回统计信息。 */
    fun importText(text: String, klass: String = "", save: Boolean = true): Map<String, Any> {
        val rows = RosterParser.parse(text, defaultClass = klass, knownClasses = classes())
        var added = 0
        var updated = 0
        var skipped = 0
        for (row in rows) {
            val name = row["name"].orEmpty()
            if (name.isEmpty()) {
                skipped++
                continue
            }
            val target = row["klass"].orEmpty().ifEmpty { klass.ifEmpty { UNASSIGNED } }
            // 名单里又出现了他 = 解除“别再自动入册”
            if (removed.remove(name)) saveRemoved()
            val student = students[name]
            if (student == null) {
                students[name] = Student(
                    name = name,
                    klass = target,
                    studentId = row["studentId"].orEmpty(),
                    note = row["note"].orEmpty()
                )
                order.add(name)
                added++
            } else {
                var changed = false
                if (target.isNotEmpty() && student.klass != target) {
                    student.klass = target; changed = true
                }
                val sid = row["studentId"].orEmpty()
                if (sid.isNotEmpty() && student.studentId != sid) {
                    student.studentId = sid; changed = true
                }
                if (changed) updated++
            }
        }
        if (save && (added > 0 || updated > 0)) save()
        log("[名单] 导入 ${rows.size} 行 -> 新增 $added 人, 更新 $updated 人" +
            if (skipped > 0) ", 跳过 $skipped 行" else "")
        return mapOf(
            "added" to added, "updated" to updated, "skipped" to skipped,
            "total" to rows.size, "rows" to rows, "classes" to classes()
        )
    }

    /** 名单概况。withPhotos = 有照片的人名, 用来标出“待录入”。 */
    fun summary(withPhotos: List<String>? = null): Map<String, Any> {
        val have = withPhotos?.toSet()
        val groups = classes().map { klass ->
            val members = sortedStudents(klass)
            mapOf(
                "klass" to klass,
                "count" to members.size,
                "students" to members.map { studentMap(it) }
            )
        }
        val pending = if (withPhotos == null) emptyList() else
            sortedStudents().filter { !have!!.contains(it.name) }.map { studentMap(it) }
        return mapOf(
            "path" to path.absolutePath,
            "encoding" to encoding,
            "total" to students.size,
            "classes" to classes(),
            "groups" to groups,
            "warnings" to warnings,
            "pending" to pending,
            "enrolled" to if (withPhotos == null) 0 else students.size - pending.size
        )
    }

    fun studentMap(student: Student): Map<String, String> = mapOf(
        "name" to student.name, "klass" to student.klass,
        "studentId" to student.studentId, "note" to student.note
    )

    fun classCounts(): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (student in students.values) counts[student.klass] = (counts[student.klass] ?: 0) + 1
        return counts
    }

    fun size(): Int = students.size

    fun debug(tag: String = TAG): Int {
        Log.i(tag, "名单 ${path.name}: ${students.size} 人, ${classes().size} 个班")
        return students.size
    }
}
