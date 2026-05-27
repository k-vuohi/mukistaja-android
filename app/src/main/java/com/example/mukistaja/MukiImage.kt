package com.example.mukistaja

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.roundToInt

object MukiImage {

    const val MUKI_W = 176
    const val MUKI_H = 264

    /**
     * Convert a Bitmap (any size) to the 5808-byte 1-bit array the Muki expects.
     * The bitmap is assumed to already be cropped/framed as the user wants it —
     * i.e. it should be 176x264 or will be scaled to fit exactly.
     * The hardware rotation (90° CW) is applied here before packing.
     */
    fun toBitArray(source: Bitmap): ByteArray? {
        return try {
            val scaled = Bitmap.createScaledBitmap(source, MUKI_W, MUKI_H, true)
            val dithered = floydSteinbergDither(scaled)
            val rotated = rotateCw90(dithered)
            packBits(rotated)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Produce a 1-bit dithered preview bitmap at MUKI_W x MUKI_H.
     * This is exactly what will be sent to the device (before hardware rotation).
     * Pass this directly to an ImageView for a WYSIWYG preview.
     */
    fun toPreviewBitmap(source: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(source, MUKI_W, MUKI_H, true)
        val black = floydSteinbergDitherToBooleans(scaled)

        val preview = Bitmap.createBitmap(MUKI_W, MUKI_H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(MUKI_W * MUKI_H) { i ->
            if (black[i]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        preview.setPixels(pixels, 0, MUKI_W, 0, 0, MUKI_W, MUKI_H)
        return preview
    }

    // -------------------------------------------------------------------------

    private fun floydSteinbergDitherToBooleans(bmp: Bitmap): BooleanArray {
        val buf = FloatArray(MUKI_W * MUKI_H) { i ->
            val p = bmp.getPixel(i % MUKI_W, i / MUKI_W)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }

        val black = BooleanArray(MUKI_W * MUKI_H)
        for (y in 0 until MUKI_H) {
            for (x in 0 until MUKI_W) {
                val idx = y * MUKI_W + x
                val old = buf[idx]
                val new = if (old < 128f) 0f else 255f
                black[idx] = new == 0f
                val err = old - new
                fun add(dx: Int, dy: Int, w: Float) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until MUKI_W && ny in 0 until MUKI_H)
                        buf[ny * MUKI_W + nx] += err * w
                }
                add(1, 0, 7f/16f); add(-1, 1, 3f/16f)
                add(0, 1, 5f/16f); add(1, 1, 1f/16f)
            }
        }
        return black
    }

    private fun floydSteinbergDither(bmp: Bitmap): BooleanArray = floydSteinbergDitherToBooleans(bmp)

    private fun rotateCw90(black: BooleanArray): BooleanArray {
        // Input MUKI_W x MUKI_H → Output MUKI_H x MUKI_W
        val out = BooleanArray(MUKI_W * MUKI_H)
        val outW = MUKI_H; val outH = MUKI_W
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val sx = y; val sy = outW - 1 - x
                out[y * outW + x] = black[sy * MUKI_W + sx]
            }
        }
        return out
    }

    private fun packBits(black: BooleanArray): ByteArray {
        val bytes = ByteArray(ceil(black.size / 8.0).toInt())
        for (i in black.indices) {
            if (black[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        return bytes
    }
}
