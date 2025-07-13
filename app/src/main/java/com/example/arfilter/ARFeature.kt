package com.example.arfilter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.arfilter.ui.screens.PowerliftingScreen
import com.example.arfilter.ui.screens.PermissionScreen
import com.example.arfilter.ui.theme.ARFilterTheme
import com.example.arfilter.viewmodel.PowerliftingViewModel

class ARFeature : ComponentActivity() {
    private var hasCameraPermission by mutableStateOf(false)
    private var hasMicrophonePermission by mutableStateOf(false)
    private var permissionsRequested by mutableStateOf(false)

    // Request multiple permissions launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
        hasMicrophonePermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        permissionsRequested = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check initial permissions
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasMicrophonePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            ARFilterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: PowerliftingViewModel = viewModel()

                    when {
                        hasCameraPermission -> {
                            // Camera permission granted, proceed with the app
                            // Note: Microphone permission is optional for voice commands
                            PowerliftingScreen(viewModel = viewModel)
                        }

                        !permissionsRequested -> {
                            // Show permission screen for required permissions
                            PermissionScreen(
                                onRequestPermission = {
                                    // Request both camera (required) and microphone (optional)
                                    requestPermissionsLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO
                                        )
                                    )
                                }
                            )
                        }

                        else -> {
                            // Permissions were requested but camera was denied
                            PermissionScreen(
                                onRequestPermission = {
                                    requestPermissionsLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}