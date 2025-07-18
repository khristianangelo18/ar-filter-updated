package com.example.arfilter.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class PowerliftingState(
    val selectedExercise: ExerciseType = ExerciseType.SQUAT,
    val tempo: Tempo = Tempo.MODERATE,
    val rangeOfMotion: Float = 300f, // Distance in dp
    val lineHeight: Float = 300f, // Vertical position of the movement guide line
    val isActive: Boolean = false,
    val currentPhase: LiftPhase = LiftPhase.READY,
    val repCount: Int = 0,
    val targetReps: Int = 8,
    val setCount: Int = 0,
    val restTime: Int = 0, // seconds
    val showFormCues: Boolean = true,
    val showTempo: Boolean = true,
    val isPaused: Boolean = false,
    val voiceCommandsEnabled: Boolean = true
)

enum class ExerciseType(val displayName: String, val description: String) {
    SQUAT("Squat", "Follow the light down and up"),
    BENCH_PRESS("Bench Press", "Control the descent, pause, press up"),
    DEADLIFT("Deadlift", "Smooth pull from floor to lockout"),
    OVERHEAD_PRESS("OHP", "Press straight up from shoulders"),
    BARBELL_ROW("Barbell Row", "Pull to chest, control the negative")
}

enum class Tempo(val displayName: String, val eccentric: Float, val pause: Float, val concentric: Float, val description: String) {
    EXPLOSIVE("Explosive", 1f, 0f, 0.5f, "Fast and powerful"),
    MODERATE("Moderate", 2f, 0f, 1f, "Controlled movement"),
    SLOW_CONTROLLED("Slow", 3f, 1f, 2f, "Maximum control"),
    PAUSE_REPS("Pause Reps", 2f, 2f, 1f, "Competition style"),
    TEMPO_SQUATS("Tempo", 4f, 2f, 1f, "Strength building")
}

enum class LiftPhase {
    READY,      // Starting position
    ECCENTRIC,  // Lowering/negative phase
    BOTTOM,     // Bottom position/pause
    CONCENTRIC, // Lifting/positive phase
    TOP,        // Top position
    REST        // Between reps
}

class PowerliftingViewModel : ViewModel() {
    private val _state = MutableStateFlow(PowerliftingState())
    val state: StateFlow<PowerliftingState> = _state.asStateFlow()

    // Track the rest timer coroutine so we can cancel it
    private var restTimerJob: Job? = null
    // Track the rep counting coroutine
    private var repCounterJob: Job? = null

    // Line height configuration
    companion object {
        private const val MIN_LINE_HEIGHT = 200f
        private const val MAX_LINE_HEIGHT = 500f
        private const val LINE_HEIGHT_STEP = 25f
    }

    fun selectExercise(exercise: ExerciseType) {
        _state.value = _state.value.copy(
            selectedExercise = exercise,
            rangeOfMotion = when (exercise) {
                ExerciseType.SQUAT -> 350f
                ExerciseType.BENCH_PRESS -> 280f
                ExerciseType.DEADLIFT -> 400f
                ExerciseType.OVERHEAD_PRESS -> 320f
                ExerciseType.BARBELL_ROW -> 250f
            }
        )
    }

    fun selectTempo(tempo: Tempo) {
        _state.value = _state.value.copy(tempo = tempo)
    }

    fun startSet() {
        // Cancel any existing rest timer and rep counter
        stopRestTimer()
        stopRepCounter()

        _state.value = _state.value.copy(
            isActive = true,
            currentPhase = LiftPhase.READY,
            repCount = 0,
            restTime = 0, // Reset rest time when starting a new set
            isPaused = false
        )

        // Start the automatic rep counting
        startRepCounter()
    }

    fun stopSession() {
        // Stop everything and reset to initial state
        stopRestTimer()
        stopRepCounter()

        _state.value = _state.value.copy(
            isActive = false,
            isPaused = false,
            currentPhase = LiftPhase.READY,
            repCount = 0,
            restTime = 0 // Important: Reset rest timer
        )
    }

    fun pauseSet() {
        val newPausedState = !_state.value.isPaused
        _state.value = _state.value.copy(isPaused = newPausedState)

        if (newPausedState) {
            // Paused - stop rep counter
            stopRepCounter()
        } else {
            // Resumed - restart rep counter
            startRepCounter()
        }
    }

    // NEW: Voice command handlers
    fun handlePlayStopCommand(shouldPlay: Boolean) {
        if (shouldPlay) {
            if (!_state.value.isActive) {
                startSet()
            } else if (_state.value.isPaused) {
                pauseSet() // Resume if paused
            }
        } else {
            if (_state.value.isActive) {
                if (!_state.value.isPaused) {
                    pauseSet() // Pause if active
                } else {
                    stopSession() // Stop if already paused
                }
            }
        }
    }

