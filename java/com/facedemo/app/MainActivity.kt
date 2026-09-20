package com.facedemo.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.facedemo.app.core.CrashReporter
import com.facedemo.app.core.OpenCv
import com.facedemo.app.ui.FaceDemoApp
import com.facedemo.app.ui.FaceDemoTheme
import com.facedemo.app.vm.AppViewModel

/**
 * 人脸识别演示程序 (安卓版) —— 与电脑版 Python 程序功能一致:
 *
 *   识别: YuNet 检测 + SFace 特征 + 余弦相似度, 达到阈值播报“某某你好”
 *   打卡: 签到/签退, 全员到场看板, 手动补签, 导出点名表
 *   录入: 从画面或相册录入, PP-HumanSeg 自动抠像去背景, 存 PNG 进人脸库
 *   名单: CSV 导入 (自动识别 GBK/UTF-16 编码), 按班级管理
 *   日志: 相似度流水 / 事件 / 快照, 可分析并给出调参建议
 *
 * 用的是和电脑版完全相同的三个 ONNX 模型 (assets/models 首次启动释放到私有目录),
 * 所以两端的相似度和阈值是可以互相印证的。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) 先装崩溃收集器: 后面任何一步出错都能留下可读的堆栈, 而不是白屏闪退
        CrashReporter.install(this)

        // 2) 加载 OpenCV 原生库。官方 AAR 不会自动加载, 不加载就会在第一次用
        //    Mat / FaceDetectorYN / Dnn 时抛 UnsatisfiedLinkError (Error, 接不住) 直接闪退。
        val opencvReady = OpenCv.ensureLoaded()

        viewModel.initialize(opencvReady)

        // 3) 处理“通过打开方式进来”的文件（微信/文件管理里点 csv 或 zip 选本应用）
        handleExternalIntent(intent)

        // 4) 状态栏 / 手势条：图标明暗跟着系统深浅色走
        //    （浅色主题要深色图标，否则白底白图标看不见；深色反过来）
        //    这里只改"图标明暗"，不去动窗口的 insets 策略 —— 保持系统默认的
        //    "内容不钻进系统栏"的行为，兼容 Android 8~14；Android 15+ 由系统强制边到边，
        //    那部分由 Compose 里的 statusBarsPadding / navigationBarsPadding 负责。
        applySystemBarAppearance()

        setContent {
            val state by viewModel.state.collectAsState()
            // 主题色跟随设置（在「设置 → 界面 → 主题色」里选，改完立刻生效）
            FaceDemoTheme(themeColor = state.themeColor) {
                FaceDemoApp(viewModel, state, this@MainActivity)
            }
        }
    }

    /** 系统栏图标明暗：跟随当前系统的深/浅色。 */
    private fun applySystemBarAppearance() {
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    /**
     * ACTION_VIEW / ACTION_SEND 进来的文件：
     *   *.zip        → 数据恢复（名单 + 人脸库 + 日志）
     *   csv 或 txt  → 名单导入
     * 其它类型给一句提示，不会闪退。
     */
    private fun handleExternalIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val uri: Uri = when (action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        try {
            val name = viewModel.nameOf(uri)
            viewModel.onExternalFileOpened(uri, name)
        } catch (t: Throwable) {
            Log.e("MainActivity", "处理外部文件失败", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) viewModel.speakerRef()?.stop()
    }
}
