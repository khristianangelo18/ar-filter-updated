package com.example.arfilter.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arfilter.viewmodel.*
import com.example.arfilter.utils.formatTime

@Composable
fun PowerliftingOverlay(
    state: PowerliftingState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Main movement guide - positioned in center
        // Only show when not resting
        if (state.restTime <= 0) {
            MovementGuide(
                exercise = state.selectedExercise,
                tempo = state.tempo,
                rangeOfMotion = state.rangeOfMotion,
                lineHeight = state.lineHeight, // Dynamic line height
                isActive = state.isActive,
                isPaused = state.isPaused,
                currentPhase = state.currentPhase,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Rest timer (only when resting - takes center stage)
        if (state.restTime > 0 && state.isActive) {
            RestTimer(
                timeLeft = state.restTime,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Line Height Indicator
        LineHeightIndicator(
            currentHeight = state.lineHeight,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        )
    }
}

// In PowerliftingOverlay.kt - Fix the MovementGuide offset calculation

@Composable
private fun MovementGuide(
    exercise: ExerciseType,
    tempo: Tempo,
    rangeOfMotion: Float,
    lineHeight: Float, // Dynamic line height parameter
    isActive: Boolean,
    isPaused: Boolean,
    currentPhase: LiftPhase,
    modifier: Modifier = Modifier
) {
    val rangeOfMotionDp = rangeOfMotion.dp
    val lineHeightDp = lineHeight.dp

    Box(
        modifier = modifier
            .height(rangeOfMotionDp + 100.dp)
            .width(130.dp)
            // ✅ FIXED: Invert the offset so higher lineHeight moves the guide UP
            .offset(y = -(lineHeightDp - rangeOfMotionDp) / 2), // Added negative sign
        contentAlignment = Alignment.Center
    ) {
        // ... rest of the MovementGuide code remains the same

        // Movement path - vertical line with dynamic height
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(rangeOfMotionDp)
                .background(Color.White.copy(alpha = 0.7f))
                .clip(RoundedCornerShape(2.dp))
        )

        // Range markers
        Column(
            modifier = Modifier.height(rangeOfMotionDp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top position marker
            RangeMarker(
                text = getTopPositionText(exercise),
                color = if (currentPhase == LiftPhase.TOP) Color.Green else Color.White
            )

            // Bottom position marker
            RangeMarker(
                text = getBottomPositionText(exercise),
                color = if (currentPhase == LiftPhase.BOTTOM) Color.Red else Color.White
            )
        }

        // Moving tempo guide - only when active and not paused
        if (isActive && !isPaused) {
            TempoGuide(
                tempo = tempo,
                rangeOfMotion = rangeOfMotionDp,
                currentPhase = currentPhase
            )
        }

        // Exercise-specific form indicators
        ExerciseFormIndicators(
            exercise = exercise,
            rangeOfMotion = rangeOfMotionDp
        )
    }
}

@Composable
private fun TempoGuide(
    tempo: Tempo,
    rangeOfMotion: Dp,
    currentPhase: LiftPhase
) {
    // Calculate total cycle time and ensure it's never zero
    val totalCycleTime = ((tempo.eccentric + tempo.pause + tempo.concentric) * 1000).coerceAtLeast(100f)

    val infiniteTransition = rememberInfiniteTransition(label = "tempo_guide")
    val animationProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = totalCycleTime.toInt(),
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "tempo_animation"
    )

    val (position, color, size) = calculateGuideProperties(
        progress = animationProgress,
        tempo = tempo,
        rangeOfMotion = rangeOfMotion,
        currentPhase = currentPhase
    )

    // Animated guide light - positioned relative to the vertical line container
    Box(
        modifier = Modifier
            .size(size)
            .offset(y = position)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, Color.White, CircleShape)
    )
}

@Composable
private fun RestTimer(
    timeLeft: Int,
    modifier: Modifier = Modifier
) {
    // Only show if there's time left
    if (timeLeft > 0) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = Color.Blue.copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = "Rest Timer",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "REST",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatTime(timeLeft),
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun LineHeightIndicator(
    currentHeight: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Height,
                contentDescription = "Line Height",
                tint = Color.Cyan,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "${currentHeight.toInt()}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "HEIGHT",
                color = Color.Gray,
                fontSize = 8.sp
            )
        }
    }
}

