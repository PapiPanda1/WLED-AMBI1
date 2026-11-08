package com.wledambilight.tv

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Settings screen, built in code so the app has no library dependencies. D-pad friendly. */
class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 1001
        const val NUM = InputType.TYPE_CLASS_NUMBER
        const val SIGNED = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        const val DECIMAL = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    @Volatile
    private var settings = Settings()
    private val edits = HashMap<String, EditText>()
    private lateinit var startSpinner: Spinner
    private lateinit var dirSpinner: Spinner
    private lateinit var aspectSpinner: Spinner
    private lateinit var orderText: TextView
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var testBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    @Volatile
    private var testing = false
    private var testThread: Thread? = null
    private var building = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.load(this)
        buildUi()
        building = false
        onChanged()
        tick()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val s = settings
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(48))
        }
        col.addView(TextView(this).apply {
            text = "WLED Ambilight"
            textSize = 30f
        })

        section(col, "WLED device")
        field(col, "IP address / hostname", "ip", s.ip, InputType.TYPE_CLASS_TEXT)

        section(col, "LEDs on each side (0 = none)")
        field(col, "Top", "top", s.top.toString(), NUM)
        field(col, "Right", "right", s.right.toString(), NUM)
        field(col, "Bottom", "bottom", s.bottom.toString(), NUM)
        field(col, "Left", "left", s.left.toString(), NUM)

        section(col, "Where the strip starts (viewed from the front)")
        startSpinner = spinner(col, "Start corner", Layout.STARTS, s.start)
        dirSpinner = spinner(col, "Direction", Layout.DIRECTIONS, s.direction)
        field(col, "Shift LEDs by", "offset", s.offset.toString(), SIGNED)
        orderText = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF55DD88.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(orderText)

        section(col, "Screen content (widescreen / full screen)")
        aspectSpinner = spinner(col, "Picture area", Layout.ASPECTS, s.aspect)
        col.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
            text = "Auto ignores black bars on movies (widescreen) or 4:3 shows so the LEDs " +
                "match the picture. If it guesses wrong, pick Full screen or Widescreen 2.39:1."
        })

        section(col, "Look")
        field(col, "FPS (1-60)", "fps", s.fps.toString(), NUM)
        field(col, "Smoothing (0-0.95)", "smooth", s.smooth.toString(), DECIMAL)
        field(col, "Saturation", "saturation", s.saturation.toString(), DECIMAL)
        field(col, "Brightness (0-1)", "brightness", s.brightness.toString(), DECIMAL)
        field(col, "Gamma", "gamma", s.gamma.toString(), DECIMAL)
        field(col, "Sample depth (0.02-0.5)", "depth", s.depth.toString(), DECIMAL)
        field(col, "Black cutoff (0-255)", "blackCutoff", s.blackCutoff.toString(), NUM)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(24), 0, 0)
        }
        startBtn = Button(this).apply {
            text = "Start"
            setOnClickListener { toggleCapture() }
        }
        testBtn = Button(this).apply {
            text = "Test pattern"
            setOnClickListener { toggleTest() }
        }
        bar.addView(startBtn)
        bar.addView(testBtn)
        col.addView(bar)

        statusText = TextView(this).apply {
            textSize = 18f
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(statusText)
        col.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
            text = "Test pattern: first LED is white; Top = red, Right = green, Bottom = blue, " +
                "Left = yellow. Use it to check the start corner and direction. " +
                "Changes apply live while running. Protected (DRM) video may show black."
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(0xFF7CC4FF.toInt())
            setPadding(0, dp(20), 0, dp(6))
        })
    }

    private fun field(parent: LinearLayout, label: String, key: String, value: String, type: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply { text = label; textSize = 18f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val et = EditText(this).apply {
            setText(value)
            inputType = type
            setSingleLine(true)
            textSize = 18f
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onChanged() }
            })
        }
        row.addView(et, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row)
        edits[key] = et
    }

    private fun spinner(parent: LinearLayout, label: String, items: List<String>, selected: String): Spinner {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply { text = label; textSize = 18f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(items.indexOf(selected).coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onChanged() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        row.addView(sp, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row)
        return sp
    }

    private fun readSettings(): Settings {
        val s = settings
        fun i(k: String, d: Int): Int = edits[k]?.text?.toString()?.trim()?.toIntOrNull() ?: d
        fun f(k: String, d: Float): Float = edits[k]?.text?.toString()?.trim()?.toFloatOrNull() ?: d
        val ip = edits["ip"]?.text?.toString()?.trim().orEmpty()
        return Settings(
            ip = if (ip.isEmpty()) s.ip else ip,
            top = i("top", s.top),
            right = i("right", s.right),
            bottom = i("bottom", s.bottom),
            left = i("left", s.left),
            start = startSpinner.selectedItem as? String ?: s.start,
            direction = dirSpinner.selectedItem as? String ?: s.direction,
            offset = i("offset", s.offset),
            fps = i("fps", s.fps).coerceIn(1, 60),
            smooth = f("smooth", s.smooth).coerceIn(0f, 0.95f),
            saturation = f("saturation", s.saturation).coerceIn(0.5f, 3f),
            brightness = f("brightness", s.brightness).coerceIn(0f, 1f),
            gamma = f("gamma", s.gamma).coerceIn(0.5f, 3f),
            depth = f("depth", s.depth).coerceIn(0.02f, 0.5f),
            blackCutoff = i("blackCutoff", s.blackCutoff).coerceIn(0, 255),
            aspect = aspectSpinner.selectedItem as? String ?: s.aspect
        )
    }

    private fun onChanged() {
        if (building) return
        settings = readSettings()
        settings.save(this)
        orderText.text = "Strip order: " + Layout.describe(settings)
    }

    // --- screen capture ---

    private fun toggleCapture() {
        if (CaptureService.running) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            return
        }
        stopTest()
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            CaptureService.status = "Screen capture isn't available on this device"
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else {
            CaptureService.status = "Screen-capture permission denied"
        }
    }

    // --- test pattern ---

    private fun toggleTest() {
        if (testing) stopTest() else startTest()
    }

    private fun startTest() {
        if (CaptureService.running) return
        testing = true
        testThread = Thread {
            val sender = WledSender()
            try {
                while (testing) {
                    val s = settings
                    if (s.total > 0) sender.send(s.ip, Layout.testFrame(s))
                    Thread.sleep(150)
                }
                val s = settings
                sender.send(s.ip, ByteArray(s.total * 3), 1)
            } catch (e: Exception) {
                testing = false
                CaptureService.status = "Test error: ${e.message}"
            } finally {
                sender.close()
            }
        }.also { it.start() }
    }

    private fun stopTest() {
        testing = false
    }

    private fun tick() {
        val running = CaptureService.running
        startBtn.text = if (running) "Stop" else "Start"
        testBtn.text = if (testing) "Stop test" else "Test pattern"
        statusText.text = "Status: " + if (testing) "Test pattern" else CaptureService.status
        ui.postDelayed({ tick() }, 500)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopTest()
        super.onDestroy()
    }
}
