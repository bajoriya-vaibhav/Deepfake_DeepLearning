package com.deepfake.detection.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioCapturer {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 44100 // 16000 or 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return true
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, // Or VOICE_RECOGNITION / UNPROCESSED
                sampleRate,
                channelConfig,
                audioFormat,
                bufSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioCapturer", "AudioRecord not initialized")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            return true
        } catch (e: Exception) {
            Log.e("AudioCapturer", "Start failed", e)
            return false
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        try {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e("AudioCapturer", "Stop failed", e)
        }
    }

    fun readChunk(): ByteArray? {
        if (!isRecording || audioRecord == null) return null
        val buffer = ByteArray(bufSize)
        val read = audioRecord!!.read(buffer, 0, bufSize)
        return if (read > 0) buffer.copyOf(read) else null
    }
}
