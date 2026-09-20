package com.facedemo.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDark
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLight

/** 应用版本号 —— 与 app/build.gradle.kts 里的 versionName 保持一致。 */
const val APP_VERSION = "1.1.0"

/**
 * 构建标记（每次出包时手动更新一次，格式 月日-时分）。
 *
 * 为什么要它：v1.0.1 这个版本号下面改了十几轮界面，光看"版本 1.0.1"分不清手机上装的是哪一次构建，
 * 排查问题时会互相猜。设置页顶部和「诊断与日志」里都会显示它，一眼就能对上。
 */
const val APP_BUILD = "0920-0136"

/** 录入输出规格（与 Enroll.OUTPUT_* 保持一致，放在这里方便界面引用） */
const val FACE_SPEC_ID_PHOTO = "idphoto"
const val FACE_SPEC_CUTOUT = "cutout"

/**
 * 界面缩放：覆盖 `LocalDensity`，dp 与 sp 一起缩放（等价系统的「显示大小」）。
 *
 * 放在 `FaceDemoApp` 里包住**设置页和主界面**两个分支，
 * 所以在设置页里拖动缩放滑杆时，设置页自己也会立刻跟着缩放。
 */
@Composable
fun UiScale(scale: Float, content: @Composable () -> Unit) {
    val base = androidx.compose.ui.platform.LocalDensity.current
    val scaled = androidx.compose.runtime.remember(base, scale) {
        androidx.compose.ui.unit.Density(base.density * scale, base.fontScale)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides scaled
    ) { content() }
}

/**
 * 主题色预设。
 *
 * MIUIX 的色板是"以某个主色为中心"生成的一整套（primary / primaryContainer / disabled* 等），
 * 这里只挑几个主色 token 覆盖，其余（背景、卡片、文字、错误色）保持 MIUIX 原样 ——
 * 这样换主题色只影响"强调色"，不会把整套界面的明暗关系搞乱。
 */
data class ThemePreset(val key: String, val label: String, val color: Color)

val THEME_PRESETS = listOf(
    ThemePreset("blue", "极光蓝", Color(0xFF3388FF)),
    ThemePreset("cyan", "青碧", Color(0xFF00B0A6)),
    ThemePreset("green", "森绿", Color(0xFF2AA45C)),
    ThemePreset("violet", "紫罗兰", Color(0xFF7C5CFF)),
    ThemePreset("amber", "琥珀", Color(0xFFF08A00)),
    ThemePreset("rose", "玫瑰", Color(0xFFEE4B6A))
)

fun themePresetColor(key: String): Color =
    THEME_PRESETS.firstOrNull { it.key == key }?.color ?: THEME_PRESETS.first().color

/**
 * 由主色推出一套 MIUIX 色板：只改"主色家族"，明暗模式各自的深浅用 lerp 插出来。
 *
 * 为什么不用 MIUIX 的 Monet/keyColor 生成器：那条路要换用 `ThemeController` 版的主题入口、
 * 而且要 API 31+ 的动态取色，行为和机型绑定；这里用显式色值最可控，也能保证深色模式不发灰。
 */
private fun brandLight(brand: Color): Colors = miuixLight(
    primary = brand,
    onPrimary = Color.White,
    primaryVariant = lerp(brand, Color.Black, 0.16f),
    onPrimaryVariant = Color.White,
    primaryContainer = lerp(brand, Color.White, 0.86f),
    onPrimaryContainer = lerp(brand, Color.Black, 0.30f),
    disabledPrimary = lerp(brand, Color.White, 0.62f),
    disabledPrimaryButton = lerp(brand, Color.White, 0.55f),
    disabledPrimarySlider = lerp(brand, Color.White, 0.62f),
    secondary = brand,
    onSecondary = Color.White,
    secondaryVariant = lerp(brand, Color.Black, 0.16f),
    onSecondaryVariant = Color.White
)

private fun brandDark(brand: Color): Colors = miuixDark(
    primary = lerp(brand, Color.White, 0.12f),
    onPrimary = Color.White,
    primaryVariant = lerp(brand, Color.White, 0.30f),
    onPrimaryVariant = lerp(brand, Color.Black, 0.70f),
    primaryContainer = lerp(brand, Color.Black, 0.62f),
    onPrimaryContainer = lerp(brand, Color.White, 0.72f),
    disabledPrimary = lerp(brand, Color.Black, 0.55f),
    disabledPrimaryButton = lerp(brand, Color.Black, 0.50f),
    disabledPrimarySlider = lerp(brand, Color.Black, 0.55f),
    secondary = lerp(brand, Color.White, 0.12f),
    onSecondary = Color.White,
    secondaryVariant = lerp(brand, Color.White, 0.30f),
    onSecondaryVariant = lerp(brand, Color.Black, 0.70f)
)

/**
 * 应用主题：**以 MIUIX（小米 HyperOS 设计体系）为底**。
 *
 * 做法：先套 MIUIX 主题（色板按用户选的主题色生成），再从 `MiuixTheme.colorScheme`
 * 把色值**搬进 MaterialTheme**，这样两套组件用的是同一个色板 ——
 *   * MIUIX 的 Card / Button / Switch / Slider / 对话框 用 MIUIX 自己的颜色
 *   * 我们自己的 Text、页面背景等走 MaterialTheme，取值与 MIUIX 一致，不会两个色系打架
 *
 * @param themeColor 主题色 key（见 [THEME_PRESETS]），默认极光蓝
 */
@Composable
fun FaceDemoTheme(themeColor: String = "blue", content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val brand = themePresetColor(themeColor)
    val scheme = remember(dark, brand) { if (dark) brandDark(brand) else brandLight(brand) }
    MiuixTheme(colors = scheme) {
        val m = MiuixTheme.colorScheme
        MaterialTheme(
            colorScheme = if (dark) {
                darkColorScheme(
                    primary = m.primary,
                    onPrimary = m.onPrimary,
                    primaryContainer = m.primaryContainer,
                    onPrimaryContainer = m.onPrimaryContainer,
                    secondary = m.secondary,
                    onSecondary = m.onSecondary,
                    tertiary = m.tertiaryContainer,
                    background = m.background,
                    onBackground = m.onBackground,
                    surface = m.surface,
                    onSurface = m.onSurface,
                    surfaceVariant = m.secondaryContainer,
                    onSurfaceVariant = m.onSurfaceVariantSummary,
                    error = m.error,
                    onError = m.onError,
                    outline = m.onBackgroundVariant,
                    outlineVariant = m.surfaceVariant
                )
            } else {
                lightColorScheme(
                    primary = m.primary,
                    onPrimary = m.onPrimary,
                    primaryContainer = m.primaryContainer,
                    onPrimaryContainer = m.onPrimaryContainer,
                    secondary = m.secondary,
                    onSecondary = m.onSecondary,
                    tertiary = m.tertiaryContainer,
                    background = m.background,
                    onBackground = m.onBackground,
                    surface = m.surface,
                    onSurface = m.onSurface,
                    surfaceVariant = m.secondaryContainer,
                    onSurfaceVariant = m.onSurfaceVariantSummary,
                    error = m.error,
                    onError = m.onError,
                    outline = m.onBackgroundVariant,
                    outlineVariant = m.surfaceVariant
                )
            },
            content = content
        )
    }
}

/** 识别结果的颜色: 通过=绿, 疑似=橙, 陌生人=红 */
fun decisionColor(accepted: Boolean, decision: String): Color = when {
    accepted -> Color(0xFF25C55E)
    decision == "疑似" -> Color(0xFFFFA726)
    else -> Color(0xFFEF5350)
}
