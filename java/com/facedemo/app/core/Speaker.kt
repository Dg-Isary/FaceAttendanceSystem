package com.facedemo.app.core

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * 播报时机控制: 把“每帧的识别结果”变成“什么时候播报”。
 * 对应 Python 版 facedemo/tts.py 的 Announcer。
 *
 *   * 需要连续 minHits 帧识别为同一人才播报 (防抖, 避免偶发误识就出声)
 *   * 同一个人两次播报之间至少间隔 cooldown 秒
 */
class Announcer(
    minHits: Int = 2,
    cooldown: Double = 6.0,
    template: String = "{name}你好"
) {
    var minHits: Int = maxOf(1, minHits)
    var cooldown: Double = maxOf(0.0, cooldown)
    var template: String = template.ifEmpty { "{name}你好" }

    private val hits = HashMap<String, Int>()
    private val lastSpoken = HashMap<String, Double>()
    val lastSimilarity = HashMap<String, Float>()

    fun reset(name: String? = null) {
        if (name == null) hits.clear() else hits.remove(name)
    }

    /** accepted: {人名: 相似度}, 本帧达到阈值的人。返回需要播报的列表。 */
    fun update(accepted: Map<String, Float>, now: Double = nowSeconds()): List<Triple<String, String, Float>> {
        for (name in hits.keys.toList()) {
            if (!accepted.containsKey(name)) hits[name] = 0
        }
        val todo = ArrayList<Triple<String, String, Float>>()
        for ((name, similarity) in accepted) {
            val count = (hits[name] ?: 0) + 1
            hits[name] = count
            if (count < minHits) continue
            if (now - (lastSpoken[name] ?: -1e9) < cooldown) continue
            lastSpoken[name] = now
            lastSimilarity[name] = similarity
            hits[name] = 0
            todo.add(Triple(name, template.replace("{name}", name), similarity))
        }
        return todo
    }

    fun cooldownLeft(name: String, now: Double = nowSeconds()): Double =
        maxOf(0.0, cooldown - (now - (lastSpoken[name] ?: -1e9)))

    companion object {
        fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
    }
}

data class SpeakRecord(val time: Long, val text: String, val ok: Boolean, val detail: String)

/**
 * 语音播报 (安卓 TextToSpeech)。对应 Python 版 facedemo/tts.py 的 Speaker。
 *
 * 手机上和电脑版差别最大的一块 —— 国内 ROM 的 TTS 环境经常“什么都不响”，
 * 所以这里做了几层兜底（真机上遇到的坑都记在注释里）：
 *
 *  1. **在主线程创建引擎**：有些 ROM 的 TTS 引擎要求调用线程有 Looper，
 *     在后台线程 new TextToSpeech 会静默失败（onInit 收到 ERROR 或永远不回调）。
 *  2. **多引擎回退**：先用系统默认引擎，失败就依次尝试 `TextToSpeech.getEngines()`
 *     里列出的每个引擎（很多机器默认引擎被禁用，但另有可用的引擎）。
 *  3. **指定音频流**：显式用 STREAM_MUSIC 并带上音量参数，避免某些 ROM 把
 *     语音播到被静音的流上（表现为“播报成功但没声音”）。
 *  4. **中文语言失败也照样播**：没有中文语音包时用默认音色读，虽不标准但能出声。
 *  5. 全部失败时只写日志不抛异常，界面上有「语音自检」能看具体原因。
 */
class Speaker(private val context: Context, private val cfg: Config, private val log: (String) -> Unit = {}) {

    companion object {
        private const val TAG = "Speaker"
    }

    // 注意：这几个字段会被**相机分析线程**读（speak() 在那里被调用），
    // 而写它们的是主线程（onInit → configure）。不加 @Volatile 的话分析线程可能
    // 一直读到旧的 ready=false —— 表现就是「开机后一直不播报，去界面上点一下才好」。
    @Volatile
    private var tts: TextToSpeech? = null
    private var attempts: List<String?> = emptyList()   // null = 系统默认引擎
    private var attemptIndex = 0
    @Volatile
    private var starting = false

