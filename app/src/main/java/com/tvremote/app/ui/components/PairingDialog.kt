package com.tvremote.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.tvremote.app.ui.theme.RemoteColors

/**
 * Shown the first time this app connects to a given TV. The TV displays a
 * 6-character code on screen (the same flow as the official Google TV app);
 * once the person types it in here, pairing completes and never needs to
 * happen again for that TV.
 */
@Composable
fun PairingDialog(
    isSubmitting: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("TV se pairing code daalo") },
        text = {
            Column {
                Text(
                    "TV screen par ek 6-character code dikh raha hoga — wahi yahan type karo.",
                    color = RemoteColors.MutedText
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6) code = it.uppercase() },
                    placeholder = { Text("A1B2C3") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = RemoteColors.AccentRed,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (code.isNotBlank()) onSubmit(code) }, enabled = !isSubmitting) {
                Text("Pair karo")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isSubmitting) { Text("Cancel") }
        }
    )
}

/**
 * Shown after a failed pairing attempt (wrong/expired code, or a connection
 * error). Retrying re-opens the pairing socket from scratch, which is what
 * makes the TV display a fresh code — so this intentionally does NOT reuse
 * whatever the person typed last time.
 */
@Composable
fun PairingErrorDialog(
    message: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pairing fail ho gayi") },
        text = { Text(message, color = RemoteColors.MutedText) },
        confirmButton = {
            Button(onClick = onRetry) { Text("Try Again") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
