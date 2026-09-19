package com.tvremote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tvremote.app.ui.theme.RemoteColors

@Composable
fun BottomControls(
    muted: Boolean,
    listening: Boolean,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onAssistant: () -> Unit,
    onMute: () -> Unit,
    onMic: () -> Unit,
    onKeyboard: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Vertical volume rocker
        Column(
            modifier = Modifier
                .width(42.dp)
                .fillMaxHeight()
                .background(RemoteColors.BtnBg, RoundedCornerShape(21.dp))
        ) {
            VolRockerButton(Icons.Filled.VolumeUp, "Volume up", Modifier.weight(1f), onVolumeUp)
            VolRockerButton(Icons.Filled.VolumeDown, "Volume down", Modifier.weight(1f), onVolumeDown)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CircleIconButton(Icons.Filled.ArrowBack, "Back", size = 40.dp, onClick = onBack)
                CircleIconButton(Icons.Filled.Home, "Home", size = 40.dp, onClick = onHome)
                CircleIconButton(Icons.Filled.MoreHoriz, "Recent apps", size = 40.dp, onClick = onRecents)
                CircleIconButton(Icons.Filled.PlayArrow, "Google Assistant", size = 40.dp, onClick = onAssistant)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CircleIconButton(
                    icon = Icons.Filled.VolumeOff,
                    contentDescription = "Mute",
                    size = 40.dp,
                    tint = if (muted) RemoteColors.AccentRed else RemoteColors.IconDim,
                    onClick = onMute
                )
                CircleIconButton(
                    icon = Icons.Filled.Mic,
                    contentDescription = if (listening) "Stop listening" else "Microphone",
                    size = 40.dp,
                    tint = if (listening) RemoteColors.AccentRed else RemoteColors.IconDim,
                    onClick = onMic
                )
                CircleIconButton(Icons.Filled.Keyboard, "Keyboard", size = 40.dp, onClick = onKeyboard)
                CircleIconButton(Icons.Filled.MoreHoriz, "More", size = 40.dp, onClick = onMore)
            }
        }
    }
}

@Composable
private fun VolRockerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = RemoteColors.IconDim, modifier = Modifier.height(16.dp))
    }
}
