package com.deepfake.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures internal audio using AudioPlaybackCapture (API 29+).
 * Falls back to microphone recording if playback capture is unavailable.
 * Records 3-second WAV segments.
 */
class AudioCapturer(
    private val mediaProjection: MediaProjection?
) {
    companion object {
        private const val TAG = "AudioCapturer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SEGMENT_DURATION_MS = 3000L
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var isUsingPlaybackCapture = false

    @Volatile
    private var latestSegment: ByteArray? = null

    fun start(scope: CoroutineScope) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2) // At least 1 second buffer

        audioRecord = try {
            createPlaybackCaptureRecord(bufferSize)
        } catch (e: Exception) {
            Log.w(TAG, "AudioPlaybackCapture unavailable, falling back to microphone", e)
            createMicrophoneRecord(bufferSize)
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        audioRecord!!.startRecording()
        Log.i(TAG, "Audio capture started (playbackCapture=$isUsingPlaybackCapture)")

        captureJob = scope.launch(Dispatchers.IO) {
            val samplesPerSegment = (SAMPLE_RATE * SEGMENT_DURATION_MS / 1000).toInt()
            val segmentBuffer = ShortArray(samplesPerSegment)

            while (isActive) {
                var totalRead = 0
                while (totalRead < samplesPerSegment && isActive) {
                    val read = audioRecord!!.read(
                        segmentBuffer, totalRead, samplesPerSegment - totalRead
                    )
                    if (read > 0) {
                        totalRead += read
                    } else {
                        delay(10)
                    }
                }

                if (totalRead > 0) {
                    latestSegment = pcmToWav(segmentBuffer, totalRead)
                    Log.d(TAG, "Audio segment ready: ${latestSegment!!.size} bytes")
                }
            }
        }
    }

    private fun createPlaybackCaptureRecord(bufferSize: Int): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        isUsingPlaybackCapture = true

        return AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    @Suppress("MissingPermission")
    private fun createMicrophoneRecord(bufferSize: Int): AudioRecord {
        isUsingPlaybackCapture = false
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
    }

    /**
     * Returns the most recently recorded audio segment as WAV bytes, or null.
     */
    fun consumeLatestSegment(): ByteArray? {
        val seg = latestSegment
        latestSegment = null
        return seg
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord?.release()
        audioRecord = null
        latestSegment = null
        Log.i(TAG, "Audio capture stopped")
    }

    /**
     * Converts raw PCM samples to a WAV byte array with proper header.
     */
    private fun pcmToWav(samples: ShortArray, count: Int): ByteArray {
        val pcmBytes = count * 2 // 16-bit = 2 bytes per sample
        val totalSize = 44 + pcmBytes // WAV header = 44 bytes

        val output = ByteArrayOutputStream(totalSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt(totalSize - 8)
        header.put("WAVE".toByteArray())

        // fmt subchunk
        header.put("fmt ".toByteArray())
        header.putInt(16) // subchunk size
        header.putShort(1) // PCM format
        header.putShort(1) // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample

        // data subchunk
        header.put("data".toByteArray())
        header.putInt(pcmBytes)

        output.write(header.array())

        // Write PCM data
        val dataBuffer = ByteBuffer.allocate(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            dataBuffer.putShort(samples[i])
        }
        output.write(dataBuffer.array())

        return output.toByteArray()
    }
}
