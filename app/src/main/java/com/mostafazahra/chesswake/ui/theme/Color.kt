package com.mostafazahra.chesswake.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ChessWake's fallback palette, used when dynamic colour is off or unavailable.
 *
 * Seeded from the same green as the launcher icon, with a warm amber tertiary so
 * that "correct" and "attention" states have somewhere to live that is not the
 * primary colour.
 */
internal val Green10 = Color(0xFF00210B)
internal val Green20 = Color(0xFF00391A)
internal val Green30 = Color(0xFF005229)
internal val Green40 = Color(0xFF2E7D32)
internal val Green80 = Color(0xFF7CDC96)
internal val Green90 = Color(0xFF98FAB0)

internal val Sage20 = Color(0xFF213528)
internal val Sage30 = Color(0xFF374B3D)
internal val Sage40 = Color(0xFF4E6354)
internal val Sage80 = Color(0xFFB5CCB9)
internal val Sage90 = Color(0xFFD0E8D5)

internal val Amber20 = Color(0xFF4A2800)
internal val Amber30 = Color(0xFF6A3C00)
internal val Amber40 = Color(0xFFA96A00)
internal val Amber80 = Color(0xFFFFB94F)
internal val Amber90 = Color(0xFFFFDDB0)

internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)
internal val Red90 = Color(0xFFFFDAD6)
internal val RedContainer = Color(0xFF93000A)

internal val Neutral10 = Color(0xFF191C19)
internal val Neutral20 = Color(0xFF2E312E)
internal val Neutral90 = Color(0xFFE1E3DE)
internal val Neutral95 = Color(0xFFEFF2EC)
internal val Neutral99 = Color(0xFFFBFDF7)
internal val NeutralVariant30 = Color(0xFF414941)
internal val NeutralVariant50 = Color(0xFF717971)
internal val NeutralVariant60 = Color(0xFF8B9389)
internal val NeutralVariant80 = Color(0xFFC1C9BE)
internal val NeutralVariant90 = Color(0xFFDDE5DA)

/**
 * Board colours.
 *
 * These intentionally ignore the app palette: a chess board should look like a
 * chess board at 6am, and a wallpaper-derived pink board would be genuinely hard
 * to read. Users can override both in settings.
 */
val BoardLightDefault = Color(0xFFF0D9B5)
val BoardDarkDefault = Color(0xFFB58863)

/** Highlight tints drawn over board squares. */
val HighlightSelected = Color(0x662E7D32)
val HighlightLastMove = Color(0x4DFFC400)
val HighlightLegalTarget = Color(0x80145520)
val HighlightCheck = Color(0x80D32F2F)
val HighlightWrongMove = Color(0x80D32F2F)
