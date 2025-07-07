package com.example.arfilter.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Color
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
            // Configure intervals for less irritating feedback
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
            // Set up voice command callbacks
            onPlayStopCommand = { shouldPlay ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.handlePlayStopCommand(shouldPlay)

                // Provide voice feedback
                if (voiceCoach.isEnabled.value && voiceCoach.isReady.value) {
                    val message = if (shouldPlay) "Starting workout" else "Stopping workout"
                    voiceCoach.speak(message, VoiceCoachManager.Priority.HIGH)
                }
            }

            onLineHeightCommand = { direction ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.adjustLineHeight(direction)

                // Provide voice feedback
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
    val lastCommand by voiceCommandManager.lastCommand.collectAsStateWithLifecycle()

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

    // Clean up voice managers
    DisposableEffect(Unit) {
        onDispose {
            voiceCoach.shutdown()
            voiceCommandManager.destroy()
        }
    }

    // Smart phase change detection with interval control
    LaunchedEffect(state.currentPhase, state.isActive, voiceEnabled, voiceReady) {
        if (voiceReady && voiceEnabled && state.isActive && state.restTime <= 0) {
            // Only announce if phase actually changed
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

    // Smart rep counting with interval control
    LaunchedEffect(state.repCount, state.isActive, voiceEnabled) {
        if (voiceReady && voiceEnabled && state.isActive && state.restTime <= 0) {
            // Only announce if rep count actually increased
            if (state.repCount > previousRepCount) {
                Log.d("VoiceCoach", "Rep count changed from $previousRepCount to ${state.repCount}")
                voiceCoach.announceRepCount(state.repCount, state.targetReps)
                previousRepCount = state.repCount
            }
        }
    }

    // Set completion announcements
    LaunchedEffect(state.setCount, voiceEnabled) {
        if (voiceReady && voiceEnabled) {
            // Only announce if set count actually increased (set completed)
            if (state.setCount > previousSetCount) {
                Log.d("VoiceCoach", "Set completed: ${state.setCount}")
                voiceCoach.announceSetComplete(state.setCount, previousRepCount)
                previousSetCount = state.setCount

                // Reset for new set
                previousRepCount = 0
                previousPhase = null
            }
        }
    }

    // Smart rest period announcements
    LaunchedEffect(state.restTime, voiceEnabled) {
        if (voiceReady && voiceEnabled && state.restTime > 0) {
            // Only announce at specific intervals during rest
            val shouldAnnounceRest = when (state.restTime) {
                in 1..5 -> true  // Final countdown
                10, 15, 30, 60 -> true  // Key milestones
                else -> false
            }

            if (shouldAnnounceRest && state.restTime != previousRestTime) {
                voiceCoach.announceRestPeriod(state.restTime)
            }
            previousRestTime = state.restTime
        }
    }

    // Reset voice coach for new workout
    LaunchedEffect(state.isActive) {
        if (state.isActive && previousSetCount == 0 && previousRepCount == 0) {
            // Starting a new workout
            voiceCoach.resetForNewWorkout()
            Log.d("VoiceCoach", "Starting new workout - reset voice coach")
        }
    }

    // Initialize camera
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = cameraProviderFuture.get()
    }

    // Bind camera when selector changes
    LaunchedEffect(cameraSelector, cameraProvider) {
        cameraProvider?.let { provider ->
            try {
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder().build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
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

        // Powerlifting Overlay - The main AR feature (NOW WITH DYNAMIC LINE HEIGHT)
        PowerliftingOverlay(
            state = state,
            modifier = Modifier.fillMaxSize()
        )

        // Quick Stats Panel (Top Left)
        PowerliftingStatsPanel(
            state = state,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 60.dp)
                .width(180.dp)
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
                }
            }
        }

        // Voice Command Status Display
        if (state.voiceCommandsEnabled && hasMicrophonePermission) {
            VoiceCommandStatusCard(
                isListening = isListening,
                lastCommand = lastCommand,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-160).dp)
            )
        }

        // Voice Status Indicator (replaces form cues text)
        if (state.isActive && state.restTime <= 0) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-200).dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (voiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Voice Status",
                        tint = if (voiceEnabled) Color.Green else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (voiceEnabled) "Voice Coach ON" else "Voice Coach OFF",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (voiceReady) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "TTS Ready",
                            tint = Color.Green,
                            modifier = Modifier.size(12.dp)
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

        // Quick Action Buttons (Bottom Center) - WITH VOICE COMMAND TOGGLE
        QuickActionButtons(
            state = state,
            voiceEnabled = voiceEnabled,
            isTtsReady = voiceReady,
            voiceCommandsEnabled = state.voiceCommandsEnabled,
            isListening = isListening,
            onStartPause = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (state.isActive) {
                    viewModel.pauseSet()
                    // Announce pause (but only if not recently announced)
                    if (voiceEnabled && voiceReady) {
                        voiceCoach.speak("Workout paused", VoiceCoachManager.Priority.HIGH, allowRepeat = false)
                    }
                } else {
                    viewModel.startSet()
                    // Announce start (always allowed for session start)
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
            },
            onCompleteSet = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.completeSet()
                // Set completion will be announced by the LaunchedEffect above
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

        // Full Controls Panel (Slide up when needed)
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PowerliftingControlsPanel(
                state = state,
                viewModel = viewModel,
                voiceCoach = voiceCoach,
                voiceCommandManager = voiceCommandManager,
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
            WelcomeOverlay(
                onDismiss = { showWelcome = false },
                onStartWorkout = {
                    showWelcome = false
                    showControls = true
                }
            )
        }
    }
}

