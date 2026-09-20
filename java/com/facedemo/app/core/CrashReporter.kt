package com.facedemo.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志收集。
 *
 * 手机上的崩溃没法像电脑那样看控制台，所以这里把未捕获异常的完整堆栈写到两个地方：
 *
 *   filesDir/crash_last.txt                          应用私有目录（“设置 → 崩溃日志”里能看/分享）
 *   getExternalFilesDir()/crash_时间戳.txt            外部私有目录（文件管理器/电脑能直接拷走）
 *
 * 写完再调起一个崩溃说明页把堆栈显示出来，最后才结束进程 —— 这样即使是启动就崩，
 * 用户也能直接把原因发出来，而不用连电脑抓 logcat。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                handle(app, thread, error)
            } catch (t: Throwable) {
                Log.e(TAG, "崩溃处理本身也失败了", t)
                previous?.uncaughtException(thread, error)
                return@setDefaultUncaughtExceptionHandler
            }
            // 不交给系统默认处理器（那会直接弹“应用屡次停止运行”），
            // 崩溃说明页已经排进队列，这里结束掉崩溃的进程即可。
            Process.killProcess(Process.myPid())
        }
        Log.i(TAG, "已安装崩溃收集器")
    }

    private fun handle(app: Context, thread: Thread, error: Throwable) {
        val text = buildText(thread, error)
        Log.e(TAG, text)

        val primary = File(app.filesDir, "crash_last.txt")
        runCatching { primary.writeText(text, Charsets.UTF_8) }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val archiveName = "crash_$stamp.txt"
        var external: File? = null
        runCatching {
            val dir = app.getExternalFilesDir(null) ?: return@runCatching
            val file = File(dir, archiveName)
            file.writeText(text, Charsets.UTF_8)
            external = file
        }

        val intent = Intent(app, com.facedemo.app.CrashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(com.facedemo.app.CrashActivity.EXTRA_TEXT, text)
            putExtra(
                com.facedemo.app.CrashActivity.EXTRA_PATH,
                external?.absolutePath ?: primary.absolutePath
            )
        }
        runCatching { app.startActivity(intent) }
    }

    private fun buildText(thread: Thread, error: Throwable): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        return buildString {
            append("时间: $stamp\n")
            append("线程: ${thread.name}\n")
            append("机型: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("架构: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
            append("异常: ${error.javaClass.name}: ${error.message}\n")
            append("\n---- 堆栈 ----\n")
            append(Log.getStackTraceString(error))
            var cause = error.cause
            var depth = 1
            while (cause != null && depth <= 5) {
                append("\n---- 起因 ${depth} ----\n")
                append(Log.getStackTraceString(cause))
                cause = cause.cause
                depth++
            }
        }
    }

    /** 上一次崩溃的文本（没有就返回空串）。 */
    fun lastCrash(context: Context): String {
        val file = File(context.filesDir, "crash_last.txt")
        return if (file.exists()) runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("") else ""
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, "crash_last.txt").delete() }
    }
}
