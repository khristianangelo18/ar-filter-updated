package com.example.arfilter.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arfilter.viewmodel.*
import com.example.arfilter.utils.getExerciseIcon

@Composable
fun PowerliftingControlsPanel(
    state: PowerliftingState,
    viewModel: PowerliftingViewModel,
    onCapturePhoto: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    voiceEnabled: Boolean,
    onToggleVoice: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp),
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

            // Exercise Selection
            ExerciseSelectionSection(
                selectedExercise = state.selectedExercise,
                onExerciseSelect = viewModel::selectExercise
            )

            // Tempo Selection
            TempoSelectionSection(
                selectedTempo = state.tempo,
                onTempoSelect = viewModel::selectTempo
            )

            // Set Configuration
            SetConfigurationSection(
                targetReps = state.targetReps,
                onTargetRepsChange = viewModel::setTargetReps,
                currentSet = state.setCount
            )

            // Display Options
            DisplayOptionsSection(
                showFormCues = state.showFormCues,
                showTempo = state.showTempo,
                onToggleFormCues = viewModel::toggleFormCues,
                onToggleTempo = viewModel::toggleTempo
            )

            // Action Buttons
            ActionButtonsSection(
                onCapturePhoto = onCapturePhoto,
                onDismiss = onDismiss
            )
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
internal fun SetConfigurationSection(
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