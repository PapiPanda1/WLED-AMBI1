package com.wledambilight.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Captures the screen at low resolution and streams edge colors to WLED. */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.wledambilight.tv.START"
        const val ACTION_STOP = "com.wledambilight.tv.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "ambilight"
        private const val CAPTURE_WIDTH = 384

        @Volatile
        var running = false

        @Volatile
        var status = "Stopped"
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var sender: WledSender? = null

    private var cfg = Settings()
    private var lastReload = 0L
    private var lastSend = 0L
    private var prev: FloatArray? = null
    private var stopped = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
            ACTION_START -> {
                // startForeground must follow startForegroundService promptly.
                startInForeground()
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                if (data == null) {
                    status = "No screen-capture permission"
                    stopSelf()
                } else if (stopped) {
                    try {
                        startCapture(code, data)
                    } catch (e: Exception) {
                        val msg = "Error: ${e.message}"
                        stopCapture()
                        status = msg
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ambilight", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("WLED Ambilight")
            .setContentText("Streaming screen colors to WLED")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCapture(code: Int, data: Intent) {
        stopped = false
        cfg = Settings.load(this)
        lastReload = SystemClock.elapsedRealtime()
        prev = null

        val t = HandlerThread("wled-capture")
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        // Required on Android 14+ before creating a virtual display.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
                stopSelf()
            }
        }, h)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        val w = CAPTURE_WIDTH
        val hgt = max(16, (w * dm.heightPixels.toFloat() / dm.widthPixels.toFloat()).roundToInt())

        val r = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ onFrame(it) }, h)
        display = mp.createVirtualDisplay(
            "wled-ambilight", w, hgt, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, h
        )
        sender = WledSender()
        running = true
        status = "Running"
    }

    private fun onFrame(r: ImageReader) {
        var latest: Image? = null
        try {
            latest = r.acquireLatestImage()
        } catch (e: Exception) {
            // ignore, treated as no frame
        }
        val img = latest ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReload > 1000) {
                cfg = Settings.load(this)
                lastReload = now
            }
            val c = cfg
            if (now - lastSend < 1000L / max(1, c.fps)) return
            lastSend = now
            if (c.total == 0) {
                status = "Set at least one LED count"
                return
            }
            val plane = img.planes[0]
            val sides = sample(plane.buffer, img.width, img.height, plane.rowStride, plane.pixelStride, c)
            val colors = Layout.assemble(sides, c)
            process(colors, c)
            sender?.send(c.ip, Layout.toBytes(prev ?: colors))
            if (status != "Running") status = "Running"
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        } finally {
            img.close()
        }
    }

    /** Average the edge pixels into one color per LED, listed in clockwise order per side. */
    private fun sample(
        buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixStride: Int, c: Settings
    ): Map<Char, FloatArray> {
        val out = HashMap<Char, FloatArray>()
        val dx = max(1, (w * c.depth).toInt())
        val dy = max(1, (h * c.depth).toInt())
        for (side in charArrayOf('T', 'R', 'B', 'L')) {
            val n = c.count(side)
            if (n == 0) {
                out[side] = FloatArray(0)
                continue
            }
            val horizontal = side == 'T' || side == 'B'
            val xs = when (side) {
                'L' -> 0 until dx
                'R' -> (w - dx) until w
                else -> 0 until w
            }
            val ys = when (side) {
                'T' -> 0 until dy
                'B' -> (h - dy) until h
                else -> 0 until h
            }
            val sum = IntArray(n * 3)
            val cnt = IntArray(n)
            for (y in ys) {
                for (x in xs) {
                    val seg = if (horizontal) x * n / w else y * n / h
                    val idx = y * rowStride + x * pixStride
                    sum[seg * 3] += buf.get(idx).toInt() and 0xFF
                    sum[seg * 3 + 1] += buf.get(idx + 1).toInt() and 0xFF
                    sum[seg * 3 + 2] += buf.get(idx + 2).toInt() and 0xFF
                    cnt[seg]++
                }
            }
            val res = FloatArray(n * 3)
            val flip = side == 'B' || side == 'L' // clockwise: bottom R->L, left B->T
            for (k in 0 until n) {
                val dst = if (flip) n - 1 - k else k
                val m = cnt[k]
                if (m > 0) {
                    res[dst * 3] = sum[k * 3].toFloat() / m
                    res[dst * 3 + 1] = sum[k * 3 + 1].toFloat() / m
                    res[dst * 3 + 2] = sum[k * 3 + 2].toFloat() / m
                }
            }
            out[side] = res
        }
        return out
    }

    /** Saturation, gamma, cutoff, brightness, then temporal smoothing into [prev]. */
    private fun process(colors: FloatArray, c: Settings) {
        val total = colors.size / 3
        for (i in 0 until total) {
            var r = colors[i * 3]
            var g = colors[i * 3 + 1]
            var b = colors[i * 3 + 2]
            if (c.saturation != 1f) {
                val gray = (r + g + b) / 3f
                r = (gray + (r - gray) * c.saturation).coerceIn(0f, 255f)
                g = (gray + (g - gray) * c.saturation).coerceIn(0f, 255f)
                b = (gray + (b - gray) * c.saturation).coerceIn(0f, 255f)
            }
            if (c.gamma != 1f) {
                r = 255f * (r / 255f).pow(c.gamma)
                g = 255f * (g / 255f).pow(c.gamma)
                b = 255f * (b / 255f).pow(c.gamma)
            }
            if (c.blackCutoff > 0 && max(r, max(g, b)) < c.blackCutoff) {
                r = 0f; g = 0f; b = 0f
            }
            val br = c.brightness.coerceIn(0f, 1f)
            colors[i * 3] = r * br
            colors[i * 3 + 1] = g * br
            colors[i * 3 + 2] = b * br
        }
        val sm = c.smooth.coerceIn(0f, 0.95f)
        val p = prev
        if (p == null || p.size != colors.size) {
            prev = colors.copyOf()
        } else {
            for (i in colors.indices) p[i] = p[i] * sm + colors[i] * (1f - sm)
        }
    }

    private fun stopCapture() {
        if (stopped) return
        stopped = true
        running = false
        status = "Stopped"
        reader?.setOnImageAvailableListener(null, null)
        try { display?.release() } catch (e: Exception) { /* ignore */ }
        display = null
        try { reader?.close() } catch (e: Exception) { /* ignore */ }
        reader = null
        val mp = projection
        projection = null
        try { mp?.stop() } catch (e: Exception) { /* ignore */ }

        // Turn the LEDs off (network calls must not run on the main thread).
        val s = sender
        sender = null
        val ip = cfg.ip
        val blank = ByteArray(cfg.total * 3)
        if (s != null) {
            val job = Runnable {
                try { s.send(ip, blank, 1) } catch (e: Exception) { /* ignore */ }
                s.close()
            }
            val h = handler
            if (h != null) h.post(job) else Thread(job).start()
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
