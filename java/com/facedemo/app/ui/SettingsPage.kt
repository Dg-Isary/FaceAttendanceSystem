package com.facedemo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.facedemo.app.core.FaceEnroller
import com.facedemo.app.vm.AppViewModel
import com.facedemo.app.vm.UiState
import java.util.Locale

/**
 * 设置页（整页，不再是挤在小对话框里）。
 *
 * 排版约定：
 *   * 一屏一个主题卡片，卡片标题带图标；相关项放在同一张卡里
 *   * 选项按钮**选中=实心、未选中=描边**，一眼能看出当前是什么
 *   * 顶部一张摘要卡，把「当前证件照规格」写清楚，改完立刻能核对
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(vm: AppViewModel, state: UiState) {
    val context = LocalContext.current
    val config = vm.config
    var report by remember { mutableStateOf<String?>(null) }
    val voiceOptions = remember(state.configRevision) { vm.voiceOptions() }
    val spec = remember(state.configRevision) { vm.outputSpecSummary() }
    val diagnostics = remember(state.configRevision) { vm.speechDiagnostics() }
    val crash = remember(state.configRevision) { vm.lastCrash() }
    val backupPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> if (uri != null) vm.restoreBackup(uri) }

    // 设置现在是底部导航栏里的一页（在「名单」后面），所以不再有自己的顶栏和「完成」按钮，
    // 切页直接用底部导航即可。布局和别的页保持一致：一个 LazyColumn 从头排到尾。
    run {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------------------------------------- 当前规格摘要
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        // 25:35 的小预览，直观说明“一寸照长什么样”
                        Box(
                            Modifier
                                .width(58.dp)
                                .height(58.dp * 35 / 25)
                                .clip(RoundedCornerShape(4.dp))
                                .background(specColor(config.mattingBackground))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "25×35",
                                fontSize = 9.sp,
                                color = if (config.mattingBackground == "white") Color.Black else Color.White
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("录入输出", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(spec, fontSize = 12.sp, lineHeight = 17.sp)
                            Text(
                                "改完立刻生效，下一次按快门就用新规格。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 版本 + 构建标记：一眼对上手机上装的是哪一次构建
                            Text(
                                "v$APP_VERSION · 构建 $APP_BUILD",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------- 识别
            item {
                SectionCard(title = "识别", icon = "sliders") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 先开关再滑杆：自动标定打开时，阈值由人脸库算出来，滑杆停用（灰掉）
                        SwitchRow("自动标定阈值", config.threshold < 0f) { auto ->
                            if (auto) vm.setAutoThreshold() else vm.setThreshold(state.threshold)
                        }
                        Text(
                            if (config.threshold < 0f) {
                                "已打开：阈值取「同一人最低分」和「不同人最高分」的中点，" +
                                    "换人、换光线都不用自己调。\n" +
                                    "（v1.1.0 起算出来的值**不低于 0.50** —— 原来会低到 0.36，误报太多。）"
                            } else {
                                "已关闭：下面这根滑杆由你手动定阈值。默认 0.500；" +
                                    "**调低会明显增加误报**（把别人认成同一个人），漏识多往下调、误报多往上调。"
                            },
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LabeledSlider(
                            label = "相似度阈值",
                            value = state.threshold,
                            range = 0.15f..0.90f,
                            // 分度 0.025：原来不分度，手指一动就跳好几个千分位，很难停住
                            step = 0.025f,
                            valueText = String.format(Locale.US, "%.3f", state.threshold),
                            enabled = config.threshold >= 0f,
                            onValueChange = { vm.setThreshold(it) }
                        )
                        val personCount = remember(state.configRevision, state.rosterRevision) {
                            vm.personThresholds().size
                        }
                        KeyValueRow(
                            "个人阈值",
                            if (personCount > 0) "$personCount 人设了专属阈值" else "没有（全部用上面的全局值）",
                            icon = "user-check"
                        )
                        Text(
                            "个人阈值：在「名单」页点某人的「改」，填一个 0.05~0.97 的小数 —— " +
                                "填了就只对这个人生效（越大越严、越小越松），留空则用上面的全局阈值。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LabeledSlider(
                            label = "检测置信度",
                            value = config.detectorScore,
                            range = 0.4f..0.95f,
                            // 0.05 一格：这个参数本来就不用调得很细
                            step = 0.05f,
                            valueText = String.format(Locale.US, "%.2f", config.detectorScore),
                            onValueChange = { vm.updateSettings { cfg -> cfg.detectorScore = it } }
                        )
                        LabeledSlider(
                            label = "识别帧宽度",
                            value = config.procWidth.toFloat(),
                            range = 320f..1280f,
                            // 64px 一格（原来是 32px 一格，65 段太多）
                            step = 64f,
                            valueText = "${config.procWidth} px",
                            onValueChange = { vm.updateSettings { cfg -> cfg.procWidth = it.toInt() } }
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { vm.rebuildEngine() }
                        ) { BtnContent("bolt", "重建引擎", size = 13.dp) }
                        SwitchRow("记录全部候选相似度", config.logAllSimilarities) {
                            vm.updateSettings { cfg -> cfg.logAllSimilarities = it }
                        }
                        SwitchRow("保存识别快照", config.saveSnapshots) {
                            vm.updateSettings { cfg -> cfg.saveSnapshots = it }
                        }
                        SwitchRow("写相似度/事件日志", config.logEnabled) {
                            vm.updateSettings { cfg -> cfg.logEnabled = it }
                        }
                    }
                }
            }

            // ---------------------------------------------- 打卡（v1.0.3 从签到页搬过来的）
            // 这里放**两个不同的间隔**，别混：
            //   ① 重新识别间隔（cooldownSeconds）—— 识别管线本身的节流：同一个人多久之内不再被重复识别
            //      （播报和打卡都跟着它走，防"一个人站那儿被反复识别"）
            //   ② 打卡间隔（attendanceIntervalHours，单位小时）—— 记录层面的节流：同一类型连着来（连着两次签到）的最短间隔
            // 两者独立：识别可以很勤（比如 3 秒），但打卡仍然要隔够 30 秒才记第二条。
            item {
                SectionCard(title = "打卡", icon = "right-to-bracket") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow("启用打卡（关掉只识别不记录）", config.attendanceEnabled) {
                            vm.setAttendanceEnabled(it)
                        }

                        Text("重新识别间隔", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        LabeledSlider(
                            label = "同一个人多久内不重复识别",
                            value = config.cooldownSeconds,
                            range = 0f..60f,
                            step = 1f,
                            valueText = "${config.cooldownSeconds.toInt()} 秒",
                            onValueChange = { vm.updateSettings { cfg -> cfg.cooldownSeconds = it } }
                        )
                        Text(
                            "识别管线本身的节流：同一个人刚被识别过，这么多秒内不再重复处理" +
                                "（**播报和打卡都跟着它走**）。防止一个人站在镜头前被反复识别、反复出声。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        Text("打卡间隔", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        LabeledSlider(
                            label = "同一类型连着来的最短间隔（小时）",
                            value = config.attendanceIntervalHours,
                            range = 0f..12f,
                            step = 0.5f,
                            valueText = hoursText(config.attendanceIntervalHours),
                            onValueChange = { vm.updateSettings { cfg -> cfg.attendanceIntervalHours = it } }
                        )
                        Text(
                            "一天可以打任意多次卡，这个间隔**只在同一类型连着来时才生效**：\n" +
                                "· 签到没签退，隔够这么久**也可以再签到**（不必等签退，间隔到了就行）；\n" +
                                "· 签到 → 签退 → 再签到：马上就能签（签退已经把这一段时间闭合了）；\n" +
                                "· 连着两次签到（中间没签退）：要隔够这么久才记第二条，防止站在镜头前被连续记录；\n" +
                                "· 签退 → 签退 同理。默认 1 小时、0.5 小时一格，拉到 0 = 完全不限制。\n" +
                                "· 另外：**没签到不能签退**（签退要收掉开着的那段签到），这条是硬规则、不受这里影响。\n" +
                                "注意：要真能连着打两次，上面的「重新识别间隔」也得够短（识别不出来的话，" +
                                "下面这个间隔再短也没用）。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        SwitchRow("名单外的人也要打卡", config.attendanceUnlisted == "record") {
                            vm.updateSettings { cfg -> cfg.attendanceUnlisted = if (it) "record" else "ignore" }
                        }
                        Text(
                            "关掉 = 不在名单里的人只识别、不记打卡；打开 = 也记一条（备注里会写「不在名单里」）。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------------------------------------------- 省电与发热
            item {
                SectionCard(title = "省电与发热", icon = "bolt") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow("手机放平时暂停相机", config.pauseWhenFlat) {
                            vm.updateSettings { cfg -> cfg.pauseWhenFlat = it }
                        }
                        Text(
                            "相机一直开着是整机最费电、最发热的部分。打开后手机放平就自动停掉相机" +
                                "（连预览一起停），举起来对着人会自己恢复。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LabeledSlider(
                            label = "识别频率",
                            value = config.analyzeIntervalMs.toFloat(),
                            range = 50f..400f,
                            valueText = String.format(
                                Locale.US, "%.1f 次/秒", 1000f / config.analyzeIntervalMs
                            ),
                            onValueChange = { vm.updateSettings { cfg -> cfg.analyzeIntervalMs = it.toInt() } }
                        )
                        Text(
                            "画面照样流畅，只是中间那些帧不做检测：一般 6~8 次/秒就够用，" +
                                "想更省电就往左拉。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------------------------------------------- 语音
            item {
                SectionCard(title = "语音播报", icon = "volume-high") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow("启用语音播报", config.ttsEnabled) {
                            vm.updateSettings { cfg -> cfg.ttsEnabled = it }
                            val speaker = vm.speakerRef()
                            speaker?.dryRun = !it
                            speaker?.muted = !it
                        }
                        KeyValueRow("当前状态", state.ttsStatus, icon = "circle-info")
                        if (state.ttsNote.isNotEmpty()) {
                            Text(state.ttsNote, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }

                        Text("播报音频流", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        FillRow {
                            listOf(
                                "auto" to "默认",
                                "music" to "媒体",
                                "notification" to "通知",
                                "system" to "系统"
                            ).forEach { (value, label) ->
                                OptionButton(label, config.ttsStream == value, Modifier.weight(1f)) {
                                    vm.updateSettings { cfg -> cfg.ttsStream = value }
                                }
                            }
                        }
                        Text(
                            "系统「文字转语音 → 试听」能响、App 不响时，先保持「默认」；" +
                                "还不行就依次试媒体/通知/系统（有些 ROM 会走通知流，通知音量静音就没声）。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text("播报方式", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        FillRow {
                            OptionButton(
                                "引擎直接播",
                                config.ttsMode != "file",
                                Modifier.weight(1f),
                                icon = "play"
                            ) { vm.updateSettings { cfg -> cfg.ttsMode = "direct" } }
                            OptionButton(
                                "合成后播放",
                                config.ttsMode == "file",
                                Modifier.weight(1f),
                                icon = "download"
                            ) { vm.updateSettings { cfg -> cfg.ttsMode = "file" } }
                        }
                        Text(
                            "「引擎直接播」有返回码 0（成功）却听不到声音时，改成「合成后播放」——" +
                                "程序先把语音合成成 wav，再用系统播放器放出来，绕开引擎自己的播放路由。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 引擎包名：自检显示「一个都没找到」或某个引擎起不来时手填
                        var engineText by remember(state.configRevision) {
                            mutableStateOf(config.ttsEngine)
                        }
                        OutlinedTextField(
                            value = engineText,
                            onValueChange = {
                                engineText = it
                                vm.updateSettings { cfg -> cfg.ttsEngine = it }
                            },
                            label = { Text("TTS 引擎包名") },
                            placeholder = { Text("例如 com.google.android.tts") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                // v1.0.5：换引擎必须重建 TTS 实例，所以**输完（失去焦点）自动重启一次**，
                                // 不用再手点「重新初始化语音」；只在值真的变了时重启。
                                .onFocusChanged { focus ->
                                    if (!focus.isFocused && engineText.trim() != config.ttsEngine) {
                                        vm.updateSettings { cfg -> cfg.ttsEngine = engineText.trim() }
                                        vm.restartSpeech()
                                    }
                                }
                        )
                        Text(
                            "正常情况留空即可。如果自检里「系统引擎」是空的、或引擎起不来，" +
                                "可以先到 系统设置 → 无障碍/语言与输入 → 文字转语音 看一眼引擎叫什么，再填到这里。" +
                                "改完自动重启语音引擎（不用再点按钮）；语速/音调/音色/音量都是改完立刻生效。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        LabeledSlider(
                            label = "语速",
                            value = config.ttsRate,
                            range = 0.5f..2.0f,
                            valueText = String.format(Locale.US, "%.1fx", config.ttsRate),
                            onValueChange = { vm.updateSettings { cfg -> cfg.ttsRate = it } }
                        )
                        LabeledSlider(
                            label = "音调",
                            value = config.ttsPitch,
                            range = 0.5f..1.6f,
                            valueText = String.format(Locale.US, "%.1f", config.ttsPitch),
                            onValueChange = { vm.updateSettings { cfg -> cfg.ttsPitch = it } }
                        )
                        LabeledSlider(
                            label = "播报音量",
                            value = config.ttsVolume,
                            range = 0f..1f,
                            valueText = "${(config.ttsVolume * 100).toInt()}%",
                            onValueChange = { vm.setSpeechVolume(it) }
                        )
                        FillRow {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { vm.testSpeech() }
                            ) { BtnContent("play", "试播一次", size = 14.dp) }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { vm.toggleMute() }
                            ) {
                                BtnContent(
                                    if (state.ttsStatus == "静音") "volume-high" else "volume-xmark",
                                    if (state.ttsStatus == "静音") "取消静音" else "静音",
                                    size = 14.dp
                                )
                            }
                        }
                        // 重新初始化 + 一键自检 并排（原来两个整行按钮上下叠着，太占地方）
                        FillRow {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { vm.restartSpeech() }
                            ) { BtnContent("rotate", "重新初始化语音", size = 13.dp) }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { vm.runSpeechSelfTest() }
                            ) { BtnContent("flask", "一键自检", size = 13.dp) }
                        }
                        Text(
                            "自检做三件事：把一段语音合成成 wav 文件 → 用系统播放器播放 → 再试一次直接播报。" +
                                "这样能把「引擎根本合不出声」和「合出来了但播放没声音」分开，结论写在最后一行。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        ) {
                            Text(
                                diagnostics,
                                fontSize = 10.sp,
                                lineHeight = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (diagnostics.contains("←")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        if (voiceOptions.isNotEmpty()) {
                            // 音色改成**下拉框**（原来是一堆整行按钮，上下紧贴着，还看不出选中谁）
                            Text(
                                "音色",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            var voiceMenu by remember { mutableStateOf(false) }
                            val currentVoice = voiceOptions.firstOrNull { it.first == config.ttsVoice }
                            val voiceLabel = currentVoice?.let { "${it.first}  [${it.second}]" }
                                ?: "自动挑选（推荐）"
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { voiceMenu = true }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FaIcon("volume-high", size = 14.dp, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        voiceLabel,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FaIcon(
                                        "chevron-down",
                                        size = 13.dp,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = voiceMenu,
                                onDismissRequest = { voiceMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("自动挑选（推荐）", fontSize = 13.sp) },
                                    leadingIcon = {
                                        if (config.ttsVoice.isEmpty()) {
                                            FaIcon("check", size = 13.dp, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    onClick = {
                                        vm.updateSettings { cfg -> cfg.ttsVoice = "" }
                                        vm.testSpeech()
                                        voiceMenu = false
                                    }
                                )
                                voiceOptions.take(12).forEach { (name, locale) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "$name  [$locale]",
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = {
                                            if (config.ttsVoice == name) {
                                                FaIcon("check", size = 13.dp, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        onClick = {
                                            vm.updateSettings { cfg -> cfg.ttsVoice = name }
                                            vm.testSpeech()
                                            voiceMenu = false
                                        }
                                    )
                                }
                            }
                            Text(
                                "有些机型（例如 ColorOS）不允许第三方 App 直接指定音色，" +
                                    "选了没变化就用「自动挑选」，或到系统设置 → 文字转语音里改默认音色。",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        SectionHeader("id-card", "播报文案模板")
                        TemplateField("识别到人时", config.ttsTemplate) {
                            vm.updateSettings { cfg -> cfg.ttsTemplate = it }
                        }
                        TemplateField("打卡成功时", config.ttsCheckinTemplate) {
                            vm.updateSettings { cfg -> cfg.ttsCheckinTemplate = it }
                        }
                        TemplateField("间隔不够时（同一类型连着来）", config.ttsDuplicateTemplate) {
                            vm.updateSettings { cfg -> cfg.ttsDuplicateTemplate = it }
                        }
                        // v1.0.7：没签到就想签退时的播报词（规则是"签退必须先签到"）
                        TemplateField("没签到就签退时", config.ttsNoCheckinTemplate) {
                            vm.updateSettings { cfg -> cfg.ttsNoCheckinTemplate = it }
                        }
                        Text(
                            "{name} 换成人名，{kind} 换成 签到/签退。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LabeledSlider(
                            label = "连续命中帧数",
                            value = config.minHits.toFloat(),
                            range = 1f..8f,
                            valueText = "${config.minHits} 帧",
                            onValueChange = { vm.updateSettings { cfg -> cfg.minHits = it.toInt().coerceAtLeast(1) } }
                        )
                        Text(
                            "「重新识别间隔」（同一个人多久内不重复识别/播报）和「打卡间隔」都在" +
                                "上面的「打卡」卡片里 —— 它们影响的是识别与记录，不只是语音，所以放一起了。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------------------------------------------- 证件照与录入
            item {
                SectionCard(title = "证件照与录入", icon = "id-card") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("输出规格", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        FillRow {
                            OptionButton(
                                "标准一寸照",
                                config.enrollOutput == FaceEnroller.OUTPUT_ID_PHOTO,
                                Modifier.weight(1f),
                                icon = "id-card"
                            ) { vm.updateSettings { cfg -> cfg.enrollOutput = FaceEnroller.OUTPUT_ID_PHOTO } }
                            OptionButton(
                                "原始抠像",
                                config.enrollOutput == FaceEnroller.OUTPUT_CUTOUT,
                                Modifier.weight(1f),
                                icon = "scissors"
                            ) { vm.updateSettings { cfg -> cfg.enrollOutput = FaceEnroller.OUTPUT_CUTOUT } }
                        }
                        Text(
                            if (config.enrollOutput == FaceEnroller.OUTPUT_ID_PHOTO) {
                                "一寸照：25×35mm，按 300dpi 输出 295×413 像素；" +
                                    "按国标取景（头高占 60%、头顶留白 8%、肩膀可见）。"
                            } else {
                                "原始抠像：只按人脸框裁切并去掉背景，不做尺寸规范。"
                            },
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text("背景", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                        BtnRow(verticalAlignment = Alignment.CenterVertically) {
                            listOf("white", "blue", "red", "transparent").forEach { value ->
                                ColorSwatch(value, config.mattingBackground == value) {
                                    vm.updateSettings { cfg -> cfg.mattingBackground = value }
                                }
                            }
                            Text(
                                backgroundLabelOf(config.mattingBackground),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "点色块直接换底色；透明那个是棋盘格，输出带透明通道的 PNG。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        LabeledSlider(
                            label = "边缘收紧",
                            value = config.mattingErode,
                            range = 0f..5f,
                            valueText = String.format(Locale.US, "%.1f%%", config.mattingErode),
                            onValueChange = { vm.updateSettings { cfg -> cfg.mattingErode = it } }
                        )
                        LabeledSlider(
                            label = "边缘羽化",
                            value = config.mattingFeather,
                            range = 0f..6f,
                            valueText = String.format(Locale.US, "%.1f%%", config.mattingFeather),
                            onValueChange = { vm.updateSettings { cfg -> cfg.mattingFeather = it } }
                        )
                        SwitchRow("抠像去背景", config.mattingEnabled) {
                            vm.updateSettings { cfg -> cfg.mattingEnabled = it }
                        }
                        if (!config.mattingEnabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FaIcon(
                                    "triangle-exclamation",
                                    size = 14.dp,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "抠像现在是关的：录入出来会是整块矩形（没抠人像）。想要人像轮廓请把它打开。",
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        FillRow {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { vm.runMattingSelfTest() }
                            ) { BtnContent("flask", "抠像自检", size = 13.dp) }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val file = vm.exportDiagnosticsBundle()
                                    if (file != null) shareFile(context, file, "application/zip")
                                }
                            ) { BtnContent("upload", "导出诊断包", size = 13.dp) }
                        }
                        Text(
                            "「抠像自检」拿当前画面跑一遍抠像，报出：模型有没有加载、走的哪条路、" +
                                "前景占比、耗时。结论里会直接写明是不是「几乎整块矩形」。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "抠像是「无幕布」式的：靠人像分割模型把整个人从任意背景里分出来，" +
                                "再按上面的收紧/羽化修边。要是某张照片效果不理想：" +
                                "① 把边缘收紧调大一点；② 让被拍的人离背景远一点、光线均匀；" +
                                "③ 实在不行把「抠像去背景」关掉，只裁切（适合本来就对着白墙拍）。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "注意：「名单 → 人脸库 → 导入照片（原样）」是直接拷贝原图、**不抠像不换底**；" +
                                "要按上面的规格处理，用「导入并抠像成一寸照」。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        LabeledSlider(
                            label = "取景外扩倍数",
                            value = config.enrollExpand,
                            range = 1.6f..4.0f,
                            valueText = String.format(Locale.US, "%.1fx", config.enrollExpand),
                            onValueChange = { vm.updateSettings { cfg -> cfg.enrollExpand = it } }
                        )
                        SwitchRow("录入时替换这个人的旧照片", config.enrollReplace) {
                            vm.updateSettings { cfg -> cfg.enrollReplace = it }
                        }
                        SwitchRow("录入后回读校验", config.enrollVerify) {
                            vm.updateSettings { cfg -> cfg.enrollVerify = it }
                        }
                        SwitchRow("录完自动选下一位待录入", config.enrollAutoNext) {
                            vm.updateSettings { cfg -> cfg.enrollAutoNext = it }
                        }
                    }
                }
            }

            // ---------------------------------------------- 界面
            item {
                SectionCard(title = "界面", icon = "sliders") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "界面缩放 " + String.format(Locale.US, "%.0f%%", config.uiScale * 100),
                            fontSize = 13.sp
                        )
                        FillRow {
                            listOf(
                                0.85f to "小",
                                1.0f to "标准",
                                1.15f to "大",
                                1.3f to "特大"
                            ).forEach { (value, label) ->
                                OptionButton(
                                    label,
                                    kotlin.math.abs(config.uiScale - value) < 0.01f,
                                    Modifier.weight(1f)
                                ) { vm.updateSettings { cfg -> cfg.uiScale = value } }
                            }
                        }
                        LabeledSlider(
                            label = "微调",
                            value = config.uiScale,
                            range = 0.75f..1.4f,
                            valueText = String.format(Locale.US, "%.2fx", config.uiScale),
                            onValueChange = { vm.updateSettings { cfg -> cfg.uiScale = it } }
                        )
                        Text(
                            "程序内所有文字和控件一起缩放（设置页自己也会跟着缩放），改完立刻生效。" +
                                "觉得字小或按钮挤就放大一档。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        SectionHeader("wand-magic-sparkles", "主题色")
                        Text(
                            "整个界面的强调色（按钮、开关、滑杆、选中态）都跟着它走；" +
                                "背景、卡片、文字这些明暗关系不变，深色模式也照常。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            THEME_PRESETS.forEach { preset ->
                                val selected = config.themeColor == preset.key
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(preset.color)
                                        .border(
                                            if (selected) 3.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape
                                        )
                                        .clickable {
                                            vm.updateSettings { cfg -> cfg.themeColor = preset.key }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        FaIcon("check", size = 16.dp, tint = Color.White)
                                    }
                                }
                            }
                        }
                        Text(
                            "当前：" + (THEME_PRESETS.firstOrNull { it.key == config.themeColor }?.label
                                ?: "极光蓝"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ---------------------------------------------- 数据备份 / 恢复
            item {
                SectionCard(title = "数据备份与恢复", icon = "folder-open") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "备份内容：名单 + 人脸库照片 + 打卡/相似度日志 + 识别快照，" +
                                "打包成一个 zip。换手机、重装 App 前导出一份就够。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FillRow(modifier = Modifier.padding(top = 8.dp)) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val file = vm.exportBackup()
                                    if (file != null) {
                                        shareFile(context, file, "application/zip")
                                    }
                                }
                            ) { BtnContent("upload", "导出全部数据", size = 13.dp) }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { backupPicker.launch(arrayOf("*/*")) }
                            ) { BtnContent("download", "选择 zip 恢复", size = 13.dp) }
                        }
                        FillRow {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    shareText(context, vm.boardCsv(""), "点名表.csv", "text/csv")
                                }
                            ) { BtnContent("file-export", "导出点名表", size = 13.dp) }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    shareText(context, vm.rosterCsvText(), "名单.csv", "text/csv")
                                }
                            ) { BtnContent("users", "导出名单", size = 13.dp) }
                        }
                        Text(
                            "也可以用系统「打开方式」导入：在微信/文件管理里点 CSV 或 zip，" +
                                "选「人脸识别」就会自动进到这里 —— 名单直接导入，zip 直接恢复。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // ---------------------------------------------- 诊断与日志
            item {
                SectionCard(title = "诊断与日志", icon = "chart-simple") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        KeyValueRow("版本", "$APP_VERSION · 构建 $APP_BUILD", icon = "circle-info")
                        KeyValueRow("抠像实现", state.matting, icon = "scissors")
                        KeyValueRow("OpenCV", vm.openCvDetail(), icon = "bolt")
                        KeyValueRow("快照张数", "${vm.snapshotCount()} 张", icon = "image")
                        Text(vm.logPaths(), fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("人脸库: ${vm.photoDirPath()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("名单: ${vm.rosterPath()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FillRow(modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { report = vm.report() }
                            ) { BtnContent("chart-simple", "分析相似度", size = 13.dp) }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    shareText(
                                        context,
                                        diagnostics + "\n\n---- 运行日志 ----\n" + state.logs.joinToString("\n"),
                                        "运行诊断.txt", "text/plain"
                                    )
                                }
                            ) { BtnContent("share-nodes", "分享诊断", size = 13.dp) }
                        }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { shareText(context, vm.report(), "识别报告.txt", "text/plain") }
                        ) { BtnContent("share-nodes", "分享相似度报告", size = 13.dp) }

                        // 日志导出：出问题时直接把文件发出来就能定位
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            onClick = {
                                val file = vm.exportDiagnosticsBundle()
                                if (file != null) shareFile(context, file, "application/zip")
                            }
                        ) { BtnContent("upload", "导出诊断包", size = 13.dp) }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val file = vm.exportDiagnostics()
                                if (file != null) shareFile(context, file, "text/plain")
                            }
                        ) { BtnContent("file-export", "导出文本日志", size = 13.dp) }
                        Text(
                            "「诊断包」是一个 zip：里面有运行日志、语音自检、崩溃日志，" +
                                "还有**最近一次录入的三张过程图**（原图 / 抠像掩码 / 抠像结果）。\n" +
                                "抠像出问题时发这个包最有用 —— 掩码图一眼就能看出模型分得对不对。\n" +
                                "过程图也在这里，可直接用文件管理器取：\n" +
                                "/sdcard/Android/data/com.facedemo.app/files/logs/enroll/",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        SectionHeader("list-check", "运行日志（最近 ${minOf(state.logs.size, 30)} 条）")
                        if (state.logs.isEmpty()) {
                            Text("暂无日志", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(
                                state.logs.takeLast(30).reversed().joinToString("\n"),
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        SectionHeader("triangle-exclamation", "崩溃日志")
                        if (crash.isEmpty()) {
                            Text("没有崩溃记录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(
                                crash.take(240),
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FillRow {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { report = crash }
                                ) { BtnContent("file-import", "看完整日志", size = 13.dp) }
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { shareText(context, crash, "崩溃日志.txt", "text/plain") }
                                ) { BtnContent("share-nodes", "分享", size = 13.dp) }
                            }
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { vm.clearCrash() }
                            ) { BtnContent("trash-can", "清除崩溃日志", size = 13.dp) }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // 抠像自检结果
    vm.mattingReport?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.clearMattingReport() },
            title = { Text("抠像自检结果") },
            text = {
                Box(Modifier.heightIn(max = 400.dp).verticalScrollable()) {
                    Text(text, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        shareText(context, text, "抠像自检.txt", "text/plain")
                    }) { BtnContent("share-nodes", "分享给开发者", size = 13.dp) }
                    TextButton(onClick = { vm.clearMattingReport() }) { Text("关闭") }
                }
            }
        )
    }

    // 语音一键自检的结果
    vm.speechReport?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.clearSpeechReport() },
            title = { Text("语音自检结果") },
            text = {
                Box(Modifier.heightIn(max = 400.dp).verticalScrollable()) {
                    Text(text, fontSize = 10.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        shareText(context, text, "语音自检.txt", "text/plain")
                    }) { BtnContent("share-nodes", "分享给开发者", size = 13.dp) }
                    TextButton(onClick = { vm.clearSpeechReport() }) { Text("关闭") }
                }
            }
        )
    }

    report?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("报告") },
            text = {
                Box(Modifier.heightIn(max = 400.dp).verticalScrollable()) {
                    Text(text, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { shareText(context, text, "报告.txt", "text/plain") }) {
                        Text("分享")
                    }
                    TextButton(onClick = { report = null }) { Text("关闭") }
                }
            }
        )
    }
}

