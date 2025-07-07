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

/**
 * Enhanced Voice Command Manager with Better Recognition
 * Includes debugging and improved command matching
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

    // Voice command callbacks
    var onPlayStopCommand: ((Boolean) -> Unit)? = null
    var onLineHeightCommand: ((String) -> Unit)? = null
    var onVoiceCoachToggle: (() -> Unit)? = null

    companion object {
        private const val TAG = "VoiceCommandManager"

        // IMPROVED: More comprehensive command patterns
        private val PLAY_COMMANDS = listOf(
            "start", "play", "begin", "go", "start workout", "start set",
            "begin set", "resume", "continue", "let's go", "workout"
        )

        private val STOP_COMMANDS = listOf(
            "stop", "pause", "halt", "end", "stop workout", "pause workout",
            "stop set", "pause set", "finish", "done"
        )

        private val INCREASE_LINE_COMMANDS = listOf(
            "line up", "raise line", "move line up", "increase line", "line higher",
            "up", "raise", "higher", "increase height", "move up", "line raise",
            "go up", "lift up", "bring up"
        )

        private val DECREASE_LINE_COMMANDS = listOf(
            "line down", "lower line", "move line down", "decrease line", "line lower",
            "down", "lower", "smaller", "decrease height", "move down", "line down",
            "go down", "bring down", "drop down"
        )

        private val VOICE_COACH_COMMANDS = listOf(
            "toggle voice", "voice on", "voice off", "mute coach", "unmute coach",
            "enable voice", "disable voice", "coach on", "coach off"
        )
    }

    fun initialize() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            _debugInfo.value = "Speech recognition not available"
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(VoiceRecognitionListener())
            }
            _debugInfo.value = "Voice recognition initialized"
            Log.d(TAG, "Voice Command Manager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech recognizer", e)
            _debugInfo.value = "Failed to initialize: ${e.message}"
        }
    }

    fun startListening() {
        if (!_isEnabled.value) {
            Log.d(TAG, "Voice commands disabled")
            _debugInfo.value = "Voice commands disabled"
            return
        }

        speechRecognizer?.let { recognizer ->
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a workout command...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10) // Increased for better matching
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // IMPROVED: Better timeout settings
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
                // IMPROVED: Prefer offline recognition if available
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
            }
        } ?: run {
            _debugInfo.value = "Speech recognizer not initialized"
        }
    }

    fun stopListening() {
        try {
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
        }
        _debugInfo.value = if (enabled) "Voice commands enabled" else "Voice commands disabled"
        Log.d(TAG, "Voice commands ${if (enabled) "enabled" else "disabled"}")
    }

    fun toggleEnabled(): Boolean {
        val newState = !_isEnabled.value
        setEnabled(newState)
        return newState
    }

    private inner class VoiceRecognitionListener : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            _debugInfo.value = "Ready - speak now!"
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Beginning of speech detected")
            _debugInfo.value = "Speech detected..."
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Volume level changed - could show audio level indicator
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio buffer received
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            _debugInfo.value = "Processing speech..."
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
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

            Log.e(TAG, "Speech recognition error: $errorMessage")
            _isListening.value = false
            _debugInfo.value = "Error: $errorMessage"

            // IMPROVED: More intelligent restart logic
            when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    // These are common, restart quickly
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (_isEnabled.value) {
                            startListening()
                        }
                    }, 500)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Wait longer for busy error
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (_isEnabled.value) {
                            startListening()
                        }
                    }, 2000)
                }
                else -> {
                    // For other errors, wait longer or don't restart
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (_isEnabled.value) {
                            startListening()
                        }
                    }, 3000)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            Log.d(TAG, "Speech results: $matches")
            Log.d(TAG, "Confidence scores: ${confidenceScores?.contentToString()}")

            matches?.let {
                _debugInfo.value = "Heard: ${matches.joinToString(", ")}"
                processVoiceCommands(it, confidenceScores)
            }
            _isListening.value = false

            // Restart listening for continuous recognition
            if (_isEnabled.value) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startListening()
                }, 800) // Slightly longer delay for better performance
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            matches?.firstOrNull()?.let { partial ->
                Log.d(TAG, "Partial result: $partial")
                _debugInfo.value = "Hearing: $partial"

                // IMPROVED: Process partial results for better responsiveness
                if (partial.length > 3) { // Only process if we have substantial input
                    processVoiceCommands(listOf(partial), null, isPartial = true)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "Speech recognition event: $eventType")
        }
    }

    // IMPROVED: Enhanced command processing with confidence scores
    private fun processVoiceCommands(
        commands: List<String>,
        confidenceScores: FloatArray? = null,
        isPartial: Boolean = false
    ) {
        for ((index, command) in commands.withIndex()) {
            val normalizedCommand = command.lowercase().trim()
            val confidence = confidenceScores?.getOrNull(index) ?: 0f

            Log.d(TAG, "Processing command: '$normalizedCommand' (confidence: $confidence)")

            // Only process high-confidence results for partial matches
            if (isPartial && confidence < 0.7f) {
                continue
            }

            _lastCommand.value = normalizedCommand

            // IMPROVED: More flexible matching with substring checks
            when {
                matchesAnyCommand(normalizedCommand, PLAY_COMMANDS) -> {
                    Log.d(TAG, "✅ PLAY command detected: '$normalizedCommand'")
                    _debugInfo.value = "Command: START"
                    onPlayStopCommand?.invoke(true)
                    return
                }

                matchesAnyCommand(normalizedCommand, STOP_COMMANDS) -> {
                    Log.d(TAG, "✅ STOP command detected: '$normalizedCommand'")
                    _debugInfo.value = "Command: STOP"
                    onPlayStopCommand?.invoke(false)
                    return
                }

                matchesAnyCommand(normalizedCommand, INCREASE_LINE_COMMANDS) -> {
                    Log.d(TAG, "✅ LINE UP command detected: '$normalizedCommand'")
                    _debugInfo.value = "Command: LINE UP"
                    onLineHeightCommand?.invoke("increase")
                    return
                }

                matchesAnyCommand(normalizedCommand, DECREASE_LINE_COMMANDS) -> {
                    Log.d(TAG, "✅ LINE DOWN command detected: '$normalizedCommand'")
                    _debugInfo.value = "Command: LINE DOWN"
                    onLineHeightCommand?.invoke("decrease")
                    return
                }

                matchesAnyCommand(normalizedCommand, VOICE_COACH_COMMANDS) -> {
                    Log.d(TAG, "✅ VOICE TOGGLE command detected: '$normalizedCommand'")
                    _debugInfo.value = "Command: TOGGLE VOICE"
                    onVoiceCoachToggle?.invoke()
                    return
                }
            }
        }

        Log.d(TAG, "❌ No matching command found in: $commands")
        _debugInfo.value = "No match: ${commands.firstOrNull() ?: "unknown"}"
    }

    // IMPROVED: Better command matching with fuzzy logic
    private fun matchesAnyCommand(input: String, commandList: List<String>): Boolean {
        return commandList.any { command ->
            // Exact match
            input == command ||
                    // Contains match (for longer phrases)
                    input.contains(command) ||
                    // Reverse contains (for partial matches)
                    command.contains(input) ||
                    // Word-based matching
                    containsAllWords(input, command)
        }
    }

    // IMPROVED: Check if input contains all words from command
    private fun containsAllWords(input: String, command: String): Boolean {
        val inputWords = input.split(" ").filter { it.isNotEmpty() }
        val commandWords = command.split(" ").filter { it.isNotEmpty() }

        return commandWords.all { commandWord ->
            inputWords.any { inputWord ->
                inputWord.startsWith(commandWord) || commandWord.startsWith(inputWord)
            }
        }
    }

    fun destroy() {
        stopListening()
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