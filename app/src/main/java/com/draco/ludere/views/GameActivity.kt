package com.draco.ludere.views

import android.app.Service
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.draco.ludere.databinding.ActivityGameBinding
import com.draco.ludere.viewmodels.GameActivityViewModel
import kotlin.math.abs

class GameActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGameBinding
    private val viewModel: GameActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lockRefreshRateTo60Hz()

        /* Use immersive mode when we change the window insets */
        window.decorView.setOnApplyWindowInsetsListener { view, windowInsets ->
            view.post { viewModel.immersive(window) }
            return@setOnApplyWindowInsetsListener windowInsets
        }

        registerInputListener()
        viewModel.setConfigOrientation(this)
        viewModel.updateGamePadVisibility(this, binding.leftContainer, binding.rightContainer)
        viewModel.prepareMenu(this)
        viewModel.setupRetroView(this, binding.retroviewContainer)
        viewModel.setupGamePads(binding.leftContainer, binding.rightContainer)
    }

    /**
     * N64 content is authored for 60fps (NTSC). LibretroDroid paces
     * emulation and audio dynamic-rate-control off the display's actual
     * vsync signal, so on adaptive/high-refresh-rate panels (90Hz, 120Hz,
     * LTPO "Smooth Display" on Pixel devices) the emulator can end up
     * running slightly faster than intended, with audio pitching up to
     * match.
     *
     * `preferredRefreshRate` is only a hint and is frequently ignored by
     * Pixel's adaptive display logic, especially for content rendered
     * inside a SurfaceView/GLSurfaceView (which is what GLRetroView uses
     * internally). Explicitly selecting a concrete 60Hz display mode via
     * `preferredDisplayModeId` is honored far more reliably.
     */
    private fun lockRefreshRateTo60Hz() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

        val sixtyHzMode = display
            ?.supportedModes
            ?.filter { it.refreshRate in 59.5..60.5 }
            // Prefer the mode that also matches the display's current resolution
            ?.minByOrNull { mode ->
                val currentMode = display.mode
                abs(mode.physicalWidth - currentMode.physicalWidth) +
                    abs(mode.physicalHeight - currentMode.physicalHeight)
            }

        val params = window.attributes
        if (sixtyHzMode != null) {
            params.preferredDisplayModeId = sixtyHzMode.modeId
        } else {
            // Fall back to the soft hint if no exact 60Hz mode is reported
            params.preferredRefreshRate = 60f
        }
        window.attributes = params
    }

    /**
     * Listen for new controller additions and removals
     */
    private fun registerInputListener() {
        val inputManager = getSystemService(Service.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                viewModel.updateGamePadVisibility(this@GameActivity, binding.leftContainer, binding.rightContainer)
            }
            override fun onInputDeviceRemoved(deviceId: Int) {
                viewModel.updateGamePadVisibility(this@GameActivity, binding.leftContainer, binding.rightContainer)
            }
            override fun onInputDeviceChanged(deviceId: Int) {
                viewModel.updateGamePadVisibility(this@GameActivity, binding.leftContainer, binding.rightContainer)
            }
        }, null)
    }

    override fun onBackPressed() = viewModel.showMenu()

    override fun onDestroy() {
        viewModel.dismissMenu()
        viewModel.dispose()
        viewModel.detachRetroView(this)
        super.onDestroy()
    }

    override fun onPause() {
        viewModel.preserveState()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return viewModel.processKeyEvent(keyCode, event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return viewModel.processKeyEvent(keyCode, event) ?: super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return viewModel.processMotionEvent(event) ?: super.onGenericMotionEvent(event)
    }
}