package com.facedemo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.facedemo.app.ui.FaceDemoTheme
import com.facedemo.app.ui.shareText
import java.io.File

/**
 * 崩溃说明页：把堆栈原文显示出来，并提供“分享/保存”按钮。
 *
 * 目的很实际 —— 手机上的闪退没法看控制台，这样用户可以直接把原因发出来。
 * 页面里也会写出崩溃文件的确切路径，拷文件也行。
 */
class CrashActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TEXT = "crash_text"
        const val EXTRA_PATH = "crash_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "(没有崩溃信息)"
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        setContent {
            FaceDemoTheme {
                CrashScreen(
                    text = text,
                    path = path,
                    onShare = { shareText(this, text, "崩溃日志.txt", "text/plain") },
                    onClose = { finish() }
                )
            }
        }
    }

    @Composable
    private fun CrashScreen(text: String, path: String, onShare: () -> Unit, onClose: () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("程序遇到了一个错误", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "下面是完整的错误信息。点“分享日志”可以直接发给开发者（微信/邮件都行）；" +
                        "也可以去文件管理器取这个文件：" + (path.ifEmpty { "应用私有目录/crash_last.txt" }),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 10.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        Text(text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(modifier = Modifier.weight(1f), onClick = onShare) { Text("分享日志") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = onClose) { Text("关闭") }
                }
                if (path.isNotEmpty()) {
                    val file = File(path)
                    Text(
                        "文件: $path" + (if (file.exists()) "  (${file.length()} 字节)" else "  (未写入)"),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
