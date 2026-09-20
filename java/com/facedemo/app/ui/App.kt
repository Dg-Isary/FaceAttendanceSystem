package com.facedemo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.facedemo.app.camera.CameraController
import com.facedemo.app.vm.AppViewModel
import com.facedemo.app.vm.UiState

/**
 * 四个页面（把「人脸录入」和「名单」拆开了）：
 *
 *   0 识别 —— 实时画面对比人脸库，达阈值播报“某某你好”并打卡
 *   1 签到 —— 全员到场看板 / 补签 / 导出点名表
 *   2 录入 —— 全屏相机取景 + 大圆快门，抠像后自动合成**标准一寸照**
 *   3 名单 —— 名单导入 / 增删改 / 人脸库管理（不碰相机）
 *
 * 相机对象在这一层持有，切页不会中断取景。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceDemoApp(vm: AppViewModel, state: UiState, lifecycleOwner: LifecycleOwner) {
    // 界面缩放包在**设置页与主界面之外**：在设置页里拖动滑杆，设置页自己也立刻缩放
    UiScale(state.uiScale) {
        FaceDemoScreen(vm, state, lifecycleOwner)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FaceDemoScreen(vm: AppViewModel, state: UiState, lifecycleOwner: LifecycleOwner) {
    var page by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val camera = remember {
        CameraController(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onFrame = { bitmap, index -> vm.onFrame(bitmap, index) },
            onStatus = { vm.onCameraStatus(it) }
        )
    }
    DisposableEffect(Unit) {
        onDispose { camera.shutdown() }
    }

    // 相机省电设置：分析频率 + 放平时自动暂停（改完设置立即生效）
    LaunchedEffect(state.configRevision) {
        camera.autoPauseWhenFlat = vm.config.pauseWhenFlat
        camera.analyzeIntervalMs = vm.config.analyzeIntervalMs.toLong()
    }

    // 只有「识别」「录入」这两个真正要画面的页面才开着相机：
    // 切到签到/名单/设置页（比如改名单、调参数的时候）就把相机整个停掉，回到相机页立刻恢复。
    val cameraPageVisible = page == 0 || page == 2
    LaunchedEffect(cameraPageVisible) { camera.setPageActive(cameraPageVisible) }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message.isNotEmpty()) {
            snackbar.showSnackbar(message)
            vm.clearMessage()
        }
    }

    // 状态栏 / 手势条**图标明暗自适应**：
    //   * 识别页是相机铺满 + 顶部压暗的纯黑画面 → 状态栏必须用**亮色图标**，不然黑底黑字看不见；
    //   * 其余页面是浅色（深色模式下深色）背景 → 用深色（深色模式下亮色）图标。
    // 手势条一律跟随系统深浅色（dock 本身是浅/深玻璃，图标压在系统手势条区域上）。
    val view = LocalView.current
    val nightMode = androidx.compose.foundation.isSystemInDarkTheme()
    LaunchedEffect(page, nightMode) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !nightMode && page != 0
        controller.isAppearanceLightNavigationBars = !nightMode
    }

    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = {
            // 顶栏 + 状态栏那一条用**同一个 surface 颜色**铺满，底下再压一条 1px 分隔线 ——
            // 不然"状态栏白 + 页面白"糊成一片，看不出层级（也就是之前那条白边不协调的根源）。
            if (page != 0) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    top.yukonga.miuix.kmp.basic.SmallTopAppBar(
                        title = when (page) {
                            1 -> "签到打卡"
                            2 -> "人脸录入"
                            3 -> "名单"
                            else -> "设置"
                        },
                        color = MaterialTheme.colorScheme.surface,
                        actions = {
                            if (state.busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 6.dp).size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            // 设置页是纯设置，不给「重载」
                            if (page != 4) {
                                TextButton(onClick = { vm.reloadGallery() }) {
                                    BtnContent("rotate", "重载", size = 15.dp)
                                }
                            }
                        }
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // 关键：MIUIX 的 Scaffold 默认 containerColor = surface（浅色下就是"白"），
        // 而且默认会把它内部的 contentWindowInsets 应用到内容区（systemBars）——
        // 于是内容上下永远留出系统栏那两条，露出白底：dock 下面那块"白块"就是它，
        // 同时也把 dock 顶高了（内容区被缩进 + dock 又在内容区里）。
        // 这里把内容区改成不吃 insets、底色改成页面底色，insets 由我们自己控制。
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        // dock 实际占掉的底部高度（含手势条），量出来之后再给页面留空间 ——
        // 以前写死 84dp，界面缩放放大后 dock 变高，页面内容就会从 dock 玻璃后面透出来，
        // 看起来就是"dock 背景上有一条细长条"。
        val density = LocalDensity.current
        var dockSpace by remember { mutableStateOf(84.dp) }

        // 外层 Box 永远铺满整屏；**dock 就挂在这一层**，所以它在每个页面上的位置完全一样。
        Box(Modifier.fillMaxSize()) {
            // 内层才是页面内容：识别页铺满（相机到边），其余页面让开顶栏、底部留出 dock 的高度。
            Box(
                if (page == 0) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                        .padding(bottom = dockSpace + 12.dp)
                }
            ) {
                when (page) {
                    0 -> RecognizePage(vm, state, camera, onOpenSettings = { page = 4 })
                    1 -> AttendancePage(vm, state)
                    2 -> EnrollFacePage(vm, state, camera, onOpenRoster = { page = 3 })
                    3 -> RosterPage(vm, state, onEnrollPerson = { name, klass ->
                        vm.setEnrollTarget(name, klass)
                        page = 2
                    })
                    else -> SettingsPage(vm, state)
                }
                if (!state.ready) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            state.status,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // dock 已经通铺到屏幕底边，下面不会再有缝隙/底色透出来，
            // 所以不再需要那块"dock 后面的实心带"。

            // ---------------- 底部悬浮导航栏（液态玻璃） ----------------
            // 挂在最外层、每个页面同一位置；不用 Scaffold 的 bottomBar（那一槽会被铺上不透明底色）。
            GlassDock(
                page = page,
                onSelect = { page = it },
                onMeasured = { h -> dockSpace = with(density) { h.toDp() } },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * 从任意 Context 往上找到 Activity（Compose 里拿 window 要用它来设状态栏图标明暗）。
 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 底部导航栏（液态玻璃）。
 *
 * 关于"真模糊"：Android 上没有既能拿到 CameraX 预览 Surface、又只糊一小块的办法
 * （RenderEffect 抓不到别的窗口/Surface 的内容，Android 12 的窗口模糊会把整窗糊掉），
 * 所以这里是**视觉玻璃**：低透明度填充 + 上缘高光描边 + 内侧暗边。
 *
 * **形态**：玻璃块从导航行一直铺到屏幕底边（只有上面两个角是圆的），
 * 图标行用 `navigationBarsPadding()` 抬到手势条上方。
 * 之前是"悬浮胶囊 + 20dp 投影 + 胶囊下面留 12dp 边距"，结果：
 *   * 投影在浅色底上是一条灰色带、往下渐隐到纯白 —— 就是那条"细长条"；
 *   * 胶囊和屏幕底边之间那条缝又是底色（纯白），更明显。
 * 现在玻璃通铺到底、不要投影，这两种"条"都不可能出现了。
 *
 * **动效**：选中态用① 滑动的玻璃指示器（spring + 中途拉长再回弹 = 液态拉伸）
 * ② 图标颜色过渡 + 轻微放大回弹 ③ 首次进入时整体上滑淡入。
 *
 * @param onMeasured 回调本 dock 实际占掉的高度，页面据此留出底部空间。
 */
