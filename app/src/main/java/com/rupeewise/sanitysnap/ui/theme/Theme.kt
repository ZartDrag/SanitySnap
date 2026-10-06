package com.rupeewise.sanitysnap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF1CC29F)
private val TealDark = Color(0xFF0E8C73)

@Composable
fun FairShareTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Teal, secondary = TealDark)
    } else {
        lightColorScheme(primary = TealDark, secondary = Teal)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
