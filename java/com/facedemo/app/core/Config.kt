package com.facedemo.app.core

import android.content.Context
import java.io.File

/**
 * 应用配置。对应 Python 版的 config.json, 存在 SharedPreferences 里。
 *
 * 路径全部落在应用私有目录, 不需要任何存储权限:
 *   filesDir/photo/            人脸库照片 (文件名 = 人名)
 *   filesDir/roster.csv        名单 (班级,姓名,学号,备注)
 *   filesDir/logs/             相似度日志 / 事件日志 / 打卡 CSV / 快照
 *   filesDir/models/           从 assets 复制出来的 ONNX 模型
 */
class Config(context: Context) {

    private val prefs = context.getSharedPreferences("facedemo", Context.MODE_PRIVATE)

    val root: File = context.filesDir
    val photoDir: File = File(root, "photo")
    val logsDir: File = File(root, "logs")
    val snapshotsDir: File = File(logsDir, "snapshots")
    val modelsDir: File = File(root, "models")
    val rosterFile: File = File(root, "roster.csv")

    /**
     * 应用外部私有目录（`/sdcard/Android/data/<包名>/files`）。
     * 录入调试图（原图 / 掩码 / 抠像结果）额外写一份到这里 ——
     * 出问题时用文件管理器或插电脑就能把图取出来发我，而 filesDir 是取不到的。
     */
    val externalRoot: File? = context.getExternalFilesDir(null)
    val debugDir: File = File(externalRoot ?: root, "logs/enroll")

    /**
     * 相似度阈值，-1 表示自动标定。
     *
     * v1.1.0：默认值从"自动标定"(-1) 改成固定 **0.50**（`FaceEngine.DEFAULT_THRESHOLD`）——
     * 用户实测 0.36 的误报率太高。想要老行为（自动标定）就在 设置 → 识别 里打开「自动标定阈值」，
     * 打开后算出来的值也不会低于 0.50（见 FaceEngine.calibrate）。
     */
    var threshold: Float
        get() = prefs.getFloat("threshold", FaceEngine.DEFAULT_THRESHOLD)
        set(value) = prefs.edit().putFloat("threshold", value).apply()

    var autoThreshold: Boolean
        get() = threshold < 0f
        set(value) {
            if (value) threshold = -1f
        }

    /**
     * v1.1.0 的一次性默认值迁移：把"恰好还是老默认 0.36"的存量设置抬到 0.50。
     *
     * 只动这一个值 —— 用户自己拖过的其它值、以及自动标定(-1)都不动
     * （自动标定的下界已经在 FaceEngine 里提到 0.50 了）。
     * 由 AppViewModel 初始化时调用一次，用 prefs 里的标记保证只跑一次。
     */
    fun migrateDefaults() {
        if (prefs.getBoolean("migratedThreshold050", false)) return
        val editor = prefs.edit().putBoolean("migratedThreshold050", true)
        val current = prefs.getFloat("threshold", FaceEngine.DEFAULT_THRESHOLD)
        if (kotlin.math.abs(current - 0.36f) < 0.0005f) {
            editor.putFloat("threshold", FaceEngine.DEFAULT_THRESHOLD)
        }
        editor.apply()
    }

    var mirror: Boolean
        get() = prefs.getBoolean("mirror", true)
        set(value) = prefs.edit().putBoolean("mirror", value).apply()

    /**
     * 界面缩放（1.0 = 系统默认）。
     * 通过覆盖 Compose 的 LocalDensity 实现，dp/sp 一起缩放，
     * 效果等同于系统的「显示大小」设置 —— 字太小、控件挤的时候可以放大。
     */
    var uiScale: Float
        get() = prefs.getFloat("uiScale", 1.0f)
        set(value) = prefs.edit().putFloat("uiScale", value.coerceIn(0.75f, 1.4f)).apply()

    /**
     * 主题色（强调色）key，见 `ui/Theme.kt` 的 THEME_PRESETS：
     * blue / cyan / green / violet / amber / rose —— 默认极光蓝。
     */
    var themeColor: String
        get() = prefs.getString("themeColor", "blue") ?: "blue"
        set(value) = prefs.edit().putString("themeColor", value).apply()

    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts", true)
        set(value) = prefs.edit().putBoolean("tts", value).apply()

    /** 指定音色 (系统 TTS 引擎的 voice.name), 空 = 自动挑中文 */
    var ttsVoice: String
        get() = prefs.getString("ttsVoice", "") ?: ""
        set(value) = prefs.edit().putString("ttsVoice", value).apply()

    var ttsRate: Float
        get() = prefs.getFloat("ttsRate", 1.0f)
        set(value) = prefs.edit().putFloat("ttsRate", value).apply()

    var ttsPitch: Float
        get() = prefs.getFloat("ttsPitch", 1.0f)
        set(value) = prefs.edit().putFloat("ttsPitch", value).apply()

