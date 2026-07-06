package com.draco.ludere.gamepad

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.draco.ludere.R
import com.draco.ludere.input.N64InputHandler
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.event.Event
import io.reactivex.disposables.CompositeDisposable

class GamePad(
    context: Context,
    padConfig: RadialGamePadConfig,
    private val sharedN64Handler: N64InputHandler? = null,
) {

    val pad = RadialGamePad(padConfig, 0f, context)

    companion object {

        @Suppress("DEPRECATION")
        fun shouldShowGamePads(activity: Activity): Boolean {

            if (!activity.resources.getBoolean(R.bool.config_gamepad))
                return false

            val hasTouchScreen =
                activity.packageManager?.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

            if (hasTouchScreen != true)
                return false


            val currentDisplayId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.display?.displayId ?: return false
                } else {
                    val wm =
                        activity.getSystemService(AppCompatActivity.WINDOW_SERVICE) as WindowManager

                    wm.defaultDisplay.displayId
                }


            val dm =
                activity.getSystemService(Service.DISPLAY_SERVICE) as DisplayManager


            if ((dm.getDisplay(currentDisplayId).flags and Display.FLAG_PRESENTATION)
                == Display.FLAG_PRESENTATION
            )
                return false


            for (id in InputDevice.getDeviceIds()) {

                val device = InputDevice.getDevice(id) ?: continue

                if ((device.sources and InputDevice.SOURCE_GAMEPAD)
                    == InputDevice.SOURCE_GAMEPAD
                )
                    return false
            }

            return true
        }
    }


    private fun eventHandler(
        event: Event,
        retroView: GLRetroView
    ) {

        when (event) {


            is Event.Button -> {

                retroView.sendKeyEvent(
                    event.action,
                    event.id
                )
            }


            is Event.Direction -> {


                when (event.id) {


                    GLRetroView.MOTION_SOURCE_DPAD -> {


                        if (sharedN64Handler != null &&
                            !sharedN64Handler.useAnalogStick
                        ) {

                            retroView.sendMotionEvent(
                                GLRetroView.MOTION_SOURCE_DPAD,
                                event.xAxis,
                                event.yAxis
                            )

                        } else if (sharedN64Handler == null) {

                            retroView.sendMotionEvent(
                                GLRetroView.MOTION_SOURCE_DPAD,
                                event.xAxis,
                                event.yAxis
                            )
                        }
                    }



                    GLRetroView.MOTION_SOURCE_ANALOG_LEFT -> {


                        if (sharedN64Handler != null &&
                            sharedN64Handler.useAnalogStick
                        ) {


                            retroView.sendMotionEvent(
                                GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                                event.xAxis,
                                event.yAxis
                            )


                        } else if (sharedN64Handler == null) {


                            retroView.sendMotionEvent(
                                GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                                event.xAxis,
                                event.yAxis
                            )
                        }
                    }



                    GLRetroView.MOTION_SOURCE_ANALOG_RIGHT -> {


                        retroView.sendMotionEvent(
                            GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                            event.xAxis,
                            event.yAxis
                        )
                    }
                }
            }
        }
    }



    fun subscribe(
        compositeDisposable: CompositeDisposable,
        retroView: GLRetroView
    ) {


        val inputDisposable = pad.events().subscribe {

            eventHandler(
                it,
                retroView
            )
        }


        compositeDisposable.add(
            inputDisposable
        )
    }
}