package com.tvremote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvremote.app.ui.theme.RemoteColors

@Composable
fun AppsRow(
    onYoutube: () -> Unit,
    onNetflix: () -> Unit,
    onPrime: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AppIcon(background = RemoteColors.YoutubeRed, onClick = onYoutube) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "YouTube", tint = Color.White)
        }
        AppIcon(background = RemoteColors.NetflixBlack, onClick = onNetflix) {
            Text("N", color = RemoteColors.NetflixRed, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
        }
        AppIcon(background = RemoteColors.PrimeBlue, onClick = onPrime) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Prime Video", tint = Color.White)
        }
        AppIcon(background = RemoteColors.BtnBg, onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = RemoteColors.Icon)
        }
    }
}

@Composable
private fun AppIcon(
    background: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(background, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
