package com.example.arfilter.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.arfilter.viewmodel.ExerciseType
import com.example.arfilter.viewmodel.LiftPhase

/**
 * Centralized utility functions for the PowerLifting AR app
 * This prevents duplicate function definitions across multiple files
 */

fun formatTime(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}

fun getPhaseColor(phase: LiftPhase): Color = when (phase) {
    LiftPhase.READY -> Color.White
    LiftPhase.ECCENTRIC -> Color.Red
    LiftPhase.BOTTOM -> Color.Yellow
    LiftPhase.CONCENTRIC -> Color.Green
    LiftPhase.TOP -> Color.Blue
    LiftPhase.REST -> Color(0xFFFF8C00) // Orange
}

fun getExerciseIcon(exercise: ExerciseType): ImageVector = when (exercise) {
    ExerciseType.SQUAT -> Icons.Default.ExpandLess
    ExerciseType.BENCH_PRESS -> Icons.Default.HorizontalRule
    ExerciseType.DEADLIFT -> Icons.Default.ExpandMore
    ExerciseType.OVERHEAD_PRESS -> Icons.Default.KeyboardArrowUp
    ExerciseType.BARBELL_ROW -> Icons.Default.KeyboardArrowDown
}