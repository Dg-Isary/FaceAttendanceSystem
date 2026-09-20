package com.facedemo.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.facedemo.app.camera.CameraController
import com.facedemo.app.vm.AppViewModel
import com.facedemo.app.vm.UiState

/**
 * 人脸录入页 —— 尽量贴近手机原生相机的体验：
 *
 *   * 全屏取景（画面占满整页），只有一层淡淡的悬浮控件
 *   * 中间虚线取景框：检测到合适大小的人脸就变绿，并提示「可以拍了」
 *   * 底部大圆快门；左右分别是「相册选图」和「前后摄切换」
 *   * 顶部显示当前要录的人（点一下可以换人 / 挑下一位待录入 / 去名单页）
 *   * 拍完立刻抠像并合成**标准一寸照**（25×35mm，295×413），下方弹出结果卡：
 *     满意就「下一位」，不满意就「重拍」（会删掉刚存的那张）
 *
 * 名单管理、名单导入等放在隔壁的「名单」页，这里只干拍照录入一件事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollFacePage(
    vm: AppViewModel,
    state: UiState,
    camera: CameraController,
    onOpenRoster: () -> Unit
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            if (state.enrollName.isBlank()) {
                vm.setEnrollTarget("", "")
            } else {
                vm.enrollFromImage(uri, state.enrollName, state.enrollClass)
            }
        }
    }

    var isFront by remember { mutableStateOf(camera.isFront) }
    var showPicker by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }

    LaunchedEffect(state.enrollTick) {
        if (state.enrollTick > 0) showResult = true
    }

    // 取景状态：脸在不在、够不够大
    val imageHeight = state.imageHeight.toFloat()
    val biggest = state.faces.maxByOrNull { it.bottom - it.top }
    val faceRatio = if (imageHeight > 0f && biggest != null) biggest.bottom - biggest.top else 0f
    val hint = when {
        !cameraGranted -> "需要摄像头权限"
        state.enrollName.isBlank() -> "先在顶部选要录入的人"
        state.faces.isEmpty() -> "把人脸放进虚线框里"
        faceRatio < 0.16f -> "靠近一点，脸有点小"
        faceRatio > 0.78f -> "退后一点，脸太满了"
        else -> "可以拍了"
    }
    val ready = cameraGranted && state.faces.isNotEmpty() && state.enrollName.isNotBlank() && !state.busy

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------------- 取景 ----------------
        if (cameraGranted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PreviewView(ctx).also { view -> camera.attach(view) } }
            )
            FaceOverlayCanvas(state, Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FaIcon("camera", size = 44.dp, tint = Color.White)
                Text("录入人脸需要摄像头权限", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                Button(
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                ) { BtnContent("check", "授权摄像头") }
            }
        }

        // ---------------- 取景框 ----------------
        if (cameraGranted) {
            Canvas(Modifier.fillMaxSize()) {
                // 恢复到上一版的固定取景框（宽 68% 屏宽、接近一寸照 25:35），
                // 这样拍完的结果卡「重拍 / 下一位」下面不会被虚线穿过，看着更清楚
                val frameWidth = size.width * 0.68f
                val frameHeight = frameWidth * 1.34f
                val left = (size.width - frameWidth) / 2f
                val top = size.height * 0.16f
                val color = if (faceRatio >= 0.16f && faceRatio <= 0.78f) Color(0xFF25C55E)
                else Color.White.copy(alpha = 0.75f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(frameWidth, frameHeight),
                    cornerRadius = CornerRadius(28f, 28f),
                    style = Stroke(
                        width = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(26f, 18f), 0f)
                    )
                )
            }
        }

        // ---------------- 放平暂停提示 ----------------
        if (camera.pausedByTilt) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xE6000000))
                    .clickable { camera.resumeFromTilt() },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.padding(horizontal = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FaIcon("bolt", size = 40.dp, tint = Color(0xFFFFC24B))
                    Text(
                        "手机放平，相机已暂停",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        "录入需要举着手机对着人 · 举起来就会自动恢复",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Button(
                        modifier = Modifier.padding(top = 16.dp),
                        onClick = { camera.resumeFromTilt() }
                    ) { BtnContent("play", "继续使用") }
                }
            }
        }

        // ---------------- 顶部：当前对象 + 进度 ----------------
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = Color(0xCC000000),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { showPicker = true }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FaIcon("id-card", size = 18.dp, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.enrollName.ifBlank { "点这里选要录入的人" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            (if (state.enrollClass.isNotBlank()) "${state.enrollClass} · " else "") +
                                "待录入 ${state.pendingCount} 人 · 已录 ${state.enrolledCount} 人" +
                                " · 点一下换人",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp
                        )
                    }
                    FaIcon("pen-to-square", size = 16.dp, tint = Color.White.copy(alpha = 0.8f))
                }
            }
            Surface(color = Color(0x99000000), shape = RoundedCornerShape(20.dp)) {
                Text(
                    hint,
                    color = if (hint == "可以拍了") Color(0xFF8CFFB0) else Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            // 抠像状态：关掉的话录出来就是一块矩形，必须一眼能看到
            Surface(
                color = if (vm.config.mattingEnabled) Color(0x99000000) else Color(0xCC8A1010),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!vm.config.mattingEnabled) {
                        FaIcon("triangle-exclamation", size = 12.dp, tint = Color(0xFFFFD24B))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (vm.config.mattingEnabled) {
                            "抠像 开 · " + vm.mattingMethodName()
                        } else {
                            "抠像已关闭，录出来是矩形（去设置打开）"
                        },
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ---------------- 拍完的结果卡 ----------------
        if (showResult && vm.enrollPreview != null) {
            Surface(
                color = Color(0xE6101010),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 132.dp)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                    Image(
                        bitmap = vm.enrollPreview!!.asImageBitmap(),
                        contentDescription = "一寸照预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(96.dp)
                            .height(134.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "已保存 ${state.lastEnrolled}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            (if (state.enrollClass.isNotBlank()) "${state.enrollClass} · " else "") +
                                "标准一寸照 25×35mm (295×413)",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        // 把抠像方式/前景占比/校验相似度也显示出来，方便判断抠得好不好
                        Text(
                            state.message
                                .removePrefix("已录入 ${state.lastEnrolled}")
                                .trim()
                                .trimStart(':', '：')
                                .trim(),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            maxLines = 3
                        )
                        Spacer(Modifier.height(8.dp))
                        // 「重拍 / 下一位」放在照片右侧的**右下角**，颜色也改成明确的高对比色
                        // （原来是 TextButton + MIUIX 主色字，在这个深色卡片上显示成黑色，看不见）
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OnDarkPillButton("rotate", "重拍", filled = false) {
                                vm.discardLastEnroll()
                                showResult = false
                            }
                            OnDarkPillButton("forward", "下一位", filled = true) {
                                showResult = false
                                vm.pickNextPending()
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 底部控制条 ----------------
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x88000000))
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 相册选图录入
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(enabled = state.enrollName.isNotBlank() && !state.busy) {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                contentAlignment = Alignment.Center
            ) { FaIcon("image", size = 20.dp, tint = Color.White) }

            // 快门
            Box(
                Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
                    .border(4.dp, Color.White, CircleShape)
                    .clickable(enabled = ready) {
                        showResult = false
                        vm.enrollFromFrame(state.enrollName, state.enrollClass)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp)
                    )
                } else {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(if (ready) Color.White else Color(0x66FFFFFF))
                    )
                }
            }

            // 前后摄切换
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable {
                        camera.switchLens()
                        isFront = camera.isFront
                        vm.onLensChanged(camera.isFront)
                    },
                contentAlignment = Alignment.Center
            ) { FaIcon("camera-rotate", size = 20.dp, tint = Color.White) }
        }
    }

    // ---------------- 选人对话框 ----------------
    if (showPicker) {
        var newName by remember { mutableStateOf(state.enrollName) }
        var newClass by remember { mutableStateOf(state.enrollClass) }
        var keyword by remember { mutableStateOf("") }
        var classFilter by remember { mutableStateOf("") }
        val all = remember(state.rosterRevision, state.galleryPhotos) { vm.people() }
        val pending = all.filter { !it.enrolled }
        val classes = remember(all) { all.map { it.klass }.distinct().sorted() }
        val listed = pending.filter { person ->
            (classFilter.isEmpty() || person.klass == classFilter) &&
                (keyword.trim().isEmpty() ||
                    person.name.contains(keyword.trim(), ignoreCase = true) ||
                    person.studentId.contains(keyword.trim(), ignoreCase = true) ||
                    person.klass.contains(keyword.trim(), ignoreCase = true))
        }
        // 选人框：内容和上一版**完全一样**（标题 + 三个输入框 + 班级筛选 + 待录入名单 +
        // 「去名单页 / 就用这个」两个按钮），只是不再用 MIUIX 的对话框 ——
        // 它内部把内容宽度写死 `widthIn(max = 420.dp)`、还要再减两侧外边距，
        // 手机上实际只有约 300dp，人名/班级挤在一起。这里自己画一张同样样式的居中卡片，
        // 只把宽度放开到屏宽的 94%。
        // 高度上限按**当前缩放后的可用高度**算（BoxWithConstraints 里的 maxHeight 已经带缩放），
        // 原来乘的是系统原始 screenHeightDp，界面放大后会算出比屏幕还高的上限，卡片底部会被切掉。
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable { showPicker = false }
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val maxCardHeight = maxHeight * 0.86f
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = maxCardHeight)
                    // 吃掉卡片上的点击，免得点空白处（标题、输入框之间的缝）把框关掉
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text("要录入谁？", fontWeight = FontWeight.Bold, fontSize = 17.sp)

                    // 姓名 + 班级 并排一行：三个输入框原来各占一整行，白白吃掉大半个卡片的高度。
                    // 高度固定 52dp 跟随界面缩放；用 placeholder 而不是 label ——
                    // Material 的浮动 label 在矮框里会飘到输入文字上面，把姓名挡住。
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("姓名", fontSize = 13.sp) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).height(52.dp)
                        )
                        OutlinedTextField(
                            value = newClass,
                            onValueChange = { newClass = it },
                            placeholder = { Text("班级 / 备注", fontSize = 13.sp) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1.3f).height(52.dp)
                        )
                    }
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        placeholder = { Text("搜索", fontSize = 13.sp) },
                        leadingIcon = { FaIcon("magnifying-glass", size = 15.dp) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)
                    )

                    if (classes.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OptionButton("全部", classFilter.isEmpty()) { classFilter = "" }
                            classes.forEach { klass ->
                                OptionButton(klass, classFilter == klass) { classFilter = klass }
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("待录入 ${pending.size} 人 · 筛选后 ${listed.size} 人", fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.pickNextPending() }) {
                            BtnContent("forward", "选下一位", size = 13.dp)
                        }
                    }

                    LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
                        items(listed.take(200), key = { it.name }) { person ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.setEnrollTarget(person.name, person.klass)
                                        showPicker = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FaIcon("circle-xmark", size = 14.dp, tint = Color(0xFFB35C00))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(person.name, fontSize = 14.sp)
                                    Text(
                                        (if (person.klass.isNotBlank()) person.klass else "未分班") +
                                            if (person.studentId.isNotBlank()) " · ${person.studentId}" else "",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (listed.isEmpty()) {
                            item {
                                Text(
                                    if (pending.isEmpty()) "名单里的人都录完了。要加人请到「名单」页。"
                                    else "没有符合筛选条件的人。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            showPicker = false
                            onOpenRoster()
                        }) { BtnContent("users", "去名单页", size = 13.dp) }
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = {
                            vm.setEnrollTarget(newName.trim(), newClass.trim())
                            showPicker = false
                        }) { BtnContent("check", "就用这个", size = 13.dp) }
                    }
                }
            }
            }
        }
    }
}
