package com.example.arfilter.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.arfilter.ui.components.*
import com.example.arfilter.viewmodel.*
import com.example.arfilter.utils.VoiceCoachManager
import com.example.arfilter.utils.VoiceCommandManager
import com.example.arfilter.utils.announcePhaseTransition
import com.example.arfilter.utils.getExerciseIcon
// Import barbell detection components
import com.example.arfilter.detector.YOLOv11ObjectDetector
import com.example.arfilter.detector.BitmapUtils
import com.example.arfilter.detector.Detection
import com.example.arfilter.detector.BarPathAnalyzer
import com.example.arfilter.detector.AutomaticPathManager
import com.example.arfilter.detector.PathPoint
import com.example.arfilter.detector.BarPath
import com.example.arfilter.detector.MovementDirection
import com.example.arfilter.detector.MovementAnalysis
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun PowerliftingScreen(
    viewModel: PowerliftingViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()

    val previewView = remember { PreviewView(context) }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_FRONT_CAMERA) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var showControls by remember { mutableStateOf(false) }

    // Background Barbell Detection States
    var barbellDetections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var isDetectionProcessing by remember { mutableStateOf(false) }
    var detectionFps by remember { mutableStateOf(0f) }
    var showBarbellTracking by remember { mutableStateOf(false) } // Toggle for background detection

    // Bar Path Tracking States
    val pathManager = remember { AutomaticPathManager() }
    var barPaths by remember { mutableStateOf<List<BarPath>>(emptyList()) }
    var pathAnalysis by remember { mutableStateOf<MovementAnalysis?>(null) }
    var totalBarbellReps by remember { mutableStateOf(0) }

    // Barbell Detector (lazy initialization)
    val barbellDetector = remember {
        try {
            YOLOv11ObjectDetector(
                context = context,
                modelPath = "optimizefloat16.tflite", // Your YOLOv11 model
                confThreshold = 0.3f,
                iouThreshold = 0.45f
            )
        } catch (e: Exception) {
            Log.e("PowerliftingScreen", "Failed to initialize barbell detector: ${e.message}", e)
            null
        }
    }

    val pathAnalyzer = remember { BarPathAnalyzer() }

    // FPS calculation for detection
    var frameCount by remember { mutableStateOf(0) }
    var lastFpsUpdate by remember { mutableStateOf(System.currentTimeMillis()) }

    // Check microphone permission
    var hasMicrophonePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    // Enhanced Voice Coach Manager with interval control
    val voiceCoach = remember {
        VoiceCoachManager(context).apply {
            updateIntervals(
                samePhaseInterval = 10000L,      // 10 seconds between same phase cues
                motivationalInterval = 20000L,    // 20 seconds between motivational cues
                repCountInterval = 4000L          // 4 seconds between rep counts
            )
        }
    }

    // Voice Command Manager
    val voiceCommandManager = remember {
        VoiceCommandManager(context).apply {
            onPlayStopCommand = { shouldPlay ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.handlePlayStopCommand(shouldPlay)

                if (voiceCoach.isEnabled.value && voiceCoach.isReady.value) {
                    val message = if (shouldPlay) "Starting workout" else "Stopping workout"
                    voiceCoach.speak(message, VoiceCoachManager.Priority.HIGH)
                }
            }

            onLineHeightCommand = { direction ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.adjustLineHeight(direction)

                if (voiceCoach.isEnabled.value && voiceCoach.isReady.value) {
                    val message = when (direction.lowercase()) {
                        "increase", "up", "raise", "higher" -> "Line raised"
                        "decrease", "down", "lower", "smaller" -> "Line lowered"
                        else -> "Line adjusted"
                    }
                    voiceCoach.speak(message, VoiceCoachManager.Priority.MEDIUM)
                }
            }

            onVoiceCoachToggle = {
                val newState = voiceCoach.toggleEnabled()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                Log.d("VoiceCommands", "Voice coach toggled to: $newState")
            }
        }
    }

    val voiceEnabled by voiceCoach.isEnabled.collectAsStateWithLifecycle()
    val voiceReady by voiceCoach.isReady.collectAsStateWithLifecycle()

    // Voice command states
    val voiceCommandsEnabled by viewModel.state.collectAsStateWithLifecycle()
    val isListening by voiceCommandManager.isListening.collectAsStateWithLifecycle()

    // Track previous state to detect actual changes
    var previousPhase by remember { mutableStateOf<LiftPhase?>(null) }
    var previousRepCount by remember { mutableStateOf(0) }
    var previousSetCount by remember { mutableStateOf(0) }
    var previousRestTime by remember { mutableStateOf(0) }

    // Initialize voice coach and voice commands
    LaunchedEffect(Unit) {
        voiceCoach.initialize()

        if (hasMicrophonePermission) {
            voiceCommandManager.initialize()
        }
    }

    // Start/stop voice command listening based on settings
    LaunchedEffect(state.voiceCommandsEnabled, hasMicrophonePermission) {
        if (state.voiceCommandsEnabled && hasMicrophonePermission) {
            voiceCommandManager.setEnabled(true)
            voiceCommandManager.startListening()
        } else {
            voiceCommandManager.setEnabled(false)
            voiceCommandManager.stopListening()
        }
    }

    // Clean up voice managers and detector
    DisposableEffect(Unit) {
        onDispose {
            voiceCoach.shutdown()
            voiceCommandManager.destroy()
            barbellDetector?.close()
        }
    }

    // Voice coaching effects (same as before)
    LaunchedEffect(state.currentPhase, state.isActive, voiceEnabled, voiceReady) {
        if (voiceReady && voiceEnabled && state.isActive && state.restTime <= 0) {
            if (state.currentPhase != previousPhase) {
                Log.d("VoiceCoach", "Phase changed from $previousPhase to ${state.currentPhase}")
                voiceCoach.announcePhaseTransition(
                    exercise = state.selectedExercise,
                    fromPhase = previousPhase,
                    toPhase = state.currentPhase
                )
                previousPhase = state.currentPhase
            }
        }
    }

    LaunchedEffect(state.repCount, state.isActive, voiceEnabled) {
        if (voiceReady && voiceEnabled && state.isActive && state.restTime <= 0) {
            if (state.repCount > previousRepCount) {
                Log.d("VoiceCoach", "Rep count changed from $previousRepCount to ${state.repCount}")
                voiceCoach.announceRepCount(state.repCount, state.targetReps)
                previousRepCount = state.repCount
            }
        }
    }

    LaunchedEffect(state.setCount, voiceEnabled) {
        if (voiceReady && voiceEnabled) {
            if (state.setCount > previousSetCount) {
                Log.d("VoiceCoach", "Set completed: ${state.setCount}")
                voiceCoach.announceSetComplete(state.setCount, previousRepCount)
                previousSetCount = state.setCount
                previousRepCount = 0
                previousPhase = null
            }
        }
    }

    LaunchedEffect(state.restTime, voiceEnabled) {
        if (voiceReady && voiceEnabled && state.restTime > 0) {
            val shouldAnnounceRest = when (state.restTime) {
                in 1..5 -> true
                10, 15, 30, 60 -> true
                else -> false
            }

            if (shouldAnnounceRest && state.restTime != previousRestTime) {
                voiceCoach.announceRestPeriod(state.restTime)
            }
            previousRestTime = state.restTime
        }
    }

    LaunchedEffect(state.isActive) {
        if (state.isActive && previousSetCount == 0 && previousRepCount == 0) {
            voiceCoach.resetForNewWorkout()
            Log.d("VoiceCoach", "Starting new workout - reset voice coach")
        }
    }

    // Initialize camera
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = cameraProviderFuture.get()
    }

    // Enhanced camera binding with barbell detection
    LaunchedEffect(cameraSelector, cameraProvider, showBarbellTracking) {
        cameraProvider?.let { provider ->
            try {
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder().build()

                // Add image analysis for barbell detection when enabled
                val analysisUseCase = if (showBarbellTracking && barbellDetector != null) {
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (!isDetectionProcessing) {
                                    isDetectionProcessing = true

                                    try {
                                        val bitmap = BitmapUtils.imageProxyToBitmap(imageProxy)
                                        val detections = barbellDetector.detect(bitmap)
                                        barbellDetections = detections

                                        val currentTime = System.currentTimeMillis()

                                        // Process bar path tracking if detections exist
                                        if (detections.isNotEmpty()) {
                                            val updatedPaths = pathManager.addDetection(detections.first(), currentTime)
                                            barPaths = updatedPaths

                                            // Analyze movement if we have active paths
                                            if (updatedPaths.isNotEmpty()) {
                                                val activePath = updatedPaths.last()
                                                if (activePath.points.size > 10) {
                                                    pathAnalysis = pathAnalyzer.analyzeMovement(activePath.points)

                                                    // Count total reps across all paths
                                                    totalBarbellReps = updatedPaths.sumOf { path ->
                                                        if (path.points.size > 20) {
                                                            pathAnalyzer.analyzeMovement(path.points)?.repCount ?: 0
                                                        } else 0
                                                    }
                                                }
                                            }
                                        }

                                        // Calculate detection FPS
                                        frameCount++
                                        if (currentTime - lastFpsUpdate >= 1000) {
                                            detectionFps = frameCount * 1000f / (currentTime - lastFpsUpdate)
                                            frameCount = 0
                                            lastFpsUpdate = currentTime
                                        }

                                    } catch (e: Exception) {
                                        Log.e("BarbellDetection", "Error processing frame: ${e.message}", e)
                                    } finally {
                                        isDetectionProcessing = false
                                        imageProxy.close()
                                    }
                                } else {
                                    imageProxy.close()
                                }
                            }
                        }
                } else null

                provider.unbindAll()

                // Bind appropriate use cases
                if (analysisUseCase != null) {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture,
                        analysisUseCase
                    )
                } else {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                }

            } catch (e: Exception) {
                Log.e("PowerliftingScreen", "Camera binding failed", e)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else
                            CameraSelector.DEFAULT_BACK_CAMERA
                    },
                    onTap = {
                        showControls = !showControls
                    }
                )
            }
    ) {
        // Camera Preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Background Barbell Detection Overlay (when enabled)
        if (showBarbellTracking) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Draw barbell detections
                drawBarbellDetections(barbellDetections, barbellDetector)

                // Draw bar paths
                drawBarPaths(barPaths)
            }
        }

        // Powerlifting Overlay - The main AR feature (always on top)
        PowerliftingOverlay(
            state = state,
            modifier = Modifier.fillMaxSize()
        )

        // Quick Stats Panel (Top Left) - Enhanced with barbell detection info
        EnhancedStatsPanel(
            state = state,
            barbellDetections = barbellDetections,
            totalBarbellReps = totalBarbellReps,
            detectionFps = detectionFps,
            showBarbellTracking = showBarbellTracking,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 60.dp)
                .width(200.dp)
        )

        // Rep Counter (Top Right)
        if (state.isActive) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 60.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.8f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "SET ${state.setCount + 1}",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${state.repCount}/${state.targetReps}",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "REPS",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )

                    // Show barbell detection reps if enabled
                    if (showBarbellTracking && totalBarbellReps > 0) {
                        Text(
                            text = "Auto: $totalBarbellReps",
                            color = Color.Cyan,
                            fontSize = 8.sp
                        )
                    }
                }
            }
        }

        // Tempo Indicator (Bottom Left)
        if (state.showTempo) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 140.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.8f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.tempo.displayName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${state.tempo.eccentric.toInt()}/${state.tempo.pause.toInt()}/${state.tempo.concentric.toInt()}",
                        color = Color.Green,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Enhanced Quick Action Buttons
        EnhancedQuickActionButtons(
            state = state,
            voiceEnabled = voiceEnabled,
            isTtsReady = voiceReady,
            voiceCommandsEnabled = state.voiceCommandsEnabled,
            isListening = isListening,
            showBarbellTracking = showBarbellTracking,
            onStartPause = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (state.isActive) {
                    viewModel.pauseSet()
                    if (voiceEnabled && voiceReady) {
                        voiceCoach.speak("Workout paused", VoiceCoachManager.Priority.HIGH, allowRepeat = false)
                    }
                } else {
                    viewModel.startSet()
                    if (voiceEnabled && voiceReady) {
                        voiceCoach.speak("Starting set", VoiceCoachManager.Priority.HIGH, allowRepeat = true)
                    }
                }
            },
            onStop = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (voiceEnabled && voiceReady) {
                    voiceCoach.speak("Workout stopped", VoiceCoachManager.Priority.HIGH, allowRepeat = true)
                }
                viewModel.stopSession()

                // Reset tracking variables
                previousPhase = null
                previousRepCount = 0
                previousSetCount = 0
                previousRestTime = 0

                // Clear bar paths
                pathManager.clearAllPaths()
                barPaths = emptyList()
                totalBarbellReps = 0
            },
            onCompleteSet = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.completeSet()
            },
            onToggleVoice = {
                val newState = voiceCoach.toggleEnabled()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                Log.d("VoiceCoach", "Voice coach toggled to: $newState")
            },
            onToggleVoiceCommands = {
                viewModel.toggleVoiceCommands()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onToggleBarbellTracking = {
                showBarbellTracking = !showBarbellTracking
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                // Clear paths when disabling
                if (!showBarbellTracking) {
                    pathManager.clearAllPaths()
                    barPaths = emptyList()
                    barbellDetections = emptyList()
                    totalBarbellReps = 0
                }

                Log.d("BarbellTracking", "Barbell tracking toggled to: $showBarbellTracking")
            },
            onSwitchCamera = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    CameraSelector.DEFAULT_BACK_CAMERA
            },
            onToggleControls = {
                showControls = !showControls
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        )

        // Full Controls Panel (Enhanced with barbell detection controls)
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            EnhancedPowerliftingControlsPanel(
                state = state,
                viewModel = viewModel,
                voiceCoach = voiceCoach,
                voiceCommandManager = voiceCommandManager,
                showBarbellTracking = showBarbellTracking,
                onToggleBarbellTracking = {
                    showBarbellTracking = !showBarbellTracking
                    if (!showBarbellTracking) {
                        pathManager.clearAllPaths()
                        barPaths = emptyList()
                        barbellDetections = emptyList()
                        totalBarbellReps = 0
                    }
                },
                onClearBarPaths = {
                    pathManager.clearAllPaths()
                    barPaths = emptyList()
                    barbellDetections = emptyList()
                    totalBarbellReps = 0
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onCapturePhoto = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        capturePhoto(context, imageCapture, cameraExecutor)
                    }
                },
                onDismiss = { showControls = false },
                modifier = Modifier.padding(top = 100.dp)
            )
        }

        // Welcome/Instructions (First time)
        var showWelcome by remember { mutableStateOf(true) }

        if (showWelcome) {
            EnhancedWelcomeOverlay(
                onDismiss = { showWelcome = false },
                onStartWorkout = {
                    showWelcome = false
                    showControls = true
                }
            )
        }
    }
}

