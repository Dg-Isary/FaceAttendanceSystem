package com.facedemo.app.core

import java.io.File
import java.nio.charset.Charset

/**
 * 文本编码自适应 + 极简 CSV 工具。
 *
 * 对应 Python 版的 facedemo/fsutil.py:decode_text 与 csv 模块的用法。
 * 中文 Windows 上 Excel 另存的 CSV 是 GBK, 另存“Unicode 文本”是 UTF-16,
 * 手机从网盘/微信拿到这类文件后必须自动识别, 否则整份名单都是乱码。
 */
object TextCodec {

    data class Decoded(val text: String, val encoding: String)

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        return prefix.indices.all { data[it] == prefix[it] }
    }

    /** 按 BOM -> UTF-8 -> GB18030 -> UTF-16 的顺序解码。 */
    fun decode(data: ByteArray): Decoded {
        if (data.isEmpty()) return Decoded("", "empty")
        if (startsWith(data, UTF8_BOM)) {
            return Decoded(String(data, 3, data.size - 3, Charsets.UTF_8), "utf-8-sig")
        }
        for ((bom, name) in listOf(UTF16LE_BOM to "utf-16-le", UTF16BE_BOM to "utf-16-be")) {
            if (startsWith(data, bom)) {
                val charset = Charset.forName(if (name.endsWith("le")) "UTF-16LE" else "UTF-16BE")
                return Decoded(String(data, 2, data.size - 2, charset), name)
            }
        }
        for (name in listOf("UTF-8", "GB18030", "UTF-16")) {
            val decoded = strictDecode(data, name)
            if (decoded != null) return Decoded(decoded, name.lowercase())
        }
        return Decoded(String(data, Charsets.UTF_8), "utf-8(替换非法字节)")
    }

    /** 严格解码: 有非法字节就返回 null (Android 的 String(bytes, charset) 默认会静默替换)。 */
    private fun strictDecode(data: ByteArray, charsetName: String): String? {
        return try {
            val charset = Charset.forName(charsetName)
            val decoder = charset.newDecoder()
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(data)).toString()
        } catch (e: Throwable) {
            null
        }
    }

    fun readSmart(file: File): Decoded = decode(file.readBytes())

    // ------------------------------------------------------------------
    // CSV
    // ------------------------------------------------------------------

    /** 解析一行 CSV, 支持双引号包裹与 "" 转义。 */
    fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out.add(sb.toString()); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** 把一行字段拼成 CSV (含转义)。 */
    fun joinCsv(fields: List<String>): String =
        fields.joinToString(",") { field ->
            if (field.contains(',') || field.contains('"') || field.contains('\n')) {
                "\"" + field.replace("\"", "\"\"") + "\""
            } else field
        }

    /** 读 CSV 成 (表头, 行) —— 自动识别编码, 兼容 \r\n 与 \n。 */
    fun readCsv(file: File): Pair<List<String>, List<List<String>>> {
        if (!file.exists()) return Pair(emptyList(), emptyList())
        val text = readSmart(file).text
        val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Pair(emptyList(), emptyList())
        val header = splitCsvLine(lines.first()).map { it.trim() }
        val rows = lines.drop(1).map { splitCsvLine(it) }
        return Pair(header, rows)
    }

    /**
     * 写 CSV: 一律带 UTF-8 BOM, 这样 Excel 双击打开中文不乱码 (与 Python 版一致)。
     */
    fun writeCsv(file: File, header: List<String>, rows: List<List<String>>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append(joinCsv(header)).append("\r\n")
        for (row in rows) sb.append(joinCsv(row)).append("\r\n")
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    fun appendCsvLine(file: File, header: List<String>, row: List<String>) =
        appendCsvLines(file, header, listOf(row))

    /** 批量追加 (每帧要写多行相似度, 逐行开关文件太慢)。 */
    fun appendCsvLines(file: File, header: List<String>, rows: List<List<String>>) {
        if (rows.isEmpty()) return
        file.parentFile?.mkdirs()
        val newFile = !file.exists() || file.length() == 0L
        val sb = StringBuilder()
        if (newFile) {
            sb.append('\uFEFF').append(joinCsv(header)).append("\r\n")
        }
        for (row in rows) sb.append(joinCsv(row)).append("\r\n")
        file.appendText(sb.toString(), Charsets.UTF_8)
    }
}
