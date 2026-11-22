package com.gratilo.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gratilo.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun GratiloLaunch(content: @Composable () -> Unit) {
    var introFinished by rememberSaveable { mutableStateOf(false) }
    if (introFinished) {
        content()
        return
    }

    val logoAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, animationSpec = tween(durationMillis = 400)) }
        delay(3_000)
        introFinished = true
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.gratilo_logo),
                contentDescription = "Gratilo",
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().alpha(logoAlpha.value)
            )
        }
    }
}
