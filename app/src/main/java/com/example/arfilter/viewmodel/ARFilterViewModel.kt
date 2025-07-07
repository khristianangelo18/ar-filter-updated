package com.example.arfilter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FilterState(
    val lineHeight: Float = 320f,
    val animationDuration: Float = 1200f,
    val selectedFilter: FilterType = FilterType.LASER_LINE,
    val isAnimationPaused: Boolean = false,
    val lineColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Green,
    val showControls: Boolean = true,
    val isRecording: Boolean = false,
    val showTutorial: Boolean = false,
    val tutorialStep: Int = 0
)

enum class FilterType {
    LASER_LINE,
    CROSSHAIR,
    HEARTS,
    STARS,
    GRID,
    FACE_OVERLAY
}

class ARFilterViewModel : ViewModel() {
    private val _filterState = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = _filterState.asStateFlow()

    private val _showInstruction = MutableStateFlow(true)
    val showInstruction: StateFlow<Boolean> = _showInstruction.asStateFlow()

    private val _showGuidance = MutableStateFlow(false)
    val showGuidance: StateFlow<Boolean> = _showGuidance.asStateFlow()

    init {
        startInitialInstructions()
    }

    private fun startInitialInstructions() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _showInstruction.value = false
            _showGuidance.value = true
            kotlinx.coroutines.delay(5000)
            _showGuidance.value = false
        }
    }

    fun updateLineHeight(height: Float) {
        _filterState.value = _filterState.value.copy(lineHeight = height)
    }

    fun updateAnimationDuration(duration: Float) {
        _filterState.value = _filterState.value.copy(animationDuration = duration)
    }

    fun toggleAnimationPause() {
        _filterState.value = _filterState.value.copy(
            isAnimationPaused = !_filterState.value.isAnimationPaused
        )
    }

    fun selectFilter(filterType: FilterType) {
        _filterState.value = _filterState.value.copy(selectedFilter = filterType)
    }

    fun updateLineColor(color: androidx.compose.ui.graphics.Color) {
        _filterState.value = _filterState.value.copy(lineColor = color)
    }

    fun toggleControls() {
        _filterState.value = _filterState.value.copy(
            showControls = !_filterState.value.showControls
        )
    }

    fun startRecording() {
        _filterState.value = _filterState.value.copy(isRecording = true)
    }

    fun stopRecording() {
        _filterState.value = _filterState.value.copy(isRecording = false)
    }

    fun showTutorial() {
        _filterState.value = _filterState.value.copy(
            showTutorial = true,
            tutorialStep = 0
        )
    }

    fun nextTutorialStep() {
        val currentStep = _filterState.value.tutorialStep
        if (currentStep < 3) {
            _filterState.value = _filterState.value.copy(tutorialStep = currentStep + 1)
        } else {
            _filterState.value = _filterState.value.copy(
                showTutorial = false,
                tutorialStep = 0
            )
        }
    }

    fun hideTutorial() {
        _filterState.value = _filterState.value.copy(
            showTutorial = false,
            tutorialStep = 0
        )
    }
}