@Composable
private fun GlassDock(
    page: Int,
    onSelect: (Int) -> Unit,
    onMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    // **四角都圆**的玻璃条：左右各留 14dp 边距 —— 全面屏的屏幕圆角会裁掉贴边的内容，
    // 留边之后 dock 不会被屏幕圆角切到。
    val barShape = RoundedCornerShape(26.dp)
    val sideMargin = 14.dp
    // 手势条（全面屏那条小横条）是系统画在我们上面的，压不到；所以这里：
    //   * 玻璃条本身**只到手势条上沿**，图标不会被手势条压住（就是你之前说的"别跟提示条重叠"）；
    //   * 手势条那一小块补一条**和玻璃底部同色**的实底，避免出现"贴地白块/灰条"。
    val gestureInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 选中指示器的颜色跟着主题色走（实心，保证看得见）
    val accent = MaterialTheme.colorScheme.primary

    // 首次出现：整体从下方滑上来 + 淡入
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            1f,
            androidx.compose.animation.core.spring(
                dampingRatio = 0.75f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
            )
        )
    }

    Column(
        modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasured(it.height) }
            .graphicsLayer {
                translationY = (1f - appear.value) * 56.dp.toPx()
                alpha = appear.value
            }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                // 左右留边：躲开全面屏的屏幕圆角（贴边会被裁掉）
                .padding(horizontal = sideMargin)
        ) {
            val itemWidth = maxWidth / 5
            val barWidth = maxWidth
            // ① 指示器位置：spring 滑过去
            val targetX = itemWidth * page
            val indicatorX by androidx.compose.animation.core.animateDpAsState(
                targetValue = targetX,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.60f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                ),
                label = "dockIndicator"
            )
            // ② 液态"拉开再收"：换页时指示器先明显拉长，再弹回原宽
            val stretch = remember { Animatable(1f) }
            LaunchedEffect(page) {
                stretch.snapTo(1f)
                stretch.animateTo(1.5f, androidx.compose.animation.core.tween(110))
                stretch.animateTo(
                    1f,
                    androidx.compose.animation.core.spring(dampingRatio = 0.38f, stiffness = 300f)
                )
            }
            val pillWidth = itemWidth * stretch.value
            val pillX = indicatorX + (itemWidth - pillWidth) / 2

            // ③ 玻璃光泽缓慢扫过（无限循环，让玻璃看起来"活着"）
            val sweep = androidx.compose.animation.core.rememberInfiniteTransition(label = "glassSweep")
            val sweepPhase by sweep.animateFloat(
                initialValue = -0.5f,
                targetValue = 1.5f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 5200,
                        easing = androidx.compose.animation.core.LinearEasing
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                ),
                label = "sweepPhase"
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(barShape)
                    .background(
                        Brush.verticalGradient(
                            if (dark) {
                                listOf(Color(0xB30B0D11), Color(0x8C14171C))
                            } else {
                                listOf(Color(0x7AFFFFFF), Color(0x3DFFFFFF))
                            }
                        )
                    )
                    // 上缘高光（玻璃的边光）
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                if (dark) {
                                    listOf(Color(0x4DFFFFFF), Color(0x0DFFFFFF))
                                } else {
                                    listOf(Color(0xCCFFFFFF), Color(0x24FFFFFF))
                                }
                            )
                        ),
                        barShape
                    )
            ) {
                // **不写死高度**：原来固定 50dp，系统字体一放大，文字下半截就被裁掉了。
                // 现在高度由内容（图标 + 文字）决定，指示器/光泽用 matchParentSize 跟着内容尺寸走。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    // ① 玻璃光泽：一条很淡的高光缓慢扫过
                    Box(Modifier.matchParentSize()) {
                        Box(
                            Modifier
                                .offset(x = barWidth * sweepPhase)
                                .width(barWidth * 0.45f)
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.White.copy(alpha = if (dark) 0.10f else 0.26f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                    }
                    // ② 滑动指示器：**实心主题色**（白色或淡色在白玻璃上等于看不见）
                    Box(Modifier.matchParentSize()) {
                        Box(
                            Modifier
                                .offset(x = pillX)
                                .width(pillWidth)
                                .fillMaxHeight()
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            accent.copy(alpha = 0.98f),
                                            accent.copy(alpha = 0.84f)
                                        )
                                    )
                                )
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = if (dark) 0.28f else 0.45f),
                                    RoundedCornerShape(20.dp)
                                )
                        )
                    }
                    // ③ 图标行（高度自适应，图标动效放大时也不会被裁）
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 每格等分宽度（5 格 = 各 1/5）
                        MiuixNavItem(page == 0, "camera", "识别", Modifier.weight(1f)) { onSelect(0) }
                        MiuixNavItem(page == 1, "clipboard-check", "签到", Modifier.weight(1f)) { onSelect(1) }
                        MiuixNavItem(page == 2, "id-card", "录入", Modifier.weight(1f)) { onSelect(2) }
                        MiuixNavItem(page == 3, "users", "名单", Modifier.weight(1f)) { onSelect(3) }
                        MiuixNavItem(page == 4, "gear", "设置", Modifier.weight(1f)) { onSelect(4) }
                    }
                }
            }
        }
        // 手势条区域：**透明占位**，不再补色。
        // 之前补了一条 `背景↔白 22%` 的实色，结果在识别页（相机是黑的）上就是一条突兀的浅色带；
        // 现在什么都不画，手势条那里露出的就是页面底色/相机画面，和系统原生表现一致。
        Spacer(Modifier.height(gestureInset))
    }
}
