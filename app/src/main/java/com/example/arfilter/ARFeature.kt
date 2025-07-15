package com.example.arfilter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
    private var hasStoragePermission by mutableStateOf(false)
    private var permissionsRequested by mutableStateOf(false)

    // Request multiple permissions launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
        hasMicrophonePermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        // Check storage permission based on Android version
        hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        } else {
            permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false
        }

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

        // Check storage permission based on Android version
        hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

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
                            // Note: Microphone and storage permissions are optional but recommended
                            PowerliftingScreen(viewModel = viewModel)
                        }

                        !permissionsRequested -> {
                            // Show permission screen for required permissions
                            PermissionScreen(
                                onRequestPermission = {
                                    // Request camera (required), microphone and storage (optional)
                                    val permissionsToRequest = mutableListOf(
                                        Manifest.permission.CAMERA,
                                        Manifest.permission.RECORD_AUDIO
                                    )

                                    // Add storage permission based on Android version
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                                    } else {
                                        permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }

                                    requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                                }
                            )
                        }

                        else -> {
                            // Permissions were requested but camera was denied
                            PermissionScreen(
                                onRequestPermission = {
                                    val permissionsToRequest = mutableListOf(
                                        Manifest.permission.CAMERA,
                                        Manifest.permission.RECORD_AUDIO
                                    )

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                                    } else {
                                        permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }

                                    requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}