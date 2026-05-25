package com.soccerstars.analyzer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.soccerstars.analyzer.databinding.ActivityHsvTunerBinding

class HsvTunerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHsvTunerBinding

    private val ballSliders   = LinkedHashMap<String, SeekBar>()
    private val playerSliders = LinkedHashMap<String, SeekBar>()

    private val ballLabels   = LinkedHashMap<String, TextView>()
    private val playerLabels = LinkedHashMap<String, TextView>()

    private data class HsvParam(val key: String, val label: String, val max: Int)

    private val params = listOf(
        HsvParam("h_lo", "H Low",  180),
        HsvParam("s_lo", "S Low",  255),
        HsvParam("v_lo", "V Low",  255),
        HsvParam("h_hi", "H High", 180),
        HsvParam("s_hi", "S High", 255),
        HsvParam("v_hi", "V High", 255)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHsvTunerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_hsv_tuner)

        val config = HsvPrefs.load(this)
        buildGroup(binding.containerBall,   config.ballRange,   ballSliders,   ballLabels)
        buildGroup(binding.containerPlayer, config.playerRange, playerSliders, playerLabels)

        updateSwatch(binding.swatchBall,   ballSliders)
        updateSwatch(binding.swatchPlayer, playerSliders)

        binding.btnSave.setOnClickListener  { saveAndApply() }
        binding.btnReset.setOnClickListener { resetDefaults() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    private fun buildGroup(
        container:  LinearLayout,
        range:      HsvRange,
        seekBars:   LinkedHashMap<String, SeekBar>,
        valueLabels: LinkedHashMap<String, TextView>
    ) {
        val initMap = mapOf(
            "h_lo" to range.hLow,  "s_lo" to range.sLow,  "v_lo" to range.vLow,
            "h_hi" to range.hHigh, "s_hi" to range.sHigh, "v_hi" to range.vHigh
        )

        for (p in params) {
            val row      = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
            val nameLbl  = TextView(this).apply { text = p.label; textSize = 12f; minWidth = 120 }
            val seekBar  = SeekBar(this).apply {
                max     = p.max
                progress= initMap[p.key] ?: 0
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valLbl   = TextView(this).apply {
                text     = (initMap[p.key] ?: 0).toString()
                textSize = 12f; minWidth = 60
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, f: Boolean) {
                    valLbl.text = v.toString()
                    updateSwatch(
                        if (seekBars === ballSliders) binding.swatchBall else binding.swatchPlayer,
                        seekBars
                    )
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            row.addView(nameLbl); row.addView(seekBar); row.addView(valLbl)
            container.addView(row)
            seekBars[p.key]   = seekBar
            valueLabels[p.key] = valLbl
        }
    }

    private fun updateSwatch(swatch: View, seekBars: LinkedHashMap<String, SeekBar>) {
        val hLo = seekBars["h_lo"]?.progress ?: 0
        val hHi = seekBars["h_hi"]?.progress ?: 180
        val sLo = seekBars["s_lo"]?.progress ?: 0
        val sHi = seekBars["s_hi"]?.progress ?: 255
        val vLo = seekBars["v_lo"]?.progress ?: 0
        val vHi = seekBars["v_hi"]?.progress ?: 255

        val hMid = ((hLo + hHi) / 2f) / 180f
        val sMid = ((sLo + sHi) / 2f) / 255f
        val vMid = ((vLo + vHi) / 2f) / 255f

        val hsv      = floatArrayOf(hMid * 360f, sMid, vMid)
        val argbColor = Color.HSVToColor(hsv)
        swatch.setBackgroundColor(argbColor)
    }

    private fun sliderValues(seekBars: LinkedHashMap<String, SeekBar>): HsvRange {
        return HsvRange(
            seekBars["h_lo"]?.progress ?: 0,
            seekBars["s_lo"]?.progress ?: 0,
            seekBars["v_lo"]?.progress ?: 0,
            seekBars["h_hi"]?.progress ?: 180,
            seekBars["s_hi"]?.progress ?: 255,
            seekBars["v_hi"]?.progress ?: 255
        )
    }

    private fun saveAndApply() {
        val ball   = sliderValues(ballSliders)
        val player = sliderValues(playerSliders)
        HsvPrefs.save(this, ball, player)

        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(OverlayService.BROADCAST_STATUS)
                .putExtra(OverlayService.EXTRA_UPDATE_CONFIG, true)
        )

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resetDefaults() {
        applyRange(ballSliders,   AnalyzerConfig.DEFAULT_BALL)
        applyRange(playerSliders, AnalyzerConfig.DEFAULT_PLAYER)
        updateSwatch(binding.swatchBall,   ballSliders)
        updateSwatch(binding.swatchPlayer, playerSliders)
    }

    private fun applyRange(seekBars: LinkedHashMap<String, SeekBar>, range: HsvRange) {
        seekBars["h_lo"]?.progress = range.hLow
        seekBars["s_lo"]?.progress = range.sLow
        seekBars["v_lo"]?.progress = range.vLow
        seekBars["h_hi"]?.progress = range.hHigh
        seekBars["s_hi"]?.progress = range.sHigh
        seekBars["v_hi"]?.progress = range.vHigh
    }
}
