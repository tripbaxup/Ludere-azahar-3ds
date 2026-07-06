package com.draco.ludere.input

import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.draco.ludere.retroview.RetroView
import com.swordfish.libretrodroid.GLRetroView

class ControllerInput {
    companion object {
        val KEYCOMBO_MENU = setOf(
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT
        )

        val EXCLUDED_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_POWER
        )
    }
    
    private val keyLog = mutableSetOf<Int>()
    var menuCallback: () -> Unit = {}
    
    // Shared N64 input handler - used by both physical and touch controls
    val n64InputHandler = N64InputHandler()

    private fun getPort(event: InputEvent): Int =
        ((event.device?.controllerNumber ?: 1) - 1).coerceAtLeast(0)

    private fun checkMenuKeyCombo() {
        if (keyLog == KEYCOMBO_MENU)
            menuCallback()
    }

    fun processKeyEvent(keyCode: Int, event: KeyEvent, retroView: RetroView): Boolean? {
        if (EXCLUDED_KEYS.contains(keyCode))
            return null

        if (retroView.frameRendered.value == false)
            return true

        val port = getPort(event)
        retroView.view.sendKeyEvent(event.action, keyCode, port)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyLog.add(keyCode)
            KeyEvent.ACTION_UP -> keyLog.remove(keyCode)
        }

        checkMenuKeyCombo()
        return true
    }

    fun processMotionEvent(event: MotionEvent, retroView: RetroView): Boolean? {
        if (retroView.frameRendered.value == false)
            return null

        val port = getPort(event)
        n64InputHandler.processN64MotionEvent(event, retroView.view, port)
        return true
    }
}
