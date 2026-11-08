package com.wledambilight.tv

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Detects black bars around the picture (letterbox for widescreen movies, pillarbox for 4:3),
 * so LED colors can be taken from the picture itself instead of from the black bars.
 *
 * Bars are only adopted once they stay stable for a moment, which avoids flicker on dark scenes.
 */
class BarDetector {
    var top = 0
        private set
    var bottom = 0
        private set
    var left = 0
        private set
    var right = 0
        private set

    private var cand = IntArray(4)
    private var candCount = 0

    val hasLetterbox: Boolean get() = top > 0 || bottom > 0
    val hasPillarbox: Boolean get() = left > 0 || right > 0

    fun reset() {
        top = 0; bottom = 0; left = 0; right = 0
        cand = IntArray(4)
        candCount = 0
    }

    fun update(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixStride: Int) {
        val m = measure(buf, w, h, rowStride, pixStride) ?: return // mostly black frame: keep last result
        if (near(m, cand)) candCount++ else {
            cand = m
            candCount = 1
        }
        // Bars shrinking (picture grew) is adopted fast; bars appearing needs a steadier signal.
        val shrinking = m[0] <= top && m[1] <= bottom && m[2] <= left && m[3] <= right
        val needed = if (shrinking) 3 else 20
        if (candCount >= needed) {
            top = cand[0]; bottom = cand[1]; left = cand[2]; right = cand[3]
        }
    }

    private fun near(a: IntArray, b: IntArray): Boolean {
        for (i in 0 until 4) if (abs(a[i] - b[i]) > 1) return false
        return true
    }

    /** Returns [top, bottom, left, right] bar sizes in pixels, or null if the frame is mostly black. */
    private fun measure(buf: ByteBuffer, w: Int, h: Int, rs: Int, ps: Int): IntArray? {
        val maxV = (h * 0.4f).toInt()
        val maxH = (w * 0.4f).toInt()
        var t = 0
        while (t < maxV && rowBlack(buf, t, w, rs, ps)) t++
        var b = 0
        while (b < maxV && rowBlack(buf, h - 1 - b, w, rs, ps)) b++
        var l = 0
        while (l < maxH && colBlack(buf, l, h, rs, ps)) l++
        var r = 0
        while (r < maxH && colBlack(buf, w - 1 - r, h, rs, ps)) r++
        if (t >= maxV || b >= maxV || l >= maxH || r >= maxH) return null

        // Real bars appear on both sides. Use the larger side, since subtitles sitting in
        // one bar make that side look shorter.
        val minV = max(2, h / 50)
        val minH = max(2, w / 50)
        val v = if (t >= minV && b >= minV) max(t, b) else 0
        val hz = if (l >= minH && r >= minH) max(l, r) else 0
        return intArrayOf(v, v, hz, hz)
    }

    private fun rowBlack(buf: ByteBuffer, y: Int, w: Int, rs: Int, ps: Int): Boolean {
        val step = max(1, w / 96)
        var n = 0
        var bright = 0
        var x = 0
        val base = y * rs
        while (x < w) {
            if (isBright(buf, base + x * ps)) bright++
            n++
            x += step
        }
        return bright * 50 <= n // at most ~2% non-black samples
    }

    private fun colBlack(buf: ByteBuffer, x: Int, h: Int, rs: Int, ps: Int): Boolean {
        val step = max(1, h / 96)
        var n = 0
        var bright = 0
        var y = 0
        while (y < h) {
            if (isBright(buf, y * rs + x * ps)) bright++
            n++
            y += step
        }
        return bright * 50 <= n
    }

    private fun isBright(buf: ByteBuffer, i: Int): Boolean {
        val r = buf.get(i).toInt() and 0xFF
        val g = buf.get(i + 1).toInt() and 0xFF
        val b = buf.get(i + 2).toInt() and 0xFF
        return max(r, max(g, b)) > BLACK
    }

    private companion object {
        const val BLACK = 28
    }
}
