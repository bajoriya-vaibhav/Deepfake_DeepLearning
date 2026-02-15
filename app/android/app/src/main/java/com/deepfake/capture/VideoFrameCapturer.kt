package com.deepfake.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Captures screen frames from a VirtualDisplay backed by MediaProjection.
 * Samples at ~1 fps, resizes to 224x224, and outputs JPEG byte arrays.
 */
class VideoFrameCapturer(
    private val mediaProjection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensity: Int
) {
    companion object {
        private const val TAG = "VideoFrameCapturer"
        private const val OUTPUT_SIZE = 224
        private const val JPEG_QUALITY = 85
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var latestFrame: ByteArray? = null

    fun start() {
        handlerThread = HandlerThread("FrameCaptureThread").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bitmap = imageToBitmap(image)
                val resized = Bitmap.createScaledBitmap(bitmap, OUTPUT_SIZE, OUTPUT_SIZE, true)
                if (resized != bitmap) bitmap.recycle()

                val outputStream = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
                resized.recycle()

                latestFrame = outputStream.toByteArray()
                Log.d(TAG, "Frame captured: ${latestFrame!!.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing frame", e)
            } finally {
                image?.close()
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "DeepfakeCapture",
            screenWidth, screenHeight, screenDensity,
            0, // flags
            imageReader!!.surface,
            null, null
        )

        Log.i(TAG, "Video frame capture started (${screenWidth}x${screenHeight})")
    }

    /**
     * Returns the most recently captured frame as a JPEG byte array, or null.
     * Consumes the frame (returns null on next call until a new frame is ready).
     */
    fun consumeLatestFrame(): ByteArray? {
        val frame = latestFrame
        latestFrame = null
        return frame
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        latestFrame = null
        Log.i(TAG, "Video frame capture stopped")
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop out padding if any
        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()
            cropped
        } else {
            bitmap
        }
    }
}
