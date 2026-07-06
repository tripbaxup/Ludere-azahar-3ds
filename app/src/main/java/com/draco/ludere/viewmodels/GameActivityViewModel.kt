package com.draco.ludere.viewmodels

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.os.Build
import android.text.InputType
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.AndroidViewModel
import com.draco.ludere.R
import com.draco.ludere.gamepad.GamePad
import com.draco.ludere.input.ControllerInput
import com.draco.ludere.input.TouchOverlayView
import com.draco.ludere.retroview.RetroView
import com.draco.ludere.utils.RetroViewUtils
import io.reactivex.disposables.CompositeDisposable

class GameActivityViewModel(application: Application) : AndroidViewModel(application) {
    private val resources = application.resources

    var retroView: RetroView? = null
    private var retroViewUtils: RetroViewUtils? = null

    private var leftGamePad: GamePad? = null
    private var rightGamePad: GamePad? = null

    private var menuDialog: AlertDialog? = null

    private var compositeDisposable = CompositeDisposable()
    private val controllerInput = ControllerInput()

    // touch overlay
    private var touchOverlay: TouchOverlayView? = null

    init {
        controllerInput.menuCallback = { showMenu() }
    }

    fun prepareMenu(activity: Activity) {
        if (menuDialog != null)
            return

        val menuOnClickListener = MenuOnClickListener()
        menuDialog = AlertDialog.Builder(activity)
            .setItems(menuOnClickListener.menuOptions, menuOnClickListener)
            .create()
    }

    fun showMenu() {
        if (retroView?.frameRendered?.value == true) {
            retroView?.let { retroViewUtils?.preserveEmulatorState(it) }
            val activity = retroView?.view?.context as? Activity
            if (activity != null) {
                prepareMenu(activity)
                menuDialog?.show()
            }
        }
    }

    fun dismissMenu() {
        if (menuDialog?.isShowing == true)
            menuDialog?.dismiss()
    }

    fun preserveState() {
        if (retroView?.frameRendered?.value == true)
            retroView?.let { retroViewUtils?.preserveEmulatorState(it) }
    }

    @Suppress("DEPRECATION")
    /**
     * Enter immersive fullscreen mode.
     *
     * Compatibility notes (API 16–35):
     * - API 16..29: uses deprecated SYSTEM_UI_FLAG_* flags on the decorView.
     * - API 30..35 (Android 11+): prefers WindowInsetsController when available.
     *   The insetsController is checked null-safely to avoid crashes on some OEM devices
     *   where the controller may be unavailable even on supported API levels.
     */
    fun immersive(window: Window) {
        if (!resources.getBoolean(R.bool.config_fullscreen))
            return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // window.insetsController may be null on some devices; use let {} for null-safety
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    fun setupRetroView(activity: ComponentActivity, container: FrameLayout) {
        retroView = RetroView(activity, compositeDisposable)
        retroViewUtils = RetroViewUtils(activity)

        retroView?.let { retroView ->
            container.addView(retroView.view)
            activity.lifecycle.addObserver(retroView.view)
            retroView.registerFrameRenderedListener()

            // Add single full-screen touch overlay above the retro view
            val overlay = TouchOverlayView(activity, controllerInput.n64InputHandler, retroView.view)
            val overlayParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            container.addView(overlay, overlayParams)
            touchOverlay = overlay

            retroView.frameRendered.observe(activity) {
                if (it != true)
                    return@observe

                retroViewUtils?.restoreEmulatorState(retroView)
            }
        }
    }

    // No-op for gamepads: touch overlay replaces radial gamepads for touch-only builds
    fun setupGamePads(leftContainer: FrameLayout, rightContainer: FrameLayout) {
        // intentionally left blank - touch overlay provides all virtual controls
    }

    fun updateGamePadVisibility(activity: Activity, leftContainer: FrameLayout, rightContainer: FrameLayout) {
        // keep original visibility logic in case UI expects containers, but they remain empty
        val visibility = if (GamePad.shouldShowGamePads(activity))
            View.VISIBLE
        else
            View.GONE

        leftContainer.visibility = visibility
        rightContainer.visibility = visibility
    }

    fun processKeyEvent(keyCode: Int, event: KeyEvent): Boolean? {
        retroView?.let {
            return controllerInput.processKeyEvent(keyCode, event, it)
        }
        return false
    }

    fun processMotionEvent(event: MotionEvent): Boolean? {
        retroView?.let {
            return controllerInput.processMotionEvent(event, it)
        }
        return false
    }

    fun detachRetroView(activity: ComponentActivity) {
        retroView?.let { activity.lifecycle.removeObserver(it.view) }
        retroView = null
        touchOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        touchOverlay = null
    }

    fun setConfigOrientation(activity: Activity) {
        when (resources.getInteger(R.integer.config_orientation)) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            3 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> return
        }.also {
            activity.requestedOrientation = it
        }
    }

