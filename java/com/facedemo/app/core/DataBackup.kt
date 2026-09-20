package com.facedemo.app.core

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据备份 / 恢复（ZIP）。
 *
 * 打包内容（都是应用私有目录里的东西）：
 *
 *     roster.csv              名单（班级,姓名,学号,备注）
 *     roster_removed.txt      手动删除、不再自动入册的人
 *     person_thresholds.csv   个人阈值（姓名,阈值；没设过的人不在这张表里）
 *     photo 目录               人脸库照片（含子目录 = 班级）
 *     logs 下的 csv              相似度 / 事件 / 打卡 日志
 *     logs/snapshots 目录      识别快照
 *     backup.txt              这份备份的说明（版本、时间、条目数）
 *
 * 恢复规则（保守，尽量不丢现网数据）：
 *   * 照片：同名覆盖（就是同一个人的同一张），其它保留
 *   * roster.csv：**先把现有名单备份成 roster_before_restore_<时间>.csv**，再覆盖
 *   * roster_removed.txt：覆盖
 *   * logs/：**只补不覆盖**，已有的日志不动（避免把新的打卡记录盖掉）
 *   * logs/snapshots/：只补不覆盖
 */
object DataBackup {

    private const val TAG = "DataBackup"
    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)

    data class RestoreResult(
        val ok: Boolean,
        val message: String,
        val photos: Int = 0,
        val logs: Int = 0,
        val roster: Boolean = false
    )

    /** 打包成 zip，返回文件（放在 cacheDir/exports 下，可直接分享）。 */
    fun export(context: Context, cfg: Config, version: String): File? {
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val stamp = stampFormat.format(Date())
            val target = File(dir, "人脸识别数据_$stamp.zip")
            var entries = 0
            ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
                // 名单 + 已删除名单 + 个人阈值
                for (name in listOf("roster.csv", "roster_removed.txt", ThresholdStore.FILE_NAME)) {
                    val file = File(cfg.root, name)
                    if (file.isFile) {
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        entries++
                    }
                }
                // 人脸库照片
                entries += zipDir(zip, cfg.photoDir, "photo")
                // 日志（含快照）
                entries += zipDir(zip, cfg.logsDir, "logs")
                // 说明
                zip.putNextEntry(ZipEntry("backup.txt"))
                zip.write(
                    ("人脸识别 安卓版 数据备份\n" +
                        "版本: $version\n" +
                        "时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}\n" +
                        "条目: $entries\n" +
                        "说明: 恢复时照片同名覆盖、名单覆盖(会先备份旧名单)、日志只补不覆盖\n")
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
            Log.i(TAG, "导出完成: ${target.name} (${entries} 项)")
            target
        } catch (t: Throwable) {
            Log.e(TAG, "导出失败", t)
            null
        }
    }

    /**
     * 只把**人脸库照片**打包成 zip（v1.0.2 新增）。
     *
     * 老师常要"把采集的人像带走"：这里只打 photo 目录（含班级子目录），
     * 另附一份 `人像清单.csv`（姓名 / 班级目录 / 文件数 / 字节数）和 `readme.txt`，
     * 不带日志、不带快照，所以包很小、发给别人也没有隐私顾虑（只有人脸照本身）。
     */
    fun exportPhotos(context: Context, cfg: Config, version: String): File? {
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val stamp = stampFormat.format(Date())
            val target = File(dir, "人脸库照片_$stamp.zip")
            var entries = 0
            val manifest = ArrayList<List<String>>()
            manifest.add(listOf("姓名", "子目录", "文件", "字节"))
            ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
                entries += zipDir(zip, cfg.photoDir, "photo")
                // 清单：photo/<班级?>/<姓名>_1.png
                if (cfg.photoDir.isDirectory) {
                    cfg.photoDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val rel = file.absolutePath.removePrefix(cfg.photoDir.absolutePath)
                                .trimStart(File.separatorChar)
                            val sub = rel.substringBefore(File.separatorChar, "")
                            val subDir = if (rel.contains(File.separatorChar)) sub else ""
                            val person = file.name.substringBeforeLast('.')
                                .substringBeforeLast('_')     // 去掉 _1 / _2 序号
                            manifest.add(listOf(person, subDir, file.name, file.length().toString()))
                        }
                    }
                }
                zip.putNextEntry(ZipEntry("人像清单.csv"))
                val csv = StringBuilder("\uFEFF")
                for (row in manifest) csv.append(TextCodec.joinCsv(row)).append("\r\n")
                zip.write(csv.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write(
                    ("人脸识别 安卓版 —— 人脸库照片导出\n" +
                        "版本: $version\n" +
                        "时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}\n" +
                        "内容: photo/ 下全部人像（含班级子目录）+ 人像清单.csv\n" +
                        "照片数: ${manifest.size - 1}\n" +
                        "说明: 只需要人像时用这个包；包含名单/日志/快照的完整备份请用「导出备份」\n")
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
            Log.i(TAG, "人像导出完成: ${target.name} (${manifest.size - 1} 张, $entries 个条目)")
            target
        } catch (t: Throwable) {
            Log.e(TAG, "人像导出失败", t)
            null
        }
    }

    private fun zipDir(zip: ZipOutputStream, dir: File, prefix: String): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        val base = dir.absolutePath
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val rel = file.absolutePath.removePrefix(base).trimStart(File.separatorChar)
                val name = "$prefix/" + rel.replace(File.separatorChar, '/')
                try {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                } catch (t: Throwable) {
                    Log.w(TAG, "打包 $name 失败: ${t.message}")
                }
            }
        }
        return count
    }

    /** 从 zip 恢复。 */
    fun restore(context: Context, cfg: Config, uri: Uri): RestoreResult {
        var photos = 0
        var logs = 0
        var roster = false
        var files = 0
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return RestoreResult(false, "打不开这个文件")
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory) {
                        files++
                        when {
                            name.endsWith("roster.csv") && name.count { it == '/' } == 0 -> {
                                backupCurrentRoster(cfg)
                                writeStream(zip, File(cfg.root, "roster.csv"))
                                roster = true
                            }
                            name == "roster_removed.txt" ->
                                writeStream(zip, File(cfg.root, "roster_removed.txt"))
                            // 个人阈值：整份覆盖（它就是一张"姓名,阈值"表）
                            name == ThresholdStore.FILE_NAME ->
                                writeStream(zip, File(cfg.root, ThresholdStore.FILE_NAME))
                            name.startsWith("photo/") -> {
                                val rel = name.removePrefix("photo/")
                                if (rel.isNotBlank()) {
                                    writeStream(zip, File(cfg.photoDir, rel), overwrite = true)
                                    photos++
                                }
                            }
                            name.startsWith("logs/") -> {
                                val rel = name.removePrefix("logs/")
                                if (rel.isNotBlank()) {
                                    val target = File(cfg.logsDir, rel)
                                    // 日志只补不覆盖，保留现有记录
                                    if (!target.exists()) {
                                        writeStream(zip, target)
                                        logs++
                                    }
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "恢复失败", t)
            return RestoreResult(false, "恢复失败: ${t.javaClass.simpleName} ${t.message}", photos, logs, roster)
        }
        if (files == 0) return RestoreResult(false, "这个 zip 里没有可用内容")
        val message = buildString {
            append("恢复完成：照片 $photos 张")
            if (roster) append("，名单已替换（旧名单已备份）") else append("，名单未包含")
            append("，日志补入 $logs 个文件")
        }
        Log.i(TAG, message)
        return RestoreResult(true, message, photos, logs, roster)
    }

    private fun backupCurrentRoster(cfg: Config) {
        try {
            val current = File(cfg.root, "roster.csv")
            if (current.isFile && current.length() > 0) {
                val backup = File(cfg.root, "roster_before_restore_${stampFormat.format(Date())}.csv")
                current.copyTo(backup, overwrite = true)
                Log.i(TAG, "旧名单已备份: ${backup.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "备份旧名单失败: ${t.message}")
        }
    }

    private fun writeStream(zip: ZipInputStream, target: File, overwrite: Boolean = true) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        if (target.exists() && !overwrite) return
        FileOutputStream(target).use { out -> zip.copyTo(out) }
    }
}
