package com.echoscribe.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * EchoScribe palette.
 *
 * Design intent: a warm, paper-like light mode and a deep, ink-like dark mode — the
 * same feeling as a well-set document in a native macOS app. Accent colours are
 * deliberately calm; the only saturated colour in the product is the recording red,
 * so "we are listening" is unambiguous at a glance.
 */

// ---- Light (paper) -------------------------------------------------------------------
val Paper0 = Color(0xFFFFFFFF)
val Paper1 = Color(0xFFFDFCFA) // app background
val Paper2 = Color(0xFFF7F5F1) // raised surface
val Paper3 = Color(0xFFEFECE6) // sunken / track
val Ink900 = Color(0xFF12131A) // primary text
val Ink600 = Color(0xFF5B5F6B) // secondary text
val Ink400 = Color(0xFF8C909C) // tertiary text / placeholder
val HairlineLight = Color(0xFFE3DFD8)

// ---- Dark (ink) ----------------------------------------------------------------------
val Night0 = Color(0xFF0B0C10)
val Night1 = Color(0xFF121319) // app background
val Night2 = Color(0xFF1A1C23) // raised surface
val Night3 = Color(0xFF23262F) // sunken / track
val Chalk100 = Color(0xFFF4F3F0) // primary text
val Chalk400 = Color(0xFFA8ADBA) // secondary text
val Chalk600 = Color(0xFF6E7484) // tertiary text
val HairlineDark = Color(0xFF2C2F39)

// ---- Accents -------------------------------------------------------------------------
/** Recording red — the single saturated colour in the product. */
val RecordRed = Color(0xFFE0443E)
val RecordRedSoft = Color(0xFFF06D67)
val RecordRedDim = Color(0xFFB3332E)

/** Primary action: a deep slate rather than an attention-seeking blue. */
val Slate700 = Color(0xFF2C3242)
val Slate500 = Color(0xFF444C60)
val Slate300 = Color(0xFF9AA3B8)

/** Muted sage used for "saved / ready" states and the live waveform. */
val Sage500 = Color(0xFF5F8B72)
val Sage300 = Color(0xFF8FB39C)

/** Warm amber for processing states. */
val Amber500 = Color(0xFFC98A3C)
