package com.openeeq

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openeeq.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── Service binding ───────────────────────────────────────────────────────

    private var eqService: EqService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            eqService = (binder as EqService.LocalBinder).service
            bound = true
            syncUiFromEngine()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            eqService = null
        }
    }

    // ── Which tab is active ───────────────────────────────────────────────────

    private enum class Tab { PARAMETRIC, GRAPHIC }
    private var activeTab = Tab.PARAMETRIC

    // Currently selected parametric band (0-based)
    private var selectedParamBand = 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions()
        setupTabs()
        setupParametricControls()
        setupGraphicControls()
        setupToggle()
        setupPreamp()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, EqService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) { unbindService(connection); bound = false }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestPermissions() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    // ── Tab setup ─────────────────────────────────────────────────────────────

    private fun setupTabs() {
        binding.tabParametric.setOnClickListener { switchTab(Tab.PARAMETRIC) }
        binding.tabGraphic.setOnClickListener   { switchTab(Tab.GRAPHIC)    }
        switchTab(Tab.PARAMETRIC)
    }

    private fun switchTab(tab: Tab) {
        activeTab = tab
        binding.panelParametric.visibility = if (tab == Tab.PARAMETRIC) View.VISIBLE else View.GONE
        binding.panelGraphic.visibility    = if (tab == Tab.GRAPHIC)    View.VISIBLE else View.GONE

        val activeColor  = ContextCompat.getColor(this, R.color.tab_active)
        val inactiveColor = ContextCompat.getColor(this, R.color.tab_inactive)
        binding.tabParametric.setBackgroundColor(if (tab == Tab.PARAMETRIC) activeColor else inactiveColor)
        binding.tabGraphic.setBackgroundColor   (if (tab == Tab.GRAPHIC)    activeColor else inactiveColor)
    }

    // ── EQ on/off ─────────────────────────────────────────────────────────────

    private fun setupToggle() {
        binding.btnToggle.setOnCheckedChangeListener { _, isOn ->
            val svc = eqService ?: return@setOnCheckedChangeListener
            if (isOn) {
                if (!svc.engine.isEnabled) try { svc.engine.start() } catch (_: Exception) {
                    binding.btnToggle.isChecked = false
                    Toast.makeText(this, "ไม่สามารถเปิด EQ ได้", Toast.LENGTH_SHORT).show()
                }
            } else {
                svc.engine.stop()
            }
            binding.statusText.text = if (isOn) "EQ เปิดอยู่" else "EQ ปิดอยู่"
        }
    }

    // ── Preamp ────────────────────────────────────────────────────────────────

    private fun setupPreamp() {
        binding.seekPreamp.max = 240   // -12 to +12 in 0.1 dB steps = 240 steps, offset 120
        binding.seekPreamp.progress = 120
        binding.seekPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val db = (p - 120) / 10f
                binding.labelPreamp.text = "PREAMP  %+.1f dB".format(db)
                if (fromUser) eqService?.engine?.setPreamp(db)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Parametric controls ───────────────────────────────────────────────────

    private fun setupParametricControls() {
        // Band selector buttons 1-8
        val bandButtons = listOf(
            binding.btnBand1, binding.btnBand2, binding.btnBand3, binding.btnBand4,
            binding.btnBand5, binding.btnBand6, binding.btnBand7, binding.btnBand8
        )
        bandButtons.forEachIndexed { i, btn ->
            btn.text = "${i + 1}"
            btn.setOnClickListener { selectParamBand(i) }
        }

        // EQ graph callbacks
        binding.eqGraph.onBandChanged = { index, hz, db, q ->
            eqService?.engine?.setParamBand(index, EqEngine.ParamBand(hz, db, q))
            binding.eqGraph.bands = eqService?.engine?.paramBands ?: binding.eqGraph.bands
            if (index == selectedParamBand) updateParamSliders(hz, db, q)
        }

        // Hz / dB / Q sliders
        setupParamSliders()

        // Clear band button
        binding.btnClearBand.setOnClickListener {
            eqService?.engine?.setParamBand(selectedParamBand, null)
            binding.eqGraph.bands = eqService?.engine?.paramBands ?: binding.eqGraph.bands
        }

        // Clear all
        binding.btnClearAll.setOnClickListener {
            eqService?.engine?.clearAllParamBands()
            binding.eqGraph.bands = eqService?.engine?.paramBands ?: binding.eqGraph.bands
        }

        selectParamBand(0)
    }

    private fun selectParamBand(index: Int) {
        selectedParamBand = index
        val band = eqService?.engine?.paramBands?.get(index)
        updateParamSliders(band?.hz ?: 1000f, band?.db ?: 0f, band?.q ?: 1.0f)
    }

    private fun setupParamSliders() {
        // Hz: 20–20000 on log scale, 1000 steps
        binding.seekHz.max = 1000
        binding.seekHz.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val hz = progressToHz(p)
                binding.labelHz.text = "Hz  " + if (hz >= 1000f) "%.1fk".format(hz/1000f) else "%.0f".format(hz)
                if (fromUser) updateCurrentBand(hz = hz)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // dB: -12 to +12, 0.1 steps = 240 total
        binding.seekDb.max = 240
        binding.seekDb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val db = (p - 120) / 10f
                binding.labelDb.text = "dB  %+.1f".format(db)
                if (fromUser) updateCurrentBand(db = db)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Q: 0.3–8.0, 77 steps of 0.1
        binding.seekQ.max = 77
        binding.seekQ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val q = 0.3f + p * 0.1f
                binding.labelQ.text = "Q  %.1f".format(q)
                if (fromUser) updateCurrentBand(q = q)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun updateCurrentBand(
        hz: Float? = null,
        db: Float? = null,
        q: Float? = null
    ) {
        val engine = eqService?.engine ?: return
        val current = engine.paramBands[selectedParamBand]
        val newHz = hz ?: current?.hz ?: 1000f
        val newDb = db ?: current?.db ?: 0f
        val newQ  = q  ?: current?.q  ?: 1.0f
        // Only create band if there's a non-zero db or it already exists
        if (current != null || (newDb != 0f)) {
            engine.setParamBand(selectedParamBand, EqEngine.ParamBand(newHz, newDb, newQ))
        }
        binding.eqGraph.bands = engine.paramBands
    }

    private fun updateParamSliders(hz: Float, db: Float, q: Float) {
        binding.seekHz.progress = hzToProgress(hz)
        binding.seekDb.progress = ((db + 12f) * 10f).roundToInt().coerceIn(0, 240)
        binding.seekQ.progress  = ((q - 0.3f) / 0.1f).roundToInt().coerceIn(0, 77)
        binding.labelHz.text = "Hz  " + if (hz >= 1000f) "%.1fk".format(hz/1000f) else "%.0f".format(hz)
        binding.labelDb.text = "dB  %+.1f".format(db)
        binding.labelQ.text  = "Q  %.1f".format(q)
    }

    // Log-scale Hz <-> seekbar progress helpers
    private fun hzToProgress(hz: Float): Int {
        val log = (Math.log10(hz.toDouble()) - Math.log10(20.0)) /
                  (Math.log10(20000.0) - Math.log10(20.0))
        return (log * 1000).roundToInt().coerceIn(0, 1000)
    }

    private fun progressToHz(p: Int): Float {
        val log = p / 1000.0 * (Math.log10(20000.0) - Math.log10(20.0)) + Math.log10(20.0)
        return Math.pow(10.0, log).toFloat()
    }

    // ── Graphic EQ controls ───────────────────────────────────────────────────

    private val graphicSeekBars: MutableList<SeekBar> = mutableListOf()

    private fun setupGraphicControls() {
        val container = binding.graphicBandContainer
        val freqs = EqEngine.GRAPHIC_FREQS
        for (i in freqs.indices) {
            val hz = freqs[i]
            val label = if (hz >= 1000f) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"

            val sb = SeekBar(this).also {
                it.max = 240
                it.progress = 120
                it.tag = i
                it.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    val db = (p - 120) / 10f
                    if (fromUser) eqService?.engine?.setGraphicGain(i, db)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })

            val tv = TextView(this).also {
                it.text = label
                it.setTextColor(0xFF4A9EBF.toInt())
                it.textSize = 11f
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
            }

            val col = LinearLayout(this).also {
                it.orientation = LinearLayout.VERTICAL
                it.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            col.addView(sb)
            col.addView(tv)
            container.addView(col)
            graphicSeekBars.add(sb)
        }

        binding.btnFlatGraphic.setOnClickListener {
            eqService?.engine?.flattenGraphic()
            graphicSeekBars.forEach { it.progress = 120 }
        }
    }

    // ── Sync UI ───────────────────────────────────────────────────────────────

    private fun syncUiFromEngine() {
        val engine = eqService?.engine ?: return
        binding.btnToggle.isChecked = engine.isEnabled
        binding.statusText.text = if (engine.isEnabled) "EQ เปิดอยู่" else "EQ ปิดอยู่"
        binding.eqGraph.bands = engine.paramBands
        // Sync graphic sliders
        engine.graphicGains.forEachIndexed { i, db ->
            if (i < graphicSeekBars.size) {
                graphicSeekBars[i].progress = ((db + 12f) * 10f).roundToInt().coerceIn(0, 240)
            }
        }
        // Sync preamp
        val preampProgress = ((engine.preampDb + 12f) * 10f).roundToInt().coerceIn(0, 240)
        binding.seekPreamp.progress = preampProgress
    }
}
