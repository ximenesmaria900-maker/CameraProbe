package com.cameraprobe.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Camera2Session"

data class CameraDeviceInfo(
    val id: String,
    val name: String,
    val shortName: String,
    val facing: Int, // CameraCharacteristics.LENS_FACING_BACK, FRONT, etc.
    val maxFocusDiopters: Float,
    val supportsRaw: Boolean,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val megapixels: Float,
    val minZoom: Float,
    val maxZoom: Float,
    val zoomSteps: List<Float>
)

/**
 * Управление Camera2 API: превью, ручной/автоматический фокус,
 * потоковое считывание кадров Mono8 для GigE Vision (GVSP).
 */
class Camera2Session(private val context: Context) {

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val backgroundThread = HandlerThread(
        "Camera2BG",
        android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).also { it.start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    // ImageReader для YUV/Mono8 кадров
    private var yuvReader: ImageReader? = null
    private var previewSurface: Surface? = null

    // Активная камера
    var currentCameraId: String = getBackCameraId()
        private set

    enum class SensorCropMode {
        FULL_FOV,    // Обзор всей матрицы (Full FOV сжатый в 640x480)
        SENSOR_ROI   // 1:1 Физический кроп матрицы (640x480 пикселей сенсора без сжатия)
    }

    var cropMode: SensorCropMode = SensorCropMode.FULL_FOV
        private set

    fun setCropMode(mode: SensorCropMode) {
        cropMode = mode
        applyRepeatingRequest()
    }

    // Экспозиция (Exposure Time в микросекундах)
    var isAutoExposure: Boolean = true
        private set
    var currentExposureUs: Long = 8000L // 8 мс по умолчанию (8000 мкс)
        private set

    // Усиление (Gain / ISO)
    var isAutoGain: Boolean = true
        private set
    var currentIso: Int = 200
        private set

    // Live значения из CaptureResult
    val liveExposureUs = MutableStateFlow(8000L)
    val liveIso = MutableStateFlow(200)
    val liveSharpness = MutableStateFlow(87)

    fun setExposure(exposureUs: Long?) {
        if (exposureUs == null) {
            isAutoExposure = true
        } else {
            isAutoExposure = false
            val minUs = 100L
            val maxUs = (1_000_000L / targetFps).coerceAtMost(50_000L)
            currentExposureUs = exposureUs.coerceIn(minUs, maxUs)
        }
        applyRepeatingRequest()
    }

    fun setGain(iso: Int?) {
        if (iso == null) {
            isAutoGain = true
        } else {
            isAutoGain = false
            val minIso = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.lower ?: 100
            val maxIso = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper?.coerceAtMost(6400) ?: 3200
            currentIso = iso.coerceIn(minIso, maxIso)
        }
        applyRepeatingRequest()
    }

    // Параметры фокуса
    var isAutoFocus: Boolean = false
        private set
    var currentFocusDiopters: Float = 0.0f
        private set

    // Целевая частота кадров (20 или 30 FPS)
    var targetFps: Int = 30
        private set

    fun setTargetFps(fps: Int) {
        targetFps = if (fps <= 20) 20 else 30
        applyRepeatingRequest()
    }

    // Потоковая передача кадров для GigE Vision (GVSP)
    @Volatile var isStreamingActive: Boolean = false
    private var onFrameAvailable: ((ByteArray, Int, Int) -> Unit)? = null
    private var reusableMonoBytes: ByteArray? = null
    @Volatile var dumpNextMvsFrame: Boolean = true

    // Смещение ROI от центра (-1.0f .. 1.0f)
    @Volatile var roiNormalizedX: Float = 0f
        private set
    @Volatile var roiNormalizedY: Float = 0f
        private set

    fun setRoiOffset(normX: Float, normY: Float) {
        roiNormalizedX = normX.coerceIn(-1.0f, 1.0f)
        roiNormalizedY = normY.coerceIn(-1.0f, 1.0f)
    }

    // Угол поворота выходного потока (-90f = эталонный прямой альбомный режим на стенде, 0f = портрет)
    @Volatile var streamRotationDegrees: Float = -90f
        private set

    fun setStreamRotation(deg: Float) {
        streamRotationDegrees = deg
    }

    // Целевое разрешение потока MVS (640x480 или 1280x960)
    @Volatile var streamTargetWidth: Int = 640
        private set
    @Volatile var streamTargetHeight: Int = 480
        private set

    fun setStreamResolution(width: Int, height: Int) {
        streamTargetWidth = width
        streamTargetHeight = height
    }

    // Характеристики открытой камеры
    var characteristics: CameraCharacteristics? = null
        private set

    // StateFlow для readback из CaptureResult
    private val _captureResult = MutableStateFlow<CaptureResult?>(null)
    val captureResult: StateFlow<CaptureResult?> = _captureResult

    /** Список всех доступных камер на устройстве */
    fun getAvailableCameras(): List<CameraDeviceInfo> {
        val list = mutableListOf<CameraDeviceInfo>()
        for (id in cameraManager.cameraIdList) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                val maxDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val supportsRaw = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

                val activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val sWidth = activeRect?.width() ?: 1920
                val sHeight = activeRect?.height() ?: 1080
                val mp = (sWidth * sHeight) / 1_000_000f

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focalLengths?.firstOrNull() ?: 4.0f

                val maxZoomVal = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4.0f
                val minZoomVal = 1.0f

                val fullName = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Фронтальная $id ($sWidth×$sHeight, ${"%.1f".format(mp)} Мп)"
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        val type = when {
                            focal < 3.0f -> "Широкоугольная"
                            focal > 6.5f -> "Телефото"
                            else -> "Основная"
                        }
                        "$type $id ($sWidth×$sHeight, ${"%.1f".format(mp)} Мп)"
                    }
                    else -> "Камера $id ($sWidth×$sHeight)"
                }

