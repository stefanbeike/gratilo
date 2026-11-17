package com.gratilo.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val GratiloBlue = Color(0xFF245A88)
val GratiloMint = Color(0xFF43E5CE)
val GratiloPink = Color(0xFFF326A5)
@Composable fun GratiloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = GratiloBlue, secondary = Color(0xFF123F46),
        tertiary = GratiloPink, background = Color(0xFFF5F8FB), surface = Color.White,
        secondaryContainer = Color(0xFFD9F4E9), error = Color(0xFFA54545)),
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content)
}
