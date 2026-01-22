package com.deepfake.detection.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object BackendClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun sendData(
        url: String, 
        videoFrameConfig: ByteArray?, 
        audioChunk: ByteArray?, 
        callback: (String) -> Unit
    ) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)

        if (videoFrameConfig != null) {
            builder.addFormDataPart(
                "video", "frame.png",
                videoFrameConfig.toRequestBody("image/png".toMediaType())
            )
        }
        
        if (audioChunk != null) {
             builder.addFormDataPart(
                "audio", "audio.wav",
                audioChunk.toRequestBody("audio/wav".toMediaType())
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(builder.build())
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("Error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "Empty"
                    callback(body)
                } else {
                    callback("Server Error: ${response.code}")
                }
            }
        })
    }
}
