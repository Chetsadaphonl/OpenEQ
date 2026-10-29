package com.openeeq

import android.media.audiofx.DynamicsProcessing
import kotlin.math.roundToInt

/**
 * EqEngine wraps Android's DynamicsProcessing API.
 *
 * Architecture:
 *  - session 0  = global / system mix (affects all audio output)
 *  - Pre-EQ     = parametric bands (we use up to 8 parametric points)
 *  - Post-EQ    = graphic 10-band
 *  - MBC / Limiter disabled for core build
 *
 * All band operations are applied to both L and R channels (stereo).
 */
class EqEngine(private val audioSession: Int = 0) {

    companion object {
        const val PARAM_BANDS = 8          // max parametric EQ points
        const val GRAPHIC_BANDS = 10       // fixed 10-band graphic EQ

        // Graphic EQ center frequencies (Hz)
        val GRAPHIC_FREQS = floatArrayOf(
            32f, 64f, 125f, 250f, 500f,
            1000f, 2000f, 4000f, 8000f, 16000f
        )

        const val DB_MIN = -12f
        const val DB_MAX = +12f
        const val PREAMP_MIN = -12f
        const val PREAMP_MAX = +12f
    }

    private var dp: DynamicsProcessing? = null
    var isEnabled: Boolean = false
        private set

    // ── State ────────────────────────────────────────────────────────────────

    /** Parametric bands: each entry is (hz, dB, Q).  null = band unused. */
    val paramBands: Array<ParamBand?> = arrayOfNulls(PARAM_BANDS)

    /** Graphic EQ gains in dB, indexed 0–9 matching GRAPHIC_FREQS. */
    val graphicGains: FloatArray = FloatArray(GRAPHIC_BANDS) { 0f }

    /** Master preamp (dB) applied as DynamicsProcessing input gain. */
    var preampDb: Float = 0f
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (dp != null) return
        try {
            dp = buildDp()
            dp!!.enabled = true
            isEnabled = true
        } catch (e: Exception) {
            dp = null
            isEnabled = false
            throw e
        }
    }

    fun stop() {
        dp?.release()
        dp = null
        isEnabled = false
    }

    fun restart() {
        stop()
        start()
    }

    // ── Parametric EQ ─────────────────────────────────────────────────────────

    data class ParamBand(
        val hz: Float,
        val db: Float,
        val q: Float,
        val type: BandType = BandType.BELL
    )

    enum class BandType {
        BELL, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, NOTCH
    }

    fun setParamBand(index: Int, band: ParamBand?) {
        require(index in 0 until PARAM_BANDS)
        paramBands[index] = band
        applyParamBands()
    }

    fun clearAllParamBands() {
        for (i in paramBands.indices) paramBands[i] = null
        applyParamBands()
    }

    // ── Graphic EQ ───────────────────────────────────────────────────────────

    fun setGraphicGain(bandIndex: Int, db: Float) {
        require(bandIndex in 0 until GRAPHIC_BANDS)
        graphicGains[bandIndex] = db.coerceIn(DB_MIN, DB_MAX)
        applyGraphicBands()
    }

    fun flattenGraphic() {
        for (i in graphicGains.indices) graphicGains[i] = 0f
        applyGraphicBands()
    }

    // ── Preamp ────────────────────────────────────────────────────────────────

    fun setPreamp(db: Float) {
        preampDb = db.coerceIn(PREAMP_MIN, PREAMP_MAX)
        applyPreamp()
    }

    // ── Internal apply ────────────────────────────────────────────────────────

    private fun applyParamBands() {
        val engine = dp ?: return
        val channelCount = engine.channelCount
        repeat(channelCount) { ch ->
            val eq = engine.getPreEqByChannelIndex(ch)
            repeat(PARAM_BANDS) { i ->
                val band = paramBands[i]
                val dpBand = if (band != null) {
                    DynamicsProcessing.EqBand(true, band.hz, band.db)
                } else {
                    DynamicsProcessing.EqBand(false, 1000f, 0f)
                }
                eq.setBand(i, dpBand)
            }
            engine.setPreEqAllChannelsTo(eq)
        }
        // Break after first channel — setPreEqAllChannelsTo mirrors to all
    }

    private fun applyGraphicBands() {
        val engine = dp ?: return
        val eq = engine.getPostEqByChannelIndex(0)
        repeat(GRAPHIC_BANDS) { i ->
            eq.setBand(i, DynamicsProcessing.EqBand(true, GRAPHIC_FREQS[i], graphicGains[i]))
        }
        engine.setPostEqAllChannelsTo(eq)
    }

    private fun applyPreamp() {
        dp?.setInputGainAllChannelsTo(preampDb)
    }

    // ── Build DynamicsProcessing ──────────────────────────────────────────────

    private fun buildDp(): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount= */ 2,
            /* preEqInUse=  */ true,
            /* preEqBandCount= */ PARAM_BANDS,
            /* mbcInUse=    */ false,
            /* mbcBandCount= */ 0,
            /* postEqInUse= */ true,
            /* postEqBandCount= */ GRAPHIC_BANDS,
            /* limiterInUse= */ false
        )
            .setPreferredFrameDuration(10f)
            .build()

        val dp = DynamicsProcessing(0, audioSession, config)

        // Init parametric bands (all disabled / flat)
        val flatPreEq = dp.getPreEqByChannelIndex(0)
        repeat(PARAM_BANDS) { i ->
            flatPreEq.setBand(i, DynamicsProcessing.EqBand(false, 1000f, 0f))
        }
        dp.setPreEqAllChannelsTo(flatPreEq)

        // Init graphic EQ bands at center freqs, 0 dB gain
        val flatPostEq = dp.getPostEqByChannelIndex(0)
        repeat(GRAPHIC_BANDS) { i ->
            flatPostEq.setBand(i, DynamicsProcessing.EqBand(true, GRAPHIC_FREQS[i], 0f))
        }
        dp.setPostEqAllChannelsTo(flatPostEq)

        return dp
    }
}
