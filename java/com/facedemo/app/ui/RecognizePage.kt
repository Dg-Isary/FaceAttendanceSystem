package com.facedemo.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.facedemo.app.camera.CameraController
import com.facedemo.app.core.ImageUtil
import com.facedemo.app.vm.AppViewModel
import com.facedemo.app.vm.UiState
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * 识别页 —— 全屏取景 + 悬浮控件，贴近手机原生相机的操作：
 *
 *   * 画面占满整页，人脸框与「[班级] 姓名 相似度 判定」直接画在画面上
 *   * 顶部一排小胶囊：阈值（**点一下进设置改**）、人脸库人数、FPS、最近一次播报
 *   * 底部：左「静音/出声」、中间**大圆快门 = 保存这张画面**、右「前后摄切换」
 *   * 保存后弹出结果卡：显示快照缩略图与识别结果，可以一键「加进人脸库」
 *     （没录入过的学生，抓一张存下来就建档了）
 *
 * 阈值调节、调试日志这些都收进右上角的「设置」里，页面上不再堆控件。
 */
@Composable
fun RecognizePage(
    vm: AppViewModel,
    state: UiState,
    camera: CameraController,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permission = granted }

    var isFront by remember { mutableStateOf(camera.isFront) }
    var showSnapshot by remember { mutableStateOf(false) }
    var enrollFromSnapshot by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (!permission) permissionLauncher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(camera) {
        isFront = camera.isFront
        vm.onLensChanged(camera.isFront)
    }
    LaunchedEffect(state.snapshotTick) {
        if (state.snapshotTick > 0) showSnapshot = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        camera.attach(view)
                        vm.onLensChanged(camera.isFront)
                    }
                }
            )
            FaceOverlayCanvas(state, Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FaIcon("camera", size = 44.dp, tint = Color.White)
                Text("识别需要摄像头权限", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                Button(
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                ) { BtnContent("check", "授权摄像头") }
            }
        }

        // ---------------- 状态栏压暗渐变 ----------------
        // 相机铺满到状态栏，浅色画面上时间和电量会看不清；压一层从上到下的黑渐变，
        // 和相机 App 的做法一致（也让顶部的悬浮胶囊更清楚）。
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(96.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x99000000), Color(0x33000000), Color.Transparent)
                    )
                )
        )

        // ---------------- 顶部状态胶囊 ----------------
        // 这一页没有顶栏（相机铺满整屏），所以自己避开状态栏；右边补一个悬浮的「重载」按钮，
        // 因为原来顶栏上那个重载按钮在这一页没有了
        Column(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = Color(0xCC000000),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.clickable { onOpenSettings() }
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FaIcon("sliders", size = 13.dp, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "阈值 " + String.format(Locale.US, "%.3f", state.threshold) +
                            (if (state.autoThreshold) " (自动)" else "") + " · 点这里调",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
            Surface(color = Color(0x99000000), shape = RoundedCornerShape(20.dp)) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FaIcon(
                        "users",
                        size = 12.dp,
                        tint = if (state.galleryPhotos > 0) Color(0xFF8CFFB0) else Color(0xFFFF8A80)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "人脸库 ${state.galleryNames.size} 人 / ${state.galleryPhotos} 张 · " +
                            String.format(Locale.US, "%.1f FPS", state.fps),
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }
            }
            if (state.lastSpoken.isNotEmpty()) {
                Surface(color = Color(0xCC7A4B00), shape = RoundedCornerShape(20.dp)) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FaIcon("volume-high", size = 12.dp, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(state.lastSpoken, color = Color.White, fontSize = 11.sp)
                    }
                }
            }
            if (state.faces.isEmpty() && permission) {
                Surface(color = Color(0x99000000), shape = RoundedCornerShape(20.dp)) {
                    Text(
                        "让人脸进入画面就开始识别",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            if (state.ttsStatus != "系统TTS" && state.ttsStatus != "已停止") {
                Surface(color = Color(0xCC7A0000), shape = RoundedCornerShape(20.dp)) {
                    Text(
                        "语音${state.ttsStatus}" + if (state.ttsNote.isNotEmpty()) "：${state.ttsNote}" else "",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // ---------------- 右上角：重载人脸库（原来在顶栏上） ----------------
        Surface(
            color = Color(0x99000000),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(10.dp)
                .clip(RoundedCornerShape(50))
                .clickable { vm.reloadGallery() }
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FaIcon("rotate", size = 13.dp, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("重载", color = Color.White, fontSize = 12.sp)
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
                        "省电降温中 · 把手机举起来对着人就会自动恢复",
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

        // ---------------- 保存结果卡 ----------------
        if (showSnapshot && state.lastSnapshot.isNotEmpty()) {
            val thumb = remember(state.snapshotTick) {
                ImageUtil.decodeFile(File(state.lastSnapshot), 480)
            }
            Surface(
                color = Color(0xE6101010),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    // 抬到「底部控制条 + 悬浮导航栏」上面，别被 dock 压住
                    .padding(start = 12.dp, end = 12.dp, bottom = 196.dp)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = "快照",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(72.dp)
                                .height(72.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.DarkGray)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "已保存快照 · " + state.lastSnapshotName,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            "相似度 " + String.format(Locale.US, "%.3f", state.lastSnapshotSimilarity) +
                                " · " + File(state.lastSnapshot).name,
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        // 同录入结果卡：深色卡片上按钮颜色写死，不然文字会变黑看不见
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            OnDarkPillButton("user-plus", "加进人脸库", filled = true) {
                                enrollFromSnapshot = true
                                showSnapshot = false
                            }
                            OnDarkPillButton("check", "知道了", filled = false) {
                                showSnapshot = false
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
                .navigationBarsPadding()
                // 空出底部悬浮导航栏的位置（dock 约 74dp 高 + 10dp 外边距）
                .padding(top = 14.dp, bottom = 84.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 静音 / 出声
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable { vm.toggleMute() },
                contentAlignment = Alignment.Center
            ) {
                FaIcon(
                    if (state.ttsStatus == "静音") "volume-xmark" else "volume-high",
                    size = 20.dp,
                    tint = Color.White
                )
            }

            // 快门 = 保存当前画面
            Box(
                Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
                    .border(4.dp, Color.White, CircleShape)
                    .clickable(enabled = permission) {
                        showSnapshot = false
                        vm.saveSnapshot()
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
                            .background(Color.White)
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

    // ---------------- 把快照的人加进人脸库 ----------------
    if (enrollFromSnapshot) {
        var newName by remember { mutableStateOf(state.enrollName) }
        var newClass by remember { mutableStateOf(state.enrollClass) }
        AlertDialog(
            onDismissRequest = { enrollFromSnapshot = false },
            title = { Text("加进人脸库") },
            text = {
                Column {
                    Text(
                        "会用当前画面裁剪、抠像，存成标准一寸照（25×35mm, 295×413）。\n" +
                            "如果这是已经录入过的人，会替换掉旧照片。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("姓名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = newClass,
                        onValueChange = { newClass = it },
                        label = { Text("班级 / 备注") },
                        placeholder = { Text("例如：一班、科任老师") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        vm.enrollFromFrame(newName.trim(), newClass.trim())
                        enrollFromSnapshot = false
                    }
                ) { BtnContent("check", "录入") }
            },
            dismissButton = {
                TextButton(onClick = { enrollFromSnapshot = false }) { Text("取消") }
            }
        )
    }
}

/** 人脸框叠加 (与 Python 版 OpenCV 画的框等价, 但用 Compose 画所以中文不会乱码)。 */
@Composable
fun FaceOverlayCanvas(state: UiState, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val imageWidth = state.imageWidth
        val imageHeight = state.imageHeight
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val drawWidth = imageWidth * scale
        val drawHeight = imageHeight * scale
        val offsetX = (size.width - drawWidth) / 2f
        val offsetY = (size.height - drawHeight) / 2f

        for (face in state.faces) {
            val left = if (state.mirrorOverlay) 1f - face.right else face.left
            val right = if (state.mirrorOverlay) 1f - face.left else face.right
            val color = decisionColor(face.accepted, face.decision)
            val topLeft = Offset(offsetX + left * drawWidth, offsetY + face.top * drawHeight)
            val boxSize = Size((right - left) * drawWidth, (face.bottom - face.top) * drawHeight)
            drawRect(color = color, topLeft = topLeft, size = boxSize, style = Stroke(width = 3f))

            val klass = if (face.klass.isNotEmpty() && face.klass != "未分班") "[${face.klass}] " else ""
            val name = face.name.ifEmpty { "陌生人" }
            // 设了个人阈值的人，在框上标一下这次用的阈值，方便现场核对
            val custom = if (face.customThreshold && face.threshold > 0f) {
                " ·专属 " + String.format(Locale.US, "%.2f", face.threshold)
            } else ""
            val label = "$klass$name " + String.format(Locale.US, "%.3f", face.similarity) +
                " ${face.decision}$custom"
            val layout = measurer.measure(
                AnnotatedString(label),
                style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            )
            val labelTop = (topLeft.y - layout.size.height - 6f).coerceAtLeast(0f)
            drawRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(topLeft.x, labelTop),
                size = Size(layout.size.width + 10f, layout.size.height + 6f)
            )
            drawText(layout, topLeft = Offset(topLeft.x + 5f, labelTop + 3f))
        }
    }
}
