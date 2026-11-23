package io.github.czefan.srunx.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1652D4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF535E78),
    secondaryContainer = Color(0xFFD7E2FF),
    surface = Color(0xFFF8F9FE),
    surfaceVariant = Color(0xFFE9ECF5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C7FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF003FA9),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFBBC6E4),
    secondaryContainer = Color(0xFF3B465F),
    surface = Color(0xFF11131A),
    surfaceVariant = Color(0xFF232734),
)

@Composable
fun SrunXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
