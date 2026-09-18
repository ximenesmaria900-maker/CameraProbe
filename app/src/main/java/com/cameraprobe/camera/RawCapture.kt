package com.cameraprobe.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Сохранение RAW (DNG) и YUV/JPEG кадров в файл.
 */
object RawCapture {

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /**
     * Сохранить RAW-кадр как DNG.
     * @param rawImage Image в формате RAW_SENSOR
     * @param session Camera2Session (нужны characteristics и captureResult)
     * @return File сохранённого DNG
     */
    fun saveDng(
        rawImage: Image,
        session: Camera2Session,
        outputDir: File,
    ): File {
        require(rawImage.format == ImageFormat.RAW_SENSOR) {
            "Expected RAW_SENSOR, got ${rawImage.format}"
        }

        val characteristics = session.characteristics
            ?: error("Camera characteristics not available")
        val captureResult = session.captureResult.value
            ?: error("No capture result available — run preview first")

        val timestamp = dateFormat.format(Date())
        val file = File(outputDir, "raw_$timestamp.dng")

        DngCreator(characteristics, captureResult).use { dng ->
            FileOutputStream(file).use { out ->
                dng.writeImage(out, rawImage)
            }
        }
        return file
    }

    /**
     * Сохранить YUV-кадр как JPEG через компрессию.
     * @return File сохранённого JPEG
     */
    fun saveYuvAsJpeg(
        yuvImage: Image,
        outputDir: File,
        quality: Int = 95,
    ): File {
        require(yuvImage.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, got ${yuvImage.format}"
        }

        val timestamp = dateFormat.format(Date())
        val file = File(outputDir, "yuv_$timestamp.jpg")

        // Конвертация YUV_420_888 → NV21 → YuvImage → JPEG
        val yuvBytes = yuvToNv21(yuvImage)
        val yuvJpeg = android.graphics.YuvImage(
            yuvBytes,
            android.graphics.ImageFormat.NV21,
            yuvImage.width,
            yuvImage.height,
            null
        )

        FileOutputStream(file).use { out ->
            yuvJpeg.compressToJpeg(
                android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height),
                quality,
                out
            )
        }
        return file
    }

    /**
     * Конвертация YUV_420_888 в NV21 (для YuvImage).
     */
    fun yuvToNv21(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    /**
     * Извлечь plane Y как ByteArray (для анализа яркости).
     */
    fun extractYPlane(image: Image): ByteArray {
        require(image.format == ImageFormat.YUV_420_888)
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    /**
     * Извлечь RAW16 данные как ShortArray (для анализа без ISP).
     */
    fun extractRaw16(image: Image): ShortArray {
        require(image.format == ImageFormat.RAW_SENSOR)
        val plane = image.planes[0]
        val buffer = plane.buffer.asShortBuffer()
        val shorts = ShortArray(buffer.remaining())
        buffer.get(shorts)
        return shorts
    }
}
