package com.tvremote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvremote.app.protocol.discovery.DiscoveredTv
import com.tvremote.app.ui.theme.RemoteColors

@Composable
fun TopBar(
    powerOn: Boolean,
    onPowerClick: () -> Unit,
    online: Boolean,
    tvName: String,
    pickerOpen: Boolean,
    discoveredTvs: List<DiscoveredTv>,
    onTogglePicker: () -> Unit,
    onSelectTv: (DiscoveredTv) -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(
                icon = Icons.Filled.PowerSettingsNew,
                contentDescription = "Power",
                tint = RemoteColors.AccentRed,
                background = if (powerOn) Color(0x24EF4444) else RemoteColors.BtnBg,
                onClick = onPowerClick
            )

            Row(
                modifier = Modifier
                    .clickable(onClick = onTogglePicker)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (online) RemoteColors.StatusOnline else RemoteColors.StatusOffline,
                            CircleShape
                        )
                )
                Text(
                    text = tvName,
                    color = RemoteColors.AccentGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 5.dp)
                )
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Select TV",
                    tint = RemoteColors.AccentGreen,
                    modifier = Modifier.size(14.dp).padding(start = 2.dp)
                )
            }

            CircleIconButton(
                icon = Icons.Filled.Menu,
                contentDescription = "Menu",
                onClick = onMenuClick
            )
        }

        if (pickerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Color(0xFF1D1F26), RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = if (discoveredTvs.isEmpty()) "Searching for TVs on your Wi-Fi…"
                               else "TVs found on your Wi-Fi",
                        color = RemoteColors.MutedText,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(6.dp)
                    )
                    discoveredTvs.forEach { tv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTv(tv) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(RemoteColors.StatusOnline, CircleShape)
                            )
                            Text(
                                text = tv.name,
                                color = RemoteColors.IconDim,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