    @Volatile
    var ready: Boolean = false
        private set
    @Volatile
    var backendName: String = "未初始化"
        private set
    @Volatile
    var engineLabel: String = ""
        private set
    var languageResult: Int = Int.MIN_VALUE
        private set
    var languageNote: String = ""
        private set
    @Volatile
    var lastError: String = ""
        private set
    @Volatile
    var muted: Boolean = false
    @Volatile
    var dryRun: Boolean = false

    /** 播报音量 0~1（有些 ROM 上必须显式给，否则按 0 处理） */
    @Volatile
    var volume: Float = 1.0f

    /** v1.0.5：上一次已经应用到引擎上的音色，用来避免每次改设置都枚举一遍 voices */
    private var lastAppliedVoice: String? = null

    /** 诊断用：调用过几次 speak、最后一次的返回码 */
    var speakAttempts: Int = 0
        private set
    var lastSpeakCode: Int = Int.MIN_VALUE
        private set
    var lastSpeakText: String = ""
        private set

    /** 引擎是否真的开始播了（onStart 回调）；没声音时用来区分“没开始”还是“播了但听不到” */
    @Volatile
    var utteranceStarted: Boolean = false
        private set

    private var player: MediaPlayer? = null
    private val pendingFiles = ArrayDeque<File>()

    /**
     * 引擎还在初始化时收到的播报，先记下来，等引擎就绪立刻补播一次 ——
     * 否则刚打开 App 那几秒里走过来的人会被“吞掉”（speak 返回 false，界面却已经显示了播报文案）。
     */
    private var pendingSpeak: String? = null
    private var pendingSpeakAt = 0L

    val history = ArrayDeque<SpeakRecord>()
    val availableEngines = ArrayList<String>()

    /** 在主线程调用（见类注释第 1 条）。 */
    fun start() {
        if (tts != null || starting) return
        starting = true
        availableEngines.clear()
        availableEngines.addAll(installedEngines())
        // 允许设置里手填引擎包名（个别 ROM 的引擎枚举不出来时用）
        val manual = cfg.ttsEngine.trim()
        attempts = if (manual.isNotEmpty()) {
            listOf<String?>(manual, null) + availableEngines.filter { it != manual }
        } else {
            listOf<String?>(null) + availableEngines
        }
        attemptIndex = 0
        log("[语音] 系统里装了 ${availableEngines.size} 个 TTS 引擎: " +
            (availableEngines.joinToString(", ").ifEmpty { "（一个都没找到）" }))
        if (manual.isNotEmpty()) log("[语音] 设置里指定的引擎: $manual")
        tryNext()
    }

    /**
     * 枚举系统里的 TTS 引擎。
     *
     * 注意 `TextToSpeech.getEngines()` 是**实例方法**（要先有实例），
     * 所以这里直接问 PackageManager 谁声明了 TTS 服务，拿包名当引擎名 ——
     * 这样才能在“默认引擎本身坏掉”的情况下换别的引擎。
     */
    private fun installedEngines(): List<String> = try {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    } catch (t: Throwable) {
        log("[语音] 枚举 TTS 引擎失败: ${t.message}")
        emptyList()
    }

