package com.tvremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.tvremote.app.ui.RemoteScreen
import com.tvremote.app.ui.theme.TVRemoteTheme

class MainActivity : ComponentActivity() {

    // Same ViewModelStore the composable's viewModel() resolves against, so
    // this is the very same instance RemoteScreen is driving.
    private val remoteViewModel: RemoteViewModel by lazy {
        ViewModelProvider(this)[RemoteViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TVRemoteTheme {
                RemoteScreen(viewModel = remoteViewModel)
            }
        }
    }

    /**
     * Android TVs drop an idle remote socket, and Android itself will freeze
     * our background sockets anyway. Rather than pretending the session
     * survived, we re-dial the moment the person comes back to the app — which
     * is what removes the "tap the TV in the list again to wake it up" step.
     */
    override fun onStart() {
        super.onStart()
        remoteViewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        remoteViewModel.onBackground()
    }
}