// Enhanced Stats Panel with Barbell Detection Info
@Composable
private fun EnhancedStatsPanel(
    state: PowerliftingState,
    barbellDetections: List<Detection>,
    totalBarbellReps: Int,
    detectionFps: Float,
    showBarbellTracking: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.85f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Exercise Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = state.selectedExercise.displayName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = state.tempo.displayName,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                // Status Indicator
                StatusIndicator(
                    isActive = state.isActive,
                    isPaused = state.isPaused,
                    currentPhase = state.currentPhase
                )
            }

            // Progress Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "SET",
                    value = "${state.setCount + 1}",
                    icon = Icons.Default.Layers,
                    color = Color.Blue
                )
                StatItem(
                    label = "REP",
                    value = "${state.repCount}/${state.targetReps}",
                    icon = Icons.Default.Repeat,
                    color = Color.Green
                )
            }

            // Barbell Detection Info (when enabled)
            if (showBarbellTracking) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "BARBELL DETECTION",
                        color = Color.Cyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "FPS: ${String.format("%.1f", detectionFps)}",
                            color = Color.Gray,
                            fontSize = 9.sp
                        )
                        Text(
                            text = "Detected: ${barbellDetections.size}",
                            color = if (barbellDetections.isNotEmpty()) Color.Green else Color.Gray,
                            fontSize = 9.sp
                        )
                    }

                    if (totalBarbellReps > 0) {
                        Text(
                            text = "Auto Reps: $totalBarbellReps",
                            color = Color.Cyan,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            // Rest Timer (if active)
            if (state.restTime > 0) {
                RestTimeIndicator(restTime = state.restTime)
            }
        }
    }
}

