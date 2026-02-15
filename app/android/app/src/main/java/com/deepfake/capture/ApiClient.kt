package com.deepfake.capture

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST API client that sends captured frames and audio to the backend server.
 * Expects the backend at <serverUrl>/predict to accept multipart POST with:
 *   - "video_frame" (JPEG image)
 *   - "audio_segment" (WAV audio)
 * And return JSON: { "prediction": "Real|Fake", "confidence": 0.95 }
 */
class ApiClient(private val serverUrl: String) {

    companion object {
        private const val TAG = "ApiClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class PredictionResult(
        val prediction: String,  // "Real" or "Fake"
        val confidence: Float
    )

    /**
     * Sends a video frame and audio segment to the backend for analysis.
     * Either parameter can be null if that modality is unavailable.
     * Returns PredictionResult or null on failure.
     */
    suspend fun sendForPrediction(
        videoFrame: ByteArray?,
        audioSegment: ByteArray?
    ): PredictionResult? = withContext(Dispatchers.IO) {
        try {
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)

            if (videoFrame != null) {
                builder.addFormDataPart(
                    "video_frame",
                    "frame.jpg",
                    videoFrame.toRequestBody("image/jpeg".toMediaType())
                )
            }

            if (audioSegment != null) {
                builder.addFormDataPart(
                    "audio_segment",
                    "audio.wav",
                    audioSegment.toRequestBody("audio/wav".toMediaType())
                )
            }

            val url = serverUrl.trimEnd('/') + "/predict"
            val request = Request.Builder()
                .url(url)
                .post(builder.build())
                .build()

            Log.d(TAG, "Sending prediction request to $url")

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "Server error: ${response.code} - $body")
                return@withContext null
            }

            val json = JSONObject(body)
            val result = PredictionResult(
                prediction = json.optString("prediction", "Unknown"),
                confidence = json.optDouble("confidence", 0.0).toFloat()
            )

            Log.i(TAG, "Prediction: ${result.prediction} (${result.confidence})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send prediction request", e)
            null
        }
    }
}