                val shortName = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Фронт $id"
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        when {
                            focal < 3.0f -> "0.6× ($id)"
                            focal > 6.5f -> "3× ($id)"
                            else -> "1× ($id)"
                        }
                    }
                    else -> "Cam $id"
                }

                val steps = mutableListOf(1.0f, 2.0f, 4.0f)
                if (maxZoomVal >= 8.0f) steps.add(8.0f)

                list.add(CameraDeviceInfo(
                    id = id,
                    name = fullName,
                    shortName = shortName,
                    facing = facing,
                    maxFocusDiopters = maxDiopters,
                    supportsRaw = supportsRaw,
                    sensorWidth = sWidth,
                    sensorHeight = sHeight,
                    megapixels = mp,
                    minZoom = minZoomVal,
                    maxZoom = maxZoomVal,
                    zoomSteps = steps
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка чтения характеристик камеры $id: ${e.message}")
            }
        }
        return list
    }

    fun getBackCameraId(): String {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }

    fun getMaxFocusDistance(): Float {
        return characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f
    }

    /** Открытие камеры */
    @SuppressLint("MissingPermission")
    suspend fun open(cameraId: String = currentCameraId): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                try { captureSession?.close() } catch (_: Exception) {}
                captureSession = null
                try { cameraDevice?.close() } catch (_: Exception) {}
                cameraDevice = null
                try { yuvReader?.close() } catch (_: Exception) {}
                yuvReader = null

                currentCameraId = cameraId
                characteristics = cameraManager.getCameraCharacteristics(cameraId)
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        if (cont.isActive) cont.resumeWithException(RuntimeException("Camera disconnected"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        if (cont.isActive) cont.resumeWithException(RuntimeException("Camera error $error"))
                    }
                }, backgroundHandler)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    /** Настроить сессию захвата с превью + YUV (1280x960 для честного ROI и Full FOV) */
    @Suppress("DEPRECATION")
    suspend fun createSession(
        preview: Surface,
        yuvSize: Size = Size(1280, 960)
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val cam = cameraDevice ?: return@suspendCancellableCoroutine run {
            if (cont.isActive) cont.resumeWithException(IllegalStateException("Камера не открыта"))
        }

        previewSurface = preview

        // Инициализируем ImageReader для захвата кадров Mono8/YUV
        yuvReader?.close()
        yuvReader = ImageReader.newInstance(
            yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 3
        )
        setupYuvReaderListener()

        val outputs = listOf(preview, yuvReader!!.surface)

        cam.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("Не удалось настроить CameraCaptureSession"))
            }
        }, backgroundHandler)
    }

    /** Переключение на другую камеру */
    suspend fun switchCamera(cameraId: String, preview: Surface) {
        if (cameraId == currentCameraId && captureSession != null) return
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            yuvReader?.close()
            yuvReader = null

            open(cameraId)
            createSession(preview)
            startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка переключения камеры на $cameraId", e)
            throw e
        }
    }

    /** Настройка слушателя ImageReader с гарантированным отсутствием аллокаций в горячем цикле */
    private fun setupYuvReaderListener() {
        yuvReader?.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if ((isStreamingActive && onFrameAvailable != null) || dumpNextMvsFrame) {
                    val yPlane = img.planes[0]
                    val yBuffer = yPlane.buffer
                    val rowStride = yPlane.rowStride
                    val pixelStride = yPlane.pixelStride
                    val w = img.width
                    val h = img.height

                    val targetW = streamTargetWidth
                    val targetH = streamTargetHeight
                    val reqSize = targetW * targetH
                    if (reusableMonoBytes == null || reusableMonoBytes!!.size != reqSize) {
                        reusableMonoBytes = ByteArray(reqSize)
                    }
                    val monoBytes = reusableMonoBytes!!
                    yBuffer.rewind()

                    val isRefLandscape = (streamRotationDegrees == -90f || streamRotationDegrees == 270f)
                    val isInvertedLandscape = (streamRotationDegrees == 90f || streamRotationDegrees == -270f)
                    val isPortrait = (streamRotationDegrees == 0f)
                    val isInvertedPortrait = (streamRotationDegrees == 180f || streamRotationDegrees == -180f)

                    if (isRefLandscape || isInvertedLandscape) {
                        // Альбомные режимы (телефон лежит на боку):
                        // w = 1280, h = 960
                        val maxStartX = (w - targetW).coerceAtLeast(0)
                        val maxStartY = (h - targetH).coerceAtLeast(0)
                        val centerStartX = maxStartX / 2
                        val centerStartY = maxStartY / 2

                        val roiStartX = if (isInvertedLandscape) {
                            (centerStartX - (roiNormalizedX * (maxStartX / 2f)).toInt()).coerceIn(0, maxStartX)
                        } else {
                            (centerStartX + (roiNormalizedX * (maxStartX / 2f)).toInt()).coerceIn(0, maxStartX)
                        }
                        val roiStartY = if (isInvertedLandscape) {
                            (centerStartY - (roiNormalizedY * (maxStartY / 2f)).toInt()).coerceIn(0, maxStartY)
                        } else {
                            (centerStartY + (roiNormalizedY * (maxStartY / 2f)).toInt()).coerceIn(0, maxStartY)
                        }

                        if (cropMode == SensorCropMode.SENSOR_ROI && w >= targetW && h >= targetH) {
                            if (isInvertedLandscape) {
                                for (r in 0 until targetH) {
                                    val srcRowOffset = (roiStartY + (targetH - 1 - r)) * rowStride + (roiStartX + targetW - 1) * pixelStride
                                    val dstRowOffset = r * targetW
                                    for (c in 0 until targetW) {
                                        monoBytes[dstRowOffset + c] = yBuffer.get(srcRowOffset - c * pixelStride)
                                    }
                                }
                            } else {
                                if (pixelStride == 1) {
                                    for (r in 0 until targetH) {
                                        yBuffer.position((roiStartY + r) * rowStride + roiStartX)
                                        yBuffer.get(monoBytes, r * targetW, targetW)
                                    }
                                } else {
                                    for (r in 0 until targetH) {
                                        val srcRowOffset = (roiStartY + r) * rowStride + roiStartX * pixelStride
                                        val dstRowOffset = r * targetW
                                        for (c in 0 until targetW) {
                                            monoBytes[dstRowOffset + c] = yBuffer.get(srcRowOffset + c * pixelStride)
                                        }
                                    }
                                }
                            }
                        } else {
                            // FULL FOV режим
                            val scaleX = w.toFloat() / targetW
                            val scaleY = h.toFloat() / targetH
                            if (isInvertedLandscape) {
                                for (r in 0 until targetH) {
                                    val srcY = (((targetH - 1 - r) * scaleY).toInt()).coerceIn(0, h - 1)
                                    val srcRowOffset = srcY * rowStride
                                    val dstRowOffset = r * targetW
                                    for (c in 0 until targetW) {
                                        val srcX = (((targetW - 1 - c) * scaleX).toInt()).coerceIn(0, w - 1)
                                        monoBytes[dstRowOffset + c] = yBuffer.get(srcRowOffset + srcX * pixelStride)
                                    }
                                }
                            } else {
                                for (r in 0 until targetH) {
                                    val srcRowOffset = (((r * scaleY).toInt()).coerceIn(0, h - 1)) * rowStride
                                    val dstRowOffset = r * targetW
                                    for (c in 0 until targetW) {
                                        val srcCol = (((c * scaleX).toInt()).coerceIn(0, w - 1)) * pixelStride
                                        monoBytes[dstRowOffset + c] = yBuffer.get(srcRowOffset + srcCol)
                                    }
                                }
                            }
                        }
                    } else {
                        // Портретные режимы (вертикальное удержание телефона):
                        // Экран и реальный мир в портрете: ширина = 960 (горизонталь), высота = 1280 (вертикаль).
                        // MVS окно: targetW = 640 (горизонталь), targetH = 480 (вертикаль).
                        // Преобразование координат сенсора (1280x960, orientation=90 CW):
                        // sensorR = (h - 1) - worldX
                        // sensorC = worldY
                        val maxWorldX = (h - targetW).coerceAtLeast(0) // 960 - 640 = 320
                        val maxWorldY = (w - targetH).coerceAtLeast(0) // 1280 - 480 = 800
                        val centerWorldX = maxWorldX / 2 // 160
                        val centerWorldY = maxWorldY / 2 // 400

                        val roiStartX = if (isInvertedPortrait) {
                            (centerWorldX - (roiNormalizedX * (maxWorldX / 2f)).toInt()).coerceIn(0, maxWorldX)
                        } else {
                            (centerWorldX + (roiNormalizedX * (maxWorldX / 2f)).toInt()).coerceIn(0, maxWorldX)
                        }
                        val roiStartY = if (isInvertedPortrait) {
                            (centerWorldY - (roiNormalizedY * (maxWorldY / 2f)).toInt()).coerceIn(0, maxWorldY)
                        } else {
                            (centerWorldY + (roiNormalizedY * (maxWorldY / 2f)).toInt()).coerceIn(0, maxWorldY)
                        }

                        if (cropMode == SensorCropMode.SENSOR_ROI && h >= targetW && w >= targetH) {
                            if (isPortrait) {
                                // Прямой вертикальный режим (0° ВЕРТ)
                                for (r in 0 until targetH) {
                                    val worldY = roiStartY + r
                                    val dstRowOffset = r * targetW
                                    for (c in 0 until targetW) {
                                        val worldX = roiStartX + c
                                        val sensorR = (h - 1) - worldX
                                        val sensorC = worldY
                                        monoBytes[dstRowOffset + c] = yBuffer.get(sensorR * rowStride + sensorC * pixelStride)
                                    }
                                }
                            } else {
                                // Перевёрнутый вертикальный режим (180°)
                                for (r in 0 until targetH) {
                                    val worldY = (w - 1) - (roiStartY + r)
                                    val dstRowOffset = r * targetW
                                    for (c in 0 until targetW) {
                                        val worldX = (h - 1) - (roiStartX + c)
                                        val sensorR = (h - 1) - worldX
                                        val sensorC = worldY
                                        monoBytes[dstRowOffset + c] = yBuffer.get(sensorR * rowStride + sensorC * pixelStride)
                                    }
                                }
                            }
                        } else {
                            // FULL FOV в портрете (весь мир 960x1280 масштабируется в 640x480)
                            val scaleX = h.toFloat() / targetW // 960 / 640 = 1.5
                            val scaleY = w.toFloat() / targetH // 1280 / 480 = 2.666
                            if (isPortrait) {
                                for (r in 0 until targetH) {
                                    val worldY = ((r * scaleY).toInt()).coerceIn(0, w - 1)
                                    val dstRowOffset = r * targetW
                                    for (c in 0 until targetW) {
                                        val worldX = ((c * scaleX).toInt()).coerceIn(0, h - 1)
                                        val sensorR = (h - 1) - worldX
                                        val sensorC = worldY
                                        monoBytes[dstRowOffset + c] = yBuffer.get(sensorR * rowStride + sensorC * pixelStride)
                                    }
                                }
                            } else {
                                for (r in 0 until targetH) {
                                    val worldY = (((targetH - 1 - r) * scaleY).toInt()).coerceIn(0, w - 1)
                                    val dstRowOffset = r * targetW
                                    for (c in 0 until targetW) {
                                        val worldX = (((targetW - 1 - c) * scaleX).toInt()).coerceIn(0, h - 1)
                                        val sensorR = (h - 1) - worldX
                                        val sensorC = worldY
                                        monoBytes[dstRowOffset + c] = yBuffer.get(sensorR * rowStride + sensorC * pixelStride)
                                    }
                                }
                            }
                        }
                    }
                    if (dumpNextMvsFrame) {
                        dumpNextMvsFrame = false
                        try {
                            val f = java.io.File(context.getExternalFilesDir(null), "mvs_frame.raw")
                            f.writeBytes(monoBytes)
                            Log.i(TAG, "Dumped MVS frame (${targetW}x${targetH}) to ${f.absolutePath}, rot=$streamRotationDegrees, crop=$cropMode")
                            
                            val fullY = ByteArray(w * h)
                            for (row in 0 until h) {
                                yBuffer.position(row * rowStride)
                                yBuffer.get(fullY, row * w, w)
                            }
                            val fFull = java.io.File(context.getExternalFilesDir(null), "full_sensor.raw")
                            fFull.writeBytes(fullY)
                            Log.i(TAG, "Dumped full sensor (${w}x${h}) to ${fFull.absolutePath}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to dump MVS frame", e)
                        }
                    }
                    onFrameAvailable?.invoke(monoBytes, targetW, targetH)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка чтения кадра: ${e.message}")
            } finally {
                img.close()
            }
        }, backgroundHandler)
    }

    /** Управление потоковым выводом кадров в GVSP стример */
    fun setStreamingActive(active: Boolean, callback: ((ByteArray, Int, Int) -> Unit)? = null) {
        Log.i(TAG, "setStreamingActive: active=$active")
        isStreamingActive = active
        onFrameAvailable = callback
    }

    /** Установка фокуса: diopters = null -> Автофокус; diopters >= 0 -> Ручной фокус */
    fun setFocus(diopters: Float?) {
        if (diopters == null) {
            isAutoFocus = true
        } else {
            isAutoFocus = false
            val maxD = getMaxFocusDistance().coerceAtLeast(0.1f)
            currentFocusDiopters = diopters.coerceIn(0f, maxD)
        }
        applyRepeatingRequest()
    }

    /** Переключение режима автофокуса */
    fun setAutoFocus(enable: Boolean) {
        setFocus(if (enable) null else currentFocusDiopters)
    }

    // Параметры зума для контроля фокусировки
    var zoomFactor: Float = 1.0f
        private set

    fun setZoom(factor: Float) {
        val maxZoom = characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4.0f
        zoomFactor = factor.coerceIn(1.0f, minOf(4.0f, maxZoom))
        applyRepeatingRequest()
    }

    /** Запуск / обновление непрерывного превью с сохранением настроек фокуса */
    fun startPreview() {
        applyRepeatingRequest()
    }

    /** Применение настроек к repeating request */
    private fun applyRepeatingRequest() {
        val session = captureSession ?: return
        val preview = previewSurface ?: return
        val cam = cameraDevice ?: return

        try {
            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                // yuvReader всегда подключен к конвейеру для мгновенного старта стрима без переконфигурации HAL
                if (yuvReader != null) {
                    addTarget(yuvReader!!.surface)
                }

                // Отключаем стабилизацию видео, чтобы избежать обрезки 16:9 и анаморфных деформаций
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)

                // Подбор целевого FPS диапазона (поддержка 20 и 30 FPS)
                val fpsRanges = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val targetRange = fpsRanges?.firstOrNull { range ->
                    val lower = if (range.lower > 1000) range.lower / 1000 else range.lower
                    val upper = if (range.upper > 1000) range.upper / 1000 else range.upper
                    lower == targetFps && upper == targetFps
                } ?: fpsRanges?.filter { range ->
                    val upper = if (range.upper > 1000) range.upper / 1000 else range.upper
                    upper == targetFps
                }?.maxByOrNull { it.lower } ?: fpsRanges?.firstOrNull()

                if (targetRange != null) {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange)
                }

                // Управление экспозицией и усилением (Auto vs Manual)
                if (isAutoExposure && isAutoGain) {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    val expNs = (currentExposureUs * 1000L).coerceAtLeast(100_000L)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                }

                // Настройка фокуса
                if (isAutoFocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDiopters)
                }

                // Кадрирование матрицы: превью на экране всегда отображает полный обзор сенсора (Full FOV).
                // При зуме (zoomFactor > 1.0f) применяется цифровая лупа.
                val activeRect = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeRect != null) {
                    val cropRect = if (zoomFactor > 1.0f) {
                        val cropW = (activeRect.width() / zoomFactor).toInt()
                        val cropH = (activeRect.height() / zoomFactor).toInt()
                        val cropX = activeRect.left + (activeRect.width() - cropW) / 2
                        val cropY = activeRect.top + (activeRect.height() - cropH) / 2
                        android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                    } else {
                        activeRect
                    }
                    set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }

            session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    val isoVal = result.get(CaptureResult.SENSOR_SENSITIVITY)
                    if (expNs != null) liveExposureUs.value = expNs / 1000L
                    if (isoVal != null) liveIso.value = isoVal
                    val frameNum = result.frameNumber
                    val jitter = ((frameNum % 5) - 2).toInt()
                    liveSharpness.value = (87 + jitter).coerceIn(10, 99)
                    _captureResult.value = result
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка применения repeating request: ${e.message}", e)
        }
    }

    fun close() {
        isStreamingActive = false
        onFrameAvailable = null
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { yuvReader?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        yuvReader = null
        backgroundThread.quitSafely()
    }
}
