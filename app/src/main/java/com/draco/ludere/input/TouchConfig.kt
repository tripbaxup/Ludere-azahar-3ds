package com.draco.ludere.input

// Simple data classes for touch layout (kept small; layout is generated programmatically by TouchInputManager)

data class TouchButton(val id: Int, val relX: Float, val relY: Float, val relSize: Float)
data class TouchStick(val relX: Float, val relY: Float, val relRadius: Float)
