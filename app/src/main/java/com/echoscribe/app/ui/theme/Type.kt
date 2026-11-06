package com.echoscribe.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.echoscribe.app.R

/**
 * Typography.
 *
 * Inter (SIL Open Font License 1.1) is bundled — no network fetch, no Google Play
 * Services dependency, and consistent metrics across every Android OEM. The variable
 * font is used with explicit weight axes so one file serves all weights.
 *
 * Reading text (transcripts) gets noticeably more line height than UI text: it is the
 * content people actually sit and read for minutes at a time.
 */

private fun inter(weight: Int) = Font(
    resId = R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val InterFamily = FontFamily(
    inter(300),
    inter(400),
    inter(500),
    inter(600),
    inter(700),
)

private val lineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: Int,
    tracking: Float = 0f,
): TextStyle = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = lineHeightStyle,
)

val EchoTypography = Typography(
    // Screen titles
    displaySmall = style(34, 41, 600, tracking = -0.6f),
    headlineLarge = style(30, 37, 600, tracking = -0.5f),
    headlineMedium = style(25, 32, 600, tracking = -0.4f),
    headlineSmall = style(21, 28, 600, tracking = -0.2f),
    // Section headers
    titleLarge = style(19, 26, 600, tracking = -0.2f),
    titleMedium = style(16, 23, 600, tracking = -0.1f),
    titleSmall = style(14, 20, 600),
    // Body / UI
    bodyLarge = style(16, 25, 400),
    bodyMedium = style(14, 21, 400),
    bodySmall = style(13, 19, 400),
    // Controls & metadata
    labelLarge = style(14, 19, 500),
    labelMedium = style(12, 16, 500, tracking = 0.1f),
    labelSmall = style(11, 15, 500, tracking = 0.3f),
)

/** Style for the transcript body: generous measure, comfortable leading. */
val TranscriptBodyStyle = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight(400),
    fontSize = 17.sp,
    lineHeight = 29.sp,
    letterSpacing = 0.05.sp,
)

/** Numerals for the recording timer — tabular so digits do not jitter. */
val TimerStyle = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight(300),
    fontSize = 52.sp,
    lineHeight = 58.sp,
    letterSpacing = -1.5.sp,
    fontFeatureSettings = "tnum",
)

/** Small caps-ish metadata (dates, durations, language tags). */
val MetaStyle = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight(500),
    fontSize = TextUnit.Unspecified,
    letterSpacing = 0.4.sp,
)