    /** 播报音量 0~1（有些 ROM 不显式给就按 0 处理） */
    var ttsVolume: Float
        get() = prefs.getFloat("ttsVolume", 1.0f)
        set(value) = prefs.edit().putFloat("ttsVolume", value).apply()

    /**
     * 播报走哪个音频流。
     *
     * 默认 `auto` = **不指定**，交给系统 TTS 引擎自己决定 —— 这是最保险的：
     * 手机系统设置里「文字转语音 → 试听」能用，说明引擎的默认路由是通的，
     * 而指定成 STREAM_MUSIC 之后反而可能因为某些 ROM 的媒体音量为 0 / 被蓝牙占用而没声音。
     * 如果系统试听正常、App 不出声，就把这里当开关一个个试。
     */
    var ttsStream: String
        get() = prefs.getString("ttsStream", "auto") ?: "auto"
        set(value) = prefs.edit().putString("ttsStream", value).apply()

    /**
     * 播报方式：
     *  * `direct` —— 引擎自己播放（默认，延迟最低）
     *  * `file`   —— 先 `synthesizeToFile` 合成 WAV，再用 MediaPlayer 播放。
     *    引擎“报成功但没声音”时换这个：播放这一段完全由我们控制（音量/音频流），
     *    绕开引擎自己的播放路由。
     */
    /**
     * 手动指定 TTS 引擎包名（留空 = 自动）。
     * 个别 ROM 的引擎枚举不出来时可以手填，例如 com.google.android.tts
     */
    var ttsEngine: String
        get() = prefs.getString("ttsEngine", "") ?: ""
        set(value) = prefs.edit().putString("ttsEngine", value.trim()).apply()
    var ttsMode: String
        get() = prefs.getString("ttsMode", "direct") ?: "direct"
        set(value) = prefs.edit().putString("ttsMode", value).apply()

    var ttsTemplate: String
        get() = prefs.getString("ttsTemplate", "{name}你好") ?: "{name}你好"
        set(value) = prefs.edit().putString("ttsTemplate", value).apply()

    var ttsCheckinTemplate: String
        get() = prefs.getString("ttsCheckin", "{name}，{kind}成功") ?: "{name}，{kind}成功"
        set(value) = prefs.edit().putString("ttsCheckin", value).apply()

    /**
     * 同一类型「间隔不够」时的播报词（v1.0.3 起语义变了）。
     *
     * 以前是「今天已经签到过了」（一天一次那套），现在改成间隔提示 ——
     * pref 键还是 ttsDuplicate，老设置不会丢；没改过的用户直接拿到新默认文案。
     */
    var ttsDuplicateTemplate: String
        get() = prefs.getString("ttsDuplicate", "{name}，{kind}间隔太短，请稍后再试")
            ?: "{name}，{kind}间隔太短，请稍后再试"
        set(value) = prefs.edit().putString("ttsDuplicate", value).apply()

    /**
     * v1.0.7：**没签到就想签退**时的播报词。
     *
     * 规则是"签退必须收掉开着的那段签到"（不签到不许签退），所以这时候要明确告诉对方
     * 为什么没记上，而不是默默忽略或说「你好」。
     */
    var ttsNoCheckinTemplate: String
        get() = prefs.getString("ttsNoCheckin", "{name}，还没签到，不能签退")
            ?: "{name}，还没签到，不能签退"
        set(value) = prefs.edit().putString("ttsNoCheckin", value).apply()

    var cooldownSeconds: Float
        get() = prefs.getFloat("cooldown", 6f)
        set(value) = prefs.edit().putFloat("cooldown", value).apply()

    var minHits: Int
        get() = prefs.getInt("minHits", 2)
        set(value) = prefs.edit().putInt("minHits", value).apply()

    /** 连续命中才打卡 (与语音共用) */
    var attendanceEnabled: Boolean
        get() = prefs.getBoolean("attendance", true)
        set(value) = prefs.edit().putBoolean("attendance", value).apply()

    var attendanceKind: String
        get() = prefs.getString("attendanceKind", "签到") ?: "签到"
        set(value) = prefs.edit().putString("attendanceKind", value).apply()

    /**
     * 打卡间隔（**小时**，v1.0.4 从"秒"改成"小时"）。
     *
     * 打卡不再有「一天一次」的限制（旧键 attendanceOnce 已废弃），
     * 唯一的限制就是这个间隔，而且**只在同一类型连着来时才生效**：
     *   * 签到 → 签退 → 签到：后一个不受限制（签退把这一段时间闭合了，可以马上再签到）
     *   * 签到 → 签到（中间没签退）：要隔这么久才记第二条，防"站那儿不动被连续记"
     *   * 签退 → 签退 同理
     *
     * 为什么按小时：实际使用里没人会隔几秒再打一次卡，按秒调（默认 30 秒）既没意义又难调；
     * 默认 1 小时、0.5 小时一格、最多 12 小时，填 0 = 完全不限制。
     * （旧键 attendanceCooldown 是秒，升级后不再读取，直接用新键的默认值。）
     */
    var attendanceIntervalHours: Float
        get() = prefs.getFloat("attendanceIntervalHours", 1f)
        set(value) = prefs.edit().putFloat("attendanceIntervalHours", value.coerceIn(0f, 12f)).apply()

