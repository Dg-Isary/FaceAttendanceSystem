package com.facedemo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.extra.SuperDialog as MiuixSuperDialog
import top.yukonga.miuix.kmp.basic.Surface as MiuixSurface
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.SwitchDefaults

/**
 * MIUIX（小米 HyperOS 设计体系，Apache-2.0）同名包装。
 *
 * 思路：**组件名和参数跟 Material3 保持一致，内部换成 MIUIX 实现**，
 * 各页面里只要把 `androidx.compose.material3.*` 的 import 去掉，
 * 调用点一行都不用改，整个应用就变成 HyperOS 的观感（圆角、配色、按压反馈、
 * 开关/滑杆/对话框都是 MIUIX 原生的）。
 *
 * 说明：`Text` / `MaterialTheme` / `OutlinedTextField` / `CircularProgressIndicator`
 * 仍用 Material3 —— 文字组件被引用得最多、换成 MIUIX 收益小风险大；
 * 输入框的 API 差异较大（MIUIX 的 TextField 用字符串做 label），留到后续再迁移。
 */

// ----------------------------------------------------------------------
// 按钮
// ----------------------------------------------------------------------

/** 主按钮：MIUIX 主色（HyperOS 蓝） */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    MiuixButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = MiuixButtonDefaults.buttonColorsPrimary(),
        content = content
    )
}

/** 次要按钮：MIUIX 的浅灰底（替代 Material3 的 OutlinedButton） */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    MiuixButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = MiuixButtonDefaults.buttonColors(),
        content = content
    )
}

/** 文字按钮：透明底 + 主题蓝字（MIUIX 的 TextButton 只接受字符串，这里用透明按钮模拟） */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val scheme = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
    MiuixButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = MiuixButtonDefaults.buttonColors(
            color = Color.Transparent,
            contentColor = scheme.primary,
            disabledColor = Color.Transparent,
            disabledContentColor = scheme.disabledSecondaryVariant
        ),
        content = content
    )
}

// ----------------------------------------------------------------------
// 卡片 / 表面
// ----------------------------------------------------------------------

/** 占位类型：让 `CardDefaults.cardColors(...)` 的调用点保持原样（MIUIX 卡片自带配色） */
class MiuixCardColors

object CardDefaults {
    @Composable
    fun cardColors(containerColor: Color = Color.Unspecified): MiuixCardColors = MiuixCardColors()

    @Composable
    fun cardElevation(defaultElevation: Dp = 0.dp): Dp = defaultElevation
}

/** 卡片：MIUIX 的 16dp 圆角 + G2 连续曲率（smooth rounding） */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    colors: MiuixCardColors? = null,
    elevation: Dp? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    MiuixCard(modifier = modifier, content = content)
}

/** 表面：悬浮胶囊、结果卡用 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    MiuixSurface(
        modifier = modifier,
        shape = shape ?: androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = color,
        content = content
    )
}

// ----------------------------------------------------------------------
// 开关 / 滑杆
// ----------------------------------------------------------------------

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // v1.0.4：关掉的时候不再跟着主题色走 —— 按常规开关的样子，**关 = 中性灰轨道 + 白圆点**，
    // 开 = 主题色轨道 + 白圆点。（原来 MIUIX 默认在两个状态下都用主题色系，一眼看不出开还是关。）
    val colors = SwitchDefaults.switchColors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
        disabledCheckedThumbColor = Color.White.copy(alpha = 0.75f),
        disabledUncheckedThumbColor = Color.White.copy(alpha = 0.75f),
        disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        disabledUncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
    MiuixSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = colors,
        enabled = enabled
    )
}

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0
) {
    MiuixSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps
    )
}

// ----------------------------------------------------------------------
// 底部导航项（MIUIX 的 NavigationBarItem 只吃 ImageVector，
// 我们用的是 Font Awesome 字形，所以自己排一个，配色跟随 MIUIX）
// ----------------------------------------------------------------------

@Composable
fun MiuixNavItem(
    selected: Boolean,
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // 选中项是坐在**实心主题色胶囊**上的，所以图标和文字要用 onPrimary（白），
    // 不然"蓝字压蓝底"会看不见 —— 之前用白胶囊 + 主色字，两边都淡，等于没有胶囊
    val selectedColor = MaterialTheme.colorScheme.onPrimary
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "navTint"
    )
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (selected) 1.26f else 0.92f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.38f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "navScale"
    )
    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
            FaIcon(icon, size = 21.dp, tint = tint)
        }
        Text(
            label,
            fontSize = 10.sp,
            color = tint,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

// ----------------------------------------------------------------------
// 对话框
// ----------------------------------------------------------------------

/**
 * 对话框：用 MIUIX 的 SuperDialog 实现，参数保持 Material3 的形状，
 * 这样 8 处 `AlertDialog(...)` 调用点一个都不用改。
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    MiuixSuperDialog(
        show = true,
        onDismissRequest = onDismissRequest,
        content = {
            Column(
                modifier = modifier.fillMaxWidth().heightIn(max = 460.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                title?.invoke()
                // 正文用 weight 吃掉剩余高度，按钮永远排在下面 —— 否则内容一长，
                // 按钮就被挤出可视区、下半截被遮住（选人对话框就是这样）
                if (text != null) {
                    Box(Modifier.weight(1f, fill = false).padding(top = 10.dp)) { text() }
                }
                Row(
                    modifier = Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    )
}


