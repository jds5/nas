package org.rokano.nasremote

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.rokano.nasremote.ui.NasRemoteApp

class MainActivity : ComponentActivity() {
    private val model: RemoteViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { NasRemoteApp(model) }
    }
    override fun onStart() { super.onStart(); model.foreground(true) }
    override fun onStop() {
        if (!isChangingConfigurations) model.foreground(false)
        super.onStop()
    }
}