@Composable
private fun RangeMarker(
    text: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ExerciseFormIndicators(
    exercise: ExerciseType,
    rangeOfMotion: Dp
) {
    when (exercise) {
        ExerciseType.SQUAT -> {
            // Depth indicator at 90 degrees
            Box(
                modifier = Modifier
                    .offset(y = rangeOfMotion * 0.7f)
                    .width(80.dp)
                    .height(2.dp)
                    .background(Color.Yellow)
            )
        }
        ExerciseType.BENCH_PRESS -> {
            // Chest touch indicator
            Box(
                modifier = Modifier
                    .offset(y = rangeOfMotion * 0.8f)
                    .width(60.dp)
                    .height(2.dp)
                    .background(Color(0xFFFF8C00)) // Orange
            )
        }
        ExerciseType.DEADLIFT -> {
            // Floor and knee level indicators
            Box(
                modifier = Modifier
                    .offset(y = rangeOfMotion)
                    .width(100.dp)
                    .height(3.dp)
                    .background(Color.Red)
            )
        }
        ExerciseType.OVERHEAD_PRESS -> {
            // Shoulder level indicator
            Box(
                modifier = Modifier
                    .offset(y = rangeOfMotion * 0.9f)
                    .width(70.dp)
                    .height(2.dp)
                    .background(Color.Blue)
            )
        }
        ExerciseType.BARBELL_ROW -> {
            // Chest level indicator
            Box(
                modifier = Modifier
                    .offset(y = rangeOfMotion * 0.6f)
                    .width(90.dp)
                    .height(2.dp)
                    .background(Color.Magenta)
            )
        }
    }
}

// Helper function to calculate guide properties during animation
private fun calculateGuideProperties(
    progress: Float,
    tempo: Tempo,
    rangeOfMotion: Dp,
    currentPhase: LiftPhase
): Triple<Dp, Color, Dp> {
    // Ensure no division by zero
    val totalTime = (tempo.eccentric + tempo.pause + tempo.concentric).coerceAtLeast(0.1f)
    val eccentricDuration = tempo.eccentric / totalTime
    val pauseDuration = tempo.pause / totalTime
    val concentricDuration = tempo.concentric / totalTime

    return when {
        progress <= eccentricDuration -> {
            // Eccentric phase - moving down from top (0) to bottom (rangeOfMotion)
            val eccentricProgress = if (eccentricDuration > 0) progress / eccentricDuration else 0f
            Triple(
                // Start at top of line (negative half range) and move down
                ((-rangeOfMotion.value / 2) + (rangeOfMotion.value * eccentricProgress)).dp,
                Color.Red,
                16.dp
            )
        }
        progress <= eccentricDuration + pauseDuration -> {
            // Pause phase - stay at bottom
            Triple(
                (rangeOfMotion.value / 2).dp,
                Color.Yellow,
                20.dp
            )
        }
        else -> {
            // Concentric phase - moving up from bottom to top
            val concentricProgress = if (concentricDuration > 0) {
                (progress - eccentricDuration - pauseDuration) / concentricDuration
            } else 0f
            Triple(
                // Start at bottom and move up to top
                ((rangeOfMotion.value / 2) - (rangeOfMotion.value * concentricProgress)).dp,
                Color.Green,
                16.dp
            )
        }
    }
}

// Helper function to get top position text for each exercise
private fun getTopPositionText(exercise: ExerciseType): String = when (exercise) {
    ExerciseType.SQUAT -> "STAND"
    ExerciseType.BENCH_PRESS -> "LOCKOUT"
    ExerciseType.DEADLIFT -> "HIP LOCK"
    ExerciseType.OVERHEAD_PRESS -> "OVERHEAD"
    ExerciseType.BARBELL_ROW -> "CHEST"
}

// Helper function to get bottom position text for each exercise
private fun getBottomPositionText(exercise: ExerciseType): String = when (exercise) {
    ExerciseType.SQUAT -> "DEPTH"
    ExerciseType.BENCH_PRESS -> "CHEST"
    ExerciseType.DEADLIFT -> "FLOOR"
    ExerciseType.OVERHEAD_PRESS -> "SHOULDERS"
    ExerciseType.BARBELL_ROW -> "HANG"
}