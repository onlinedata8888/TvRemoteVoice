package com.tvremote.app.protocol.remote

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Records from the phone's microphone in exactly the shape the Android TV
 * Remote Service expects for a voice session: raw PCM, 16-bit, mono, 8 kHz.
 *
 * 8 kHz is not guaranteed to be a supported capture rate on every phone, so
 * if AudioRecord refuses it we open the universally-supported 16 kHz instead
 * and halve it ourselves. Averaging each pair of samples (rather than simply
 * throwing every second one away) acts as a crude low-pass filter, which
 * keeps the downsampling from aliasing speech into mush.
 */
class VoiceCapture private constructor(
    private val recorder: AudioRecord,
    private val needsDownsample: Boolean,
    private val readBuffer: ByteArray
) {

    /** PCM 16-bit mono 8 kHz bytes, ready to go straight into a voice payload. */
    private val outBuffer = ByteArray(readBuffer.size)

    fun start() {
        recorder.startRecording()
    }

    /**
     * Reads one chunk. Returns the buffer plus the number of valid bytes in
     * it, or null when the recorder has stopped or errored.
     */
    fun read(): Pair<ByteArray, Int>? {
        val read = recorder.read(readBuffer, 0, readBuffer.size)
        if (read <= 0) return null
        if (!needsDownsample) {
            System.arraycopy(readBuffer, 0, outBuffer, 0, read)
            return outBuffer to read
        }
        // Little-endian 16-bit frames: average each adjacent pair -> half the rate.
        var outIndex = 0
        var i = 0
        while (i + 3 < read) {
            val a = ((readBuffer[i + 1].toInt() shl 8) or (readBuffer[i].toInt() and 0xFF)).toShort().toInt()
            val b = ((readBuffer[i + 3].toInt() shl 8) or (readBuffer[i + 2].toInt() and 0xFF)).toShort().toInt()
            val avg = ((a + b) / 2).coerceIn(-32768, 32767)
            outBuffer[outIndex] = (avg and 0xFF).toByte()
            outBuffer[outIndex + 1] = ((avg shr 8) and 0xFF).toByte()
            outIndex += 2
            i += 4
        }
        if (outIndex == 0) return null
        return outBuffer to outIndex
    }

    fun stop() {
        try { recorder.stop() } catch (_: Throwable) {}
        try { recorder.release() } catch (_: Throwable) {}
    }

    companion object {
        private const val TARGET_RATE = 8_000
        private const val FALLBACK_RATE = 16_000

        /** Returns null if the mic can't be opened at all (permission, or in use elsewhere). */
        @SuppressLint("MissingPermission")
        fun open(): VoiceCapture? {
            tryOpen(TARGET_RATE)?.let { return VoiceCapture(it.first, false, ByteArray(it.second)) }
            tryOpen(FALLBACK_RATE)?.let { return VoiceCapture(it.first, true, ByteArray(it.second)) }
            return null
        }

        @SuppressLint("MissingPermission")
        private fun tryOpen(rate: Int): Pair<AudioRecord, Int>? {
            return try {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) return null
                // Read in ~chunks well under the buffer so we never drop audio
                // while a payload is being written to the socket.
                val chunk = (minBuf.coerceAtLeast(2048)).let { if (it % 4 == 0) it else it + (4 - it % 4) }
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    chunk * 4
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    null
                } else {
                    recorder to chunk
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
