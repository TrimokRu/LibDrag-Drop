package com.jgomezdev.draganddropcompose

import androidx.compose.runtime.Composable

enum class SlideState {
    NONE,
    UP,
    DOWN
}

sealed class ApsState{
    object Loading: ApsState()
    data class Success(val browser: @Composable  () -> Unit): ApsState()
    object Failed: ApsState()
}