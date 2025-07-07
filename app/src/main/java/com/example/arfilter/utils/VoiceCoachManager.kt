package com.example.arfilter.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
// FIXED: Import the enums from your viewmodel package
import com.example.arfilter.viewmodel.ExerciseType
import com.example.arfilter.viewmodel.LiftPhase

/**
 * Enhanced Voice Coach Manager with Smart Interval Control
 * Prevents repetitive and irritating voice feedback during workouts
 */
class VoiceCoachManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // Voice settings
    var speechRate = 0.85f
        set(value) {
            field = value.coerceIn(0.1f, 3.0f)
            tts?.setSpeechRate(field)
        }

    var pitch = 1.1f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    // Coaching preferences
    var verboseMode = false
    var motivationalMode = true
    var countdownEnabled = true

    // SMART INTERVAL CONTROL - Anti-repetition system
    private val announcementHistory = mutableMapOf<String, Long>()
    private var lastPhaseAnnounced: LiftPhase? = null
    private var lastExerciseAnnounced: ExerciseType? = null
    private var lastRepAnnounced: Int = -1
    private var lastSetAnnounced: Int = -1

    // Configurable intervals (in milliseconds) - FIXED: Made var instead of val
    private var intervalSettings = IntervalSettings()

    data class IntervalSettings(
        val samePhaseInterval: Long = 8000L,        // 8 seconds between same phase cues
        val differentPhaseInterval: Long = 2000L,   // 2 seconds between different phases
        val repCountInterval: Long = 3000L,         // 3 seconds between rep announcements
        val motivationalInterval: Long = 15000L,    // 15 seconds between motivational cues
        val generalAnnouncementInterval: Long = 5000L, // 5 seconds for general announcements
        val restAnnouncementInterval: Long = 10000L,   // 10 seconds during rest periods
        val setCompleteInterval: Long = 1000L       // 1 second for set completion (immediate)
    )

    companion object {
        private const val TAG = "VoiceCoachManager"
        private const val UTTERANCE_ID_PREFIX = "coach_"
    }

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { textToSpeech ->
                    val result = textToSpeech.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "Language not supported")
                        _isReady.value = false
                    } else {
                        setupTtsSettings(textToSpeech)
                        _isReady.value = true
                        Log.d(TAG, "Voice Coach initialized successfully")

                        // Welcome message (only once)
                        speak("Voice coach ready", priority = Priority.LOW, allowRepeat = false)
                    }
                }
            } else {
                Log.e(TAG, "TextToSpeech initialization failed")
                _isReady.value = false
            }
        }
    }

    private fun setupTtsSettings(textToSpeech: TextToSpeech) {
        textToSpeech.setSpeechRate(speechRate)
        textToSpeech.setPitch(pitch)

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                Log.e(TAG, "TTS error for utterance: $utteranceId")
            }
        })
    }

    enum class Priority {
        LOW,     // General feedback, can be skipped
        MEDIUM,  // Form cues, important but can wait
        HIGH,    // Rep counting, safety alerts
        CRITICAL // Emergency, never skip
    }

    /**
     * Smart speak function with intelligent interval control
     */
    fun speak(
        text: String,
        priority: Priority = Priority.MEDIUM,
        allowRepeat: Boolean = false,
        customInterval: Long? = null
    ) {
        if (!_isEnabled.value || !_isReady.value || tts == null) return

        val currentTime = System.currentTimeMillis()
        val interval = customInterval ?: getIntervalForPriority(priority)

        // Check if we should skip this announcement based on timing
        if (!allowRepeat && shouldSkipAnnouncement(text, currentTime, interval)) {
            Log.d(TAG, "Skipping repetitive announcement: $text")
            return
        }

        // Critical announcements override everything
        val queueMode = when (priority) {
            Priority.CRITICAL -> TextToSpeech.QUEUE_FLUSH
            Priority.HIGH -> TextToSpeech.QUEUE_ADD
            Priority.MEDIUM, Priority.LOW -> TextToSpeech.QUEUE_ADD
        }

        val utteranceId = "${UTTERANCE_ID_PREFIX}${System.currentTimeMillis()}"

        tts?.speak(text, queueMode, null, utteranceId)

        // Update announcement history
        announcementHistory[text] = currentTime

        Log.d(TAG, "Speaking: $text (Priority: $priority)")
    }

    private fun getIntervalForPriority(priority: Priority): Long {
        return when (priority) {
            Priority.CRITICAL -> 0L // No interval for critical
            Priority.HIGH -> intervalSettings.repCountInterval
            Priority.MEDIUM -> intervalSettings.generalAnnouncementInterval
            Priority.LOW -> intervalSettings.motivationalInterval
        }
    }

    private fun shouldSkipAnnouncement(text: String, currentTime: Long, interval: Long): Boolean {
        val lastAnnouncementTime = announcementHistory[text]
        return lastAnnouncementTime != null && (currentTime - lastAnnouncementTime) < interval
    }

    /**
     * Smart form cue announcement with phase change detection
     */
    fun announceFormCue(exercise: ExerciseType, phase: LiftPhase) {
        val currentTime = System.currentTimeMillis()

        // Only announce if phase actually changed or enough time has passed
        val shouldAnnounce = when {
            lastPhaseAnnounced != phase -> {
                // Different phase - use shorter interval
                true
            }
            lastExerciseAnnounced != exercise -> {
                // Different exercise - always announce
                true
            }
            else -> {
                // Same phase - check if enough time has passed
                val lastAnnouncement = announcementHistory["phase_${phase.name}"]
                lastAnnouncement == null ||
                        (currentTime - lastAnnouncement) >= intervalSettings.samePhaseInterval
            }
        }

        if (!shouldAnnounce) {
            Log.d(TAG, "Skipping form cue for phase $phase - too soon")
            return
        }

        val cue = getFormCueForPhase(exercise, phase)
        val interval = if (lastPhaseAnnounced != phase) {
            intervalSettings.differentPhaseInterval
        } else {
            intervalSettings.samePhaseInterval
        }

        speak(cue, Priority.MEDIUM, allowRepeat = false, customInterval = interval)

        // Update tracking
        lastPhaseAnnounced = phase
        lastExerciseAnnounced = exercise
        announcementHistory["phase_${phase.name}"] = currentTime
    }

    /**
     * Smart rep counting with interval control
     */
    fun announceRepCount(current: Int, target: Int) {
        if (!countdownEnabled) return

        // Skip if same rep was recently announced
        if (lastRepAnnounced == current) {
            Log.d(TAG, "Skipping rep count $current - already announced")
            return
        }

        val cue = when {
            current == 1 -> "First rep"
            current == target -> "Final rep! Push it!"
            current > target -> "Extra rep! You've got this!"
            target - current == 1 -> "One more rep!"
            target - current == 2 -> "Two more reps!"
            else -> "Rep $current"
        }

        speak(cue, Priority.HIGH, allowRepeat = false, customInterval = intervalSettings.repCountInterval)
        lastRepAnnounced = current
    }

    /**
     * Set completion with immediate announcement
     */
    fun announceSetComplete(setNumber: Int, reps: Int) {
        // Always announce set completion (but prevent double announcements)
        if (lastSetAnnounced == setNumber) return

        val cue = if (motivationalMode) {
            "Set $setNumber complete! $reps reps. Excellent work!"
        } else {
            "Set $setNumber complete. $reps reps."
        }

        speak(cue, Priority.HIGH, allowRepeat = false, customInterval = intervalSettings.setCompleteInterval)
        lastSetAnnounced = setNumber
    }

    /**
     * Smart rest period announcements
     */
    fun announceRestPeriod(seconds: Int) {
        val cue = when {
            seconds > 60 -> "Rest ${seconds / 60} minutes"
            seconds > 30 -> "Rest $seconds seconds"
            seconds <= 10 && seconds > 0 -> "Get ready! $seconds seconds left"
            else -> return // Don't announce very short or zero rest
        }

        speak(cue, Priority.MEDIUM, allowRepeat = false, customInterval = intervalSettings.restAnnouncementInterval)
    }

    /**
     * Controlled motivational announcements
     */
    fun announceMotivation() {
        if (!motivationalMode) return

        val currentTime = System.currentTimeMillis()
        val lastMotivation = announcementHistory["motivation"]

        // Only motivate every 15+ seconds
        if (lastMotivation != null && (currentTime - lastMotivation) < intervalSettings.motivationalInterval) {
            return
        }

        val motivationalCues = arrayOf(
            "You've got this!",
            "Strong work!",
            "Keep pushing!",
            "Form looks great!",
            "Stay focused!",
            "Power through it!"
        )

        val cue = motivationalCues.random()
        speak(cue, Priority.LOW, allowRepeat = false)
        announcementHistory["motivation"] = currentTime
    }

    /**
     * Safety alerts - immediate, no intervals
     */
    fun announceSafetyAlert(message: String) {
        speak("Safety alert: $message", Priority.CRITICAL, allowRepeat = true)
    }

    /**
     * Reset announcement history for new workout
     */
    fun resetForNewWorkout() {
        announcementHistory.clear()
        lastPhaseAnnounced = null
        lastExerciseAnnounced = null
        lastRepAnnounced = -1
        lastSetAnnounced = -1
        Log.d(TAG, "Voice coach reset for new workout")
    }

    /**
     * Update interval settings for user customization - FIXED
     */
    fun updateIntervals(
        samePhaseInterval: Long? = null,
        motivationalInterval: Long? = null,
        repCountInterval: Long? = null
    ) {
        intervalSettings = intervalSettings.copy(
            samePhaseInterval = samePhaseInterval ?: intervalSettings.samePhaseInterval,
            motivationalInterval = motivationalInterval ?: intervalSettings.motivationalInterval,
            repCountInterval = repCountInterval ?: intervalSettings.repCountInterval
        )
        Log.d(TAG, "Updated intervals: samePhase=${intervalSettings.samePhaseInterval}, motivational=${intervalSettings.motivationalInterval}, repCount=${intervalSettings.repCountInterval}")
    }

    private fun getFormCueForPhase(exercise: ExerciseType, phase: LiftPhase): String {
        return if (verboseMode) {
            getVerboseFormCue(exercise, phase)
        } else {
            getConciseFormCue(exercise, phase)
        }
    }

    private fun getConciseFormCue(exercise: ExerciseType, phase: LiftPhase): String {
        return when (exercise) {
            ExerciseType.SQUAT -> when (phase) {
                LiftPhase.READY -> "Ready"
                LiftPhase.ECCENTRIC -> "Sit back"
                LiftPhase.BOTTOM -> "Hit depth"
                LiftPhase.CONCENTRIC -> "Drive up"
                LiftPhase.TOP -> "Lockout"
                LiftPhase.REST -> "Reset"
            }
            ExerciseType.BENCH_PRESS -> when (phase) {
                LiftPhase.READY -> "Set position"
                LiftPhase.ECCENTRIC -> "Control down"
                LiftPhase.BOTTOM -> "Touch and pause"
                LiftPhase.CONCENTRIC -> "Press up"
                LiftPhase.TOP -> "Lockout"
                LiftPhase.REST -> "Reset"
            }
            ExerciseType.DEADLIFT -> when (phase) {
                LiftPhase.READY -> "Set position"
                LiftPhase.ECCENTRIC -> "Hinge back"
                LiftPhase.BOTTOM -> "Touch floor"
                LiftPhase.CONCENTRIC -> "Drive hips"
                LiftPhase.TOP -> "Stand tall"
                LiftPhase.REST -> "Reset"
            }
            ExerciseType.OVERHEAD_PRESS -> when (phase) {
                LiftPhase.READY -> "Set position"
                LiftPhase.ECCENTRIC -> "Control down"
                LiftPhase.BOTTOM -> "Shoulders loaded"
                LiftPhase.CONCENTRIC -> "Press up"
                LiftPhase.TOP -> "Overhead"
                LiftPhase.REST -> "Reset"
            }
            ExerciseType.BARBELL_ROW -> when (phase) {
                LiftPhase.READY -> "Bent over"
                LiftPhase.ECCENTRIC -> "Lower slow"
                LiftPhase.BOTTOM -> "Full stretch"
                LiftPhase.CONCENTRIC -> "Pull to chest"
                LiftPhase.TOP -> "Squeeze blades"
                LiftPhase.REST -> "Control down"
            }
        }
    }

    private fun getVerboseFormCue(exercise: ExerciseType, phase: LiftPhase): String {
        return when (exercise) {
            ExerciseType.SQUAT -> when (phase) {
                LiftPhase.READY -> "Feet shoulder width apart. Core engaged."
                LiftPhase.ECCENTRIC -> "Sit back and down. Knees track over toes."
                LiftPhase.BOTTOM -> "Hit proper depth. Stay tight through the core."
                LiftPhase.CONCENTRIC -> "Drive through your heels. Push the floor away."
                LiftPhase.TOP -> "Full hip extension. Lock it out."
                LiftPhase.REST -> "Reset your position. Take a breath."
            }
            ExerciseType.BENCH_PRESS -> when (phase) {
                LiftPhase.READY -> "Arch your back. Feet planted firmly."
                LiftPhase.ECCENTRIC -> "Control the bar down. Keep your shoulders back."
                LiftPhase.BOTTOM -> "Touch your chest. Brief pause."
                LiftPhase.CONCENTRIC -> "Press to full lockout. Drive through your hands."
                LiftPhase.TOP -> "Arms fully extended. Hold the position."
                LiftPhase.REST -> "Maintain tension. Prepare for next rep."
            }
            ExerciseType.DEADLIFT -> when (phase) {
                LiftPhase.READY -> "Bar over mid foot. Neutral spine."
                LiftPhase.ECCENTRIC -> "Hinge at the hips. Keep the bar close."
                LiftPhase.BOTTOM -> "Bar touches the floor. Reset your grip."
                LiftPhase.CONCENTRIC -> "Drive your hips forward. Stand up strong."
                LiftPhase.TOP -> "Full hip extension. Shoulders back."
                LiftPhase.REST -> "Reset your position. Stay focused."
            }
            ExerciseType.OVERHEAD_PRESS -> when (phase) {
                LiftPhase.READY -> "Bar at shoulder level. Core tight."
                LiftPhase.ECCENTRIC -> "Control the descent. Stay balanced."
                LiftPhase.BOTTOM -> "Shoulders loaded and ready."
                LiftPhase.CONCENTRIC -> "Press straight up. No forward drift."
                LiftPhase.TOP -> "Full overhead lockout. Arms straight."
                LiftPhase.REST -> "Lower to shoulders. Reset position."
            }
            ExerciseType.BARBELL_ROW -> when (phase) {
                LiftPhase.READY -> "Bent over position. Neutral spine."
                LiftPhase.ECCENTRIC -> "Lower with control. Feel the stretch."
                LiftPhase.BOTTOM -> "Full arm extension. Maintain tension."
                LiftPhase.CONCENTRIC -> "Pull to your chest. Lead with your elbows."
                LiftPhase.TOP -> "Squeeze your shoulder blades together."
                LiftPhase.REST -> "Control the weight down. Stay in position."
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (!enabled) {
            tts?.stop()
        } else if (enabled && _isReady.value) {
            speak("Voice coach enabled", Priority.LOW, allowRepeat = false)
        }
    }

    // ADDED: Missing toggleEnabled function
    fun toggleEnabled(): Boolean {
        val newState = !_isEnabled.value
        setEnabled(newState)
        return newState
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _isReady.value = false
    }
}

// FIXED: Extension function with correct type references from viewmodel
fun VoiceCoachManager.announcePhaseTransition(
    exercise: ExerciseType,
    fromPhase: LiftPhase?,
    toPhase: LiftPhase
) {
    // Only announce if phase actually changed
    if (fromPhase != toPhase) {
        when (toPhase) {
            LiftPhase.READY -> announceFormCue(exercise, toPhase)
            LiftPhase.ECCENTRIC -> {
                announceFormCue(exercise, toPhase)
                // Add occasional motivation during new reps
                if (fromPhase == LiftPhase.TOP && motivationalMode) {
                    // Only 30% chance to avoid over-motivation
                    if (kotlin.random.Random.nextFloat() < 0.3f) {
                        announceMotivation()
                    }
                }
            }
            LiftPhase.BOTTOM -> announceFormCue(exercise, toPhase)
            LiftPhase.CONCENTRIC -> announceFormCue(exercise, toPhase)
            LiftPhase.TOP -> announceFormCue(exercise, toPhase)
            LiftPhase.REST -> {
                // Only announce rest in verbose mode
                if (verboseMode) announceFormCue(exercise, toPhase)
            }
        }
    }
}