/**
 * 选项按钮：选中=实心，未选中=描边 —— 一眼能看出当前选的是哪个。
 *
 * 宽度交给外层决定：
 *   * 在 [FillRow] 里传 `Modifier.weight(1f)` → 平分整行宽度，一行放得下就不换行；
 *   * 不传 weight（例如班级标签）→ 按文字自然宽度排，放不下由 [BtnRow] 换行。
 * 这里只兜一个最小高度，让一排按钮高度一致。
 */
@Composable
fun OptionButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: String? = null,
    onClick: () -> Unit
) {
    val m = modifier.heightIn(min = 40.dp)
    if (selected) {
        Button(modifier = m, onClick = onClick) {
            BtnContent(icon, label, size = 13.dp)
        }
    } else {
        OutlinedButton(modifier = m, onClick = onClick) {
            BtnContent(icon, label, size = 13.dp)
        }
    }
}

@Composable
private fun specColor(name: String): Color = when (name) {
    "blue" -> Color(0xFF438EDB)
    "red" -> Color(0xFFD62D2D)
    "gray" -> Color(0xFF808080)
    "transparent" -> Color(0xFFEDEDED)
    else -> Color.White
}

@Composable
private fun Modifier.verticalScrollable(): Modifier {
    val scrollState = rememberScrollState()
    return this.verticalScroll(scrollState)
}

/** 小时数说成人话：0 → "不限"，1 → "1 小时"，1.5 → "1.5 小时" */
private fun hoursText(hours: Float): String = when {
    hours <= 0f -> "不限"
    hours == hours.toInt().toFloat() -> "${hours.toInt()} 小时"
    else -> "$hours 小时"
}

@Composable
private fun TemplateField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    )
}