    private fun tryNext() {
        val attempt = attempts.getOrNull(attemptIndex)
        if (attemptIndex > 0 && attempt == null) {
            backendName = "不可用"
            ready = false
            starting = false
            lastError = lastError.ifEmpty { "系统里没有可用的语音引擎" }
            log("[语音] 所有引擎都初始化失败, 语音不可用")
            return
        }
        val label = attempt ?: "系统默认引擎"
        log("[语音] 正在初始化: $label")
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                engineLabel = label
                configure()
            } else {
                lastError = "$label 初始化失败 (status=$status)"
                log("[语音] $lastError")
                runCatching { tts?.shutdown() }
                tts = null
                attemptIndex++
                tryNext()
            }
        }
        tts = try {
            if (attempt == null) TextToSpeech(context, listener)
            else TextToSpeech(context, listener, attempt)
        } catch (t: Throwable) {
            lastError = "$label 创建失败: ${t.javaClass.simpleName} ${t.message}"
            log("[语音] $lastError")
            tts = null
            attemptIndex++
            tryNext()
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceStarted = true
            }

            override fun onDone(utteranceId: String?) {
                // 兼容模式：合成完了我们自己播
                if (cfg.ttsMode == "file") {
                    if (pendingFiles.isEmpty()) return
                    val file = pendingFiles.removeFirst()
                    playFile(file)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onError(utteranceId, -1)
            override fun onError(utteranceId: String?, errorCode: Int) {
                lastError = "播报失败 (errorCode=$errorCode)"
                log("[语音] $lastError")
            }
        })
    }

    private fun configure() {
        val engine = tts ?: return
        // 中文优先，任何失败都不阻止出声
        languageResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        languageNote = when (languageResult) {
            TextToSpeech.LANG_MISSING_DATA -> "系统缺少中文语音数据（设置→语音合成 里安装中文）"
            TextToSpeech.LANG_NOT_SUPPORTED -> "该引擎不支持中文，会用默认音色读（发音可能不准）"
            else -> "中文(简体)"
        }
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            val fallback = engine.setLanguage(Locale.CHINA)
            if (fallback >= 0) languageResult = fallback
            log("[语音] $languageNote")
        }
        engine.setSpeechRate(cfg.ttsRate.coerceIn(0.3f, 2.5f))
        engine.setPitch(cfg.ttsPitch.coerceIn(0.3f, 2.5f))
        volume = cfg.ttsVolume.coerceIn(0f, 1f)

        val wanted = cfg.ttsVoice
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        if (wanted.isNotEmpty()) {
            val found = voices.firstOrNull { it.name == wanted }
                ?: voices.firstOrNull { it.name.contains(wanted, ignoreCase = true) }
            if (found != null) engine.voice = found else log("[语音] 未找到音色 $wanted, 用默认音色")
        } else if (voices.isNotEmpty()) {
            // 优先挑中文音色，免得用英文引擎读中文
            val chinese = voices.filter { it.locale.language.startsWith("zh") }
                .filter { !it.isNetworkConnectionRequired }
            (chinese.firstOrNull { it.quality >= Voice.QUALITY_NORMAL } ?: chinese.firstOrNull())
                ?.let { runCatching { engine.voice = it } }
        }

        ready = true
        starting = false
        backendName = "系统TTS"
        val voiceName = runCatching { engine.voice?.name }.getOrNull() ?: "默认"
        val defaultEngine = runCatching { engine.defaultEngine }.getOrNull() ?: ""
        if (defaultEngine.isNotEmpty()) {
            log("[语音] 系统默认引擎: $defaultEngine")
        }
        runCatching { engine.engines?.map { it.name } }.getOrNull()?.let { names ->
            if (names.isNotEmpty() && availableEngines.isEmpty()) availableEngines.addAll(names)
        }
        log("[语音] 播报后端就绪: 引擎=$engineLabel, 音色=$voiceName, $languageNote")

        // 引擎初始化那几秒里如果已经识别到人，把攒下的那句补播掉
        val pending = pendingSpeak
        val age = System.currentTimeMillis() - pendingSpeakAt
        pendingSpeak = null
        if (pending != null && age in 0..20_000) {
            log("[语音] 补播（初始化期间攒下的）: $pending")
            speak(pending)
        }
    }

    /**
     * v1.0.5：把设置里**能立刻生效**的那几项推给正在跑的引擎。
     *
     * 为什么需要：语速 / 音调 / 音色 / 音量这些东西以前只在引擎初始化时读一次 `cfg`，
     * 于是"改了设置要重启 App 才生效"—— 现在 `AppViewModel.updateSettings()` 每次改设置都调这里。
     *
     * 只做"改参数"，不重建引擎（换引擎包名那种重活仍然走「重新初始化语音」）。
     * 引擎还没起来时不用管：configure() 会按当前的 cfg 走一遍。
     */
    fun applyLiveConfig() {
        dryRun = !cfg.ttsEnabled
        volume = cfg.ttsVolume.coerceIn(0f, 1f)
        val engine = tts ?: return
        runCatching {
            engine.setSpeechRate(cfg.ttsRate.coerceIn(0.3f, 2.5f))
            engine.setPitch(cfg.ttsPitch.coerceIn(0.3f, 2.5f))
            val wanted = cfg.ttsVoice
            // 音色：只在真的变了的时候去枚举 voices（枚举有开销，拖滑杆时不该每次都做）
            if (wanted == lastAppliedVoice) return@runCatching
            lastAppliedVoice = wanted
            if (wanted.isEmpty()) return@runCatching
            val voices = runCatching { engine.voices }.getOrNull().orEmpty()
            val found = voices.firstOrNull { it.name == wanted }
                ?: voices.firstOrNull { it.name.contains(wanted, ignoreCase = true) }
            if (found != null && engine.voice?.name != found.name) {
                engine.voice = found
                log("[语音] 音色已切换: ${found.name}")
            }
        }.onFailure { log("[语音] 应用语音设置失败: ${it.message}") }
    }

    /** 设置界面用：把语音环境的所有关键信息列出来，出问题一眼能看出卡在哪。 */
    fun diagnostics(): String {
        val audio = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }.getOrNull()
        val musicVolume = audio?.let {
            runCatching { it.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        } ?: -1
        val musicMax = audio?.let {
            runCatching { it.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        } ?: -1
        val notifyVolume = audio?.let {
            runCatching { it.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrDefault(-1)
        } ?: -1
        val notifyMax = audio?.let {
            runCatching { it.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) }.getOrDefault(-1)
        } ?: -1
        return buildString {
            append("引擎: ${engineLabel.ifEmpty { "（未初始化）" }}\n")
            append("状态: ${if (ready) "就绪" else "未就绪"}  后端: $backendName\n")
            append("语言: ${languageNote.ifEmpty { "未知" }}\n")
            append("音色: ${runCatching { tts?.voice?.name }.getOrNull() ?: "默认"}\n")
            append("音频流: ${streamName()}   方式: ${if (cfg.ttsMode == "file") "合成后播放(兼容)" else "引擎直播"}\n")
            append("开关: 静音=$muted 模拟=$dryRun 音量=${(volume * 100).toInt()}%\n")
            append("播报调用: $speakAttempts 次" +
                (if (lastSpeakCode != Int.MIN_VALUE) "，最后一次返回码 $lastSpeakCode" +
                    if (lastSpeakCode == 0) "（成功）" else "（失败）" else "") + "\n")
            append("引擎是否真的开始播: ${if (utteranceStarted) "是" else "否"}\n")
            append("媒体音量: $musicVolume / $musicMax" +
                if (musicVolume == 0) "  ← 媒体音量为 0，先按音量键调大！" else "")
            append("\n")
            append("通知音量: $notifyVolume / $notifyMax" +
                if (notifyVolume == 0 && cfg.ttsStream == "notification") "  ← 音频流选的是通知，但通知音量是 0！" else "")
            append("\n")
            append("系统引擎: " + availableEngines.joinToString(", ").ifEmpty {
                "（一个都没找到）" + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    " ← Android 11+ 软件包可见性问题：清单里必须声明 <queries> TTS_SERVICE（本版已声明）"
                else ""
            })
            append("\n")
            if (lastError.isNotEmpty()) append("上次错误: $lastError")
        }
    }

    private fun streamName(): String = when (cfg.ttsStream) {
        "music" -> "媒体"
        "notification" -> "通知"
        "system" -> "系统"
        "voice" -> "通话"
        else -> "默认（交给引擎）"
    }

    /** 可选音色列表 (调试/设置界面用)。 */
    @Synchronized
    fun voices(): List<Voice> =
        runCatching { tts?.voices?.sortedBy { it.locale.toString() } }.getOrNull().orEmpty()

    /**
     * 播报一句。可以被相机分析线程直接调用 —— 加锁是为了不和 configure() 抢引擎。
     * 静音/关闭时只写日志, 不出声 (与电脑版 --no-tts 的 dry-run 行为一致)。
     */
    @Synchronized
    fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        if (dryRun || muted) {
            log(if (dryRun) "[语音/模拟] $text" else "[语音] (已静音) $text")
            history.addLast(SpeakRecord(System.currentTimeMillis(), text, false, "未播报"))
            trimHistory()
            return false
        }
        val engine = tts
        if (engine == null || !ready) {
            lastError = if (engine == null) "TTS 未初始化" else "TTS 尚未就绪"
            // 引擎还在初始化（不是彻底失败）：把句子攒下来，就绪后补播一次
            if (starting || engine != null) {
                pendingSpeak = text
                pendingSpeakAt = System.currentTimeMillis()
                log("[语音] 引擎还没就绪，先记下这句，就绪后补播: $text")
            } else {
                log("[语音] 播报失败: $lastError")
            }
            history.addLast(SpeakRecord(System.currentTimeMillis(), text, false, lastError))
            trimHistory()
            return false
        }
        val id = UUID.randomUUID().toString()
        utteranceStarted = false
        if (cfg.ttsMode == "file") {
            // 兼容模式：合成到文件再自己播（绕开引擎的播放路由）
            val dir = File(context.cacheDir, "tts")
            dir.mkdirs()
            val target = File(dir, "say_${System.currentTimeMillis()}.wav")
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
            }
            val synth = engine.synthesizeToFile(text, params, target, target.name)
            speakAttempts++
            lastSpeakCode = synth
            lastSpeakText = text
            val synthOk = synth == TextToSpeech.SUCCESS
            if (!synthOk) lastError = "synthesizeToFile 返回 $synth"
            if (synthOk) pendingFiles.addLast(target)
            history.addLast(
                SpeakRecord(System.currentTimeMillis(), text, synthOk, if (synthOk) "已合成待播放" else lastError)
            )
            trimHistory()
            if (!synthOk) log("[语音] 合成失败: $lastError") else log("[语音] 已合成: ${target.name}")
            return synthOk
        }

        // 默认：交给引擎直接播
        // 音频流默认不指定(交给引擎), 也可以在设置里指定成媒体/通知/系统
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
            when (cfg.ttsStream) {
                "music" -> putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                "notification" -> putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION)
                "system" -> putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_SYSTEM)
                "voice" -> putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                else -> Unit      // auto: 不指定, 用引擎默认
            }
        }
        val code = engine.speak(text, TextToSpeech.QUEUE_ADD, params, id)
        speakAttempts++
        lastSpeakCode = code
        lastSpeakText = text
        val ok = code == TextToSpeech.SUCCESS
        if (!ok) {
            lastError = "speak() 返回 $code"
            log("[语音] 播报失败: $lastError")
        }
        history.addLast(SpeakRecord(System.currentTimeMillis(), text, ok, if (ok) "" else lastError))
        trimHistory()
        return ok
    }

    private fun trimHistory() {
        while (history.size > 50) history.removeFirst()
    }

    /**
     * 一键自检：把「引擎能不能合成语音」和「能不能播放」**分开**测，给出确定结论。
     *
     * 为什么要这么测：`speak()` 返回 0 只代表“引擎接受了请求”，它内部是异步的，
     * 没声音时根本分不清是引擎没合成出来、还是合成了但播放路由被静音。
     * 用 `synthesizeToFile` 落一个 wav 文件就能一刀切开：
     *   * 文件出来了（几万字节）→ 引擎没问题，问题在播放环节；
     *   * 文件没出来 → 引擎本身合成失败（缺语音包 / 引擎坏了）。
     *
     * 注意：里面有等待合成完成的 sleep，**必须在后台线程调用**。
     */
    @Synchronized
    fun selfTest(text: String = "测试一下，张三你好"): String {
        val sb = StringBuilder(diagnostics())
        sb.append("\n---- 一键自检 ----\n")
        val engine = tts
        if (engine == null || !ready) {
            sb.append("引擎未就绪，先点「重新初始化语音」再自检\n")
            return sb.toString()
        }

        val dir = File(context.cacheDir, "tts").apply { mkdirs() }
        val file = File(dir, "selftest.wav")
        runCatching { file.delete() }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val code = runCatching { engine.synthesizeToFile(text, params, file, "selftest") }
            .getOrElse { -999 }
        sb.append("① synthesizeToFile 返回码 = $code")
            .append(if (code == TextToSpeech.SUCCESS) "（已接受）" else "（失败）").append('\n')

        var waited = 0
        while (waited < 6000 && (!file.exists() || file.length() < 1000L)) {
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                break
            }
            waited += 200
        }
        val synthOk = file.exists() && file.length() > 1000L
        sb.append("② 合成结果: ")
        if (synthOk) {
            sb.append("${file.length()} 字节（等待 ${waited}ms）→ 引擎能出声，问题在【播放环节】\n")
        } else {
            sb.append("没有生成有效文件（等待 ${waited}ms）→ 引擎【本身合成失败】\n")
        }

        if (synthOk) {
            playFile(file)
            sb.append("③ 已把这个 wav 交给系统播放器播放，请听有没有声音\n")
        } else {
            sb.append("③ 合成失败，跳过播放测试\n")
        }

        val speakCode = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_ADD, params, "selftest_speak")
        }.getOrElse { -999 }
        sb.append("④ 直接 speak() 返回码 = $speakCode")
            .append(if (speakCode == TextToSpeech.SUCCESS) "（已接受）" else "（失败）").append('\n')
        sb.append("⑤ 结论: ")
        sb.append(
            when {
                synthOk && speakCode == TextToSpeech.SUCCESS ->
                    "引擎可用。若②③都没声音 → 查媒体音量 / 音频流 / 蓝牙，或换音色"
                synthOk -> "引擎能合成但 speak() 被拒 → 把「播报方式」改成「合成后播放」"
                else -> "引擎合成失败 → 多半没装中文语音包，或该引擎不可用（点「重新初始化语音」换引擎）"
            }
        ).append('\n')
        lastError = ""
        return sb.toString()
    }

    /** 用 MediaPlayer 播放合成好的 WAV（兼容模式的播放环节）。 */
    private fun playFile(file: File) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setVolume(volume, volume)
                setOnCompletionListener {
                    runCatching { file.delete() }
                }
                setOnErrorListener { _, what, extra ->
                    lastError = "MediaPlayer 播放失败 ($what/$extra)"
                    log("[语音] $lastError")
                    true
                }
                prepare()
                start()
            }
            utteranceStarted = true
        } catch (t: Throwable) {
            lastError = "播放失败: ${t.javaClass.simpleName} ${t.message}"
            log("[语音] $lastError")
        }
    }

    fun sayName(name: String): Boolean =
        speak((cfg.ttsTemplate.ifEmpty { "{name}你好" }).replace("{name}", name))

    /**
     * 启动兜底：过几秒回头看引擎到底起没起来。
     *
     * 有些 ROM 在 App 刚启动时创建的 TTS 引擎 `onInit` 根本不回调（既不成功也不报错），
     * 这时 `starting` 会一直挂着、`ready` 永远是 false，表现就是「一进 App 不播报，
     * 去设置里点一下/开关一下才好」—— 这里自动重来一次，不需要用户去点任何东西。
     *
     * @return 当前是否已经就绪
     */
    @Synchronized
    fun ensureReady(): Boolean {
        if (ready) return true
        if (starting) return false      // 还在初始化，再等等
        log("[语音] 引擎仍未就绪，自动重新初始化一次")
        restart()
        return false
    }

    /** 重新初始化（设置页「重新初始化语音」按钮，用来在装好中文语音包后立刻生效）。 */
    @Synchronized
    fun restart() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        starting = false
        attemptIndex = 0
        lastError = ""
        speakAttempts = 0
        lastSpeakCode = Int.MIN_VALUE
        start()
    }

    @Synchronized
    fun stop() {
        runCatching { tts?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        starting = false
        backendName = "已停止"
    }

    fun status(): String = when {
        dryRun -> "模拟"
        muted -> "静音"
        ready -> backendName
        else -> "未就绪"
    }

    fun debugLog(): Int {
        Log.i(TAG, "语音: ${status()}, 历史 ${history.size} 条")
        return history.size
    }
}
