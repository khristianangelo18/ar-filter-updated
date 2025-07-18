package com.example.arfilter.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest

/**
 * IMPROVED Voice Command Manager - Fixed Delays and Immediate Command Execution
 * Main fixes:
 * 1. Immediate command execution without restart delays
 * 2. Simplified command matching
 * 3. Better state management
 * 4. Reduced restart frequency
 */
class VoiceCommandManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _lastCommand = MutableStateFlow("")
    val lastCommand: StateFlow<String> = _lastCommand.asStateFlow()

    private val _debugInfo = MutableStateFlow("")
    val debugInfo: StateFlow<String> = _debugInfo.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null

    // IMPROVED: Reduced restart delays and better error tracking
    private var lastErrorTime = 0L
    private var consecutiveErrors = 0
    private var lastCommandTime = 0L
    private var isProcessingCommand = false

    // Voice command callbacks
    var onPlayStopCommand: ((Boolean) -> Unit)? = null
    var onLineHeightCommand: ((String) -> Unit)? = null
    var onVoiceCoachToggle: (() -> Unit)? = null

    companion object {
        private const val TAG = "VoiceCommandManager"
        private const val MIN_RESTART_DELAY = 500L // Reduced from 1000L
        private const val MAX_RESTART_DELAY = 3000L // Reduced from 10000L
        private const val MAX_CONSECUTIVE_ERRORS = 3 // Reduced from 5
        private const val COMMAND_COOLDOWN = 1000L // Prevent rapid-fire commands

        // SIMPLIFIED: Basic command patterns for better recognition
        private val PLAY_COMMANDS = setOf(
            "start", "play", "go", "begin", "resume", "workout"
        )

        private val STOP_COMMANDS = setOf(
            "stop", "pause", "halt", "end", "finish"
        )

        private val LINE_UP_COMMANDS = setOf(
            "up", "raise", "higher", "increase", "line up"
        )

        private val LINE_DOWN_COMMANDS = setOf(
            "down", "lower", "decrease", "smaller", "line down"
        )

        private val VOICE_TOGGLE_COMMANDS = setOf(
            "voice", "mute", "unmute", "coach", "toggle voice"
        )
    }

    fun initialize() {
        // Check microphone permission first
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            _debugInfo.value = "Microphone permission not granted"
            Log.w(TAG, "Microphone permission not granted")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            _debugInfo.value = "Speech recognition not available"
            return
        }

        try {
            createSpeechRecognizer()
            _debugInfo.value = "Voice recognition initialized"
            Log.d(TAG, "Voice Command Manager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech recognizer", e)
            _debugInfo.value = "Failed to initialize: ${e.message}"
        }
    }

    private fun createSpeechRecognizer() {
        // Destroy existing recognizer first
        speechRecognizer?.destroy()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(VoiceRecognitionListener())
        }
    }

    fun startListening() {
        if (!_isEnabled.value) {
            Log.d(TAG, "Voice commands disabled")
            _debugInfo.value = "Voice commands disabled"
            return
        }

        // Cancel any pending restart
        restartRunnable?.let { mainHandler.removeCallbacks(it) }

        speechRecognizer?.let { recognizer ->
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

                // IMPROVED: Optimized settings for faster response
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3) // Reduced from 5
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) // Enable for faster response

                // IMPROVED: Shorter timeouts for faster response
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)

                // Use online recognition for better accuracy
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            try {
                recognizer.startListening(intent)
                _isListening.value = true
                _debugInfo.value = "Listening..."
                Log.d(TAG, "Started listening for voice commands")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                _isListening.value = false
                _debugInfo.value = "Failed to start listening: ${e.message}"
                scheduleRestart(1000L)
            }
        } ?: run {
            _debugInfo.value = "Speech recognizer not initialized"
            Log.w(TAG, "Speech recognizer not initialized, attempting to recreate")
            createSpeechRecognizer()
        }
    }

    fun stopListening() {
        try {
            // Cancel any pending restart
            restartRunnable?.let { mainHandler.removeCallbacks(it) }

            speechRecognizer?.stopListening()
            _isListening.value = false
            _debugInfo.value = "Stopped listening"
            Log.d(TAG, "Stopped listening for voice commands")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognizer", e)
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (!enabled) {
            stopListening()
        } else {
            // Reset error tracking when re-enabling
            consecutiveErrors = 0
        }
        _debugInfo.value = if (enabled) "Voice commands enabled" else "Voice commands disabled"
        Log.d(TAG, "Voice commands ${if (enabled) "enabled" else "disabled"}")
    }

    fun toggleEnabled(): Boolean {
        val newState = !_isEnabled.value
        setEnabled(newState)
        return newState
    }

    private fun scheduleRestart(delay: Long) {
        if (!_isEnabled.value || isProcessingCommand) return

        restartRunnable?.let { mainHandler.removeCallbacks(it) }

        val actualDelay = delay.coerceIn(MIN_RESTART_DELAY, MAX_RESTART_DELAY)

        restartRunnable = Runnable {
            if (_isEnabled.value && !isProcessingCommand) {
                Log.d(TAG, "Restarting speech recognition after ${actualDelay}ms delay")
                startListening()
            }
        }

        mainHandler.postDelayed(restartRunnable!!, actualDelay)
    }

    private inner class VoiceRecognitionListener : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            _debugInfo.value = "Listening..."
            // Reset error tracking on successful start
            consecutiveErrors = 0
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech detected")
            _debugInfo.value = "Speech detected..."
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Audio level monitoring could be added here
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio buffer received
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            _debugInfo.value = "Processing..."
        }

        override fun onError(error: Int) {
            val currentTime = System.currentTimeMillis()
            val errorMessage = getErrorMessage(error)

            Log.e(TAG, "Speech recognition error: $errorMessage (code: $error)")
            _isListening.value = false
            _debugInfo.value = "Error: $errorMessage"

            // Track consecutive errors
            if (currentTime - lastErrorTime < 5000) {
                consecutiveErrors++
            } else {
                consecutiveErrors = 1
            }
            lastErrorTime = currentTime

            // IMPROVED: Smarter restart logic with reduced delays
            val restartDelay = when {
                consecutiveErrors > MAX_CONSECUTIVE_ERRORS -> {
                    Log.w(TAG, "Too many consecutive errors ($consecutiveErrors)")
                    _debugInfo.value = "Too many errors - will retry in 5 seconds"
                    5000L
                }

                error == SpeechRecognizer.ERROR_NO_MATCH -> {
                    // Most common - restart quickly
                    800L + (consecutiveErrors * 200L)
                }

                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Timeout - restart immediately
                    500L
                }

                error == SpeechRecognizer.ERROR_AUDIO -> {
                    // Audio issues
                    createSpeechRecognizer()
                    1500L
                }

                else -> {
                    1000L
                }
            }

            scheduleRestart(restartDelay)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            Log.d(TAG, "Speech results: $matches")

            matches?.let { matchList ->
                if (matchList.isNotEmpty()) {
                    _debugInfo.value = "Heard: ${matchList[0]}"

                    // IMPROVED: Immediate command processing without waiting
                    val commandProcessed = processVoiceCommandsImmediate(matchList, confidenceScores)

                    if (commandProcessed) {
                        _debugInfo.value = "Command executed!"
                        // IMPROVED: Longer delay after successful command to prevent rapid firing
                        scheduleRestart(2000L)
                    } else {
                        _debugInfo.value = "No command found"
                        // Quick restart if no command found
                        scheduleRestart(800L)
                    }
                } else {
                    _debugInfo.value = "Empty results"
                    scheduleRestart(800L)
                }
            } ?: run {
                _debugInfo.value = "No results"
                scheduleRestart(800L)
            }

            _isListening.value = false
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // IMPROVED: Process partial results for faster response
            val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            partialMatches?.let { matches ->
                if (matches.isNotEmpty()) {
                    val partialText = matches[0].lowercase().trim()

                    // Check for clear commands in partial results
                    if (partialText.length > 3) { // Only process if we have enough text
                        val quickMatch = checkForQuickCommands(partialText)
                        if (quickMatch) {
                            Log.d(TAG, "Quick command detected in partial: $partialText")
                            // Stop listening immediately since we found a command
                            speechRecognizer?.stopListening()
                        }
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "Speech recognition event: $eventType")
        }
    }

    /**
     * IMPROVED: Immediate command processing with better matching
     */
    private fun processVoiceCommandsImmediate(
        commands: List<String>,
        confidenceScores: FloatArray? = null
    ): Boolean {
        val currentTime = System.currentTimeMillis()

        // Prevent rapid-fire commands
        if (currentTime - lastCommandTime < COMMAND_COOLDOWN) {
            Log.d(TAG, "Command cooldown active, ignoring")
            return false
        }

        isProcessingCommand = true

        try {
            for ((index, command) in commands.withIndex()) {
                val normalizedCommand = command.lowercase().trim()
                val confidence = confidenceScores?.getOrNull(index) ?: 1.0f

                Log.d(TAG, "Processing command: '$normalizedCommand' (confidence: $confidence)")

                // Skip very low confidence results only if we have confidence scores
                if (confidence < 0.4f && confidenceScores != null) {
                    continue
                }

                _lastCommand.value = normalizedCommand

                // IMPROVED: Direct word matching with immediate execution
                val words = normalizedCommand.split("\\s+".toRegex())

                for (word in words) {
                    when {
                        PLAY_COMMANDS.any { cmd -> normalizedCommand.contains(cmd) } -> {
                            Log.d(TAG, "✅ PLAY command: '$normalizedCommand'")
                            executeCommand { onPlayStopCommand?.invoke(true) }
                            return true
                        }

                        STOP_COMMANDS.any { cmd -> normalizedCommand.contains(cmd) } -> {
                            Log.d(TAG, "✅ STOP command: '$normalizedCommand'")
                            executeCommand { onPlayStopCommand?.invoke(false) }
                            return true
                        }

                        LINE_UP_COMMANDS.any { cmd -> normalizedCommand.contains(cmd) } ||
                                normalizedCommand.contains("line up") -> {
                            Log.d(TAG, "✅ LINE UP command: '$normalizedCommand'")
                            executeCommand { onLineHeightCommand?.invoke("increase") }
                            return true
                        }

                        LINE_DOWN_COMMANDS.any { cmd -> normalizedCommand.contains(cmd) } ||
                                normalizedCommand.contains("line down") -> {
                            Log.d(TAG, "✅ LINE DOWN command: '$normalizedCommand'")
                            executeCommand { onLineHeightCommand?.invoke("decrease") }
                            return true
                        }

                        VOICE_TOGGLE_COMMANDS.any { cmd -> normalizedCommand.contains(cmd) } -> {
                            Log.d(TAG, "✅ VOICE TOGGLE command: '$normalizedCommand'")
                            executeCommand { onVoiceCoachToggle?.invoke() }
                            return true
                        }
                    }
                }
            }

            Log.d(TAG, "❌ No matching command found in: $commands")
            return false

        } finally {
            isProcessingCommand = false
        }
    }

    /**
     * Check for quick commands in partial results
     */
    private fun checkForQuickCommands(text: String): Boolean {
        return PLAY_COMMANDS.any { text.contains(it) } ||
                STOP_COMMANDS.any { text.contains(it) } ||
                text.contains("line up") || text.contains("line down")
    }

    /**
     * Execute command with proper timing tracking
     */
    private fun executeCommand(action: () -> Unit) {
        try {
            action()
            lastCommandTime = System.currentTimeMillis()
            Log.d(TAG, "Command executed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command", e)
        }
    }

    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error ($error)"
        }
    }

    fun destroy() {
        stopListening()
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer", e)
        }
        speechRecognizer = null
        _debugInfo.value = "Voice manager destroyed"
        Log.d(TAG, "Voice Command Manager destroyed")
    }
}