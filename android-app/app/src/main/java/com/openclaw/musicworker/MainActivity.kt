package com.openclaw.musicworker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openclaw.musicworker.data.AppContainer
import com.openclaw.musicworker.ui.AppViewModel
import com.openclaw.musicworker.ui.MusicWorkerApp
import com.openclaw.musicworker.ui.theme.MusicWorkerTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer(applicationContext) }

    private val viewModel: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                AppViewModel(container.repository)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MusicWorkerTheme {
                MusicWorkerApp(viewModel = viewModel)
            }
        }
    }
}
