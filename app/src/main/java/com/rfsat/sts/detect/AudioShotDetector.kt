package com.rfsat.sts.detect

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.rfsat.sts.log.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Listens for the report and timestamps it.
 *
 * PURPOSE, WHICH IS NARROWER THAN IT SOUNDS. This does not detect shots. It
 * detects LOUD TRANSIENTS, and hands their times to [LiveHitDetector] so
 * that a visual candidate arriving just after one can be believed on weaker
 * evidence. That is all it is for, and it is worth having because the visual
 * detector's false-positive rate is what limits how sensitive it can be:
 * knowing that something went bang 200 ms ago is exactly the prior that lets
 * it be more sensitive without becoming more wrong.
 *
 * WHY THE THRESHOLD IS ADAPTIVE. A muzzle report is 130 dB outdoors and an
 * air rifle is quieter than a door closing, so no fixed level works across
 * the disciplines this app covers. Instead the detector tracks a slow
 * running estimate of the ambient level and fires on a transient that is a
 * large multiple of it. A slow estimate, deliberately: a fast one would
 * adapt to the report itself and swallow the very event being looked for.
 *
 * AND WHERE IT SHOULD BE TURNED OFF. On an indoor range with several firing
 * points, most reports are not yours. The gate would then admit low-
 * confidence candidates at random times, which is worse than no gate at all.
 * The Session screen exposes it as a checkbox, off by default, and says so.
 */
class AudioShotDetector(
    /** Multiple of the running noise floor that counts as a report. */
    private val triggerRatio: Double = 8.0,
    /** Minimum gap between reported events, ms. Shorter than the fastest
     *  rapid-fire cadence (ISSF Rapid Fire is 4 s for 5 shots, so 800 ms
     *  apart) but long enough to reject the echo of the previous shot. */
    private val refractoryMs: Long = 250L
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    val isRunning: Boolean get() = running.get()

    /**
     * @param onReport called on a background thread with the event time.
     * Requires RECORD_AUDIO; the caller must have obtained it.
     */
    @SuppressLint("MissingPermission")
    fun start(onReport: (Long) -> Unit, onError: (String) -> Unit) {
        if (running.getAndSet(true)) return
        thread = Thread {
            var rec: AudioRecord? = null
            try {
                val rate = 44100
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) { onError("This device reports no usable audio input."); return@Thread }
                // UNPROCESSED rather than MIC: MIC applies automatic gain
                // control and noise suppression, both of which are designed
                // to flatten exactly the transient this detector looks for.
                // Available unconditionally at minSdk 26.
                rec = AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4
                )
                record = rec
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    onError("Could not open the microphone.")
                    return@Thread
                }
                rec.startRecording()

                val buf = ShortArray(1024)
                // Noise floor, initialised high so the first few blocks
                // cannot trigger before the estimate has settled.
                var floor = 2000.0
                var lastEvent = 0L

                while (running.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var peak = 0.0
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = abs(buf[i].toDouble())
                        if (v > peak) peak = v
                        sum += v
                    }
                    val mean = sum / n

                    val now = System.currentTimeMillis()
                    if (peak > floor * triggerRatio && now - lastEvent > refractoryMs) {
                        lastEvent = now
                        onReport(now)
                    }

                    // Slow one-pole update, and only on quiet blocks: letting
                    // the report itself into the average is what would make
                    // the detector deaf to a string of shots.
                    if (peak < floor * triggerRatio) {
                        floor = 0.98 * floor + 0.02 * maxOf(mean, 1.0)
                    }
                }
            } catch (t: Throwable) {
                Logger.e("AudioShotDetector", "Audio capture failed", t)
                onError("Audio capture failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { rec?.stop() }
                runCatching { rec?.release() }
                record = null
                running.set(false)
            }
        }.also { it.isDaemon = true; it.name = "sts-audio"; it.start() }
    }

    fun stop() {
        running.set(false)
        runCatching { record?.stop() }
        thread = null
    }
}
