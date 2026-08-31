package dev.grokdesktop.quest

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 16 kHz mono PCM16, ~100 ms frames, posted to loopback `POST /api/stt/audio`.
 * Does not write clips to disk.
 */
class PttAudioRecord {
    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_MS = 100
        const val FRAME_BYTES = SAMPLE_RATE / (1000 / FRAME_MS) * 2
        const val SILENCE_AVG = 280
        private const val TAG = "GrokPtt"
        private val SESSION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        fun isSafeSessionId(id: String): Boolean {
            if (id.isEmpty() || id.length > 128) return false
            if (id.contains("..") || id.contains("/") || id.contains("\\") || id.contains("\u0000")) {
                return false
            }
            return SESSION_ID.matches(id)
        }
    }

    @Volatile private var running = false
    private val lock = Any()
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    fun isRunning(): Boolean = running

    @SuppressLint("MissingPermission")
    fun start(port: Int, sessionId: String): String? {
        if (port <= 0) return "runtime not ready"
        if (!isSafeSessionId(sessionId)) return "invalid session"
        synchronized(lock) {
            stopLocked()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return "microphone unavailable"
            val bufSize = maxOf(minBuf, FRAME_BYTES * 4)
            val rec = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } catch (t: Exception) {
                Log.w(TAG, "AudioRecord build failed: ${t.javaClass.simpleName}")
                return "could not open microphone"
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return "could not open microphone"
            }
            try {
                rec.startRecording()
            } catch (t: Exception) {
                rec.release()
                Log.w(TAG, "startRecording failed: ${t.javaClass.simpleName}")
                return "could not start microphone"
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                return "could not start microphone"
            }
            record = rec
            running = true
            thread = Thread({ captureLoop(rec, port, sessionId) }, "grok-ptt").also { it.start() }
        }
        return null
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running = false
        val t = thread
        thread = null
        val rec = record
        record = null
        if (t != null && t.isAlive) {
            t.interrupt()
            try {
                t.join(750)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (rec != null) {
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            try {
                rec.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun captureLoop(rec: AudioRecord, port: Int, sessionId: String) {
        val frame = ByteArray(FRAME_BYTES)
        while (running) {
            val n = try {
                rec.read(frame, 0, FRAME_BYTES)
            } catch (_: Exception) {
                break
            }
            if (!running) break
            if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION) {
                break
            }
            if (n <= 0) continue
            if (isSilent(frame, n)) continue
            if (!postFrame(port, sessionId, frame, n)) break
        }
    }

    private fun isSilent(buf: ByteArray, length: Int): Boolean {
        val even = length and 1.inv()
        if (even < 2) return true
        var sum = 0L
        var i = 0
        while (i < even) {
            val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            sum += if (signed < 0) -signed.toLong() else signed.toLong()
            i += 2
        }
        return sum / (even / 2) < SILENCE_AVG
    }

    private fun postFrame(port: Int, sessionId: String, buf: ByteArray, length: Int): Boolean {
        val even = length and 1.inv()
        if (even <= 0) return true
        val b64 = Base64.encodeToString(buf, 0, even, Base64.NO_WRAP)
        val payload = JSONObject().put("sessionId", sessionId).put("pcm", b64).toString()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://127.0.0.1:$port/api/stt/audio").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 1500
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code == 404) {
                running = false
                false
            } else {
                true
            }
        } catch (t: Exception) {
            Log.w(TAG, "stt audio post failed: ${t.javaClass.simpleName}")
            true
        } finally {
            conn?.disconnect()
        }
    }
}
