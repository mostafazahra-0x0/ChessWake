package com.mostafazahra.chesswake.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mostafazahra.chesswake.settings.domain.ThemeMode

private val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = Sage40,
    onSecondary = Color.White,
    secondaryContainer = Sage90,
    onSecondaryContainer = Sage20,
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber20,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Color(0xFF410002),
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    outline = NeutralVariant50,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Green20,
    primaryContainer = Green30,
    onPrimaryContainer = Green90,
    secondary = Sage80,
    onSecondary = Sage20,
    secondaryContainer = Sage30,
    onSecondaryContainer = Sage90,
    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    error = Red80,
    onError = Color(0xFF690005),
    errorContainer = RedContainer,
    onErrorContainer = Red90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    outline = NeutralVariant60,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral10,
)

/** The two board colours, overridable from settings. */
data class BoardColors(val light: Color, val dark: Color)

/** Provides the board colours to the board renderer without prop drilling. */
val LocalBoardColors = staticCompositionLocalOf {
    BoardColors(BoardLightDefault, BoardDarkDefault)
}

/**
 * True while rendering the full-screen alarm.
 *
 * The alarm screen forces its own high-contrast treatment (always dark, extra
 * large tap targets) because it is read by someone who is asleep, regardless of
 * what theme they chose for daytime use.
 */
val LocalAlarmMode = staticCompositionLocalOf { false }

/**
 * Applies the ChessWake Material 3 theme.
 *
 * Dynamic colour (Material You) is used on Android 12+ when the user has it
 * enabled, and the fallback palette otherwise, so the app looks intentional on
 * every supported version.
 *
 * On expressive Material 3: see the note in `Type.kt` for why this uses stable
 * `MaterialTheme` rather than the alpha `MaterialExpressiveTheme`.
 */
@Composable
fun ChessWakeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    boardColors: BoardColors = BoardColors(BoardLightDefault, BoardDarkDefault),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Material You: derive the palette from the user's wallpaper.
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    // Keep the system bars in step with the surface so edge-to-edge content does
    // not sit behind a bar of the wrong colour.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalBoardColors provides boardColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ChessWakeTypography,
            shapes = ChessWakeShapes,
            content = content,
        )
    }
}

/** Convenience accessor mirroring `MaterialTheme.colorScheme`. */
object ChessWakeThemeDefaults {
    val boardColors: BoardColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBoardColors.current

    val isAlarmMode: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalAlarmMode.current
}