    /** 不在名单里的人: ignore 只识别不打卡 | record 也记一条 */
    var attendanceUnlisted: String
        get() = prefs.getString("attendanceUnlisted", "ignore") ?: "ignore"
        set(value) = prefs.edit().putString("attendanceUnlisted", value).apply()

    /** 录入时人脸框外扩倍数 */
    var enrollExpand: Float
        get() = prefs.getFloat("enrollExpand", 2.8f)
        set(value) = prefs.edit().putFloat("enrollExpand", value).apply()

    var enrollShots: Int
        get() = prefs.getInt("enrollShots", 1)
        set(value) = prefs.edit().putInt("enrollShots", value).apply()

    /** 识别降频: 每 N 帧识别一次 (1 = 每帧), 越小越流畅 */
    var recognitionInterval: Int
        get() = prefs.getInt("interval", 1)
        set(value) = prefs.edit().putInt("interval", value).apply()

    /** 检测时把画面缩到的宽度 */
    var procWidth: Int
        get() = prefs.getInt("procWidth", 640)
        set(value) = prefs.edit().putInt("procWidth", value).apply()

    /**
     * 逐帧分析的最小间隔（毫秒）。相机本身会一直出帧，但只有到这个间隔才真正做检测+识别：
     * 数值越大越省电、越不发热，越小越跟手。
     */
    var analyzeIntervalMs: Int
        get() = prefs.getInt("analyzeInterval", 120)
        set(value) = prefs.edit().putInt("analyzeInterval", value.coerceIn(50, 500)).apply()

    /** 手机放平时自动暂停相机（省电、降温），举起来自动恢复 */
    var pauseWhenFlat: Boolean
        get() = prefs.getBoolean("pauseWhenFlat", true)
        set(value) = prefs.edit().putBoolean("pauseWhenFlat", value).apply()

    var detectorScore: Float
        get() = prefs.getFloat("detectorScore", 0.72f)
        set(value) = prefs.edit().putFloat("detectorScore", value).apply()

    /** 相似度日志是否记录全部候选人 (调试用, 关掉只记最佳匹配) */
    var logAllSimilarities: Boolean
        get() = prefs.getBoolean("logAllSim", true)
        set(value) = prefs.edit().putBoolean("logAllSim", value).apply()

    var saveSnapshots: Boolean
        get() = prefs.getBoolean("snapshots", true)
        set(value) = prefs.edit().putBoolean("snapshots", value).apply()

    var logEnabled: Boolean
        get() = prefs.getBoolean("logging", true)
        set(value) = prefs.edit().putBoolean("logging", value).apply()

    /** 录入输出规格: idphoto = 标准一寸照(295×413), cutout = 原始抠像裁切 */
    var enrollOutput: String
        get() = prefs.getString("enrollOutput", "idphoto") ?: "idphoto"
        set(value) = prefs.edit().putString("enrollOutput", value).apply()

    /** 录入背景: transparent / white / blue / red / gray (证件照常用白、蓝、红) */
    var mattingBackground: String
        get() = prefs.getString("mattingBg", "white") ?: "white"
        set(value) = prefs.edit().putString("mattingBg", value).apply()

    /** 是否做抠像去背景（关掉 = 只按人脸框裁切，背景保持原样） */
    var mattingEnabled: Boolean
        get() = prefs.getBoolean("mattingEnabled", true)
        set(value) = prefs.edit().putBoolean("mattingEnabled", value).apply()

    /** 抠像边缘内缩强度（相对人脸宽度的百分比）：背景抠不干净就调大 */
    var mattingErode: Float
        get() = prefs.getFloat("mattingErode", 2.2f)
        set(value) = prefs.edit().putFloat("mattingErode", value).apply()

    /** 抠像边缘羽化强度（同上）：边缘发硬/有锯齿就调大 */
    var mattingFeather: Float
        get() = prefs.getFloat("mattingFeather", 2.2f)
        set(value) = prefs.edit().putFloat("mattingFeather", value).apply()

    /** 录完一个人自动把下一位待录入的选上 */
    var enrollAutoNext: Boolean
        get() = prefs.getBoolean("enrollAutoNext", true)
        set(value) = prefs.edit().putBoolean("enrollAutoNext", value).apply()

    var enrollReplace: Boolean
        get() = prefs.getBoolean("enrollReplace", true)
        set(value) = prefs.edit().putBoolean("enrollReplace", value).apply()

    var enrollVerify: Boolean
        get() = prefs.getBoolean("enrollVerify", true)
        set(value) = prefs.edit().putBoolean("enrollVerify", value).apply()

    fun ensureDirs() {
        photoDir.mkdirs()
        logsDir.mkdirs()
        snapshotsDir.mkdirs()
        modelsDir.mkdirs()
        debugDir.mkdirs()
    }
}
