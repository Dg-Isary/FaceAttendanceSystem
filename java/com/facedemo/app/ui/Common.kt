package com.facedemo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/** 小节卡片 (标题前带一个圆形图标徽章) */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        FaIcon(icon, size = 15.dp, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            Box(Modifier.padding(top = 12.dp)) {
                // 卡片内的项之间留出间距，否则滑杆/开关/按钮行会互相贴住
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
    }
}

/** 设置页/列表里的小节标题: 图标 + 文字 */
@Composable
fun SectionHeader(icon: String, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FaIcon(icon, size = 13.dp, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

/* ------------------------------------------------------------------ */
/*  颜色色块：直接用颜色表示，比写“白底/蓝底/红底”直观也简洁            */
/* ------------------------------------------------------------------ */

/** 背景色 -> 实际颜色（transparent 返回 null，画棋盘格表示“透明”） */
fun backgroundColorOf(name: String): androidx.compose.ui.graphics.Color? = when (name) {
    "white" -> Color(0xFFFFFFFF)
    "blue" -> Color(0xFF438EDB)
    "red" -> Color(0xFFD62D2D)
    "gray" -> Color(0xFF9AA0A6)
    else -> null
}

fun backgroundLabelOf(name: String): String = when (name) {
    "white" -> "白底"
    "blue" -> "蓝底"
    "red" -> "红底"
    "gray" -> "灰底"
    else -> "透明底"
}

/**
 * 一个颜色色块。选中时外面套一圈主题色圆环。
 * 透明用“棋盘格”表示（和设计软件里的习惯一致）。
 */
@Composable
fun ColorSwatch(
    name: String,
    selected: Boolean,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: () -> Unit
) {
    val color = backgroundColorOf(name)
    val ring = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(ring)
            .padding(if (selected) 3.dp else 1.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (color != null) {
            Box(Modifier.fillMaxSize().background(color))
            if (selected) {
                FaIcon(
                    "check",
                    size = 15.dp,
                    tint = if (name == "white") Color(0xFF444444) else Color.White
                )
            }
        } else {
            // 透明：棋盘格（Canvas 里不是 @Composable 上下文, 颜色要先在外部取好）
            val ringColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val cell = this.size.minDimension / 4f
                for (row in 0..3) {
                    for (col in 0..3) {
                        val light = (row + col) % 2 == 0
                        drawRect(
                            color = if (light) Color(0xFFFFFFFF) else Color(0xFFD8DCE3),
                            topLeft = androidx.compose.ui.geometry.Offset(col * cell, row * cell),
                            size = androidx.compose.ui.geometry.Size(cell, cell)
                        )
                    }
                }
                if (selected) {
                    drawCircle(
                        color = ringColor,
                        radius = this.size.minDimension / 2f - 1f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                    )
                }
            }
        }
    }
}

/**
 * 一排按钮的容器：**每个按钮按自己的文字取宽度，一行放不下就自动换行**，每行居中。
 *
 * 适合"数量不定 / 文字长短差别大"的场合（班级标签、背景色块这种）。
 * 数量固定、想让它们**撑满整行、平分宽度**的场合用 [FillRow]。
 */
@Composable
fun BtnRow(
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable FlowRowScope.() -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = verticalAlignment,
        content = content
    )
}

/**
 * 一行**等宽**按钮：撑满整行、平均分宽、按钮内容居中。
 *
 * 为什么要有它：用 [BtnRow] 时每个按钮有个最小宽度，界面缩放调大后
 * 4 个短按钮（例如「默认/媒体/通知/系统」）明明挤一挤放得下，却因为最小宽度被顶到第二行，
 * 看着又散又不齐。这里改成"平分整行宽度"，一行永远放得下；
 * 文字放不下时由 [LocalButtonAutoShrink] 让它**自动缩字**，而不是换行或者切掉半个字。
 *
 * 用法（按钮里要写 `Modifier.weight(1f)`）：
 * ```
 * FillRow {
 *     Button(Modifier.weight(1f)) { BtnContent("check", "保存") }
 *     OutlinedButton(Modifier.weight(1f)) { BtnContent("xmark", "取消") }
 * }
 * ```
 */
