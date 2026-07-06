package com.draco.ludere.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.swordfish.libretrodroid.GLRetroView
import kotlin.math.*

/**
 * Full-screen overlay that implements touch-only virtual controls.
 *
 * Layout strategy: every element is anchored to a screen CORNER using an
 * offset scaled by min(width, height) ("u"). This keeps controls in the
 * actual reachable thumb zones consistently across aspect ratios, instead
 * of using raw percentages of full height (which pushed clusters toward
 * the middle of the screen on tall/portrait aspect ratios).
 *
 * - Bottom-left: analog stick, drawn as a draggable knob (or a D-pad, drawn
 *   as four directional buttons with the active direction highlighted, if
 *   n64Handler.useAnalogStick == false)
 * - Bottom-right: A/B face buttons (X and Y have been removed)
 * - Center, in the space X/Y used to occupy: Z shoulder button (mapped to
 *   L2, the standard N64 Z mapping)
 * - Directly above Z: C-Up/Down/Left/Right (mapped to the right analog
 *   stick, which is how N64 cores read the C buttons).
 * - Tucked into the top-right notch of the C-pad diamond, between C-Up and
 *   C-Right: R shoulder button (mapped to R1).
 * - Tucked into the top-left notch of the C-pad diamond, between C-Up and
 *   C-Left, mirroring R: L shoulder button (mapped to L1).
 * - Top-center: Start
 *
 * Handles all touch input directly (multi-touch aware): the left pointer
 * that lands nearest the stick drives the analog stick/dpad, and every
 * other pointer is tested against the face buttons, shoulder buttons,
 * start, and C-pad hit regions.
 */
