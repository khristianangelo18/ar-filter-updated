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
 * IMPROVED Voice Command Manager with Better Recognition
 * Fixes "no speech match found" issues and improves reliability
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

    // Add restart delay tracking to prevent rapid restarts
    private var lastErrorTime = 0L
    private var consecutiveErrors = 0

    // Voice command callbacks
    var onPlayStopCommand: ((Boolean) -> Unit)? = null
    var onLineHeightCommand: ((String) -> Unit)? = null
    var onVoiceCoachToggle: (() -> Unit)? = null

    companion object {
        private const val TAG = "VoiceCommandManager"
        private const val MIN_RESTART_DELAY = 1000L // 1 second minimum
        private const val MAX_RESTART_DELAY = 10000L // 10 seconds maximum
        private const val MAX_CONSECUTIVE_ERRORS = 5

        // SIMPLIFIED: More basic command patterns - easier to match
        private val PLAY_COMMANDS = setOf(
            "start", "play", "go", "begin", "resume"
        )

        private val STOP_COMMANDS = setOf(
            "stop", "pause", "halt", "end"
        )

        private val LINE_UP_COMMANDS = setOf(
            "up", "raise", "higher", "increase"
        )

        private val LINE_DOWN_COMMANDS = setOf(
            "down", "lower", "decrease", "smaller"
        )

        private val VOICE_TOGGLE_COMMANDS = setOf(
            "voice", "mute", "unmute", "coach"
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

                // IMPROVED: More lenient settings for better recognition
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) // Disable partial results to reduce noise

                // IMPROVED: Adjusted timeout settings for better reliability
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)

                // IMPROVED: Use online recognition for better accuracy
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            try {
                recognizer.startListening(intent)
                _isListening.value = true
                _debugInfo.value = "Listening for commands..."
                Log.d(TAG, "Started listening for voice commands")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                _isListening.value = false
                _debugInfo.value = "Failed to start listening: ${e.message}"
                scheduleRestart(2000L) // Restart after 2 seconds
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
        if (!_isEnabled.value) return

        restartRunnable?.let { mainHandler.removeCallbacks(it) }

        val actualDelay = delay.coerceIn(MIN_RESTART_DELAY, MAX_RESTART_DELAY)

        restartRunnable = Runnable {
            if (_isEnabled.value) {
                Log.d(TAG, "Restarting speech recognition after ${actualDelay}ms delay")
                startListening()
            }
        }

        mainHandler.postDelayed(restartRunnable!!, actualDelay)
    }

    private inner class VoiceRecognitionListener : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            _debugInfo.value = "Ready - speak now!"
            // Reset error tracking on successful start
            consecutiveErrors = 0
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Beginning of speech detected")
            _debugInfo.value = "Speech detected..."
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could show audio level indicator here
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio buffer received
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            _debugInfo.value = "Processing speech..."
        }

        override fun onError(error: Int) {
            val currentTime = System.currentTimeMillis()
            val errorMessage = getErrorMessage(error)

            Log.e(TAG, "Speech recognition error: $errorMessage (code: $error)")
            _isListening.value = false
            _debugInfo.value = "Error: $errorMessage"

            // Track consecutive errors
            if (currentTime - lastErrorTime < 5000) { // Within 5 seconds of last error
                consecutiveErrors++
            } else {
                consecutiveErrors = 1
            }
            lastErrorTime = currentTime

            // IMPROVED: Smarter restart logic based on error type and frequency
            val restartDelay = when {
                consecutiveErrors > MAX_CONSECUTIVE_ERRORS -> {
                    Log.w(TAG, "Too many consecutive errors ($consecutiveErrors), stopping auto-restart")
                    _debugInfo.value = "Too many errors - please restart manually"
                    return // Don't auto-restart
                }

                error == SpeechRecognizer.ERROR_NO_MATCH -> {
                    // Most common error - restart quickly but not immediately
                    1500L + (consecutiveErrors * 500L) // Increase delay with each error
                }

                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // No speech detected - restart after short delay
                    1000L + (consecutiveErrors * 300L)
                }

                error == SpeechRecognizer.ERROR_AUDIO -> {
                    // Audio issues - wait longer and recreate recognizer
                    createSpeechRecognizer()
                    3000L + (consecutiveErrors * 1000L)
                }

                error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    // Network issues - wait longer
                    5000L + (consecutiveErrors * 2000L)
                }

                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Service busy - recreate and wait
                    createSpeechRecognizer()
                    4000L + (consecutiveErrors * 1500L)
                }

                else -> {
                    // Other errors - moderate delay
                    2000L + (consecutiveErrors * 1000L)
                }
            }

            scheduleRestart(restartDelay)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            Log.d(TAG, "Speech results: $matches")
            Log.d(TAG, "Confidence scores: ${confidenceScores?.contentToString()}")

            matches?.let { matchList ->
                if (matchList.isNotEmpty()) {
                    _debugInfo.value = "Heard: ${matchList[0]}"
                    val commandProcessed = processVoiceCommands(matchList, confidenceScores)

                    if (!commandProcessed) {
                        _debugInfo.value = "No match: ${matchList[0]}"
                        Log.d(TAG, "No matching command found in results")
                    }
                } else {
                    _debugInfo.value = "Empty results"
                    Log.d(TAG, "Received empty results list")
                }
            } ?: run {
                _debugInfo.value = "No results"
                Log.d(TAG, "No results in bundle")
            }

            _isListening.value = false

            // Restart listening for continuous recognition if enabled
            if (_isEnabled.value) {
                scheduleRestart(1000L) // 1 second delay for normal restart
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Disabled partial results for better stability
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "Speech recognition event: $eventType")
        }
    }

    // SIMPLIFIED: Much simpler and more reliable command processing
    private fun processVoiceCommands(
        commands: List<String>,
        confidenceScores: FloatArray? = null
    ): Boolean {
        for ((index, command) in commands.withIndex()) {
            val normalizedCommand = command.lowercase().trim()
            val confidence = confidenceScores?.getOrNull(index) ?: 0f

            Log.d(TAG, "Processing command: '$normalizedCommand' (confidence: $confidence)")

            // Skip very low confidence results
            if (confidence < 0.3f && confidenceScores != null) {
                continue
            }

            _lastCommand.value = normalizedCommand

            // SIMPLIFIED: Check for simple keyword matches instead of complex patterns
            val words = normalizedCommand.split("\\s+".toRegex())

            for (word in words) {
                when {
                    PLAY_COMMANDS.contains(word) -> {
                        Log.d(TAG, "✅ PLAY command detected: '$word' in '$normalizedCommand'")
                        _debugInfo.value = "Command: START"
                        onPlayStopCommand?.invoke(true)
                        return true
                    }

                    STOP_COMMANDS.contains(word) -> {
                        Log.d(TAG, "✅ STOP command detected: '$word' in '$normalizedCommand'")
                        _debugInfo.value = "Command: STOP"
                        onPlayStopCommand?.invoke(false)
                        return true
                    }

                    LINE_UP_COMMANDS.contains(word) || normalizedCommand.contains("line up") -> {
                        Log.d(TAG, "✅ LINE UP command detected: '$word' in '$normalizedCommand'")
                        _debugInfo.value = "Command: LINE UP"
                        onLineHeightCommand?.invoke("increase")
                        return true
                    }

                    LINE_DOWN_COMMANDS.contains(word) || normalizedCommand.contains("line down") -> {
                        Log.d(TAG, "✅ LINE DOWN command detected: '$word' in '$normalizedCommand'")
                        _debugInfo.value = "Command: LINE DOWN"
                        onLineHeightCommand?.invoke("decrease")
                        return true
                    }

                    VOICE_TOGGLE_COMMANDS.contains(word) -> {
                        Log.d(TAG, "✅ VOICE TOGGLE command detected: '$word' in '$normalizedCommand'")
                        _debugInfo.value = "Command: TOGGLE VOICE"
                        onVoiceCoachToggle?.invoke()
                        return true
                    }
                }
            }
        }

        Log.d(TAG, "❌ No matching command found in: $commands")
        return false
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
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
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