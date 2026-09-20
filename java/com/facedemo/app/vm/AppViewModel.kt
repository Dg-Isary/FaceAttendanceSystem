package com.facedemo.app.vm

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.facedemo.app.core.AttendanceRecord
import com.facedemo.app.core.AttendanceSession
import com.facedemo.app.core.AttendanceStore
import com.facedemo.app.core.Config
import com.facedemo.app.core.CrashReporter
import com.facedemo.app.core.DataBackup
import com.facedemo.app.core.EnrollResult
import com.facedemo.app.core.EventRecord
import com.facedemo.app.core.Face
import com.facedemo.app.core.FaceEngine
import com.facedemo.app.core.FaceEnroller
import com.facedemo.app.core.GallerySaver
import com.facedemo.app.core.ImageUtil
import com.facedemo.app.core.LogStore
import com.facedemo.app.core.MatchResult
import com.facedemo.app.core.Matting
import com.facedemo.app.core.Models
import com.facedemo.app.core.OpenCv
import com.facedemo.app.core.Roster
import com.facedemo.app.core.RosterParser
import com.facedemo.app.core.Speaker
import com.facedemo.app.core.ThresholdStore
import com.facedemo.app.core.TextCodec
import com.facedemo.app.core.Announcer
import com.facedemo.app.ui.APP_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 画面上的一个人脸框 (坐标已归一化到 0~1, 便于任意尺寸的画布绘制)。 */
data class FaceOverlay(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val name: String,
    val klass: String,
    val similarity: Float,
    val decision: String,
    val accepted: Boolean,
    /** 这次判定用的阈值（可能是这个人的专属阈值） */
    val threshold: Float = 0f,
    /** 用的是个人专属阈值（画面上会标出来） */
    val customThreshold: Boolean = false
)

data class UiState(
    val status: String = "正在初始化…",
    val ready: Boolean = false,
    val cameraStatus: String = "相机未启动",
    val mirrorOverlay: Boolean = false,
    val threshold: Float = FaceEngine.DEFAULT_THRESHOLD,
    val autoThreshold: Boolean = false,
    val faces: List<FaceOverlay> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val fps: Float = 0f,
    val frameIndex: Int = 0,
    val lastSpoken: String = "",
    val attendanceText: String = "",
    val attendanceKind: String = "签到",
    val galleryPhotos: Int = 0,
    val galleryNames: List<String> = emptyList(),
    val rosterCount: Int = 0,
    val classCount: Int = 0,
    /** 名单里还没录入人脸 / 已经录入的人数 */
    val pendingCount: Int = 0,
    val enrolledCount: Int = 0,
    val matting: String = "-",
    val ttsStatus: String = "未就绪",
    val ttsNote: String = "",
    val logs: List<String> = emptyList(),
    val busy: Boolean = false,
    val message: String = "",
    val attendanceRevision: Int = 0,
    val rosterRevision: Int = 0,
    val configRevision: Int = 0,
    /** 每成功录入一个人 +1, 界面据此自动跳下一位待录入 */
    val enrollTick: Int = 0,
    val lastEnrolled: String = "",
    /** 刚保存的录入文件名（用于「重拍」时删掉它） */
    val lastEnrolledFile: String = "",
    /** 当前要录入的人（相机页顶部显示, 名单页点名字也会设置它） */
    val enrollName: String = "",
    val enrollClass: String = "",
    /** 手动保存的快照（识别页快门） */
    val lastSnapshot: String = "",
    val lastSnapshotName: String = "",
    val lastSnapshotSimilarity: Float = 0f,
    val snapshotTick: Int = 0,
    /** 界面缩放（0.75~1.40）。放在 state 里，改完设置页自己也会立刻等比缩放 */
    val uiScale: Float = 1f,
    /** 主题色 key（见 ui/Theme.kt 的 THEME_PRESETS） */
    val themeColor: String = "blue"
)

