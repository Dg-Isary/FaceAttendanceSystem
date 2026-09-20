package com.facedemo.app.core

import java.io.File
import java.util.Locale

/**
 * 个人阈值（每人一个专属相似度阈值）。
 *
 * 需求：某个人的识别老是偏松/偏紧时，可以只给他一个人定一个阈值；
 * **留空 = 用全局阈值**（设置页那个，可能是手动值也可能是自动标定值）。
 *
 * 为什么单独存一个文件而不是写进 `roster.csv`：
 *   * `roster.csv` 的列（班级,姓名,学号,备注）是和电脑版**完全一致**的，
 *     两边可以互相导入；往里加列会破坏这个约定。
 *   * 所以这里单独放 `person_thresholds.csv`（姓名,阈值），
 *     电脑版不认识这个文件、也不会被它影响，删掉它只是回到"全部用全局阈值"。
 *
 * 文件用 TextCodec 读写：UTF-8 BOM + CRLF，Excel 双击打开不乱码，人工改也行
 * （改完在设置页点「重载名单」或者重新进 App 就生效）。
 */
class ThresholdStore(
    private val config: Config,
    private val log: (String) -> Unit = {}
) {

    /** 名字 -> 阈值 */
    private val map = LinkedHashMap<String, Float>()

    val file: File get() = File(config.root, FILE_NAME)

    val size: Int get() = map.size

    @Synchronized
    fun load(): Int {
        map.clear()
        if (!file.exists()) {
            log("[阈值] 还没有个人阈值文件（全部用全局阈值）")
            return 0
        }
        try {
            val (header, rows) = TextCodec.readCsv(file)
            // 表头可有可无：认得出「姓名/阈值」就按表头映射，认不出就按 第1列=姓名 第2列=阈值
            val nameIndex = header.indexOfFirst { it == "姓名" || it == "name" }
            val valueIndex = header.indexOfFirst { it == "阈值" || it == "threshold" }
            val hasHeader = nameIndex >= 0 || valueIndex >= 0
            val ni = if (hasHeader && nameIndex >= 0) nameIndex else 0
            val vi = if (hasHeader && valueIndex >= 0) valueIndex else 1
            for (row in rows) {
                val name = row.getOrNull(ni)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val raw = row.getOrNull(vi)?.trim().orEmpty()
                val value = parseThreshold(raw) ?: continue
                map[name] = value
            }
            log("[阈值] 已载入 ${map.size} 人的专属阈值")
        } catch (t: Throwable) {
            log("[阈值] 读取失败: ${t.javaClass.simpleName} ${t.message}")
        }
        return map.size
    }

    @Synchronized
    fun save() {
        val rows = map.entries.map { (name, value) ->
            listOf(name, String.format(Locale.US, "%.3f", value))
        }
        try {
            TextCodec.writeCsv(file, listOf("姓名", "阈值"), rows)
        } catch (t: Throwable) {
            log("[阈值] 保存失败: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 这个人的专属阈值；没有则 null（= 用全局值）。 */
    @Synchronized
    fun get(name: String): Float? = map[name.trim()]

    /** 设置/清除一个人的阈值；value = null 或超出范围就当作清除。 */
    @Synchronized
    fun set(name: String, value: Float?): Boolean {
        val key = name.trim()
        if (key.isEmpty()) return false
        val ok = value != null && value in MIN..MAX
        val had = map.containsKey(key)
        if (!ok) {
            if (!had) return false
            map.remove(key)
        } else {
            if (had && map[key] == value) return false
            map[key] = value!!
        }
        save()
        return true
    }

    @Synchronized
    fun remove(name: String) {
        if (map.remove(name.trim()) != null) save()
    }

    @Synchronized
    fun all(): Map<String, Float> = LinkedHashMap(map)

    /** 名单里已经不存在的人，把阈值一起清掉（删人时用）。 */
    @Synchronized
    fun retainOnly(names: Collection<String>): Int {
        val keep = names.toHashSet()
        val removed = map.keys.filter { it !in keep }
        if (removed.isEmpty()) return 0
        removed.forEach { map.remove(it) }
        save()
        return removed.size
    }

    /**
     * 清理那些"名字已经不在名单里"的残留项。
     * 只在名单非空时执行，避免名单还没加载完就把整份阈值清空。
     */
    @Synchronized
    fun prune(names: Collection<String>): Int {
        if (names.isEmpty() || map.isEmpty()) return 0
        return retainOnly(names)
    }

    companion object {
        const val FILE_NAME = "person_thresholds.csv"

        /** 允许范围与全局阈值滑杆一致（0.15~0.90 取宽一点），超出视为清空 */
        const val MIN = 0.05f
        const val MAX = 0.97f

        /** 把用户输入解析成阈值；空白 = null（清除），非法 = null。 */
        fun parseThreshold(text: String): Float? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val v = t.toFloatOrNull() ?: return null
            if (v < MIN || v > MAX) return null
            return v
        }
    }
}
