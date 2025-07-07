package com.example.arfilter.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arfilter.viewmodel.*
import com.example.arfilter.utils.formatTime
import com.example.arfilter.utils.getPhaseColor

@Composable
fun PowerliftingStatsPanel(
    state: PowerliftingState,
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

            // Rest Timer (if active)
            if (state.restTime > 0) {
                RestTimeIndicator(restTime = state.restTime)
            }
        }
    }
}

@Composable
private fun StatusIndicator(
    isActive: Boolean,
    isPaused: Boolean,
    currentPhase: LiftPhase
) {
    val color = when {
        !isActive -> Color.Gray
        isPaused -> Color(0xFFFF8C00) // Orange
        else -> getPhaseColor(currentPhase)
    }

    // Only animate when active and not paused
    if (isActive && !isPaused) {
        val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "status_alpha"
        )

        StatusIndicatorContent(color = color.copy(alpha = alpha), currentPhase = currentPhase, isActive = isActive, isPaused = isPaused)
    } else {
        // Static version when not active or paused
        StatusIndicatorContent(color = color, currentPhase = currentPhase, isActive = isActive, isPaused = isPaused)
    }
}

@Composable
private fun StatusIndicatorContent(
    color: Color,
    currentPhase: LiftPhase,
    isActive: Boolean,
    isPaused: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = color,
                    shape = CircleShape
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