    fun dispose() {
        compositeDisposable.dispose()
        compositeDisposable = CompositeDisposable()
    }

    inner class MenuOnClickListener : DialogInterface.OnClickListener {
        private val context = getApplication<Application>().applicationContext

        val menuOptions: Array<String>
            get() {
                val baseOptions = arrayOf(
                    context.getString(R.string.menu_reset),
                    context.getString(R.string.menu_save_state),
                    context.getString(R.string.menu_load_state),
                    context.getString(R.string.menu_mute),
                    context.getString(R.string.menu_fast_forward)
                )

                val toggleLabel = if (controllerInput.n64InputHandler.useAnalogStick) {
                    context.getString(R.string.menu_toggle_analog)
                } else {
                    context.getString(R.string.menu_toggle_dpad)
                }

                return baseOptions + toggleLabel
            }

        override fun onClick(dialog: DialogInterface?, which: Int) {
            val context = getApplication<Application>().applicationContext
            val baseOptions = arrayOf(
                context.getString(R.string.menu_reset),
                context.getString(R.string.menu_save_state),
                context.getString(R.string.menu_load_state),
                context.getString(R.string.menu_mute),
                context.getString(R.string.menu_fast_forward)
            )

            when {
                which < baseOptions.size -> {
                    when (baseOptions[which]) {
                        context.getString(R.string.menu_reset) -> retroView?.view?.reset()
                        context.getString(R.string.menu_save_state) -> retroView?.let {
                            retroViewUtils?.saveState(it)
                        }
                        context.getString(R.string.menu_load_state) -> retroView?.let{
                            retroViewUtils?.loadState(it)
                        }
                        context.getString(R.string.menu_mute) -> retroView?.let {
                            it.view.audioEnabled = !it.view.audioEnabled
                        }
                        context.getString(R.string.menu_fast_forward) -> retroView?.let {
                            showSpeedDialog(it)
                        }
                    }
                }
                which == baseOptions.size -> {
                    controllerInput.n64InputHandler.useAnalogStick = !controllerInput.n64InputHandler.useAnalogStick
                    controllerInput.n64InputHandler.reset()
                    // persist pref for next sessions
                    val prefs = context.getSharedPreferences("touch_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("touch_use_analog", controllerInput.n64InputHandler.useAnalogStick).apply()
                }
            }
        }
        
        private fun showSpeedDialog(retroView: RetroView) {
            val activity = retroView.view.context as? Activity ?: return
            
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("Set Game Speed")
            
            val layout = LinearLayout(activity)
            layout.orientation = LinearLayout.VERTICAL
            layout.setPadding(50, 40, 50, 10)
            
            val input = EditText(activity)
            input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            
            // retroView.view.frameSpeed is a plain integer multiplier
            // (1 = normal, 2 = 2x, 4 = 4x, ...), NOT a percentage.
            // Display it directly as a decimal multiplier (e.g. "1.0").
            val currentSpeedDecimal = retroView.view.frameSpeed.toFloat()
            input.setText(currentSpeedDecimal.toString())
            input.setSelection(input.text.length)
            layout.addView(input)
            
            val helpText = TextView(activity)
            helpText.text = """
                1.00 = normal
                0.95 = slightly slower
                0.90 = slower
                0.80 = noticeably slower
                1.10 = slightly faster
                2.00 = double speed
            """.trimIndent()
            helpText.textSize = 14f
            helpText.setPadding(0, 20, 0, 0)
            layout.addView(helpText)
            
            builder.setView(layout)
            
            builder.setPositiveButton("OK") { _, _ ->
                val speedStr = input.text.toString()
                val speed = speedStr.toFloatOrNull() ?: 1.0f
                // frameSpeed is a plain integer multiplier (1 = normal speed).
                // Do NOT multiply by 100 -- that treats it as a percentage and
                // was causing e.g. "1.00" (normal speed) to set frameSpeed to
                // 100 (100x speed) instead of 1 (1x speed).
                retroView.view.frameSpeed = speed.toInt().coerceAtLeast(1)
            }
            builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
            
            builder.show()
        }
    }
}