package com.tvremote.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RemoteDarkScheme = darkColorScheme(
    background = RemoteColors.PageBg2,
    surface = RemoteColors.ScreenBg1,
    primary = RemoteColors.AccentGreen,
    onBackground = RemoteColors.Icon,
    onSurface = RemoteColors.Icon
)

@Composable
fun TVRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RemoteDarkScheme,
        content = content
    )
}
