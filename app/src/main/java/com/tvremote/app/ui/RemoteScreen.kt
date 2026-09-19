package com.tvremote.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvremote.app.ConnectionState
import com.tvremote.app.RemoteViewModel
import com.tvremote.app.VoiceState
import com.tvremote.app.ui.components.AppsRow
import com.tvremote.app.ui.components.BottomControls
import com.tvremote.app.ui.components.PairingDialog
import com.tvremote.app.ui.components.PairingErrorDialog
import com.tvremote.app.ui.components.TopBar
import com.tvremote.app.ui.components.Touchpad
import com.tvremote.app.ui.components.VolumeSlider
import com.tvremote.app.ui.theme.RemoteColors

@Composable
fun RemoteScreen(viewModel: RemoteViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoice() else viewModel.onMicPermissionDenied()
    }

    val onMicPressed: () -> Unit = {
        if (state.voiceState != VoiceState.IDLE) {
            viewModel.stopVoice()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startVoice()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(listOf(RemoteColors.ScreenBg1, RemoteColors.ScreenBg2))
            )
            .padding(horizontal = 16.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TopBar(
            powerOn = state.powerOn,
            onPowerClick = viewModel::togglePower,
            online = state.online,
            tvName = state.selectedTvName,
            pickerOpen = state.pickerOpen,
            discoveredTvs = state.discoveredTvs,
            onTogglePicker = viewModel::togglePicker,
            onSelectTv = viewModel::connectTo,
            onMenuClick = viewModel::menu
        )

        val hint = state.statusMessage ?: when (state.connectionState) {
            ConnectionState.SCANNING -> "Scanning your Wi-Fi for a TV\u2026"
            ConnectionState.CONNECTING -> "TV se dobara connect kar raha hoon\u2026"
            ConnectionState.DISCONNECTED -> "Tap the TV name above to find your Android TV"
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                color = RemoteColors.MutedText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        VolumeSlider(
            percent = state.volumePercent,
            known = state.volumeKnown,
            onChange = viewModel::setVolume,
            onStep = viewModel::stepVolume
        )

        AppsRow(
            onYoutube = viewModel::launchYoutube,
            onNetflix = viewModel::launchNetflix,
            onPrime = viewModel::launchPrime,
            onSettings = viewModel::openSettings
        )

        Touchpad(
            cursorMode = state.cursorMode,
            onCursorModeToggle = viewModel::toggleCursorMode,
            onDirection = viewModel::dpadDirection,
            onTap = viewModel::dpadTap,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        BottomControls(
            muted = state.muted,
            listening = state.voiceState != VoiceState.IDLE,
            onVolumeUp = { viewModel.stepVolume(true) },
            onVolumeDown = { viewModel.stepVolume(false) },
            onBack = viewModel::back,
            onHome = viewModel::home,
            onRecents = viewModel::recentApps,
            onAssistant = viewModel::assistant,
            onMute = viewModel::toggleMute,
            onMic = onMicPressed,
            onKeyboard = viewModel::openKeyboard,
            onMore = viewModel::menu,
            modifier = Modifier.height(96.dp)
        )
    }

    if (state.connectionState == ConnectionState.NEEDS_PAIRING_CODE ||
        state.connectionState == ConnectionState.PAIRING
    ) {
        // Reaching here means the pairing socket is open and the TV is
        // actively showing a code right now — safe to ask for it.
        PairingDialog(
            isSubmitting = state.connectionState == ConnectionState.PAIRING,
            errorMessage = null,
            onSubmit = viewModel::submitPairingCode,
            onCancel = viewModel::cancelPairing
        )
    } else {
        val errorMessage = state.errorMessage
        if (state.connectionState == ConnectionState.ERROR && errorMessage != null) {
            PairingErrorDialog(
                message = errorMessage,
                onRetry = viewModel::retryPairing,
                onCancel = viewModel::cancelPairing
            )
        }
    }
}
