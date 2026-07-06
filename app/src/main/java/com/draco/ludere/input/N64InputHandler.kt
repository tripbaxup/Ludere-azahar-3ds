package com.draco.ludere.input

import android.view.MotionEvent
import com.swordfish.libretrodroid.GLRetroView
import kotlin.math.abs

class N64InputHandler {

    companion object {
        private const val DEADZONE_THRESHOLD = 0.05f
        private const val ANALOG_MAX = 1f
    }

    private var analogLeftX = 0f
    private var analogLeftY = 0f

    private var analogRightX = 0f
    private var analogRightY = 0f

    private var dpadX = 0f
    private var dpadY = 0f

    var useAnalogStick = true


    fun processN64MotionEvent(
        event: MotionEvent,
        retroView: GLRetroView,
        port: Int = 0
    ) {

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        val rawX = event.getAxisValue(MotionEvent.AXIS_X)
        val rawY = event.getAxisValue(MotionEvent.AXIS_Y)

        val rightX = event.getAxisValue(MotionEvent.AXIS_Z)
        val rightY = event.getAxisValue(MotionEvent.AXIS_RZ)



        if (!useAnalogStick) {

            val analogDpadX =
                if (rawX > 0.5f) 1f
                else if (rawX < -0.5f) -1f
                else 0f


            val analogDpadY =
                if (rawY > 0.5f) 1f
                else if (rawY < -0.5f) -1f
                else 0f


            val finalDpadX =
                if (hatX != 0f) hatX else analogDpadX


            val finalDpadY =
                if (hatY != 0f) hatY else analogDpadY


            if (finalDpadX != dpadX || finalDpadY != dpadY) {

                dpadX = finalDpadX
                dpadY = finalDpadY

                retroView.sendMotionEvent(
                    GLRetroView.MOTION_SOURCE_DPAD,
                    finalDpadX,
                    finalDpadY,
                    port
                )
            }


            return
        }



        // D-PAD
        if (hatX != dpadX || hatY != dpadY) {

            dpadX = hatX
            dpadY = hatY

            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_DPAD,
                hatX,
                hatY,
                port
            )
        }




        // LEFT ANALOG
        val analogX = applyDeadzone(rawX)

        // NORMAL N64 CONTROL
        // UP = UP
        // DOWN = DOWN
        val analogY = applyDeadzone(rawY)



        analogLeftX = analogX
        analogLeftY = analogY


        retroView.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
            analogX,
            analogY,
            port
        )





        // RIGHT ANALOG
        val rightAnalogX =
            applyDeadzone(rightX)

        val rightAnalogY =
            applyDeadzone(rightY)



        if (rightAnalogX != analogRightX ||
            rightAnalogY != analogRightY
        ) {

            analogRightX = rightAnalogX
            analogRightY = rightAnalogY


            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                rightAnalogX,
                rightAnalogY,
                port
            )
        }
    }





    private fun applyDeadzone(value: Float): Float {

        if (abs(value) < DEADZONE_THRESHOLD) {
            return 0f
        }


        val sign =
            if (value > 0) 1f else -1f


        val absValue =
            abs(value)


        val scaled =
            (absValue - DEADZONE_THRESHOLD) /
                    (ANALOG_MAX - DEADZONE_THRESHOLD)


        return scaled * sign
    }





    fun reset() {

        analogLeftX = 0f
        analogLeftY = 0f

        analogRightX = 0f
        analogRightY = 0f

        dpadX = 0f
        dpadY = 0f
    }





    fun sendVirtualAnalogLeft(
        x: Float,
        y: Float,
        retroView: GLRetroView,
        port: Int = 0
    ) {

        val ax = applyDeadzone(x)

        val ay = applyDeadzone(y)


        analogLeftX = ax
        analogLeftY = ay


        retroView.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
            ax,
            ay,
            port
        )
    }





    fun sendVirtualDpad(
        x: Float,
        y: Float,
        retroView: GLRetroView,
        port: Int = 0
    ) {

        if (x != dpadX || y != dpadY) {

            dpadX = x
            dpadY = y


            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_DPAD,
                x,
                y,
                port
            )
        }
    }
}