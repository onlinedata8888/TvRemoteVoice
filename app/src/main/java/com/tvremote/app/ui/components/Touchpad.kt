package com.tvremote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvremote.app.ui.theme.RemoteColors

/**
 * The main navigation surface. A drag beyond [thresholdPx] fires one DPAD
 * step in that direction (repeating as the drag continues), and a tap sends
 * DPAD_CENTER — this is how remote apps drive a TV that has no real pointer.
 */
@Composable
fun Touchpad(
    cursorMode: Boolean,
    onCursorModeToggle: () -> Unit,
    onDirection: (dx: Int, dy: Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    thresholdPx: Float = 55f
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RemoteColors.PadBg, RoundedCornerShape(22.dp))
            .pointerInput(Unit) {
                var accumulated = Offset.Zero
                // Only one direction command per touch — otherwise a single
                // real-world swipe (which easily covers more than thresholdPx)
                // ends up firing 2-3 times and the TV jumps multiple steps
                // for what felt like one gesture.
                var fired = false
                detectDragGestures(
                    onDragStart = { accumulated = Offset.Zero; fired = false },
                    onDragEnd = { accumulated = Offset.Zero; fired = false },
                    onDragCancel = { accumulated = Offset.Zero; fired = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (fired) return@detectDragGestures
                        accumulated += dragAmount
                        if (kotlin.math.abs(accumulated.x) > thresholdPx || kotlin.math.abs(accumulated.y) > thresholdPx) {
                            onDirection(accumulated.x.toInt(), accumulated.y.toInt())
                            fired = true
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "touch pad",
            color = RemoteColors.MutedText,
            fontSize = 11.sp
        )

        Box(modifier = Modifier.padding(10.dp).fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            CircleIconButton(
                icon = Icons.Filled.NearMe,
                contentDescription = "Cursor mode",
                size = 36.dp,
                background = if (cursorMode) Color(0x2E22D3B8) else Color(0x8C14151A),
                tint = if (cursorMode) RemoteColors.SliderFill1 else RemoteColors.Icon,
                onClick = onCursorModeToggle
            )
        }
    }
}