    // NEW: Line height adjustment via voice commands
    fun adjustLineHeight(direction: String) {
        val currentHeight = _state.value.lineHeight
        val newHeight = when (direction.lowercase()) {
            "increase", "up", "raise", "higher" -> {
                (currentHeight + LINE_HEIGHT_STEP).coerceAtMost(MAX_LINE_HEIGHT) // ✅ FIXED: adding for up
            }
            "decrease", "down", "lower", "smaller" -> {
                (currentHeight - LINE_HEIGHT_STEP).coerceAtLeast(MIN_LINE_HEIGHT) // ✅ FIXED: subtracting for down
            }
            else -> currentHeight
        }

        if (newHeight != currentHeight) {
            _state.value = _state.value.copy(lineHeight = newHeight)
            Log.d("VoiceCommand", "Line height adjusted: $direction -> $currentHeight to $newHeight")
        }
    }

    // NEW: Set line height directly
    fun setLineHeight(height: Float) {
        val clampedHeight = height.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
        _state.value = _state.value.copy(lineHeight = clampedHeight)
    }

    // NEW: Toggle voice commands
    fun toggleVoiceCommands() {
        _state.value = _state.value.copy(
            voiceCommandsEnabled = !_state.value.voiceCommandsEnabled
        )
    }

    fun completeRep() {
        val currentState = _state.value
        if (currentState.repCount < currentState.targetReps) {
            _state.value = currentState.copy(
                repCount = currentState.repCount + 1,
                currentPhase = if (currentState.repCount + 1 >= currentState.targetReps) {
                    LiftPhase.REST
                } else {
                    LiftPhase.READY
                }
            )
        }
    }

    fun completeSet() {
        stopRepCounter() // Stop the rep counter

        _state.value = _state.value.copy(
            setCount = _state.value.setCount + 1,
            isActive = false,
            repCount = 0,
            currentPhase = LiftPhase.REST,
            restTime = getRestTime(),
            isPaused = false
        )
        startRestTimer()
    }

    fun skipRest() {
        stopRestTimer()
        _state.value = _state.value.copy(
            restTime = 0,
            currentPhase = LiftPhase.READY
        )
    }

    fun updatePhase(phase: LiftPhase) {
        _state.value = _state.value.copy(currentPhase = phase)
    }

    fun setTargetReps(reps: Int) {
        _state.value = _state.value.copy(targetReps = reps.coerceIn(1, 20))
    }

    fun toggleFormCues() {
        _state.value = _state.value.copy(showFormCues = !_state.value.showFormCues)
    }

    fun toggleTempo() {
        _state.value = _state.value.copy(showTempo = !_state.value.showTempo)
    }

    private fun getRestTime(): Int {
        return when (_state.value.selectedExercise) {
            ExerciseType.SQUAT, ExerciseType.DEADLIFT -> 180 // 3 minutes
            ExerciseType.BENCH_PRESS -> 150 // 2.5 minutes
            ExerciseType.OVERHEAD_PRESS, ExerciseType.BARBELL_ROW -> 120 // 2 minutes
        }
    }

    private fun startRepCounter() {
        stopRepCounter() // Cancel any existing counter

        repCounterJob = viewModelScope.launch {
            while (_state.value.isActive && _state.value.repCount < _state.value.targetReps) {
                if (!_state.value.isPaused) {
                    val tempo = _state.value.tempo
                    val totalCycleTime = ((tempo.eccentric + tempo.pause + tempo.concentric) * 1000).toLong()

                    // Update phases during the rep
                    updatePhase(LiftPhase.ECCENTRIC)
                    delay((tempo.eccentric * 1000).toLong())

                    if (!_state.value.isActive || _state.value.isPaused) break

                    updatePhase(LiftPhase.BOTTOM)
                    delay((tempo.pause * 1000).toLong())

                    if (!_state.value.isActive || _state.value.isPaused) break

                    updatePhase(LiftPhase.CONCENTRIC)
                    delay((tempo.concentric * 1000).toLong())

                    if (!_state.value.isActive || _state.value.isPaused) break

                    updatePhase(LiftPhase.TOP)

                    // Complete the rep
                    completeRep()

                    // Check if set is complete
                    if (_state.value.repCount >= _state.value.targetReps) {
                        // Auto-complete the set
                        completeSet()
                        break
                    } else {
                        // Brief pause between reps
                        delay(500)
                        updatePhase(LiftPhase.READY)
                    }
                } else {
                    // If paused, just wait
                    delay(100)
                }
            }
        }
    }

    private fun stopRepCounter() {
        repCounterJob?.cancel()
        repCounterJob = null
    }

    private fun startRestTimer() {
        // Cancel any existing timer first
        stopRestTimer()

        restTimerJob = viewModelScope.launch {
            var timeLeft = _state.value.restTime
            while (timeLeft > 0 && _state.value.restTime > 0) { // Check both conditions
                delay(1000)
                timeLeft--
                // Double-check the state hasn't been reset by stop/start
                if (_state.value.restTime > 0) {
                    _state.value = _state.value.copy(restTime = timeLeft)
                }
            }
            // Timer completed naturally
            if (timeLeft <= 0) {
                _state.value = _state.value.copy(
                    restTime = 0,
                    currentPhase = LiftPhase.READY
                )
            }
        }
    }

    private fun stopRestTimer() {
        restTimerJob?.cancel()
        restTimerJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopRestTimer() // Clean up when ViewModel is destroyed
        stopRepCounter() // Clean up rep counter
    }
}