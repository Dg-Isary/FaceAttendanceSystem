package com.facedemo.app.core

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 一条事件 (语音播报 / 阈值调整 / 录入 ...), 供界面增量显示。 */
data class EventRecord(
    val id: Int,
    val time: String,
    val event: String,
    val name: String,
    val similarity: Float?,
    val threshold: Float?,
    val detail: String
)

/**
 * 日志: 相似度流水 / 事件记录 / 识别快照 / 日志分析。
 * 对应 Python 版 facedemo/logstore.py, CSV 列完全一致, 手机与电脑的文件可以互换。
 *
 *   logs/similarity_YYYYmmdd_HHMMSS.csv   每张人脸与每个人的相似度 (调试用)
 *   logs/events_YYYYmmdd_HHMMSS.csv       阈值调整 / 识别命中 / 语音播报等事件
 *   logs/snapshots/ 下的 jpg                 播报/打卡时的抓拍
 */
class LogStore(private val cfg: Config, private val log: (String) -> Unit = {}) {

    companion object {
        val SIMILARITY_HEADER = listOf("时间", "帧号", "人脸序号", "候选人", "相似度", "是否最佳",
            "阈值", "判定", "是否达标", "人脸框")
        val EVENT_HEADER = listOf("时间", "事件", "人名", "相似度", "阈值", "详情")
    }

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)

    var enabled: Boolean = true
    val similarityFile: File
    val eventsFile: File
    val snapshotDir: File

    var similarityCount: Int = 0
        private set
    var eventCount: Int = 0
        private set

    private val eventQueue = ArrayDeque<EventRecord>()
    private var eventId = 0
    private var rowsSinceFlush = 0

    init {
        cfg.logsDir.mkdirs()
        cfg.snapshotsDir.mkdirs()
        val stamp = fileFormat.format(Date())
        similarityFile = File(cfg.logsDir, "similarity_$stamp.csv")
        eventsFile = File(cfg.logsDir, "events_$stamp.csv")
        snapshotDir = cfg.snapshotsDir
        try {
            TextCodec.writeCsv(similarityFile, SIMILARITY_HEADER, emptyList())
            TextCodec.writeCsv(eventsFile, EVENT_HEADER, emptyList())
        } catch (e: Throwable) {
            log("[日志] 无法创建日志文件: ${e.message}")
            enabled = false
        }
    }

    private fun nowStamp(): String =
        stampFormat.format(Date()) + "." + String.format(Locale.US, "%03d", System.currentTimeMillis() % 1000)

    // ------------------------------------------------------------------
    /** 记录一帧里每张人脸与每个候选人的相似度。 */
    fun logFrame(frameIndex: Int, pairs: List<Pair<Face, MatchResult>>, threshold: Float, everyN: Int = 1) {
        if (!enabled || pairs.isEmpty()) return
        if (everyN > 1 && frameIndex % everyN != 0) return
        val stamp = nowStamp()
        val rows = ArrayList<List<String>>()
        for ((faceIndex, pair) in pairs.withIndex()) {
            val (face, result) = pair
            val box = "${face.x},${face.y},${face.w},${face.h}"
            val ranking: List<Pair<String, Float>> = if (cfg.logAllSimilarities && result.scores.isNotEmpty()) {
                result.scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
            } else if (result.name != null) {
                listOf(result.name to result.similarity)
            } else {
                emptyList()
            }
            for ((name, similarity) in ranking) {
                val isBest = name == result.name
                // 阈值这一列写"这张脸实际用的阈值"：设了个人专属阈值的人会与全局值不同
                val usedThreshold = if (result.threshold > 0f) result.threshold else threshold
                rows.add(
                    listOf(
                        stamp, frameIndex.toString(), faceIndex.toString(), name,
                        String.format(Locale.US, "%.4f", similarity),
                        if (isBest) "1" else "0",
                        String.format(Locale.US, "%.4f", usedThreshold),
                        if (isBest) result.decision else "",
                        if (isBest && result.accepted) "1" else "0",
                        box
                    )
                )
                similarityCount++
            }
        }
        if (rows.isEmpty()) return
        try {
            TextCodec.appendCsvLines(similarityFile, SIMILARITY_HEADER, rows)
        } catch (e: Throwable) {
            log("[日志] 写相似度失败: ${e.message}")
            return
        }
        rowsSinceFlush++
        if (rowsSinceFlush >= 10) rowsSinceFlush = 0
    }

    /** 记录一条事件, 同时放进内存环形缓冲, 界面可以增量拉取。 */
    @Synchronized
    fun logEvent(
        event: String,
        name: String = "",
        similarity: Float? = null,
        threshold: Float? = null,
        detail: String = ""
    ): EventRecord {
        eventId++
        val record = EventRecord(
            id = eventId,
            time = nowStamp(),
            event = event,
            name = name,
            similarity = similarity,
            threshold = threshold,
            detail = detail
        )
        eventQueue.addLast(record)
        while (eventQueue.size > 500) eventQueue.removeFirst()
        if (enabled) {
            try {
                TextCodec.appendCsvLine(
                    eventsFile, EVENT_HEADER,
                    listOf(
                        record.time, event, name,
                        similarity?.let { String.format(Locale.US, "%.4f", it) } ?: "",
                        threshold?.let { String.format(Locale.US, "%.4f", it) } ?: "",
                        detail
                    )
                )
                eventCount++
            } catch (e: Throwable) {
                log("[日志] 写事件失败: ${e.message}")
            }
        }
        return record
    }

    @Synchronized
    fun recentEvents(since: Int = 0, limit: Int = 200): List<EventRecord> {
        val list = eventQueue.filter { it.id > since }
        return if (limit in 1 until list.size) list.takeLast(limit) else list
    }

    @Synchronized
    fun lastEventId(): Int = eventQueue.lastOrNull()?.id ?: 0

    // ------------------------------------------------------------------
    /** 保存一张快照 (播报/打卡抓拍)。 */
    fun snapshot(frame: Bitmap, name: String = "", similarity: Float? = null, frameIndex: Int = 0): String? {
        return try {
            snapshotDir.mkdirs()
            val stamp = fileFormat.format(Date())
            val parts = ArrayList<String>()
            parts.add(stamp)
            parts.add("f$frameIndex")
            if (name.isNotEmpty()) parts.add(name.replace(File.separator, "_"))
            if (similarity != null) parts.add(String.format(Locale.US, "%.3f", similarity))
            val target = File(snapshotDir, parts.joinToString("_") + ".jpg")
            FileOutputStream(target).use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            target.absolutePath
        } catch (e: Throwable) {
            log("[日志] 快照保存失败: ${e.message}")
            null
        }
    }

    fun pathsText(): String = buildString {
        append("相似度日志: ${similarityFile.name}\n")
        append("事件日志:   ${eventsFile.name}\n")
        append("快照目录:   ${snapshotDir.absolutePath}")
    }

    fun listSnapshots(limit: Int = 60): List<File> =
        snapshotDir.listFiles()?.filter { it.isFile && Models.isImage(it.name) }
            ?.sortedByDescending { it.lastModified() }?.take(limit) ?: emptyList()

    // ------------------------------------------------------------------
    /** 最近一份相似度日志。 */
    fun newestSimilarityFile(): File? =
        cfg.logsDir.listFiles()?.filter { it.isFile && it.name.startsWith("similarity_") && it.name.endsWith(".csv") }
            ?.maxByOrNull { it.lastModified() }

    /** 分析相似度日志: 各人统计 + 阈值扫描 + 调参建议。 */
    fun report(file: File? = null): String {
        val target = file ?: newestSimilarityFile()
            ?: return "[报告] ${cfg.logsDir.absolutePath} 里没有 similarity_*.csv"
        if (!target.exists()) return "[报告] 文件不存在: ${target.absolutePath}"
        val (header, rows) = TextCodec.readCsv(target)
        if (header.isEmpty() || rows.isEmpty()) return "[报告] ${target.name} 没有数据行"
        val index = HashMap<String, Int>()
        for ((column, field) in header.withIndex()) index[field.trim()] = column
        fun pick(row: List<String>, key: String): String {
            val column = index[key] ?: return ""
            return row.getOrNull(column)?.trim() ?: ""
        }

        val sb = StringBuilder()
        sb.append("[报告] 文件: ${target.name}\n")
        sb.append("[报告] 共 ${rows.size} 行, 时间范围 ${pick(rows.first(), "时间")} ~ ${pick(rows.last(), "时间")}\n")

        val best = rows.filter { pick(it, "是否最佳") == "1" }
        val people = LinkedHashMap<String, MutableList<Float>>()
        for (row in rows) {
            val name = pick(row, "候选人").ifEmpty { "?" }
            people.getOrPut(name) { ArrayList() }.add(pick(row, "相似度").toFloatOrNull() ?: 0f)
        }

        sb.append("\n")
        sb.append(String.format(Locale.US, "%-14s%8s%10s%9s%9s%9s\n", "候选人", "采样行", "最佳次数", "平均", "最高", "最低"))
        sb.append("-".repeat(62)).append("\n")
        val thresholds = best.mapNotNull { pick(it, "阈值").toFloatOrNull() }.distinct().sorted()
        val threshold = thresholds.lastOrNull() ?: 0f
        for (name in people.keys.sortedByDescending { people[it]!!.max() }) {
            val values = people[name]!!
            val bestCount = best.count { pick(it, "候选人") == name }
            sb.append(
                String.format(
                    Locale.US, "%-14s%8d%10d%9.3f%9.3f%9.3f\n",
                    name, values.size, bestCount, values.average(), values.max(), values.min()
                )
            )
        }

        sb.append("\n当前阈值(日志中最后一次): ").append(String.format(Locale.US, "%.3f", threshold)).append("\n")
        val decisions = LinkedHashMap<String, Int>()
        for (row in best) {
            val decision = pick(row, "判定").ifEmpty { "?" }
            decisions[decision] = (decisions[decision] ?: 0) + 1
        }
        sb.append("最佳匹配的判定分布: ").append(decisions.entries.joinToString(", ") { "${it.key}=${it.value}" }).append("\n")

        sb.append("\n阈值扫描 (依据日志中最相似的那一行的相似度):\n")
        sb.append(String.format(Locale.US, "%8s%12s%10s   达标的人\n", "阈值", "达标行数", "占比"))
        sb.append("-".repeat(62)).append("\n")
        for (i in 2 until 19) {
            val candidate = i * 0.05f
            val hit = best.filter { (pick(it, "相似度").toFloatOrNull() ?: 0f) >= candidate }
            val names = hit.map { pick(it, "候选人").ifEmpty { "?" } }.distinct().sorted()
            sb.append(
                String.format(
                    Locale.US, "%8.2f%12d%9.1f%%   %s\n",
                    candidate, hit.size, hit.size * 100.0 / maxOf(1, best.size),
                    if (names.isEmpty()) "-" else names.joinToString(", ")
                )
            )
        }

        sb.append("\n[报告] 调参建议:\n")
        if (best.isEmpty()) {
            sb.append("  - 日志里没有最佳匹配记录, 可能是没有检测到人脸\n")
            return sb.toString()
        }
        val never = people.keys.filter { name ->
            rows.none { pick(it, "候选人") == name && pick(it, "是否达标") == "1" }
        }.sorted()
        if (never.isEmpty()) {
            sb.append("  - 每个人至少有一次达标记录\n")
        } else {
            sb.append("  - 本次日志中从未达标的人: ${never.joinToString(", ")}")
                .append(" (也可能是本次没走到镜头前; 若其中有本人, 请降低阈值或换更清晰的正脸照)\n")
        }
        if (people.size > 1) {
            val top = people.mapValues { it.value.max() }
            val bestName = top.maxByOrNull { it.value }!!.key
            val second = top.values.sortedDescending()[1]
            sb.append(
                String.format(
                    Locale.US,
                    "  - 最像的是 %s (%.3f), 次高 %.3f, 差距 %.3f (差距越大越可靠)\n",
                    bestName, top[bestName]!!, second, top[bestName]!! - second
                )
            )
        }
        sb.append("  - 建议把阈值设在“自己人的相似度”与“其他人相似度”之间\n")
        return sb.toString()
    }
}