// Enhanced Quick Action Buttons with Barbell Detection Toggle
@Composable
private fun EnhancedQuickActionButtons(
    state: PowerliftingState,
    voiceEnabled: Boolean,
    isTtsReady: Boolean,
    voiceCommandsEnabled: Boolean,
    isListening: Boolean,
    showBarbellTracking: Boolean,
    onStartPause: () -> Unit,
    onStop: () -> Unit,
    onCompleteSet: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleVoiceCommands: () -> Unit,
    onToggleBarbellTracking: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Settings/Controls Toggle
        FloatingActionButton(
            onClick = onToggleControls,
            modifier = Modifier.size(44.dp),
            containerColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Controls",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // 2. Barbell Detection Toggle
        FloatingActionButton(
            onClick = onToggleBarbellTracking,
            modifier = Modifier.size(44.dp),
            containerColor = if (showBarbellTracking) Color(0xFF00BCD4) else Color.Gray
        ) {
            Icon(
                imageVector = Icons.Default.TrackChanges,
                contentDescription = "Toggle Barbell Detection",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // 3. Voice Commands Toggle Button
        FloatingActionButton(
            onClick = onToggleVoiceCommands,
            modifier = Modifier.size(44.dp),
            containerColor = if (voiceCommandsEnabled) {
                if (isListening) Color.Red else Color(0xFF4CAF50)
            } else Color.Gray
        ) {
            Icon(
                imageVector = if (voiceCommandsEnabled) {
                    if (isListening) Icons.Default.MicOff else Icons.Default.Mic
                } else Icons.Default.MicOff,
                contentDescription = "Toggle Voice Commands",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // 4. Voice Coach Toggle Button
        FloatingActionButton(
            onClick = onToggleVoice,
            modifier = Modifier.size(44.dp),
            containerColor = if (voiceEnabled) Color(0xFF4CAF50) else Color.Gray
        ) {
            Icon(
                imageVector = if (voiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                contentDescription = "Toggle Voice Coach",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // 5. Start/Pause Button (CENTRAL PLAY BUTTON - PROMINENT)
        FloatingActionButton(
            onClick = onStartPause,
            modifier = Modifier.size(68.dp), // Made larger for prominence
            containerColor = if (state.isActive && !state.isPaused) Color(0xFFFF8C00) else Color.Green,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 8.dp,
                pressedElevation = 12.dp
            )
        ) {
            Icon(
                imageVector = if (state.isActive && !state.isPaused) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isActive && !state.isPaused) "Pause" else "Start",
                tint = Color.White,
                modifier = Modifier.size(32.dp) // Larger icon for central button
            )
        }

        // 6. Stop Button
        FloatingActionButton(
            onClick = onStop,
            modifier = Modifier.size(44.dp),
            containerColor = if (state.isActive) Color.Red else Color.Red.copy(alpha = 0.5f)
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = "Stop Session",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // 7. Camera Switch
        FloatingActionButton(
            onClick = onSwitchCamera,
            modifier = Modifier.size(44.dp),
            containerColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Icon(
                Icons.Default.FlipCameraAndroid,
                contentDescription = "Switch Camera",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Complete Set Button (appears as overlay when needed)
        if (state.isActive && !state.isPaused) {
            FloatingActionButton(
                onClick = onCompleteSet,
                modifier = Modifier.size(36.dp),
                containerColor = Color(0xFF4CAF50)
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Complete Set",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Enhanced Welcome Overlay with Barbell Detection Info
@Composable
private fun EnhancedWelcomeOverlay(
    onDismiss: () -> Unit,
    onStartWorkout: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.FitnessCenter,
                    contentDescription = "Powerlifting",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )

                Text(
                    text = "PowerLifting AR Coach",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Your AI voice coach with automatic barbell detection for perfect form and tempo tracking.",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InstructionItem("🔴 Red Light: Control the negative")
                    InstructionItem("🟡 Yellow Light: Pause at bottom")
                    InstructionItem("🟢 Green Light: Drive up strong")
                    InstructionItem("🔊 Voice Coach: Real-time form cues")
                    InstructionItem("🎤 Voice Commands: \"Start\", \"Stop\", \"Up\", \"Down\"")
                    InstructionItem("🎯 Barbell Detection: Automatic tracking (optional)")
                    InstructionItem("📊 Bar Path: Real-time movement analysis")
                    InstructionItem("👆 Tap: Hide/show controls")
                    InstructionItem("👆👆 Double tap: Switch camera")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Skip", color = Color.Gray)
                    }

                    Button(
                        onClick = onStartWorkout,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Green
                        )
                    ) {
                        Text("Start Workout")
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionItem(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

// Enhanced Controls Panel with Barbell Detection Settings
@Composable
fun EnhancedPowerliftingControlsPanel(
    state: PowerliftingState,
    viewModel: PowerliftingViewModel,
    voiceCoach: VoiceCoachManager,
    voiceCommandManager: VoiceCommandManager,
    showBarbellTracking: Boolean,
    onToggleBarbellTracking: () -> Unit,
    onClearBarPaths: () -> Unit,
    onCapturePhoto: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val voiceEnabled by voiceCoach.isEnabled.collectAsStateWithLifecycle()
    val voiceReady by voiceCoach.isReady.collectAsStateWithLifecycle()
    val isSpeaking by voiceCoach.isSpeaking.collectAsStateWithLifecycle()

    // Voice command states
    val isListening by voiceCommandManager.isListening.collectAsStateWithLifecycle()
    val voiceCommandsEnabled = state.voiceCommandsEnabled

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 750.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header with close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "WORKOUT CONTROLS",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            // Barbell Detection Settings Section (NEW)
            BarbellDetectionSettingsSection(
                showBarbellTracking = showBarbellTracking,
                onToggleBarbellTracking = onToggleBarbellTracking,
                onClearBarPaths = onClearBarPaths
            )

            // Voice Command Settings Section
            VoiceCommandSettingsSection(
                voiceCommandManager = voiceCommandManager,
                voiceCommandsEnabled = voiceCommandsEnabled,
                isListening = isListening,
                onToggleVoiceCommands = viewModel::toggleVoiceCommands
            )

            // Voice Coach Settings Section
            VoiceCoachSettingsSection(
                voiceCoach = voiceCoach,
                voiceEnabled = voiceEnabled,
                voiceReady = voiceReady,
                isSpeaking = isSpeaking
            )

            // Line Height Control Section
            LineHeightControlSection(
                currentHeight = state.lineHeight,
                onHeightChange = viewModel::setLineHeight
            )

            // Exercise Selection Section
            ExerciseSelectionSection(
                selectedExercise = state.selectedExercise,
                onExerciseSelect = viewModel::selectExercise
            )

            // Tempo Selection Section
            TempoSelectionSection(
                selectedTempo = state.tempo,
                onTempoSelect = viewModel::selectTempo
            )

            // Set Configuration Section
            SetConfigurationSection(
                targetReps = state.targetReps,
                onTargetRepsChange = viewModel::setTargetReps,
                currentSet = state.setCount
            )

            // Display Options Section
            DisplayOptionsSection(
                showFormCues = state.showFormCues,
                showTempo = state.showTempo,
                onToggleFormCues = viewModel::toggleFormCues,
                onToggleTempo = viewModel::toggleTempo
            )

            // Action Buttons Section
            ActionButtonsSection(
                onCapturePhoto = onCapturePhoto,
                onDismiss = onDismiss
            )
        }
    }
}

// NEW: Barbell Detection Settings Section
@Composable
private fun BarbellDetectionSettingsSection(
    showBarbellTracking: Boolean,
    onToggleBarbellTracking: () -> Unit,
    onClearBarPaths: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = "BARBELL DETECTION",
            icon = Icons.Default.TrackChanges
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Gray.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Main barbell detection toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Background Detection",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (showBarbellTracking) "AI tracking active" else "Manual mode only",
                            color = if (showBarbellTracking) Color.Cyan else Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = showBarbellTracking,
                        onCheckedChange = { onToggleBarbellTracking() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Cyan,
                            checkedTrackColor = Color.Cyan.copy(alpha = 0.5f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                        )
                    )
                }

                if (showBarbellTracking) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Features:",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    val features = listOf(
                        "• Real-time barbell detection using YOLOv11",
                        "• Automatic bar path tracking",
                        "• Movement analysis and rep counting",
                        "• Background processing (doesn't interfere with AR)",
                        "• Cyan overlay shows detected barbells and paths"
                    )

                    features.forEach { feature ->
                        Text(
                            text = feature,
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Clear paths button
                    Button(
                        onClick = onClearBarPaths,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red.copy(alpha = 0.7f)
                        )
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear Paths",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear Bar Paths")
                    }
                }
            }
        }
    }
}

// Drawing functions for barbell detection overlay
private fun DrawScope.drawBarbellDetections(
    detections: List<Detection>,
    detector: YOLOv11ObjectDetector?
) {
    if (detector == null) return

    val canvasWidth = size.width
    val canvasHeight = size.height

    detections.forEachIndexed { index, detection ->
        val bbox = detection.bbox

        val left = (bbox.left * canvasWidth).coerceIn(0f, canvasWidth)
        val top = (bbox.top * canvasHeight).coerceIn(0f, canvasHeight)
        val right = (bbox.right * canvasWidth).coerceIn(0f, canvasWidth)
        val bottom = (bbox.bottom * canvasHeight).coerceIn(0f, canvasHeight)

        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f

        // Draw center point
        drawCircle(
            color = Color.White,
            radius = 3.dp.toPx(),
            center = Offset(centerX, centerY)
        )

        // Draw bounding box if large enough
        if (right > left + 20f && bottom > top + 20f) {
            drawRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw label

        }
    }
}

private fun DrawScope.drawBarPaths(paths: List<BarPath>) {
    val canvasWidth = size.width
    val canvasHeight = size.height
    val currentTime = System.currentTimeMillis()

    paths.forEachIndexed { pathIndex, path ->
        val points = path.points
        if (points.size > 1) {
            // Draw path with time-based fade effect
            for (i in 0 until points.size - 1) {
                val startPoint = points[i]
                val endPoint = points[i + 1]

                val startX = startPoint.x * canvasWidth
                val startY = startPoint.y * canvasHeight
                val endX = endPoint.x * canvasWidth
                val endY = endPoint.y * canvasHeight

                // Time-based alpha (newer points are more opaque)
                val timeSincePoint = currentTime - endPoint.timestamp
                val maxAge = 15000L // 15 seconds
                val alpha = (1f - (timeSincePoint.toFloat() / maxAge)).coerceIn(0.2f, 1f)

                drawLine(
                    color = Color.Cyan.copy(alpha = alpha),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f), 0f)
                )
            }

            // Draw current position (last point) with emphasis
            if (points.isNotEmpty()) {
                val lastPoint = points.last()
                val pointX = lastPoint.x * canvasWidth
                val pointY = lastPoint.y * canvasHeight

                drawCircle(
                    color = Color.White,
                    radius = 4.dp.toPx(),
                    center = Offset(pointX, pointY)
                )

                // Outer ring for emphasis
                drawCircle(
                    color = Color.White.copy(alpha = 0.5f),
                    radius = 8.dp.toPx(),
                    center = Offset(pointX, pointY),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

// Helper components from the original PowerliftingScreen that need to be copied
@Composable
private fun StatusIndicator(
    isActive: Boolean,
    isPaused: Boolean,
    currentPhase: LiftPhase
) {
    val color = when {
        !isActive -> Color.Gray
        isPaused -> Color(0xFFFF8C00) // Orange
        else -> when (currentPhase) {
            LiftPhase.READY -> Color.White
            LiftPhase.ECCENTRIC -> Color.Red
            LiftPhase.BOTTOM -> Color.Yellow
            LiftPhase.CONCENTRIC -> Color.Green
            LiftPhase.TOP -> Color.Blue
            LiftPhase.REST -> Color(0xFFFF8C00)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = color,
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        )
        Text(
            text = when {
                !isActive -> "READY"
                isPaused -> "PAUSED"
                else -> currentPhase.name
            },
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color = Color.White
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun VoiceCommandSettingsSection(
    voiceCommandManager: VoiceCommandManager,
    voiceCommandsEnabled: Boolean,
    isListening: Boolean,
    onToggleVoiceCommands: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = "VOICE COMMANDS",
            icon = Icons.Default.Mic
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Gray.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Voice Commands",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when {
                                !voiceCommandsEnabled -> "Disabled"
                                isListening -> "Listening..."
                                else -> "Ready"
                            },
                            color = when {
                                !voiceCommandsEnabled -> Color.Gray
                                isListening -> Color.Red
                                else -> Color.Green
                            },
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = voiceCommandsEnabled,
                        onCheckedChange = { onToggleVoiceCommands() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Blue,
                            checkedTrackColor = Color.Blue.copy(alpha = 0.5f)
                        )
                    )
                }

                if (voiceCommandsEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Available Commands:",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    val commands = listOf(
                        "\"Start\" or \"Play\" - Start workout",
                        "\"Stop\" or \"Pause\" - Stop workout",
                        "\"Line up\" or \"Raise line\" - Increase line height",
                        "\"Line down\" or \"Lower line\" - Decrease line height",
                        "\"Toggle voice\" - Toggle voice coach"
                    )

                    commands.forEach { command ->
                        Text(
                            text = "• $command",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCoachSettingsSection(
    voiceCoach: VoiceCoachManager,
    voiceEnabled: Boolean,
    voiceReady: Boolean,
    isSpeaking: Boolean
) {
    var showAdvancedSettings by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = "VOICE COACH",
            icon = Icons.Default.RecordVoiceOver
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Gray.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Voice Coaching",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when {
                                !voiceReady -> "Initializing..."
                                isSpeaking -> "Speaking..."
                                voiceEnabled -> "Smart intervals active"
                                else -> "Disabled"
                            },
                            color = when {
                                !voiceReady -> Color.Yellow
                                isSpeaking -> Color.Green
                                voiceEnabled -> Color.Green
                                else -> Color.Gray
                            },
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = voiceEnabled,
                        onCheckedChange = { voiceCoach.setEnabled(it) },
                        enabled = voiceReady,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Green,
                            checkedTrackColor = Color.Green.copy(alpha = 0.5f)
                        )
                    )
                }

                if (voiceEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = { showAdvancedSettings = !showAdvancedSettings },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Cyan)
                    ) {
                        Text("Advanced Settings")
                        Icon(
                            imageVector = if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(visible = showAdvancedSettings) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Smart Interval Settings",
                                color = Color.Gray,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "• Same phase cues: 10s intervals\n• Motivational: 20s intervals\n• Rep counts: 4s intervals\n• Anti-repetition enabled",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )

                            Button(
                                onClick = {
                                    voiceCoach.speak(
                                        "Voice coach test. This is how I sound with current settings.",
                                        VoiceCoachManager.Priority.LOW
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Gray.copy(alpha = 0.3f)
                                )
                            ) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = "Test Voice",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Test Voice")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineHeightControlSection(
    currentHeight: Float,
    onHeightChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            title = "LINE HEIGHT CONTROL",
            icon = Icons.Default.Height
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Gray.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Movement Guide Height",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = "${currentHeight.toInt()}dp",
                        color = Color.Cyan,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = currentHeight,
                    onValueChange = onHeightChange,
                    valueRange = 200f..500f,
                    steps = 11,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Cyan,
                        activeTrackColor = Color.Cyan,
                        inactiveTrackColor = Color.Gray
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { onHeightChange((currentHeight - 25f).coerceAtLeast(200f)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f))
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Lower", tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Lower", color = Color.White)
                    }

                    Button(
                        onClick = { onHeightChange((currentHeight + 25f).coerceAtMost(500f)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Green.copy(alpha = 0.7f))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Raise", tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Raise", color = Color.White)
                    }
                }

                Text(
                    text = "Tip: Use voice commands \"line up\" or \"line down\" for hands-free adjustment",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ExerciseSelectionSection(
    selectedExercise: ExerciseType,
    onExerciseSelect: (ExerciseType) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(
            title = "EXERCISE",
            icon = Icons.Default.FitnessCenter
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(ExerciseType.entries) { exercise ->
                ExerciseCard(
                    exercise = exercise,
                    isSelected = selectedExercise == exercise,
                    onClick = { onExerciseSelect(exercise) }
                )
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    exercise: ExerciseType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .clickable { onClick() }
            .width(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                Color.Gray.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = getExerciseIcon(exercise),
                contentDescription = exercise.displayName,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = exercise.displayName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Text(
                text = exercise.description,
                color = Color.Gray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TempoSelectionSection(
    selectedTempo: Tempo,
    onTempoSelect: (Tempo) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(
            title = "TEMPO",
            icon = Icons.Default.Speed
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Tempo.entries.forEach { tempo ->
                TempoRow(
                    tempo = tempo,
                    isSelected = selectedTempo == tempo,
                    onClick = { onTempoSelect(tempo) }
                )
            }
        }
    }
}

@Composable
private fun TempoRow(
    tempo: Tempo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            else
                Color.Gray.copy(alpha = 0.2f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = tempo.displayName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${tempo.eccentric.toInt()}/${tempo.pause.toInt()}/${tempo.concentric.toInt()} - ${tempo.description}",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }

            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SetConfigurationSection(
    targetReps: Int,
    onTargetRepsChange: (Int) -> Unit,
    currentSet: Int
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(
            title = "SET CONFIGURATION",
            icon = Icons.Default.Repeat
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Gray.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SET ${currentSet + 1}",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Current Set",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = { onTargetRepsChange(targetReps - 1) },
                        enabled = targetReps > 1
                    ) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = "Decrease reps",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "$targetReps",
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "REPS",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }

                    IconButton(
                        onClick = { onTargetRepsChange(targetReps + 1) },
                        enabled = targetReps < 20
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Increase reps",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayOptionsSection(
    showFormCues: Boolean,
    showTempo: Boolean,
    onToggleFormCues: () -> Unit,
    onToggleTempo: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(
            title = "DISPLAY OPTIONS",
            icon = Icons.Default.Visibility
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Gray.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleFormCues() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Form Cues",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Form Cues",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Show technique reminders",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                    Switch(
                        checked = showFormCues,
                        onCheckedChange = { onToggleFormCues() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Green,
                            checkedTrackColor = Color.Green.copy(alpha = 0.5f)
                        )
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleTempo() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = "Tempo Display",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Tempo Display",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Show timing indicators",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                    Switch(
                        checked = showTempo,
                        onCheckedChange = { onToggleTempo() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Blue,
                            checkedTrackColor = Color.Blue.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtonsSection(
    onCapturePhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(
            title = "ACTIONS",
            icon = Icons.Default.CameraAlt
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onCapturePhoto,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Blue
                )
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Capture",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "CAPTURE",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray
                )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "CLOSE",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = title,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// Photo capture function
private suspend fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    cameraExecutor: ExecutorService
) {
    imageCapture ?: return

    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis())
    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PowerliftingAR")
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                Log.e("PowerliftingScreen", "Photo capture failed: ${exception.message}", exception)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d("PowerliftingScreen", "Photo captured successfully")
            }
        }
    )
}

@Composable
private fun RestTimeIndicator(restTime: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFF8C00).copy(alpha = 0.2f) // Orange
        ),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Timer,
                contentDescription = "Rest Timer",
                tint = Color(0xFFFF8C00), // Orange
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "REST: ${formatTime(restTime)}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// Utility function for time formatting
private fun formatTime(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}

// Include the remaining helper functions from the original PowerliftingScreen
// (VoiceCommandSettingsSection, VoiceCoachSettingsSection, etc.)
// These would be copied from the original file as they are unchanged