// Voice Command Status Card
@Composable
private fun VoiceCommandStatusCard(
    isListening: Boolean,
    lastCommand: String,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isListening || lastCommand.isNotEmpty(),
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isListening) Color.Red.copy(alpha = 0.8f) else Color.Green.copy(alpha = 0.8f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Mic else Icons.Default.Check,
                    contentDescription = if (isListening) "Listening" else "Command Received",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isListening) "Listening..." else "\"$lastCommand\"",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun QuickActionButtons(
    state: PowerliftingState,
    voiceEnabled: Boolean,
    isTtsReady: Boolean,
    voiceCommandsEnabled: Boolean,
    isListening: Boolean,
    onStartPause: () -> Unit,
    onStop: () -> Unit,
    onCompleteSet: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleVoiceCommands: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Settings/Controls Toggle
        FloatingActionButton(
            onClick = onToggleControls,
            modifier = Modifier.size(48.dp),
            containerColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Controls",
                tint = Color.White
            )
        }

        // 2. Voice Commands Toggle Button
        FloatingActionButton(
            onClick = onToggleVoiceCommands,
            modifier = Modifier.size(48.dp),
            containerColor = if (voiceCommandsEnabled) {
                if (isListening) Color.Red else Color(0xFF4CAF50)
            } else Color.Gray
        ) {
            Icon(
                imageVector = if (voiceCommandsEnabled) {
                    if (isListening) Icons.Default.MicOff else Icons.Default.Mic
                } else Icons.Default.MicOff,
                contentDescription = "Toggle Voice Commands",
                tint = Color.White
            )
        }

        // 3. Voice Coach Toggle Button
        FloatingActionButton(
            onClick = onToggleVoice,
            modifier = Modifier.size(48.dp),
            containerColor = if (voiceEnabled) Color(0xFF4CAF50) else Color.Gray
        ) {
            Icon(
                imageVector = if (voiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                contentDescription = "Toggle Voice Coach",
                tint = Color.White
            )
        }

        // 4. Start/Pause Button (CENTRAL PLAY BUTTON - PROMINENT)
        FloatingActionButton(
            onClick = onStartPause,
            modifier = Modifier.size(72.dp), // Made larger for prominence
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
                modifier = Modifier.size(36.dp) // Larger icon for central button
            )
        }

        // 5. Stop Button
        FloatingActionButton(
            onClick = onStop,
            modifier = Modifier.size(48.dp),
            containerColor = if (state.isActive) Color.Red else Color.Red.copy(alpha = 0.5f)
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = "Stop Session",
                tint = Color.White
            )
        }

        // 6. Camera Switch
        FloatingActionButton(
            onClick = onSwitchCamera,
            modifier = Modifier.size(48.dp),
            containerColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Icon(
                Icons.Default.FlipCameraAndroid,
                contentDescription = "Switch Camera",
                tint = Color.White
            )
        }

        // Complete Set Button (appears as overlay when needed)
        if (state.isActive && !state.isPaused) {
            FloatingActionButton(
                onClick = onCompleteSet,
                modifier = Modifier.size(40.dp),
                containerColor = Color(0xFF4CAF50)
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Complete Set",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun WelcomeOverlay(
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
                    text = "Your AI voice coach will guide you through perfect form and tempo during your lifts. Now with voice commands!",
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
                    InstructionItem("🎤 Voice Commands: \"Start\", \"Stop\", \"Line up/down\"")
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

@Composable
fun PowerliftingControlsPanel(
    state: PowerliftingState,
    viewModel: PowerliftingViewModel,
    voiceCoach: VoiceCoachManager,
    voiceCommandManager: VoiceCommandManager,
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
            .heightIn(max = 700.dp),
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

// Voice Command Settings Section
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
                // Main voice commands toggle
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
                            checkedTrackColor = Color.Blue.copy(alpha = 0.5f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
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

// Voice Coach Settings Section
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
                // Main voice toggle
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
                            checkedTrackColor = Color.Green.copy(alpha = 0.5f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                        )
                    )
                }

                if (voiceEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Advanced settings toggle
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

                            // Voice mode toggles
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                VoiceToggleSetting(
                                    label = "Detailed Cues",
                                    description = "More detailed form instructions",
                                    checked = voiceCoach.verboseMode,
                                    onCheckedChange = { voiceCoach.verboseMode = it }
                                )

                                VoiceToggleSetting(
                                    label = "Motivational",
                                    description = "Encouraging phrases and motivation",
                                    checked = voiceCoach.motivationalMode,
                                    onCheckedChange = { voiceCoach.motivationalMode = it }
                                )

                                VoiceToggleSetting(
                                    label = "Rep Counting",
                                    description = "Voice rep announcements",
                                    checked = voiceCoach.countdownEnabled,
                                    onCheckedChange = { voiceCoach.countdownEnabled = it }
                                )
                            }

                            // Test voice button
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
private fun VoiceToggleSetting(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp
            )
            Text(
                text = description,
                color = Color.Gray,
                fontSize = 12.sp
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Cyan,
                checkedTrackColor = Color.Cyan.copy(alpha = 0.5f),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
            )
        )
    }
}

// Line Height Control Section
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
                // Current height display
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

                // Height adjustment slider
                Slider(
                    value = currentHeight,
                    onValueChange = onHeightChange,
                    valueRange = 200f..500f,
                    steps = 11, // 25dp increments
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Cyan,
                        activeTrackColor = Color.Cyan,
                        inactiveTrackColor = Color.Gray
                    )
                )

                // Manual adjustment buttons
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

// Exercise Selection Section
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

// Tempo Selection Section
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

// Set Configuration Section
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

                // Rep counter controls
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

// Display Options Section
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
                // Form Cues Toggle
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

                // Tempo Display Toggle
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

// Action Buttons Section
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

// Section Header
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

