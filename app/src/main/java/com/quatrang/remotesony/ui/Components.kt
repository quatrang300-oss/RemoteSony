@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.quatrang.remotesony.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Nút remote: phản hồi ngay khi chạm, rung nhẹ.
 * [repeat] = giữ để lặp (âm lượng, kênh, mũi tên).
 */
@Composable
fun KeyButton(
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = RoundedCornerShape(16.dp),
    border: Boolean = true,
    onPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onPress)
    val outline = MaterialTheme.colorScheme.outlineVariant
    var base = modifier.clip(shape)
        .background(if (pressed) container.copy(alpha = 0.55f) else container)
    if (border) base = base.border(0.5.dp, outline, shape)
    Box(
        modifier = base.pointerInput(repeat) {
            detectTapGestures(onPress = {
                pressed = true
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                latest()
                var job: Job? = null
                if (repeat) {
                    job = scope.launch {
                        delay(450)
                        while (isActive) {
                            latest()
                            delay(120)
                        }
                    }
                }
                tryAwaitRelease()
                job?.cancel()
                pressed = false
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
fun KeyIcon(icon: ImageVector, label: String, size: Int = 24) {
    Icon(icon, contentDescription = label, modifier = Modifier.size(size.dp))
}

@Composable
fun KeyLabel(text: String, size: Int = 14) {
    Text(text, fontSize = size.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun StatusPill(text: String, bg: Color, fg: Color, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(icon, null, modifier = Modifier.size(14.dp), tint = fg)
            Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = RoundedCornerShape(50), color = bg, content = content)
    } else {
        Surface(shape = RoundedCornerShape(50), color = bg, content = content)
    }
}
