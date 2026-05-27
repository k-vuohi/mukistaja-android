package com.example.mukistaja

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A custom View that shows a source bitmap and lets the user:
 *  - Pinch-zoom to scale
 *  - Drag to pan
 * The crop is always locked to MUKI_W:MUKI_H (176:264) aspect ratio.
 * Call getCroppedBitmap() to extract the current crop at full source resolution.
 */
class CropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var sourceBitmap: Bitmap? = null

    // Transform: maps source-bitmap coords into view coords
    private val matrix = Matrix()
    private val matrixInverse = Matrix()

    // Gesture detectors
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    // Preview overlay
    private var previewBitmap: Bitmap? = null
    private val previewPaint = Paint().apply { alpha = 200 }
    private val overlayPaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    var onCropChanged: (() -> Unit)? = null

    fun setBitmap(bmp: Bitmap) {
        sourceBitmap = bmp
        previewBitmap = null
        fitBitmapToView()
        invalidate()
    }

    fun setPreview(preview: Bitmap?) {
        previewBitmap = preview
        invalidate()
    }

    fun rotate90() {
        val bmp = sourceBitmap ?: return
        val m = Matrix().apply { postRotate(90f) }
        sourceBitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        previewBitmap = null
        fitBitmapToView()
        invalidate()
        onCropChanged?.invoke()
    }

    /**
     * Returns the portion of the source bitmap currently visible in the crop frame,
     * scaled to exactly MUKI_W x MUKI_H.
     */
    fun getCroppedBitmap(): Bitmap? {
        val bmp = sourceBitmap ?: return null
        val cropRect = getCropRectInSource() ?: return null

        val x = cropRect.left.toInt().coerceIn(0, bmp.width - 1)
        val y = cropRect.top.toInt().coerceIn(0, bmp.height - 1)
        val w = cropRect.width().toInt().coerceIn(1, bmp.width - x)
        val h = cropRect.height().toInt().coerceIn(1, bmp.height - y)

        val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
        return Bitmap.createScaledBitmap(cropped, MukiImage.MUKI_W, MukiImage.MUKI_H, true)
    }

    // -------------------------------------------------------------------------

    private fun fitBitmapToView() {
        val bmp = sourceBitmap ?: return
        if (width == 0 || height == 0) return

        val cropW = cropFrameWidth()
        val cropH = cropFrameHeight()

        // Scale so bitmap fills the crop frame
        val scale = max(cropW / bmp.width.toFloat(), cropH / bmp.height.toFloat())
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
        matrix.invert(matrixInverse)
    }

    private fun cropFrameWidth() = width.toFloat() * 0.85f
    private fun cropFrameHeight() = cropFrameWidth() * MukiImage.MUKI_H / MukiImage.MUKI_W

    private fun cropFrameRect(): RectF {
        val cw = cropFrameWidth(); val ch = cropFrameHeight()
        val cx = (width - cw) / 2f; val cy = (height - ch) / 2f
        return RectF(cx, cy, cx + cw, cy + ch)
    }

    private fun getCropRectInSource(): RectF? {
        val frame = cropFrameRect()
        val pts = floatArrayOf(frame.left, frame.top, frame.right, frame.bottom)
        matrixInverse.mapPoints(pts)
        return RectF(pts[0], pts[1], pts[2], pts[3])
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitBitmapToView()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = sourceBitmap ?: return
        val frame = cropFrameRect()

        canvas.save()
        canvas.concat(matrix)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.restore()

        // Darken outside crop frame
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, overlayPaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), overlayPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, overlayPaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, overlayPaint)

        // Draw dithered preview inside crop frame (if available)
        previewBitmap?.let {
            canvas.drawBitmap(it, null, frame, previewPaint)
        }

        // Crop frame border
        canvas.drawRect(frame, borderPaint)
    }

    // -------------------------------------------------------------------------
    // Touch handling

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val dx = event.getX(idx) - lastTouchX
                        val dy = event.getY(idx) - lastTouchY
                        matrix.postTranslate(dx, dy)
                        matrix.invert(matrixInverse)
                        lastTouchX = event.getX(idx)
                        lastTouchY = event.getY(idx)
                        invalidate()
                        onCropChanged?.invoke()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == activePointerId) {
                    val newIdx = if (idx == 0) 1 else 0
                    lastTouchX = event.getX(newIdx)
                    lastTouchY = event.getY(newIdx)
                    activePointerId = event.getPointerId(newIdx)
                }
            }
        }
        return true
    }

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val bmp = sourceBitmap ?: return false
            val scaleFactor = detector.scaleFactor

            // Clamp: don't let bitmap get smaller than crop frame
            val frame = cropFrameRect()
            val values = FloatArray(9)
            matrix.getValues(values)
            val currentScale = values[Matrix.MSCALE_X]
            val minScale = max(
                frame.width() / bmp.width,
                frame.height() / bmp.height
            )
            val newScale = (currentScale * scaleFactor).coerceIn(minScale, minScale * 8f)
            val clampedFactor = newScale / currentScale

            matrix.postScale(clampedFactor, clampedFactor, detector.focusX, detector.focusY)
            matrix.invert(matrixInverse)
            invalidate()
            onCropChanged?.invoke()
            return true
        }
    }
}