class TouchOverlayView(
    context: Context,
    private val n64Handler: N64InputHandler,
    private val retroView: GLRetroView,
    private val port: Int = 0,
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(90, 255, 255, 255)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(40, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val leftInsetPct = 0.36f
    private val leftBottomInsetPct = 0.36f
    private val leftBottomInsetPortraitPct = 0.56f
    private val stickRadiusPct = 0.13f
    private val stickDragRangePct = 0.30f

    private val stickDeadzonePct = 0.08f

    private val rightInsetPct = 0.30f
    private val rightBottomInsetPct = 0.30f
    private val buttonRadiusPct = 0.085f
    private val buttonSpacingPct = 0.115f

    private val cRadiusPct = 0.058f
    private val cSpacingPct = 0.10f

    private val zRadiusPct = 0.09f

    private val startTopInsetPct = 0.14f
    private val startRadiusPct = 0.075f

    // R and L are tucked into the corner notches of the C-pad diamond (see
    // cGeometry/rCenter/lCenter), so they're sized a bit smaller than the
    // other action buttons to keep overlap with the C buttons modest.
    private val rRadiusPct = 0.062f
    private val lRadiusPct = 0.062f

    // Below this displacement (as a fraction of stickDragRangePct), an axis
    // reads as centered/neutral for digital D-pad output.
    private val digitalThreshold = 0.5f

    // How much bigger than the drawn radius a button's touch target is,
    // so small/fast taps are easier to land.
    private val hitPadding = 1.15f

    private var leftPointerId: Int = -1
    private var stickVisualDx = 0f
    private var stickVisualDy = 0f

    // pointerId -> keyCode, for the single-shot circular buttons (A, B, Z, START, R, L)
    private val buttonPointers = mutableMapOf<Int, Int>()

    // pointerId -> C-direction bitmask, for the C-pad
    private val cButtonPointers = mutableMapOf<Int, Int>()

    private companion object {
        const val C_UP = 1
        const val C_DOWN = 2
        const val C_LEFT = 4
        const val C_RIGHT = 8
    }

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun unit() = min(width, height).toFloat()
    private fun isPortrait() = height > width

    private fun leftCenter(): Pair<Float, Float> {
        val u = unit()
        val bottomInset = if (isPortrait()) leftBottomInsetPortraitPct else leftBottomInsetPct
        return Pair(u * leftInsetPct, height - u * bottomInset)
    }

    private fun rightCenter(): Pair<Float, Float> {
        val u = unit()
        return Pair(width - u * rightInsetPct, height - u * rightBottomInsetPct)
    }

    private data class CGeometry(val cx: Float, val cy: Float, val radius: Float, val spacing: Float)

    private data class FaceButton(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val color: Int,
        val label: String,
        val keyCode: Int,
    )

    // Single source of truth for the A/B face buttons. Both onDraw (what
    // gets drawn) and handlePointerDown (what gets pressed) read from this
    // one function, so the label, color, position, and key code for each
    // button can never drift out of sync with each other again.
    //
    // IMPORTANT: the key codes below are intentionally "crossed" relative to
    // their on-screen labels. Android's KEYCODE_BUTTON_A/B follow the
    // Xbox-style convention (A = primary/south button), but the core reads
    // input through libretro's RetroPad abstraction, which follows the
    // SNES/N64-style convention instead (B is the primary button - see
    // RETRO_DEVICE_ID_JOYPAD_B being slot 0 vs. _A being slot 8). So sending
    // Android's KEYCODE_BUTTON_A actually lands on the core's B button, and
    // vice versa. This was confirmed in-game: the on-screen "B" circle was
    // triggering the N64's jump/A action before this fix. If a future core
    // or library update changes this behavior, flip these two key codes
    // back and this comment can go away.
    private fun faceButtons(): List<FaceButton> {
        val u = unit()
        val (rightCx, rightCy) = rightCenter()
        val r = u * buttonRadiusPct
        val sp = u * buttonSpacingPct
        val green = Color.argb(96, 0, 170, 0)
        val purple = Color.argb(96, 140, 0, 200)

        return listOf(
            FaceButton(rightCx + sp, rightCy + sp, r, green, "B", KeyEvent.KEYCODE_BUTTON_Y),
            FaceButton(rightCx - sp, rightCy + sp, r, purple, "A", KeyEvent.KEYCODE_BUTTON_B),
        )
    }

    private val cAboveZGapPct = 0.03f

    private fun zCenter(): Pair<Float, Float> {
        val u = unit()
        val (rightCx, rightCy) = rightCenter()
        val sp = u * buttonSpacingPct
        return Pair(rightCx, rightCy - sp)
    }

    private fun cGeometry(): CGeometry {
        val u = unit()
        val (zCx, zCy) = zCenter()
        val zR = u * zRadiusPct
        val gap = u * cAboveZGapPct
        val cR = u * cRadiusPct
        val cSp = u * cSpacingPct

        val cy = zCy - zR - gap - (cSp + cR)
        return CGeometry(zCx, cy, cR, cSp)
    }

    private fun startCenter(): Pair<Float, Float> {
        val u = unit()
        return Pair(width * 0.5f, u * startTopInsetPct)
    }

    // R sits in the top-right notch of the C-pad diamond - diagonally
    // between the C-Up and C-Right buttons, equidistant from both.
    private fun rCenter(): Pair<Float, Float> {
        val cGeo = cGeometry()
        return Pair(cGeo.cx + cGeo.spacing, cGeo.cy - cGeo.spacing)
    }

    // L mirrors R on the opposite side: the top-left notch of the C-pad
    // diamond, diagonally between the C-Up and C-Left buttons.
    private fun lCenter(): Pair<Float, Float> {
        val cGeo = cGeometry()
        return Pair(cGeo.cx - cGeo.spacing, cGeo.cy - cGeo.spacing)
    }

    private fun drawLabeledCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, label: String) {
        fillPaint.color = color
        canvas.drawCircle(cx, cy, r, fillPaint)
        strokePaint.strokeWidth = r * 0.06f
        canvas.drawCircle(cx, cy, r, strokePaint)
        textPaint.textSize = r * 0.85f
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }

    private fun digitalAxis(v: Float): Int = when {
        v > digitalThreshold -> 1
        v < -digitalThreshold -> -1
        else -> 0
    }

    // Renders the D-pad as four directional buttons in a diamond (same
    // visual language as the C-pad), with the currently-pressed direction(s)
    // lit up so there's clear feedback while dragging.
    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float) {
        val u = unit()
        val dragR = u * stickDragRangePct
        val armR = u * stickRadiusPct * 1.2f
        val armSpacing = u * stickRadiusPct * 1.7f

        canvas.drawCircle(cx, cy, dragR, guidePaint)

        val digitalX = digitalAxis(stickVisualDx)
        val digitalY = digitalAxis(stickVisualDy)

        val idleColor = Color.argb(70, 255, 255, 255)
        val activeColor = Color.argb(170, 255, 255, 255)

        drawLabeledCircle(canvas, cx, cy - armSpacing, armR, if (digitalY < 0) activeColor else idleColor, "\u2191")
        drawLabeledCircle(canvas, cx, cy + armSpacing, armR, if (digitalY > 0) activeColor else idleColor, "\u2193")
        drawLabeledCircle(canvas, cx - armSpacing, cy, armR, if (digitalX < 0) activeColor else idleColor, "\u2190")
        drawLabeledCircle(canvas, cx + armSpacing, cy, armR, if (digitalX > 0) activeColor else idleColor, "\u2192")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val u = unit()

        val (leftCx, leftCy) = leftCenter()
        val dragR = u * stickDragRangePct

        if (n64Handler.useAnalogStick) {
            val knobR = u * stickRadiusPct
            canvas.drawCircle(leftCx, leftCy, dragR, guidePaint)

            val knobTravel = dragR - knobR
            val knobCx = leftCx + stickVisualDx * knobTravel
            val knobCy = leftCy + stickVisualDy * knobTravel
            fillPaint.color = Color.argb(90, 255, 255, 255)
            canvas.drawCircle(knobCx, knobCy, knobR, fillPaint)
            strokePaint.strokeWidth = u * 0.004f
            canvas.drawCircle(knobCx, knobCy, knobR, strokePaint)
        } else {
            drawDpad(canvas, leftCx, leftCy)
        }

        for (btn in faceButtons()) {
            drawLabeledCircle(canvas, btn.cx, btn.cy, btn.radius, btn.color, btn.label)
        }

        val cGeo = cGeometry()
        val amber = Color.argb(150, 210, 170, 0)
        drawLabeledCircle(canvas, cGeo.cx, cGeo.cy - cGeo.spacing, cGeo.radius, amber, "\u2191")
        drawLabeledCircle(canvas, cGeo.cx, cGeo.cy + cGeo.spacing, cGeo.radius, amber, "\u2193")
        drawLabeledCircle(canvas, cGeo.cx - cGeo.spacing, cGeo.cy, cGeo.radius, amber, "\u2190")
        drawLabeledCircle(canvas, cGeo.cx + cGeo.spacing, cGeo.cy, cGeo.radius, amber, "\u2192")

        val (zCx, zCy) = zCenter()
        drawLabeledCircle(canvas, zCx, zCy, u * zRadiusPct, Color.argb(150, 40, 40, 50), "Z")

        val (startCx, startCy) = startCenter()
        drawLabeledCircle(canvas, startCx, startCy, u * startRadiusPct, Color.argb(130, 0, 110, 190), "+")

        val (rCx, rCy) = rCenter()
        drawLabeledCircle(canvas, rCx, rCy, u * rRadiusPct, Color.argb(150, 90, 70, 130), "R")

        val (lCx, lCy) = lCenter()
        drawLabeledCircle(canvas, lCx, lCy, u * lRadiusPct, Color.argb(150, 90, 70, 130), "L")
    }

    private fun distSq(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun isInside(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean =
        distSq(x, y, cx, cy) <= r * r

    private fun cDirectionAt(x: Float, y: Float): Int? {
        val cGeo = cGeometry()
        val hitR = cGeo.radius * 1.3f
        val candidates = listOf(
            C_UP to Pair(cGeo.cx, cGeo.cy - cGeo.spacing),
            C_DOWN to Pair(cGeo.cx, cGeo.cy + cGeo.spacing),
            C_LEFT to Pair(cGeo.cx - cGeo.spacing, cGeo.cy),
            C_RIGHT to Pair(cGeo.cx + cGeo.spacing, cGeo.cy),
        )
        for ((dir, pos) in candidates) {
            if (isInside(x, y, pos.first, pos.second, hitR)) return dir
        }
        return null
    }

    private fun updateCButtonState() {
        var mask = 0
        for (v in cButtonPointers.values) mask = mask or v

        val x = when {
            mask and C_RIGHT != 0 && mask and C_LEFT != 0 -> 0f
            mask and C_RIGHT != 0 -> 1f
            mask and C_LEFT != 0 -> -1f
            else -> 0f
        }
        val y = when {
            mask and C_DOWN != 0 && mask and C_UP != 0 -> 0f
            mask and C_DOWN != 0 -> 1f
            mask and C_UP != 0 -> -1f
            else -> 0f
        }

        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, x, y, port)
    }

    private fun pressButton(pointerId: Int, keyCode: Int) {
        buttonPointers[pointerId] = keyCode
        retroView.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode, port)
    }

    private fun updateStick(x: Float, y: Float) {
        val (leftCx, leftCy) = leftCenter()
        val u = unit()
        val dragR = u * stickDragRangePct

        var dx = x - leftCx
        var dy = y - leftCy
        val dist = hypot(dx, dy)
        if (dist > dragR && dist > 0f) {
            val scale = dragR / dist
            dx *= scale
            dy *= scale
        }

        stickVisualDx = dx / dragR
        stickVisualDy = dy / dragR

        if (n64Handler.useAnalogStick) {
            n64Handler.sendVirtualAnalogLeft(stickVisualDx, stickVisualDy, retroView, port)
        } else {
            n64Handler.sendVirtualDpad(
                digitalAxis(stickVisualDx).toFloat(),
                digitalAxis(stickVisualDy).toFloat(),
                retroView,
                port,
            )
        }
    }

    private fun releaseStick() {
        leftPointerId = -1
        stickVisualDx = 0f
        stickVisualDy = 0f
        if (n64Handler.useAnalogStick) {
            n64Handler.sendVirtualAnalogLeft(0f, 0f, retroView, port)
        } else {
            n64Handler.sendVirtualDpad(0f, 0f, retroView, port)
        }
    }

    private fun handlePointerDown(event: MotionEvent, idx: Int) {
        val id = event.getPointerId(idx)
        val x = event.getX(idx)
        val y = event.getY(idx)
        val u = unit()

        // 1. Analog stick / dpad (only one pointer may drive it at a time)
        val (leftCx, leftCy) = leftCenter()
        val stickGrabR = u * stickDragRangePct * 1.3f
        if (leftPointerId == -1 && isInside(x, y, leftCx, leftCy, stickGrabR)) {
            leftPointerId = id
            updateStick(x, y)
            return
        }

        // 2. A / B face buttons
        for (btn in faceButtons()) {
            if (isInside(x, y, btn.cx, btn.cy, btn.radius * hitPadding)) {
                pressButton(id, btn.keyCode)
                return
            }
        }

        // 3. Z shoulder button
        val (zCx, zCy) = zCenter()
        if (isInside(x, y, zCx, zCy, u * zRadiusPct * hitPadding)) {
            pressButton(id, KeyEvent.KEYCODE_BUTTON_L2)
            return
        }

        // 4. Start
        val (startCx, startCy) = startCenter()
        if (isInside(x, y, startCx, startCy, u * startRadiusPct * hitPadding)) {
            pressButton(id, KeyEvent.KEYCODE_BUTTON_START)
            return
        }

        // 5. C-pad. Checked before R/L since R and L are tucked tightly into
        // the diamond's corner notches and slightly overlap it - in that
        // sliver of overlap, the C-pad should win.
        cDirectionAt(x, y)?.let { dir ->
            cButtonPointers[id] = dir
            updateCButtonState()
            return
        }

        // 6. R shoulder button (top-right notch of the C-pad diamond)
        val (rCx, rCy) = rCenter()
        if (isInside(x, y, rCx, rCy, u * rRadiusPct * hitPadding)) {
            pressButton(id, KeyEvent.KEYCODE_BUTTON_R1)
            return
        }

        // 7. L shoulder button (top-left notch of the C-pad diamond)
        val (lCx, lCy) = lCenter()
        if (isInside(x, y, lCx, lCy, u * lRadiusPct * hitPadding)) {
            pressButton(id, KeyEvent.KEYCODE_BUTTON_L1)
        }
    }

    private fun handlePointerMove(event: MotionEvent, idx: Int) {
        val id = event.getPointerId(idx)
        val x = event.getX(idx)
        val y = event.getY(idx)

        if (id == leftPointerId) {
            updateStick(x, y)
            return
        }

        if (cButtonPointers.containsKey(id)) {
            val dir = cDirectionAt(x, y)
            if (dir != null) {
                if (cButtonPointers[id] != dir) {
                    cButtonPointers[id] = dir
                    updateCButtonState()
                }
            } else {
                cButtonPointers.remove(id)
                updateCButtonState()
            }
        }
    }

    private fun handlePointerUp(id: Int) {
        if (id == leftPointerId) {
            releaseStick()
            return
        }

        buttonPointers.remove(id)?.let { code ->
            retroView.sendKeyEvent(KeyEvent.ACTION_UP, code, port)
        }

        if (cButtonPointers.remove(id) != null) {
            updateCButtonState()
        }
    }

    private fun releaseAll() {
        if (leftPointerId != -1) {
            releaseStick()
        }

        for (code in buttonPointers.values.toList()) {
            retroView.sendKeyEvent(KeyEvent.ACTION_UP, code, port)
        }
        buttonPointers.clear()

        if (cButtonPointers.isNotEmpty()) {
            cButtonPointers.clear()
            updateCButtonState()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown(event, event.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    handlePointerMove(event, i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
            }
        }
        invalidate()
        return true
    }
}