@Composable
fun FillRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    CompositionLocalProvider(LocalButtonAutoShrink provides true) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * 在 [FillRow] 里的按钮文字是否允许自动缩字。
 * 只有等宽按钮行里才打开 —— 其它地方的文字保持原来的字号不动。
 */
val LocalButtonAutoShrink = compositionLocalOf { false }

/** 一行: 标签 + 值 (可选前置图标) */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    icon: String? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            FaIcon(icon, size = 13.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            key, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 把滑杆的值吸附到"分度"上（等分 [steps]+1 段，端点对齐 range）。
 * 传 step 而不是 steps 是为了不用手算段数，改范围时也不会出现半格的怪值。
 */
private fun snapToStep(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int
): Float {
    if (steps <= 0) return value
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val segments = steps + 1
    val index = ((value - range.start) / span * segments).roundToInt().coerceIn(0, segments)
    return range.start + span * index / segments
}

/** 由目标分度值算出 Slider 需要的 steps（= 段数 − 1） */
private fun stepCount(range: ClosedFloatingPointRange<Float>, step: Float): Int {
    if (step <= 0f) return 0
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0
    return ((span / step).roundToInt() - 1).coerceAtLeast(0)
}

/**
 * 带数值显示的滑杆。
 *
 * @param step 分度值（推荐用它而不是 [steps]）：例如 0.025 表示每次只走 0.025 一格。
 *   识别那几个滑杆原来不分度，手指稍微一动值就跳好几个千分位，很难停在想要的数上；
 *   给了 step 之后拖动会"一格一格"停，好控制。
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueText: String = String.format(Locale.US, "%.3f", value),
    enabled: Boolean = true,
    step: Float? = null,
    onValueChange: (Float) -> Unit
) {
    val count = if (step != null) stepCount(range, step) else steps
    val shown = if (step != null) snapToStep(value, range, count) else value
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                valueText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        Slider(
            value = shown,
            onValueChange = { raw ->
                onValueChange(if (step != null) snapToStep(raw, range, count) else raw)
            },
            valueRange = range,
            steps = count,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
    }
}

/** 一行开关 */
@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 列表行里的**纯图标小圆钮**。
 *
 * 为什么要有它：名单行里原来放「录入 / 改 / 删除」三个带文字的按钮，一行宽度不够时
 * 文字会被挤得裁掉（图标还在、字没了，看起来像"按钮没文字"）。
 * 这种"看一眼图标就懂"的动作直接用图标，既不会被裁又更清爽。
 *
 * @param description 无障碍描述（TalkBack 会读出来），也是长按提示里的名字
 */
@Composable
fun CircleIconButton(
    icon: String,
    tint: androidx.compose.ui.graphics.Color,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        FaIcon(icon, size = size * 0.46f, tint = tint)
    }
}

/**
 * 深色卡片上的胶囊按钮（**颜色写死**，不跟随 MIUIX 按钮的取色）。
 *
 * 为什么需要它：录入结果卡、识别结果卡都是深色半透明卡片（`0xE6101010`），
 * 而 MIUIX 的 `TextButton` 在这种底上文字会显示成黑色 → 按钮"看不见"。
 * 这里显式给前景色：实心款 = 主题色底 + `onPrimary` 字；描边款 = 白 14% 底 + 白字。
 *
 * @param filled true = 主操作（实心主题色），false = 次操作（半透明白描边）
 */
@Composable
fun OnDarkPillButton(
    icon: String,
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (filled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.16f)
    // 两种款式都**写死白字**：深色卡片上最稳，不会被主题里各种 on* 颜色算歪
    val fg = Color.White
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(
                1.dp,
                if (filled) Color.Transparent else Color.White.copy(alpha = 0.45f),
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FaIcon(icon, size = 14.dp, tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/** 小圆点状态徽标 */
@Composable
fun StatusChip(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(7.dp).background(color, RoundedCornerShape(50))
        )
        Text(
            text, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
