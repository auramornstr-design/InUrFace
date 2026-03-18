/**
 * In Your Face — Adaptive Interface Overlay System
 * Authors: Sunni (Sir) Morningstar and Cael Devo
 *
 * TeachModeOverlay.kt
 * A full-screen translucent capture layer that mounts over the foreign app
 * when Teach Mode is active. Intercepts exactly one touch event, records
 * the screen coordinates, and passes them to the Diplomat via the MarkerSurface.
 *
 * Visual behavior:
 *   - Semi-transparent dark tint over the foreign app
 *   - Animated pulsing crosshair that follows the last known finger position
 *   - "Tap the element you want to remap" instruction text
 *   - Ripple animation at tap point before dismissing
 *   - Cancel button in top-right corner
 *
 * Architecture rule honored:
 *   This view belongs to the DiplomatRuntime's overlay window. It intercepts
 *   touch, extracts screen coordinates, and hands them off via MarkerSurface.
 *   It never calls into the foreign app itself — that is the Diplomat's job.
 */

package com.inyourface.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

class TeachModeOverlay(
    context: Context,
    private val markerSurface: MarkerSurface,
    private val requestId: String,
    private val onCancelled: () -> Unit
) : View(context) {

    // ─── State ────────────────────────────────────────────────────────────────

    private var captureState = CaptureState.WAITING
    private var tapX = -1f
    private var tapY = -1f
    private var crosshairX = -1f
    private var crosshairY = -1f
    private var rippleRadius = 0f
    private var rippleAlpha = 0f
    private var pulseScale = 1f
    private var instructionAlpha = 1f

    private enum class CaptureState { WAITING, TAPPED, DISMISSING }

    // ─── Animators ────────────────────────────────────────────────────────────

    private val pulseAnimator = ValueAnimator.ofFloat(1f, 1.18f, 1f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulseScale = it.animatedValue as Float
            if (captureState == CaptureState.WAITING) invalidate()
        }
    }

    private val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 400L
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            val t = it.animatedValue as Float
            rippleRadius = t * 180f
            rippleAlpha = (1f - t) * 0.7f
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                captureState = CaptureState.DISMISSING
                dismissOverlay()
            }
        })
    }

    // ─── Paints ───────────────────────────────────────────────────────────────

    private val tintPaint = Paint().apply {
        color = Color.argb(130, 5, 10, 30)
        style = Paint.Style.FILL
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 233, 69, 96) // brand accent
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val crosshairCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 233, 69, 96)
        style = Paint.Style.FILL
    }

    private val crosshairCircleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 233, 69, 96)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 48f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val instructionSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 34f
    }

    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 233, 69, 96)
        textAlign = Paint.Align.RIGHT
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val cancelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    init {
        // Start with instruction text centered — crosshair will appear on first move
        crosshairX = -1f
        crosshairY = -1f
    }

    fun start() {
        pulseAnimator.start()
        alpha = 0f
        animate().alpha(1f).setDuration(200L).start()
    }

    private fun dismissOverlay() {
        animate()
            .alpha(0f)
            .setDuration(250L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { onCancelled() }
            .start()
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Full screen tint
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)

        // Cancel button — top right
        drawCancelButton(canvas)

        when (captureState) {
            CaptureState.WAITING -> {
                drawInstruction(canvas)
                if (crosshairX > 0 && crosshairY > 0) drawCrosshair(canvas, crosshairX, crosshairY)
            }
            CaptureState.TAPPED -> {
                drawRipple(canvas, tapX, tapY)
            }
            CaptureState.DISMISSING -> {}
        }
    }

    private fun drawInstruction(canvas: Canvas) {
        val cy = height * 0.18f
        instructionPaint.alpha = (instructionAlpha * 255).toInt()
        instructionSubPaint.alpha = (instructionAlpha * 180).toInt()

        canvas.drawText("TEACH MODE", width / 2f, cy, instructionPaint)
        canvas.drawText(
            "Tap the element you want to remap",
            width / 2f,
            cy + 60f,
            instructionSubPaint
        )
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        val outerR = 44f * pulseScale
        val innerR = 14f
        val lineLen = 60f

        // Inner fill
        crosshairCirclePaint.alpha = (0.6f * pulseScale * 255).toInt()
        canvas.drawCircle(cx, cy, outerR, crosshairCirclePaint)

        // Border circle
        crosshairCircleBorderPaint.alpha = (0.9f * 255).toInt()
        canvas.drawCircle(cx, cy, outerR, crosshairCircleBorderPaint)

        // Inner dot
        crosshairPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, innerR * 0.4f, crosshairPaint)
        crosshairPaint.style = Paint.Style.STROKE

        // Cross lines — gap in center so the inner circle is clear
        val gapR = outerR + 16f
        // Horizontal
        canvas.drawLine(cx - gapR - lineLen, cy, cx - gapR, cy, crosshairPaint)
        canvas.drawLine(cx + gapR, cy, cx + gapR + lineLen, cy, crosshairPaint)
        // Vertical
        canvas.drawLine(cx, cy - gapR - lineLen, cx, cy - gapR, crosshairPaint)
        canvas.drawLine(cx, cy + gapR, cx, cy + gapR + lineLen, crosshairPaint)
    }

    private fun drawRipple(canvas: Canvas, cx: Float, cy: Float) {
        ripplePaint.alpha = (rippleAlpha * 255).toInt()
        canvas.drawCircle(cx, cy, rippleRadius, ripplePaint)
    }

    private fun drawCancelButton(canvas: Canvas) {
        val padding = 48f
        val btnRight = width.toFloat() - padding
        val btnTop = padding
        val label = "✕  Cancel"

        // Background pill
        val bounds = RectF(
            btnRight - cancelPaint.measureText(label) - 32f,
            btnTop - 40f,
            btnRight + 16f,
            btnTop + 20f
        )
        canvas.drawRoundRect(bounds, 24f, 24f, cancelBgPaint)
        canvas.drawText(label, btnRight, btnTop, cancelPaint)
    }

    // ─── Touch Handling ───────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (captureState == CaptureState.DISMISSING) return true

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                // Track finger for crosshair even before lift
                if (captureState == CaptureState.WAITING) {
                    crosshairX = event.x
                    crosshairY = event.y
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y

                // Check cancel button hit area
                val cancelRight = width.toFloat() - 48f
                val cancelTop = 48f - 40f
                if (x > cancelRight - 300f && y < cancelTop + 70f) {
                    pulseAnimator.cancel()
                    captureState = CaptureState.DISMISSING

                    // Place cancel marker on surface
                    markerSurface.place(
                        OverlayMarker.TeachModeCancel(
                            id = generateMarkerId(),
                            placedAtMs = System.currentTimeMillis()
                        )
                    )
                    dismissOverlay()
                    return true
                }

                // Real element tap — capture coordinates and signal Diplomat
                if (captureState == CaptureState.WAITING) {
                    tapX = x; tapY = y
                    captureState = CaptureState.TAPPED
                    pulseAnimator.cancel()

                    // Place the teach marker with real screen coordinates
                    markerSurface.place(
                        OverlayMarker.TeachModeActivate(
                            id = requestId,
                            placedAtMs = System.currentTimeMillis(),
                            screenX = x,
                            screenY = y
                        )
                    )

                    // Play ripple then dismiss
                    rippleAnimator.start()
                }
            }
        }
        return true // consume all touches — foreign app should not receive them
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
        rippleAnimator.cancel()
    }
}