/**
 * 整个应用的“大脑”, 对应 Python 版 face_demo.py / web_server.py 里的识别循环与各功能串联。
 *
 * 线程约定:
 *   * onFrame 由 CameraController 的分析线程调用 (单线程)
 *   * 引擎/录入相关的重活都在 engineLock 里串行执行, 避免 OpenCV 对象被并发使用
 *     (Python 版 v1.0.2 踩过这个坑: 并发用 FaceDetectorYN 会直接抛 cv2.error)
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "AppViewModel"

        /** 初始化成功的内部标记 (避免和错误信息混淆) */
        private const val READY = "__ready__"
    }

    val config = Config(app)

    val state = MutableStateFlow(UiState())

    private val engineLock = Any()

    private var engine: FaceEngine? = null
    private var roster: Roster? = null
    private var attendance: AttendanceStore? = null
    private var logs: LogStore? = null
    private var speaker: Speaker? = null
    private var matting: Matting? = null
    private var enroller: FaceEnroller? = null
    private var announcer: Announcer? = null

    /** 个人阈值（每人一个专属阈值；留空的人用全局阈值） */
    private var thresholds: ThresholdStore? = null

    /** 最近一帧的副本 (录入/抓拍用); 分析线程写, 主线程读, 用锁保护 */
    private var lastFrame: Bitmap? = null
    private var lastAnalyzeAt = 0L
    private var fpsSmoothed = 0f
    private var previousFrameAt = 0L
    private var frameCounter = 0

    var enrollPreview: Bitmap? = null
        private set

    private val logBuffer = ArrayDeque<String>()
    private val consoleFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    // ==================================================================
    // 初始化
    // ==================================================================
    fun initialize(opencvReady: Boolean = OpenCv.loaded) {
        if (state.value.ready || state.value.busy) return
        // 先把界面缩放按上次保存的值应用上，否则启动时会先按 1.0 显示再跳一下
        state.update { it.copy(busy = true, status = "正在释放模型…", uiScale = config.uiScale, themeColor = config.themeColor) }
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    config.ensureDirs()
                    // v1.1.0：把老默认阈值 0.36 一次性抬到 0.50（只动这一个值，见 Config.migrateDefaults）
                    config.migrateDefaults()
                    val copied = Models.ensureModels(getApplication(), config.modelsDir)
                    if (copied.isNotEmpty()) addLog("[模型] 已释放: ${copied.joinToString(", ")}")
                    addLog("[模型] 目录: ${config.modelsDir.absolutePath}")

                    // 没有 OpenCV 原生库, 后面每一个 Mat/FaceDetectorYN 调用都会抛 Error 直接闪退,
                    // 所以这里必须停下来并把原因显示出来
                    if (!opencvReady && !OpenCv.ensureLoaded()) {
                        addLog("[错误] ${OpenCv.detail}")
                        return@withContext OpenCv.detail
                    }
                    addLog("[OpenCV] ${OpenCv.detail}")

                    val rosterStore = Roster(config) { addLog(it) }
                    val att = AttendanceStore(config) { addLog(it) }
                    att.enabled = config.attendanceEnabled
                    val logStore = LogStore(config) { addLog(it) }
                    logStore.enabled = config.logEnabled
                    val tts = Speaker(getApplication<Application>(), config) { addLog(it) }
                    tts.dryRun = !config.ttsEnabled
                    tts.muted = false
                    tts.volume = config.ttsVolume
                    // 语音引擎一定要在主线程创建: 部分 ROM 在后台线程 new TextToSpeech 会静默失败
                    Handler(Looper.getMainLooper()).post {
                        runCatching { tts.start() }
                            .onFailure { addLog("[语音] TTS 初始化异常: ${it.message}") }
                        // 引擎是**异步**初始化的：启动后回头刷两次状态，
                        // 免得界面一直停在「未就绪」（以前要用户去点一下开关才会刷新）；
                        // 第二次如果还没就绪，就自动重来一次初始化 —— 不用用户做任何操作。
                        Handler(Looper.getMainLooper()).postDelayed({
                            state.update { it.copy(ttsStatus = tts.status()) }
                        }, 1_500)
                        Handler(Looper.getMainLooper()).postDelayed({
                            val ok = runCatching { tts.ensureReady() }.getOrDefault(false)
                            state.update { it.copy(ttsStatus = tts.status()) }
                            val tail = if (ok) "" else "（还没就绪，可到设置→语音播报看诊断）"
                            addLog(
                                "[语音] 启动自检: 状态=${tts.status()}, 就绪=${tts.ready}," +
                                    " 开关=${if (tts.dryRun) "关" else "开"}, 静音=${tts.muted}," +
                                    " 模式=${config.ttsMode}, 引擎=${tts.engineLabel.ifEmpty { "-" }}" + tail
                            )
                        }, 5_000)
                    }
                    val matte = Matting(config.modelsDir) { addLog(it) }
                    val thresholdStore = ThresholdStore(config) { addLog(it) }
                    thresholdStore.load()
                    val eng = FaceEngine(config, rosterStore)

                    synchronized(engineLock) {
                        engine = eng
                        roster = rosterStore
                        attendance = att
                        logs = logStore
                        speaker = tts
                        matting = matte
                        thresholds = thresholdStore
                        rosterStore.syncFromGallery(eng.galleryNames()) { name -> eng.classOf(name) }
                        enroller = FaceEnroller(config, eng, matte, rosterStore) { addLog(it) }
                        announcer = Announcer(config.minHits, config.cooldownSeconds.toDouble(), config.ttsTemplate)
                    }
                    // 人脸库加载单独兜底: 万一某张照片有问题, 引擎本身依然是可用的,
                    // 界面照常工作(阈值/打卡/录入都能用), 只是人脸库为空并给出警告,
                    // 不能因为一张图就让整个 App 停在“初始化失败”。
                    try {
                        synchronized(engineLock) { reloadGalleryLocked() }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.e(TAG, "人脸库加载失败", t)
                        addLog("[错误] 人脸库加载失败: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    READY
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // 注意 catch Throwable: UnsatisfiedLinkError / OutOfMemoryError 这类是 Error,
                    // 用 catch(Exception) 会漏掉, 漏掉就是闪退
                    Log.e(TAG, "初始化失败", t)
                    addLog("[错误] 初始化失败: ${t.javaClass.simpleName}: ${t.message}")
                    "初始化失败: ${t.javaClass.simpleName}: ${t.message}"
                }
            }
            state.update {
                it.copy(
                    busy = false, ready = message == READY,
                    status = if (message == READY) "就绪" else message,
                    matting = matting?.methodName ?: "-",
                    ttsStatus = speaker?.status() ?: it.ttsStatus
                )
            }
        }
    }

    private fun reloadGalleryLocked() {
        val eng = engine ?: return
        eng.loadGallery()
        roster?.syncFromGallery(eng.galleryNames()) { name -> eng.classOf(name) }
        val threshold = resolveThreshold()
        val withPhotos = eng.gallery.map { it.name }.toSet()
        val pending = roster?.students?.keys?.count { !withPhotos.contains(it) } ?: 0
        val enrolled = (roster?.size() ?: 0) - pending
        state.update {
            it.copy(
                galleryPhotos = eng.gallery.size,
                galleryNames = eng.galleryNames(),
                threshold = threshold,
                rosterCount = roster?.size() ?: 0,
                classCount = roster?.classes()?.size ?: 0,
                pendingCount = pending,
                enrolledCount = enrolled,
                rosterRevision = it.rosterRevision + 1,
                attendanceRevision = it.attendanceRevision + 1
            )
        }
        addLog("[识别] 人脸库: ${eng.gallery.size} 张 / ${eng.galleryNames().size} 人, 阈值 " +
            String.format(Locale.US, "%.3f", threshold) + if (config.threshold < 0) " (自动)" else " (手动)")
        for (warning in eng.warnings) addLog("[识别] ! $warning")
    }

    fun reloadGallery() {
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            // 重载失败不能变成闪退: 把原因显示出来即可
            val message = try {
                withContext(Dispatchers.IO) {
                    synchronized(engineLock) { reloadGalleryLocked() }
                }
                "人脸库已重新加载"
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "重载人脸库失败", t)
                addLog("[错误] 重载人脸库失败: ${t.javaClass.simpleName}: ${t.message}")
                "重载失败: ${t.javaClass.simpleName}: ${t.message}"
            }
            state.update { it.copy(busy = false, message = message) }
        }
    }

    /** procWidth / detectorScore 改了要重建引擎 (模型要重新加载)。 */
    fun rebuildEngine() {
        viewModelScope.launch {
            state.update { it.copy(busy = true, status = "正在重建引擎…") }
            val message = try {
                withContext(Dispatchers.IO) {
                    synchronized(engineLock) {
                        val eng = FaceEngine(config, roster ?: Roster(config) { addLog(it) })
                        engine = eng
                        enroller = FaceEnroller(
                            config, eng, matting ?: Matting(config.modelsDir) { addLog(it) },
                            roster
                        ) { addLog(it) }
                    }
                    try {
                        synchronized(engineLock) { reloadGalleryLocked() }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.e(TAG, "重建后人脸库加载失败", t)
                        addLog("[错误] 人脸库加载失败: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
                "引擎已重建"
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "重建引擎失败", t)
                addLog("[错误] 重建引擎失败: ${t.javaClass.simpleName}: ${t.message}")
                "重建失败: ${t.javaClass.simpleName}: ${t.message}"
            }
            state.update {
                it.copy(busy = false, status = if (engine != null) "就绪" else it.status, message = message)
            }
        }
    }

    // ==================================================================
    // 识别主循环 (由相机分析线程调用)
    // ==================================================================
    fun onFrame(bitmap: Bitmap, index: Int) {
        val eng = engine
        val current = state.value
        if (eng == null || !current.ready) return

        val now = System.currentTimeMillis()
        if (now - lastAnalyzeAt < 70L) return          // 最多 ~14 次/秒, 给相机留出余量
        lastAnalyzeAt = now

        val delta = if (previousFrameAt == 0L) 0f else (now - previousFrameAt) / 1000f
        previousFrameAt = now
        if (delta > 1e-6f) {
            fpsSmoothed = if (fpsSmoothed <= 0f) 1f / delta else fpsSmoothed * 0.85f + (1f / delta) * 0.15f
        }

        val threshold = current.threshold
        val pairs: List<Pair<Face, MatchResult>> = try {
            // 每个人的专属阈值在这里生效：没设的人传 null，就用全局阈值
            synchronized(engineLock) {
                eng.analyze(bitmap, threshold) { name -> thresholds?.get(name) }
            }
        } catch (e: Throwable) {
                if (e is CancellationException) throw e
            Log.w(TAG, "识别失败: ${e.message}")
            return
        }

        // 保存一份画面副本 (录入用), 尺寸不大, 只在必要时拷贝
        synchronized(engineLock) {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            lastFrame?.recycle()
            lastFrame = copy
        }

        val overlays = pairs.map { (face, result) ->
            FaceOverlay(
                left = face.x.toFloat() / bitmap.width,
                top = face.y.toFloat() / bitmap.height,
                right = (face.x + face.w).toFloat() / bitmap.width,
                bottom = (face.y + face.h).toFloat() / bitmap.height,
                name = result.name ?: "",
                klass = result.name?.let { eng.classOf(it) } ?: "",
                similarity = result.similarity,
                decision = result.decision,
                accepted = result.accepted,
                threshold = result.threshold,
                customThreshold = result.customThreshold
            )
        }

        // 相似度日志（每行写这个人实际用的阈值，个人阈值一眼能看出来）
        frameCounter++
        logs?.logFrame(frameCounter, pairs, threshold, everyN = 1)

        // 播报 + 打卡 (共用同一个触发点, 与 Python 版一致)
        val accepted = HashMap<String, Float>()
        for ((_, result) in pairs) {
            if (result.accepted && !result.name.isNullOrEmpty()) {
                accepted[result.name] = maxOf(accepted[result.name] ?: 0f, result.similarity)
            }
        }
        raiseAnnouncements(accepted, bitmap)

        state.update {
            it.copy(
                faces = overlays,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                fps = fpsSmoothed,
                frameIndex = frameCounter
            )
        }
    }

    /** 到阈值的人 -> 防抖 -> 播报 + 打卡。 */
    private fun raiseAnnouncements(accepted: Map<String, Float>, frame: Bitmap) {
        val pending = announcer?.update(accepted) ?: return
        for ((name, greeting, similarity) in pending) {
            val snapshot = logs?.snapshot(frame, name, similarity, frameCounter)
            val text = recordAttendance(name, similarity, snapshot) ?: greeting
            if (text.isNotEmpty()) speaker?.speak(text)
            // 顺手刷新一次语音状态：引擎是启动后才就绪的，不刷新界面会一直停在「未就绪」
            state.update { it.copy(lastSpoken = text, ttsStatus = speaker?.status() ?: it.ttsStatus) }
            // 日志里记的阈值也按这个人的实际阈值（有专属阈值时一眼能看出来）
            val used = thresholdFor(name)
            addLog(
                "[播报] $text  (相似度 " + String.format(Locale.US, "%.3f", similarity) +
                    ", 阈值 " + String.format(Locale.US, "%.3f", used) +
                    if (personThreshold(name) != null) " 专属)" else ")"
            )
            logs?.logEvent("语音播报", name, similarity, used,
                "快照: ${snapshot?.let { File(it).name } ?: "未保存"}")
        }
    }

    /** 打卡并返回应该播报的文案; 打卡关闭时返回 null (用普通问候语)。 */
    private fun recordAttendance(name: String, similarity: Float, snapshot: String?): String? {
        val att = attendance ?: return null
        val logStore = logs
        val rosterStore = roster
        if (!att.enabled) return null
        val usedThreshold = thresholdFor(name)
        val klass = rosterStore?.classOf(name).orEmpty().ifEmpty { engine?.classOf(name).orEmpty() }
        if (config.attendanceUnlisted == "ignore" && rosterStore != null && !rosterStore.exists(name)) {
            logStore?.logEvent("未在名单", name, similarity, usedThreshold,
                "只识别不打卡 (attendance_unlisted=ignore)")
            return null
        }
        val result = att.record(
            name = name, similarity = similarity, threshold = usedThreshold,
            source = "摄像头", snapshot = snapshot.orEmpty(), klass = klass
        )
        state.update { it.copy(attendanceRevision = it.attendanceRevision + 1) }
        updateAttendanceText()
        if (result.ok) {
            logStore?.logEvent("打卡成功", name, similarity, usedThreshold,
                "${result.record?.kind} $klass  快照: ${snapshot?.let { File(it).name } ?: "未保存"}")
            return config.ttsCheckinTemplate.replace("{name}", name)
                .replace("{kind}", result.record?.kind ?: att.kind)
        }
        return when (result.reason) {
            // v1.0.3：不再有「一天一次」，被挡下来的只有"同一类型连着来、间隔还不够"
            "cooldown" -> {
                logStore?.logEvent("打卡间隔不够", name, similarity, usedThreshold, result.message)
                config.ttsDuplicateTemplate.replace("{name}", name).replace("{kind}", att.kind)
            }
            // v1.0.7：没签到不许签退 —— 这时候要说清楚原因，不能只回一句「某某你好」
            "no-checkin" -> {
                logStore?.logEvent("签退缺少签到", name, similarity, usedThreshold, result.message)
                config.ttsNoCheckinTemplate.replace("{name}", name).replace("{kind}", att.kind)
            }
            else -> null
        }
    }

    private fun updateAttendanceText() {
        val att = attendance ?: return
        val summary = att.summary()
        var text = "${att.kind}模式 · 今日 ${summary["total"]} 条"
        val last = summary["last"] as String
        if (last.isNotEmpty()) text += " · 最近 $last"
        state.update { it.copy(attendanceText = text, attendanceKind = att.kind) }
    }

    fun onCameraStatus(text: String) {
        state.update { it.copy(cameraStatus = text) }
    }

    fun onLensChanged(isFront: Boolean) {
        state.update { it.copy(mirrorOverlay = isFront) }
    }

    // ==================================================================
    // 阈值 / 设置
    // ==================================================================
    private fun resolveThreshold(): Float {
        val eng = engine
        // v1.1.0：兜底值也跟着默认阈值走（0.36 → 0.50）
        return if (config.threshold < 0f) {
            eng?.autoThreshold ?: FaceEngine.DEFAULT_THRESHOLD
        } else {
            config.threshold
        }
    }

    /** 全局阈值（自动标定值或手动值），界面显示用。 */
    fun globalThreshold(): Float = resolveThreshold()

    /** 这个人实际用的阈值：有专属阈值就用它，否则用全局阈值。 */
    fun thresholdFor(name: String): Float =
        thresholds?.get(name) ?: resolveThreshold()

    /** 这个人的专属阈值（没设为 null）。 */
    fun personThreshold(name: String): Float? = thresholds?.get(name)

    /** 所有设了专属阈值的人（姓名 -> 阈值）。 */
    fun personThresholds(): Map<String, Float> = thresholds?.all() ?: emptyMap()

    /**
     * 设置/清除某个人的专属阈值。
     * @param value null 或超出范围 = 清除（改回用全局阈值）
     */
    fun setPersonThreshold(name: String, value: Float?) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            state.update { it.copy(message = "请先选一个人") }
            return
        }
        val store = thresholds
        if (store == null) {
            state.update { it.copy(message = "存储还没就绪，稍后再试") }
            return
        }
        store.set(clean, value)
        state.update {
            it.copy(
                configRevision = it.configRevision + 1,
                rosterRevision = it.rosterRevision + 1,
                message = if (value == null) "$clean 改回全局阈值" else
                    "$clean 的专属阈值 " + String.format(Locale.US, "%.3f", value)
            )
        }
        addLog(
            if (value == null) "[阈值] $clean -> 全局 " + String.format(Locale.US, "%.3f", resolveThreshold())
            else "[阈值] $clean -> " + String.format(Locale.US, "%.3f", value) + " (专属)"
        )
        logs?.logEvent(
            "个人阈值", clean,
            threshold = value ?: resolveThreshold(),
            detail = if (value == null) "改回全局阈值" else "专属阈值"
        )
    }

    /** 删掉一个名字时，把他的专属阈值一起清掉。 */
    private fun dropPersonThreshold(name: String) {
        val store = thresholds ?: return
        if (store.get(name) == null) return
        store.remove(name)
        addLog("[阈值] 已随 $name 一起删除")
    }

    fun setThreshold(value: Float) {
        config.threshold = value.coerceIn(0.05f, 0.97f)
        val resolved = resolveThreshold()
        state.update { it.copy(threshold = resolved, autoThreshold = false, configRevision = it.configRevision + 1) }
        logs?.logEvent("调整阈值", threshold = resolved, detail = "安卓端手动")
        addLog("[设置] 阈值 -> " + String.format(Locale.US, "%.3f", resolved))
    }

    fun setAutoThreshold() {
        config.threshold = -1f
        val resolved = resolveThreshold()
        state.update { it.copy(threshold = resolved, autoThreshold = true, configRevision = it.configRevision + 1) }
        logs?.logEvent("调整阈值", threshold = resolved, detail = "安卓端自动")
        addLog("[设置] 阈值 -> " + String.format(Locale.US, "%.3f", resolved) + " (自动)")
    }

    /**
     * 修改配置并让界面刷新。
     *
     * v1.0.5：**改完立刻推给正在跑的组件**，不再需要重启 App。
     * 以前 Announcer 是构造时把 minHits/cooldown/template **拷一份**存起来的，
     * 所以改「重新识别间隔」「连续命中帧数」在重启前根本不生效；
     * 语音的语速/音调/音色也一样只在引擎初始化时读一次。现在统一在这里推：
     *   * Announcer：连续命中帧数、重新识别间隔、播报模板（每帧都在读这些字段，改完下一帧就生效）
     *   * Speaker：语速 / 音调 / 音量 / 音色（换引擎包名那种重活仍走「重新初始化语音」）
     *   * 打卡开关、日志开关（这两个本来就有专门方法，顺手一起同步，避免漏）
     *   * 相机分析频率 / 放平暂停由 App.kt 监听 configRevision 处理（之前就是实时的）
     */
    fun updateSettings(block: (Config) -> Unit) {
        block(config)
        val eng = engine
        // ---- 推给运行中的组件（都在同一个分析线程上被读，改完下一帧生效）----
        announcer?.let { a ->
            a.minHits = config.minHits.coerceAtLeast(1)
            a.cooldown = config.cooldownSeconds.toDouble()
            a.template = config.ttsTemplate.ifEmpty { "{name}你好" }
        }
        speaker?.applyLiveConfig()
        attendance?.enabled = config.attendanceEnabled
        logs?.enabled = config.logEnabled
        state.update {
            it.copy(
                threshold = if (eng != null) resolveThreshold() else it.threshold,
                autoThreshold = config.threshold < 0f,
                configRevision = it.configRevision + 1,
                uiScale = config.uiScale,
                themeColor = config.themeColor,
                ttsStatus = speaker?.status() ?: it.ttsStatus
            )
        }
    }

    fun toggleMute(): Boolean {
        val s = speaker ?: return false
        s.muted = !s.muted
        state.update { it.copy(ttsStatus = s.status(), message = if (s.muted) "语音已静音" else "语音已恢复") }
        addLog(if (s.muted) "[语音] 已静音" else "[语音] 已恢复")
        return s.muted
    }

    fun testSpeech() {
        val s = speaker ?: return
        val text = config.ttsTemplate.replace("{name}", "测试")
        addLog("[语音] 试播: $text")
        val ok = s.speak(text)
        state.update { it.copy(ttsStatus = s.status(), message = if (ok) "已发出试播" else "试播失败: ${s.lastError}") }
    }

    // ==================================================================
    // 打卡
    // ==================================================================
    fun setAttendanceKind(kind: String) {
        val att = attendance ?: return
        att.setKind(kind)
        config.attendanceKind = att.kind
        logs?.logEvent("打卡模式", detail = att.kind)
        updateAttendanceText()
        addLog("[打卡] 模式: ${att.kind}")
    }

    fun setAttendanceEnabled(enabled: Boolean) {
        config.attendanceEnabled = enabled
        attendance?.enabled = enabled
        updateAttendanceText()
        addLog(if (enabled) "[打卡] 已开启" else "[打卡] 已关闭")
    }

    /**
     * 手工补签（v1.0.7）。
     *
     * 注意 `kind` 必须传**当前**模式：以前界面传的是 `board.kind`，而 board 是
     * `remember(attendanceRevision, ...)` 缓存的，切模式不会触发重算 ——
     * 结果是"签退切回签到后马上点补签，补的还是签退"，现在由调用方传实时值。
     *
     * 补签也要守规则：**没签到不能补签退**（AttendanceStore 里统一拦，返回 no-checkin）。
     */
    fun manualSign(name: String, kind: String = "") {
        val att = attendance ?: return
        val clean = enroller?.sanitizeName(name).orEmpty()
        if (clean.isEmpty()) {
            state.update { it.copy(message = "请填写姓名") }
            return
        }
        val klass = roster?.classOf(clean).orEmpty().ifEmpty { engine?.classOf(clean).orEmpty() }
        val target = kind.ifEmpty { att.kind }
        val result = att.manual(clean, klass, target)
        logs?.logEvent(
            if (result.ok) "手动补签" else "手动补签被拒",
            clean, 1f, state.value.threshold, "$target $klass ${result.message}"
        )
        state.update {
            it.copy(message = result.message, attendanceRevision = it.attendanceRevision + 1)
        }
        updateAttendanceText()
        addLog("[打卡] ${result.message}")
    }

    /** 今天这个人的原始记录（签到页「管理」对话框用）。 */
    fun recordsOf(name: String): List<AttendanceRecord> = attendance?.recordsOf(name) ?: emptyList()

    /** 今天这个人的打卡段（签到-签退配对）。 */
    fun sessionsOf(name: String): List<AttendanceSession> = attendance?.sessions(name) ?: emptyList()

    /** 这个人现在能不能签退（有没有开着的那段签到）—— 界面用来给按钮加提示/禁用。 */
    fun canCheckOut(name: String): Boolean = attendance?.hasOpenSession(name) ?: false

    /**
     * v1.0.9：删掉今天的一条打卡记录（签到页「管理」里点记录 → 确认）。
     * 只删**这一条**（不是 v1.0.6 删掉的"这个人的这类记录全删"），并记一条事件日志留痕。
     */
    fun deleteRecord(id: Int) {
        val att = attendance ?: return
        val (ok, message) = att.deleteRecord(id)
        logs?.logEvent(if (ok) "删除打卡记录" else "删除打卡记录失败", detail = message)
        state.update {
            it.copy(message = message, attendanceRevision = it.attendanceRevision + 1)
        }
        updateAttendanceText()
        addLog("[打卡] $message")
    }

    // v1.0.6：撤销打卡（cancelSign）连同 AttendanceStore.cancel 一起删掉了。
    // 它删的是"这个人的这类记录**全部**"，一天多次打卡时点一下就把当天所有签到清空；
    // 按钮又按"有没有签到"显示、跟当前模式对不上，所以整条路径都去掉了，只留补签。
    // （v1.0.9 按用户要求补回了"精确删一条"的能力：deleteRecord(id)，入口在「管理」对话框里点记录。）

    fun board(klass: String = ""): Map<String, Any?> {
        return attendance?.board(roster, boardGalleryNames(), klass) ?: emptyMap()
    }

    fun boardCsv(klass: String = ""): String =
        attendance?.boardCsv(roster, boardGalleryNames(), klass) ?: "还没有打卡记录\n"

    /** 看板/点名表用的“有人脸库照片的人”——排除手动删除过的人, 免得他们又冒出来。 */
    private fun boardGalleryNames(): List<String> {
        val names = engine?.galleryNames() ?: emptyList()
        val blocked = roster?.removedNames()?.toSet().orEmpty()
        return if (blocked.isEmpty()) names else names.filterNot { blocked.contains(it) }
    }

    fun onAttendanceChanged() {
        state.update { it.copy(attendanceRevision = it.attendanceRevision + 1) }
        updateAttendanceText()
    }

    // ==================================================================
    // 名单
    // ==================================================================
    fun rosterSummary(): Map<String, Any?> =
        roster?.summary(engine?.galleryNames() ?: emptyList()) ?: emptyMap()

    fun importRosterText(text: String, klass: String = ""): String {
        val store = roster ?: return "名单未就绪"
        val stats = store.importText(text, klass = klass)
        val skipped = stats["skipped"] as Int
        val message = "导入 ${stats["total"]} 行: 新增 ${stats["added"]} 人, 更新 ${stats["updated"]} 人" +
            if (skipped > 0) ", 跳过 $skipped 行" else ""
        state.update { it.copy(message = message, rosterRevision = it.rosterRevision + 1) }
        reloadGallery()
        return message
    }

    /** 从手机里选 CSV/TXT 文件导入名单 (自动识别 UTF-8 / GBK / UTF-16 编码)。 */
    fun importRosterFile(uri: Uri) {
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            val message = withContext(Dispatchers.IO) {
                try {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext "读取失败: 打不开文件"
                    val decoded = TextCodec.decode(bytes)
                    val preview = decoded.text.split("\n").take(3).joinToString(" / ") { it.trim() }
                    addLog("[名单] 文件编码识别为 ${decoded.encoding}")
                    addLog("[名单] 前几行: ${preview.take(120)}")
                    val store = roster ?: return@withContext "名单未就绪"
                    val stats = store.importText(decoded.text)
                    val text = "编码 ${decoded.encoding}: 导入 ${stats["total"]} 行, 新增 ${stats["added"]} 人, " +
                        "更新 ${stats["updated"]} 人"
                    state.update { it.copy(rosterRevision = it.rosterRevision + 1) }
                    text
                } catch (e: Throwable) {
                if (e is CancellationException) throw e
                    "导入失败: ${e.message}"
                }
            }
            state.update { it.copy(busy = false, message = message) }
            addLog("[名单] $message")
            reloadGallery()
        }
    }

    fun addStudent(name: String, klass: String, studentId: String = "", note: String = "") {
        val store = roster ?: return
        val clean = name.trim()
        if (clean.isEmpty()) {
            state.update { it.copy(message = "姓名不能为空") }
            return
        }
        store.add(clean, klass.trim().ifEmpty { Roster.UNASSIGNED }, studentId, note)
        state.update { it.copy(message = "已添加 $clean", rosterRevision = it.rosterRevision + 1) }
        reloadGallery()
    }

    /** 从名单移除；deletePhotos=true 时连他的人脸库照片一起删掉。 */
    fun removeStudent(name: String, deletePhotos: Boolean = false) {
        val store = roster ?: return
        val existed = store.exists(name)
        store.remove(name)
        // 名单里本来就没有(只在人脸库里有照片)的人, 也要记住“别再自动入册”
        store.blockAutoEnroll(name)
        // 他的专属阈值也一起清掉，免得以后同名的人莫名拿到旧阈值
        dropPersonThreshold(name)
        var removedPhotos = 0
        if (deletePhotos) {
            val files = enroller?.personFiles()?.get(name).orEmpty()
            for (file in files) {
                val (ok, _) = enroller?.deletePhoto(file) ?: (false to "")
                if (ok) removedPhotos++
            }
        }
        val detail = buildString {
            append(name)
            if (!existed) append(" (本来就不在名单里)")
            if (deletePhotos) append(" 同时删除 $removedPhotos 张照片")
        }
        logs?.logEvent("删除名单", detail = detail)
        addLog("[名单] 删除 $detail")
        state.update {
            it.copy(
                message = "已删除 $name" + if (deletePhotos) "，并删除 $removedPhotos 张照片" else "（照片保留）",
                rosterRevision = it.rosterRevision + 1
            )
        }
        reloadGallery()
    }

    fun setStudentClass(name: String, klass: String) {
        val store = roster ?: return
        store.setClass(name, klass)
        state.update { it.copy(message = "$name -> ${klass.ifEmpty { Roster.UNASSIGNED }}", rosterRevision = it.rosterRevision + 1) }
        reloadGallery()
    }

    fun setStudentId(name: String, studentId: String) {
        val store = roster ?: return
        store.setStudentId(name, studentId)
        state.update {
            it.copy(
                message = "$name 的学号已更新",
                rosterRevision = it.rosterRevision + 1,
                attendanceRevision = it.attendanceRevision + 1
            )
        }
    }

    /** 被手动删除、当前不会被自动入册的人。 */
    fun removedNames(): List<String> = roster?.removedNames() ?: emptyList()

    /** 清掉“已删除”记录，让有人脸照片的人重新自动入册。 */
    fun restoreAutoEnroll() {
        val store = roster ?: return
        val count = store.clearRemoved()
        state.update { it.copy(message = "已恢复自动入册（$count 人）", rosterRevision = it.rosterRevision + 1) }
        addLog("[名单] 已恢复自动入册，共 $count 人")
        reloadGallery()
    }

    fun reloadRoster() {
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            val message = try {
                withContext(Dispatchers.IO) {
                    synchronized(engineLock) {
                        roster?.load()
                        // 名单换了，个人阈值文件也可能被人工改过，一起重读
                        runCatching { thresholds?.load() }
                        try {
                            reloadGalleryLocked()
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            Log.e(TAG, "重载名单后加载人脸库失败", t)
                            addLog("[错误] 人脸库加载失败: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
                "名单已重载"
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "重载名单失败", t)
                addLog("[错误] 重载名单失败: ${t.javaClass.simpleName}: ${t.message}")
                "重载名单失败: ${t.javaClass.simpleName}: ${t.message}"
            }
            state.update { it.copy(busy = false, message = message) }
        }
    }

    // ==================================================================
    // 人脸库 / 录入
    // ==================================================================
    fun galleryPhotos(): Map<String, List<String>> = enroller?.personFiles() ?: emptyMap()

    fun deletePhoto(fileName: String) {
        val (ok, message) = enroller?.deletePhoto(fileName) ?: (false to "录入模块未就绪")
        state.update { it.copy(message = message) }
        addLog("[人脸库] $message")
        if (ok) reloadGallery()
        logs?.logEvent("删除照片", detail = fileName)
    }

    /**
     * 从相册选图导入人脸库。
     *
     * `process = false`：**原样拷贝**（文件名即人名，不做任何处理）；
     * `process = true` ：走完整录入流水线 —— 抠像 + 按当前设置合成一寸照。
     *
     * 之前只有“原样拷贝”这一条路，从相册导入后发现背景没被换掉，很容易以为
     * “背景设置无效”。现在两条路都摆出来，按钮上写清楚各自做什么。
     */
    fun importPhotos(uris: List<Uri>, process: Boolean = false) {
        if (uris.isEmpty()) return
        if (process) {
            enrollFromImages(uris, state.value.enrollClass)
            return
        }
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = "正在导入 ${uris.size} 张…") }
            val message = withContext(Dispatchers.IO) {
                var ok = 0
                var failed = 0
                for (uri in uris) {
                    try {
                        val name = File(displayName(uri)).name
                        val bytes = getApplication<Application>().contentResolver
                            .openInputStream(uri)?.use { it.readBytes() }
                        if (bytes == null) {
                            failed++
                            continue
                        }
                        val target = File(config.photoDir, name)
                        target.writeBytes(bytes)
                        ok++
                        addLog("[人脸库] 已导入 $name")
                    } catch (e: Throwable) {
                if (e is CancellationException) throw e
                        failed++
                        addLog("[人脸库] ! 导入失败: ${e.message}")
                    }
                }
                "导入完成: $ok 张" + if (failed > 0) ", 失败 $failed 张" else ""
            }
            state.update { it.copy(busy = false, message = message) }
            reloadGallery()
        }
    }

    private fun displayName(uri: Uri): String {
        var name = ""
        try {
            getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index) ?: ""
            }
        } catch (e: Throwable) {
                if (e is CancellationException) throw e
            Log.w(TAG, "读取文件名失败: ${e.message}")
        }
        if (name.isEmpty()) name = "import_${System.currentTimeMillis()}.jpg"
        if (!Models.isImage(name)) name = "$name.jpg"
        return name
    }

    /** 从当前画面录入 (抠像去背景)。 */
    fun enrollFromFrame(name: String, klass: String) {
        val target = name.trim()
        val targetClass = klass.trim()
        setEnrollTarget(target, targetClass, announce = false)
        if (target.isEmpty()) {
            state.update { it.copy(message = "请先选要录入的人") }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = "正在抠像，合成一寸照…") }
            val result = withContext(Dispatchers.IO) {
                try {
                    synchronized(engineLock) {
                        // 在锁内取帧并立刻拷贝: 相机的分析线程也在锁内回收上一帧
                        val source = lastFrame
                        if (source == null || source.isRecycled) {
                            EnrollResult(false, name, "还没有画面, 稍后再试")
                        } else {
                            val copy = source.copy(Bitmap.Config.ARGB_8888, false)
                            val outcome = enroller?.enrollFrame(copy, name, source = "安卓摄像头", klass = klass)
                            copy.recycle()
                            outcome
                        }
                    }
                } catch (e: Throwable) {
                if (e is CancellationException) throw e
                    Log.e(TAG, "录入失败", e)
                    EnrollResult(false, name, "录入失败: ${e.message}")
                }
            }
            finishEnroll(result)
        }
    }

    /** 从相册选一张照片录入 (抠像后存进人脸库)。 */
    fun enrollFromImage(uri: Uri, name: String, klass: String) {
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = "正在处理图片…") }
            val result = withContext(Dispatchers.IO) {
                try {
                    val bytes = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) return@withContext EnrollResult(false, name, "打不开图片")
                    val bitmap = ImageUtil.decodeScaled(bytes)
                        ?: return@withContext EnrollResult(false, name, "无法解码图片")
                    synchronized(engineLock) {
                        val outcome = enroller?.enrollFrame(
                            bitmap, name.ifEmpty { displayName(uri).substringBeforeLast('.') },
                            source = displayName(uri), klass = klass
                        )
                        bitmap.recycle()
                        outcome
                    }
                } catch (e: Throwable) {
                if (e is CancellationException) throw e
                    EnrollResult(false, name, "录入失败: ${e.message}")
                }
            }
            finishEnroll(result)
        }
    }

    /** 从相册批量录入 (对每张图做抠像, 人名取文件名, 相当于电脑版的 --enroll-from)。 */
    fun enrollFromImages(uris: List<Uri>, klass: String) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = "正在批量抠像录入 ${uris.size} 张…") }
            val message = withContext(Dispatchers.IO) {
                var ok = 0
                var failed = 0
                for (uri in uris) {
                    try {
                        val fileName = File(displayName(uri)).name
                        val person = fileName.substringBeforeLast('.')
                        val bytes = getApplication<Application>().contentResolver
                            .openInputStream(uri)?.use { it.readBytes() }
                        val bitmap = bytes?.let { ImageUtil.decodeScaled(it) }
                        if (bitmap == null) {
                            failed++
                            addLog("[录入] ! 无法读取 $fileName")
                            continue
                        }
                        val outcome = synchronized(engineLock) {
                            enroller?.enrollFrame(bitmap, person, source = fileName, klass = klass)
                        }
                        bitmap.recycle()
                        if (outcome?.ok == true) ok++ else failed++
                        addLog("[录入] ${outcome?.message ?: "录入模块未就绪"}")
                    } catch (e: Throwable) {
                if (e is CancellationException) throw e
                        failed++
                        addLog("[录入] ! ${e.message}")
                    }
                }
                "批量录入完成: 成功 $ok 张" + if (failed > 0) ", 失败 $failed 张" else ""
            }
            state.update {
                it.copy(busy = false, message = message, rosterRevision = it.rosterRevision + 1)
            }
            logs?.logEvent("批量录入", detail = message)
            reloadGallery()
        }
    }

    private fun finishEnroll(result: EnrollResult?) {
        if (result == null) {
            state.update { it.copy(busy = false, message = "录入模块未就绪") }
            return
        }
        // 旧预览位图不主动 recycle: 界面可能还在用, 交给 GC 回收更安全
        enrollPreview = null
        if (result.ok && result.saved.isNotEmpty()) {
            val file = File(config.photoDir, result.saved.first())
            enrollPreview = ImageUtil.decodeFile(file, 900)
        }
        state.update {
            it.copy(
                busy = false,
                message = result.message,
                rosterRevision = it.rosterRevision + 1,
                configRevision = it.configRevision + 1,
                enrollTick = if (result.ok) it.enrollTick + 1 else it.enrollTick,
                lastEnrolled = if (result.ok) result.name else it.lastEnrolled,
                lastEnrolledFile = if (result.ok) result.saved.firstOrNull().orEmpty() else ""
            )
        }
        logs?.logEvent("人脸录入", result.name, threshold = state.value.threshold, detail = result.message)
        reloadGallery()
        // 录完自动选下一位
        if (result.ok && config.enrollAutoNext) {
            nextPendingPerson(result.name)?.let { next ->
                setEnrollTarget(next.name, next.klass, announce = false)
            }
        }
    }

    /**
     * 撤销刚录入的这张照片，方便「重拍」。
     *
     * 注意：成功录入后如果开着「录完自动选下一位」，当前对象已经跳到下一个人了；
     * 这时点「重拍」必须**把当前对象拨回刚被删掉的那个人**，否则再按快门就会把
     * 张三的照片记到李四名下 —— 之前就是这个逻辑错了。
     */
    fun discardLastEnroll() {
        val fileName = state.value.lastEnrolledFile
        val person = state.value.lastEnrolled
        if (fileName.isEmpty()) {
            state.update { it.copy(message = "没有可撤销的录入") }
            return
        }
        val (ok, message) = enroller?.deletePhoto(fileName) ?: (false to "录入模块未就绪")
        enrollPreview = null
        val backClass = roster?.classOf(person).orEmpty().ifEmpty { state.value.enrollClass }
        state.update {
            it.copy(
                message = if (ok) "已删除 $fileName，当前对象改回 $person，可以重拍" else message,
                lastEnrolledFile = "",
                enrollName = if (ok && person.isNotEmpty()) person else it.enrollName,
                enrollClass = if (ok && person.isNotEmpty()) backClass else it.enrollClass,
                rosterRevision = it.rosterRevision + 1
            )
        }
        addLog(
            "[录入] 重拍: $message" +
                if (ok && person.isNotEmpty()) "（当前对象已改回 $person）" else ""
        )
        if (ok) reloadGallery()
    }

    /** 选中要录入的人（姓名 + 班级/备注），相机页顶部和名单页点名字都用它。 */
    fun setEnrollTarget(name: String, klass: String, announce: Boolean = true) {
        state.update { it.copy(enrollName = name, enrollClass = klass) }
        if (announce && name.isNotEmpty()) addLog("[录入] 当前对象: $name" + if (klass.isNotEmpty()) " ($klass)" else "")
    }

    /** 名单里的一个人 + 已有照片数（v1.0.7 起带上备注，签到页的「管理」对话框要显示）。 */
    data class PersonRow(
        val name: String,
        val klass: String,
        val studentId: String,
        val photos: Int,
        val note: String = ""
    ) {
        val enrolled: Boolean get() = photos > 0
    }

    /** 名单全部人（按班级 + 姓名排序），带照片数。 */
    fun people(): List<PersonRow> {
        val store = roster ?: return emptyList()
        val photos = enroller?.personFiles() ?: emptyMap()
        return store.sortedStudents().map { student ->
            PersonRow(
                name = student.name,
                klass = student.klass,
                studentId = student.studentId,
                photos = photos[student.name]?.size ?: 0,
                note = student.note
            )
        }
    }

    fun pendingPeople(): List<PersonRow> = people().filter { !it.enrolled }

    /** 下一个还没录入的人（跳过刚录完的那个）。 */
    private fun nextPendingPerson(after: String): PersonRow? {
        val waiting = pendingPeople().sortedWith(compareBy({ it.klass }, { it.name }))
        return waiting.firstOrNull { it.name != after } ?: waiting.firstOrNull()
    }

    /** 「下一位待录入」。 */
    fun pickNextPending(advance: Boolean = true) {
        val next = nextPendingPerson(state.value.lastEnrolled)
        if (next == null) {
            state.update { it.copy(message = "名单里的人都录完了") }
            return
        }
        setEnrollTarget(next.name, next.klass)
        if (advance) state.update { it.copy(message = "下一位: ${next.name}") }
    }

    // ==================================================================
    // 日志 / 报告
    // ==================================================================
    fun events(since: Int = 0): List<EventRecord> = logs?.recentEvents(since, 120) ?: emptyList()

    fun report(): String = logs?.report() ?: "日志模块未就绪"

    fun logPaths(): String = logs?.pathsText() ?: "-"

    fun snapshots(): List<File> = logs?.listSnapshots() ?: emptyList()

    /** 系统可选音色 (名称 -> 语言)。 */
    fun voiceOptions(): List<Pair<String, String>> =
        speaker?.voices()?.map { it.name to it.locale.toString() } ?: emptyList()

    fun voiceNote(): String = speaker?.languageNote ?: ""

    /** 抠像当前用的实现名（界面上显示"抠像 开 · pphumanseg"）。 */
    fun mattingMethodName(): String = when {
        matting == null -> "未就绪"
        !config.mattingEnabled -> "已关闭"
        matting?.modelLoaded == true -> "人像分割模型"
        else -> "GrabCut（模型未加载）"
    }

    /** 抠像自检的报告。 */
    var mattingReport: String? = null
        private set

    fun clearMattingReport() {
        mattingReport = null
        state.update { it.copy(configRevision = it.configRevision + 1) }
    }

    /**
     * 抠像自检：拿最近一帧画面跑一遍抠像，报出「模型有没有加载 / 走的哪条路 /
     * 前景占比 / 耗时」。用来确认「为什么抠出来是个方块」。
     */
    fun runMattingSelfTest() {
        val matte = matting
        val eng = engine
        if (matte == null || eng == null) {
            state.update { it.copy(message = "抠像模块还没就绪") }
            return
        }
        val frame = synchronized(engineLock) {
            val source = lastFrame
            if (source == null || source.isRecycled) null else source.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (frame == null) {
            state.update { it.copy(message = "还没有画面：先到「识别」页让相机跑一会儿再来自检") }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = "正在做抠像自检…") }
            val report = withContext(Dispatchers.IO) {
                try {
                    val faces = eng.detect(frame)
                    val face = faces.maxByOrNull { it.w * it.h }
                    val box = face?.let { eng.faceRect(it, config.enrollExpand, frame.width, frame.height) }
                    val patch = if (box != null) {
                        Bitmap.createBitmap(frame, box.x, box.y, box.width, box.height)
                    } else {
                        frame
                    }
                    val local = if (face != null && box != null) {
                        org.opencv.core.Rect(face.x - box.x, face.y - box.y, face.w, face.h)
                    } else {
                        null
                    }
                    val text = matte.selfTest(
                        patch, local, config.mattingErode, config.mattingFeather, config.mattingEnabled
                    ) + if (face == null) "\n注意：这一帧没检测到人脸，用的是整幅画面。\n" else ""
                    if (patch !== frame) patch.recycle()
                    text
                } catch (t: Throwable) {
                    "自检异常: ${t.javaClass.simpleName} ${t.message}"
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                }
            }
            mattingReport = report
            addLog("[抠像] 自检: " + report.replace("\n", " | "))
            state.update {
                it.copy(busy = false, message = "抠像自检完成", configRevision = it.configRevision + 1)
            }
        }
    }

    /** 语音自检信息（引擎、音色、语言、媒体音量、上次错误）。 */
    fun speechDiagnostics(): String = speaker?.diagnostics() ?: "语音模块未就绪"

    /** 一键自检的报告（跑完放在这里，设置页弹出来可看可分享）。 */
    var speechReport: String? = null
        private set

    fun clearSpeechReport() {
        speechReport = null
        state.update { it.copy(configRevision = it.configRevision + 1) }
    }

    /**
     * 一键语音自检：在后台线程跑（里面有等待合成完成的等待），
     * 结论会明确区分「引擎不能合成」和「能合成但播放没声」。
     */
    fun runSpeechSelfTest() {
        val s = speaker ?: return
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = "正在做语音自检（约 3 秒）…") }
            val report = withContext(Dispatchers.IO) {
                runCatching { s.selfTest() }
                    .getOrElse { "自检异常: ${it.javaClass.simpleName} ${it.message}" }
            }
            speechReport = report
            addLog("[语音] 自检完成")
            state.update {
                it.copy(
                    busy = false,
                    message = "语音自检完成，可点「分享自检结果」发我",
                    configRevision = it.configRevision + 1
                )
            }
        }
    }

    /**
     * 把诊断信息 + 运行日志导成一个 txt 文件（放外部私有目录，文件管理器/电脑都能取），
     * 返回文件路径。用户直接把这个文件发出来即可定位问题。
     */
    fun exportDiagnostics(): File? {
        return try {
            val text = buildString {
                append("人脸识别 安卓版 $APP_VERSION\n")
                append("机型: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                append("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
                append("架构: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}\n\n")
                append("---- 语音自检 ----\n")
                append(speechReport ?: speechDiagnostics())
                append("\n\n---- 运行日志（最近 ${state.value.logs.size} 条）----\n")
                append(state.value.logs.joinToString("\n"))
                append("\n")
            }
            val app = getApplication<Application>()
            val dir = app.getExternalFilesDir(null) ?: app.filesDir
            val file = File(dir, "诊断_${System.currentTimeMillis()}.txt")
            file.writeText(text, Charsets.UTF_8)
            addLog("[诊断] 已导出 ${file.absolutePath}")
            state.update { it.copy(message = "诊断已导出：${file.name}") }
            file
        } catch (t: Throwable) {
            addLog("[诊断] 导出失败: ${t.message}")
            state.update { it.copy(message = "导出失败: ${t.message}") }
            null
        }
    }

    /** 重新初始化语音（装好中文语音包后可以立刻生效）。 */
    fun restartSpeech() {
        val s = speaker ?: return
        addLog("[语音] 重新初始化…")
        Handler(Looper.getMainLooper()).post {
            runCatching { s.restart() }
                .onFailure { addLog("[语音] 重新初始化失败: ${it.message}") }
        }
        state.update { it.copy(message = "正在重新初始化语音…", configRevision = it.configRevision + 1) }
    }

    fun setSpeechVolume(value: Float) {
        updateSettings { cfg -> cfg.ttsVolume = value.coerceIn(0f, 1f) }
        speaker?.volume = value.coerceIn(0f, 1f)
    }

    // ==================================================================
    // 识别页: 手动保存当前画面
    // ==================================================================
    /**
     * 保存当前画面（识别页快门）。
     *
     * 文件名里带上识别到的名字与相似度，方便回头翻；同时写一条事件日志。
     * 保存后界面上会出现结果卡，可以一键「加进人脸库」——这就是老师最常用的
     * “遇到没录入的学生，抓一张存下来”的流程。
     */
    fun saveSnapshot() {
        val frame = synchronized(engineLock) {
            val source = lastFrame
            if (source == null || source.isRecycled) null else source.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (frame == null) {
            state.update { it.copy(message = "还没有画面, 稍后再试") }
            return
        }
        val best = state.value.faces.maxByOrNull { it.similarity }
        val name = when {
            best == null -> "无脸"
            best.name.isBlank() -> "陌生人"
            else -> best.name
        }
        val similarity = best?.similarity ?: 0f
        val path = logs?.snapshot(frame, name, if (best != null) similarity else null, frameCounter)
        frame.recycle()
        if (path == null) {
            state.update { it.copy(message = "保存失败: 快照目录不可写") }
            return
        }
        logs?.logEvent(
            "手动快照", if (best == null || best.name.isBlank()) "" else best.name, similarity,
            state.value.threshold, "文件 ${File(path).name}"
        )
        addLog("[快照] 已保存 ${File(path).name}")
        state.update {
            it.copy(
                lastSnapshot = path,
                lastSnapshotName = name,
                lastSnapshotSimilarity = similarity,
                snapshotTick = it.snapshotTick + 1,
                message = "已保存快照: ${File(path).name}"
            )
        }
    }

    /** 快照目录里的最近几张（设置页可查看数量）。 */
    fun snapshotCount(): Int = logs?.listSnapshots(500)?.size ?: 0

    /** 把刚保存的快照当成这个人的人脸库照片（识别页结果卡里的「加进人脸库」）。 */
    fun enrollFromCurrentFrame(name: String, klass: String) = enrollFromFrame(name, klass)

    /** OpenCV 原生库状态 (设置页显示, 出问题时一眼能看出是加载失败还是别的原因)。 */
    fun openCvDetail(): String = OpenCv.detail

    /** 当前录入输出的规格说明（设置页顶部摘要、录入页提示都用它）。 */
    fun outputSpecSummary(): String {
        val bg = FaceEnroller.backgroundName(config.mattingBackground)
        return if (config.enrollOutput == FaceEnroller.OUTPUT_CUTOUT) {
            "原始抠像 · $bg · 按人脸框裁切，不做尺寸规范"
        } else {
            "标准一寸照 25×35mm（${FaceEnroller.ID_PHOTO_WIDTH}×${FaceEnroller.ID_PHOTO_HEIGHT} 像素）· $bg"
        }
    }

    /** 上一次崩溃日志。 */
    fun lastCrash(): String = CrashReporter.lastCrash(getApplication())

    fun clearCrash() {
        CrashReporter.clear(getApplication())
        state.update { it.copy(configRevision = it.configRevision + 1, message = "崩溃日志已清除") }
    }

    // ==================================================================
    // 数据备份 / 恢复
    // ==================================================================
    /** 导出全部数据（名单 + 人脸库 + 日志/快照）为 zip，放在缓存目录里供分享。 */
    fun exportBackup(): File? {
        val file = DataBackup.export(getApplication(), config, APP_VERSION)
        state.update {
            it.copy(message = if (file == null) "导出失败" else "已导出: ${file.name}")
        }
        addLog(if (file == null) "[备份] 导出失败" else "[备份] 已导出 ${file.name} (${file.length() / 1024} KB)")
        logs?.logEvent("导出备份", detail = file?.name ?: "失败")
        return file
    }

    /** 从 zip 恢复（设置页按钮 / 系统「打开方式」都走这里）。 */
    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            state.update { it.copy(busy = true, message = "正在恢复数据…") }
            val result = withContext(Dispatchers.IO) {
                val r = DataBackup.restore(getApplication(), config, uri)
                // 备份包里可能带了 person_thresholds.csv，恢复后重新读一遍
                runCatching { thresholds?.load() }
                r
            }
            addLog("[备份] ${result.message}")
            logs?.logEvent("恢复备份", detail = result.message)
            state.update {
                it.copy(
                    busy = false,
                    message = result.message,
                    rosterRevision = it.rosterRevision + 1,
                    configRevision = it.configRevision + 1
                )
            }
            reloadGallery()
        }
    }

    /** 名单导出成 CSV 文本（表头 + 全部人，UTF-8 BOM，Excel 直接打开不乱码）。 */
    fun rosterCsvText(): String {
        val store = roster ?: return "还没有名单\n"
        val sb = StringBuilder("\uFEFF")
        sb.append(TextCodec.joinCsv(RosterParser.HEADER)).append("\r\n")
        store.sortedStudents().forEach { student ->
            sb.append(TextCodec.joinCsv(student.toRow())).append("\r\n")
        }
        return sb.toString()
    }

    /**
     * 一键导出「诊断包」：把诊断文本 + 最近一次录入的三张过程图（原图/掩码/抠像结果）
     * 打成一个 zip。用户点一下、分享出来，开发者就能直接看到抠像到底发生了什么 ——
     * 比让用户自己去翻 `/sdcard/Android/data/...` 靠谱得多。
     */
    fun exportDiagnosticsBundle(): File? {
        return try {
            val app = getApplication<Application>()
            val dir = File(app.cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
            val zip = File(dir, "诊断包_$stamp.zip")
            val text = buildString {
                append("人脸识别 安卓版 $APP_VERSION 诊断包\n")
                append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}\n")
                append("机型: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                append("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
                append("架构: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}\n")
                append("抠像设置: 开关=${config.mattingEnabled}, 收紧=${config.mattingErode}%, " +
                    "羽化=${config.mattingFeather}%, 输出=${config.enrollOutput}, 底色=${config.mattingBackground}\n")
                append("\n---- 语音自检 ----\n")
                append(speechReport ?: speechDiagnostics())
                append("\n\n---- 运行日志（最近 ${state.value.logs.size} 条）----\n")
                append(state.value.logs.joinToString("\n"))
                append("\n\n---- 崩溃日志 ----\n")
                append(lastCrash().ifEmpty { "（没有崩溃记录）" })
                append("\n")
            }
            var images = 0
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip).buffered()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("诊断.txt"))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                // 最近一次录入的三张过程图（原图 / 掩码 / 抠像结果）
                val debug = config.debugDir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(6)
                    .orEmpty()
                for (image in debug) {
                    try {
                        zos.putNextEntry(java.util.zip.ZipEntry("录入过程图/${image.name}"))
                        image.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        images++
                    } catch (t: Throwable) {
                        Log.w(TAG, "打包 ${image.name} 失败: ${t.message}")
                    }
                }
                if (images == 0) {
                    zos.putNextEntry(java.util.zip.ZipEntry("录入过程图/README.txt"))
                    zos.write(
                        ("还没有录入过程图。\n" +
                            "录入一次人脸后会在这里生成：\n" +
                            "  xxx_1原图.jpg  取景框里的原图\n" +
                            "  xxx_2掩码.png  抠像掩码（白=人像、黑=背景）\n" +
                            "  xxx_3抠像.jpg  抠像结果\n" +
                            "也可以直接去 ${config.debugDir.absolutePath} 取。\n").toByteArray(Charsets.UTF_8)
                    )
                    zos.closeEntry()
                }
            }
            addLog("[诊断] 已导出诊断包 ${zip.name}（含 $images 张过程图）")
            state.update { it.copy(message = "诊断包已导出：${zip.name}") }
            zip
        } catch (t: Throwable) {
            Log.e(TAG, "导出诊断包失败", t)
            state.update { it.copy(message = "导出诊断包失败: ${t.message}") }
            null
        }
    }

    /** 系统「打开方式」进来的文件：按扩展名分发（zip → 数据恢复，csv/txt → 名单导入）。 */
    fun onExternalFileOpened(uri: Uri, displayName: String) {
        val lower = displayName.lowercase(Locale.US)
        addLog("[打开方式] 收到文件: $displayName")
        state.update { it.copy(message = "收到文件 $displayName") }
        when {
            lower.endsWith(".zip") -> restoreBackup(uri)
            lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv") ->
                importRosterFile(uri)
            else -> state.update {
                it.copy(message = "不认识这个文件类型：$displayName（支持 .zip 备份 / .csv .txt 名单）")
            }
        }
    }

    /** 取一个 uri 的显示文件名（供外部打开时判断类型）。 */
    fun nameOf(uri: Uri): String = displayName(uri)

    /** 设置界面要直接调 Speaker 的开关 (静音/模拟)。 */
    fun speakerRef(): Speaker? = speaker

    // ==================================================================
    // v1.0.2 新增：多日打卡导出 / 人像打包 / 照片存相册
    // ==================================================================

    /** 所有日期的原始打卡记录合并成一张 CSV（最前面多一列「日期」）。 */
    fun allAttendanceCsv(): String {
        val text = attendance?.allRecordsCsv() ?: "还没有打卡记录\n"
        addLog("[打卡] 导出全部记录（${attendance?.attendanceDays()?.size ?: 0} 天）")
        return text
    }

    /** 所有日期的「签到-签退」配对明细 CSV（一天多次打卡就是多行）。 */
    fun allSessionsCsv(): String {
        val text = attendance?.allSessionsCsv() ?: "还没有打卡记录\n"
        addLog("[打卡] 导出配对明细")
        return text
    }

    /** 只打包人脸库照片（zip，附人像清单.csv）。 */
    fun exportPhotosZip(): File? {
        val app = getApplication<Application>()
        val file = DataBackup.exportPhotos(app, config, APP_VERSION)
        state.update {
            it.copy(message = if (file == null) "人像打包失败" else "人像压缩包已生成：${file.name}")
        }
        addLog(if (file == null) "[人脸库] 人像打包失败" else "[人脸库] 人像压缩包 ${file.name}")
        return file
    }

    /** 把某个人的第一张人脸库照片保存到系统相册（Android 10+ 不需要任何权限）。 */
    fun savePhotoToLocal(fileName: String): Pair<Boolean, String> {
        val app = getApplication<Application>()
        val file = File(config.photoDir, fileName)
        val result = GallerySaver.saveImage(app, file, fileName)
        state.update { it.copy(message = result.second) }
        addLog("[人脸库] ${result.second}")
        return result
    }

    fun photoDirPath(): String = config.photoDir.absolutePath

    fun rosterPath(): String = config.rosterFile.absolutePath

    fun modelDirPath(): String = config.modelsDir.absolutePath

    fun clearMessage() = state.update { it.copy(message = "") }

    private fun addLog(line: String) {
        val stamp = consoleFormat.format(Date())
        val text = "$stamp $line"
        Log.i(TAG, line)
        synchronized(logBuffer) {
            logBuffer.addLast(text)
            while (logBuffer.size > 200) logBuffer.removeFirst()
        }
        state.update { it.copy(logs = synchronized(logBuffer) { logBuffer.toList() }) }
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(engineLock) {
            lastFrame?.recycle()
            lastFrame = null
        }
        speaker?.stop()
        matting?.release()
    }
}
