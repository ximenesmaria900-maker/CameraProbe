package com.cameraprobe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.SensorManager
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import android.os.Build
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cameraprobe.camera.Camera2Session
import com.cameraprobe.camera.CameraDeviceInfo
import com.cameraprobe.gige.GvcpServer
import com.cameraprobe.gige.NetworkInfo
import com.cameraprobe.gige.NetworkUtils
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val cameraSession by lazy { Camera2Session(this) }
    private val gvcpServer by lazy { GvcpServer(this) }

    private var displayRotationState = mutableStateOf(Surface.ROTATION_0)

    private fun updateDisplayRotation() {
        displayRotationState.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            recreate()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateDisplayRotation()
        hideSystemUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateDisplayRotation()

        // Экран всегда включён + скрыть системные панели (Immersive Fullscreen)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // Глобальный перехват сбоев
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("CameraProbe_CRASH", "UNCAUGHT in $thread", throwable)
            try {
                val log = File(getExternalFilesDir(null), "crash_log.txt")
                log.writeText("CRASH: $throwable\n${throwable.stackTraceToString()}")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Захват системных блокировок Wi-Fi и процессора
        NetworkUtils.acquireWifiLock(this)
        NetworkUtils.acquireWakeLock(this)
        NetworkUtils.acquireMulticastLock(this)

        // Связываем события стриминга GigE Vision напрямую с Camera2
        gvcpServer.onStreamStart = {
            android.util.Log.i("MainActivity", "🟢 onStreamStart: включение передачи кадров в GVSP")
            cameraSession.setStreamingActive(true) { frameBytes, w, h ->
                gvcpServer.streamer.sendFrame(frameBytes, w, h)
            }
        }
        gvcpServer.onStreamStop = {
            android.util.Log.i("MainActivity", "⚪ onStreamStop: остановка передачи кадров")
            cameraSession.setStreamingActive(false)
        }

        // Автозапуск сервера GVCP для Hikrobot MVS
        try {
            gvcpServer.start()
        } catch (e: Exception) {
            android.util.Log.e("GvcpServer", "Failed to start GVCP server in onCreate", e)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color(0xFF050B0A)) {
                    CameraAppScreen(
                        session = cameraSession,
                        gvcpServer = gvcpServer,
                        displayRotation = displayRotationState.value,
                        onRequestPermissions = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.READ_MEDIA_IMAGES
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateDisplayRotation()
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkUtils.releaseWifiLock()
        NetworkUtils.releaseWakeLock()
        cameraSession.close()
        gvcpServer.stop()
    }
}

enum class ActivePopup {
    EXPOSURE,
    GAIN,
    FOCUS
}

private fun updateTextureTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    rotationDeg: Float
) {
    if (viewWidth <= 0 || viewHeight <= 0) return
    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    val bufferWidth = 1280f
    val bufferHeight = 960f
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

    // sensorOrientation=90 уже компенсируется SurfaceTexture внутри GL.
    // Здесь компенсируем только поворот экрана (дисплея) относительно портрета.
    // Итоговые углы: -90f→270°, 0f→0°, 90f→90°, 180f→180°
    when (rotationDeg) {
        0f -> {
            // Портрет: сенсор 1280x960 с sensorOrientation=90 поворачиваем на 90° по часовой стрелке,
            // буфер становится 960 (ширина) x 1280 (высота) — естественный 3:4 портрет
            val bufferRect = RectF(0f, 0f, bufferHeight, bufferWidth)
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = kotlin.math.max(viewWidth / bufferHeight, viewHeight / bufferWidth)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f, centerX, centerY)
        }
        -90f -> {
            // Телефон на левом боку (стенд, ROTATION_270) — компенсация +270°
            val bufferRect = RectF(0f, 0f, bufferHeight, bufferWidth)
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = kotlin.math.max(viewWidth / bufferHeight, viewHeight / bufferWidth)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(270f, centerX, centerY)
        }
        90f -> {
            // Телефон на правом боку (ROTATION_90) — компенсация +90°
            val bufferRect = RectF(0f, 0f, bufferHeight, bufferWidth)
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = kotlin.math.max(viewWidth / bufferHeight, viewHeight / bufferWidth)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f, centerX, centerY)
        }
        180f -> {
            // Перевёрнутый портрет — компенсация 270°
            val bufferRect = RectF(0f, 0f, bufferHeight, bufferWidth)
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = kotlin.math.max(viewWidth / bufferHeight, viewHeight / bufferWidth)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(270f, centerX, centerY)
        }
        else -> {
            val scale = kotlin.math.max(viewWidth / bufferWidth, viewHeight / bufferHeight)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(rotationDeg, centerX, centerY)
        }
    }
    textureView.setTransform(matrix)
}

@Composable
fun CameraAppScreen(
    session: Camera2Session,
    gvcpServer: GvcpServer,
    displayRotation: Int,
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var previewSurface by remember { mutableStateOf<Surface?>(null) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Синхронизация ориентации строго с дисплеем окна (исключает визуальные артефакты и черные области при повороте)
    val autoAngle = when (displayRotation) {
        Surface.ROTATION_0 -> 0f
        Surface.ROTATION_90 -> -90f
        Surface.ROTATION_180 -> 180f
        Surface.ROTATION_270 -> 90f
        else -> 0f
    }

    // Камеры, масштаб, режим сенсора
    var availableCameras by remember { mutableStateOf<List<CameraDeviceInfo>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf(session.currentCameraId) }
    var selectedFps by remember { mutableStateOf(30) }
    var currentZoom by remember { mutableStateOf(1.0f) }
    var cropMode by remember { mutableStateOf(Camera2Session.SensorCropMode.SENSOR_ROI) }
    var rotationAngle by remember { mutableStateOf(autoAngle) }
    var roiNormX by remember { mutableStateOf(0f) }
    var roiNormY by remember { mutableStateOf(0f) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    LaunchedEffect(autoAngle) {
        if (rotationAngle != autoAngle) {
            rotationAngle = autoAngle
            session.setStreamRotation(autoAngle)
            textureViewRef?.let { tv ->
                if (tv.width > 0 && tv.height > 0) {
                    updateTextureTransform(tv, tv.width, tv.height, autoAngle)
                }
            }
        }
    }

    // Экспозиция, усиление, фокус
    var isAutoExposure by remember { mutableStateOf(session.isAutoExposure) }
    var exposureUs by remember { mutableStateOf(19200L) } // 19.2 ms как на макете
    var isAutoGain by remember { mutableStateOf(session.isAutoGain) }
    var gainIso by remember { mutableStateOf(750) } // ISO 750 как на макете

    val liveExposureUs by session.liveExposureUs.collectAsState()
    val liveIso by session.liveIso.collectAsState()

    var isAutoAF by remember { mutableStateOf(false) }
    var focusDiopters by remember { mutableStateOf(2.0f) } // 2.00 dpt как на макете
    var maxFocusDiopters by remember { mutableStateOf(10.0f) }

    // Сетевой статус MVS
    val isGvcpRunning by gvcpServer.isRunning.collectAsState()
    val isMvsConnected by gvcpServer.isConnected.collectAsState()
    val streamFps by gvcpServer.streamer.fps.collectAsState()
    val totalFrames by gvcpServer.streamer.totalFrames.collectAsState()
    var activeNetInfo by remember { mutableStateOf(NetworkUtils.getActiveNetworkInfo()) }

    // Активное всплывающее меню настройки
    var activePopup by remember { mutableStateOf<ActivePopup?>(null) }

    // Проверка разрешений
    if (!hasCameraPermission) {
        PermissionScreen(onRequest = {
            onRequestPermissions()
            hasCameraPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        })
        return
    }

    LaunchedEffect(Unit) {
        availableCameras = session.getAvailableCameras()
        activeNetInfo = NetworkUtils.getActiveNetworkInfo()
        session.setTargetFps(selectedFps)
    }

    // Синхронизация параметров при командах от Hikrobot MVS
    LaunchedEffect(Unit) {
        gvcpServer.onFocusChangedByMvs = { mvsDiopters, isAuto ->
            if (isAuto) {
                isAutoAF = true
                session.setAutoFocus(true)
            } else if (mvsDiopters != null) {
                isAutoAF = false
                focusDiopters = mvsDiopters
                session.setFocus(mvsDiopters)
            }
        }
        gvcpServer.onExposureChangedByMvs = { mvsExpUs, isAuto ->
            if (isAuto) {
                isAutoExposure = true
                session.setExposure(null)
            } else if (mvsExpUs != null) {
                isAutoExposure = false
                exposureUs = mvsExpUs
                session.setExposure(mvsExpUs)
            }
        }
        gvcpServer.onGainChangedByMvs = { mvsIso, isAuto ->
            if (isAuto) {
                isAutoGain = true
                session.setGain(null)
            } else if (mvsIso != null) {
                isAutoGain = false
                gainIso = mvsIso
                session.setGain(mvsIso)
            }
        }
    }

    // Инициализация сессии камеры
    LaunchedEffect(previewSurface, selectedCameraId) {
        val surface = previewSurface ?: return@LaunchedEffect
        try {
            cameraReady = false
            cameraError = null
            session.open(selectedCameraId)
            session.createSession(surface)
            maxFocusDiopters = session.getMaxFocusDistance().coerceAtLeast(1.0f)
            session.setCropMode(cropMode)
            session.setStreamRotation(rotationAngle)
            session.setRoiOffset(roiNormX, roiNormY)
            session.setZoom(currentZoom)
            session.setFocus(if (isAutoAF) null else focusDiopters)
            session.setExposure(if (isAutoExposure) null else exposureUs)
            session.setGain(if (isAutoGain) null else gainIso)
            cameraReady = true
        } catch (e: Exception) {
            cameraError = e.message ?: "Ошибка камеры"
            android.util.Log.e("CameraProbe", "Camera init failed", e)
        }
    }

    // =========================================================================
    // ГЛАВНЫЙ СЛОЙ ИНТЕРФЕЙСА (Индустриальный стиль Cyber-Instrument / Emerald)
    // =========================================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050B0A))
    ) {
        // --- 1. Фоновая координатная сетка прибора ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridStep = 44.dp.toPx()
            val gridColor = Color(0x0C00F076)
            var x = 0f
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += gridStep
            }
            var y = 0f
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += gridStep
            }
        }

        // --- 2. Центр: Полноразмерное живое превью с рамкой ROI и лазерным сечением ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = if (isLandscape) 0.dp else 44.dp,
                    bottom = if (isLandscape) 0.dp else 184.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            val isPortraitMode = !isLandscape

            BoxWithConstraints(
                modifier = (if (isLandscape) {
                    Modifier
                        .fillMaxHeight(0.96f)
                        .aspectRatio(4f / 3f, matchHeightConstraintsFirst = true)
                } else {
                    Modifier
                        .fillMaxWidth(0.98f)
                        .fillMaxHeight()
                        .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
                })
                    .background(Color(0xFF060D0A))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                roiNormX = 0f
                                roiNormY = 0f
                                session.setRoiOffset(0f, 0f)
                                session.dumpNextMvsFrame = true
                            },
                            onTap = {
                                activePopup = null
                            }
                        )
                    }
                    .pointerInput(cropMode, isPortraitMode) {
                        if (cropMode == Camera2Session.SensorCropMode.SENSOR_ROI) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                activePopup = null
                                val boxWpx = size.width.toFloat()
                                val boxHpx = size.height.toFloat()
                                val refW = if (isPortraitMode) 960f else 1280f
                                val refH = if (isPortraitMode) 1280f else 960f
                                val scale = kotlin.math.max(boxWpx / refW, boxHpx / refH)
                                val maxTravelX = ((refW - 640f) * scale).coerceAtLeast(1f)
                                val maxTravelY = ((refH - 480f) * scale).coerceAtLeast(1f)
                                val deltaNormX = dragAmount.x / (maxTravelX / 2f)
                                val deltaNormY = dragAmount.y / (maxTravelY / 2f)
                                roiNormX = (roiNormX + deltaNormX).coerceIn(-1.0f, 1.0f)
                                roiNormY = (roiNormY + deltaNormY).coerceIn(-1.0f, 1.0f)
                                session.setRoiOffset(roiNormX, roiNormY)
                            }
                        }
                    }
            ) {
                val density = LocalDensity.current
                val boxW = maxWidth
                val boxH = maxHeight
                val boxWpx = with(density) { boxW.toPx() }
                val boxHpx = with(density) { boxH.toPx() }

                val isPortraitMode = !isLandscape
                val refW = if (isPortraitMode) 960f else 1280f
                val refH = if (isPortraitMode) 1280f else 960f

                val scale = kotlin.math.max(boxWpx / refW, boxHpx / refH)
                val roiWpx = 640f * scale
                val roiHpx = 480f * scale
                val maxTravelX = ((refW - 640f) * scale).coerceAtLeast(0f)
                val maxTravelY = ((refH - 480f) * scale).coerceAtLeast(0f)
                val roiLeftPx = (boxWpx / 2f) - (roiWpx / 2f) + (roiNormX * (maxTravelX / 2f))
                val roiTopPx = (boxHpx / 2f) - (roiHpx / 2f) + (roiNormY * (maxTravelY / 2f))

                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            textureViewRef = this
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    st.setDefaultBufferSize(1280, 960)
                                    updateTextureTransform(this@apply, w, h, rotationAngle)
                                    previewSurface = Surface(st)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    updateTextureTransform(this@apply, w, h, rotationAngle)
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    previewSurface = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    update = { view ->
                        textureViewRef = view
                        if (view.width > 0 && view.height > 0) {
                            updateTextureTransform(view, view.width, view.height, rotationAngle)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Оверлей: Красная лазерная линия + Жёлтая рамка кадрирования ROI
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (cropMode == Camera2Session.SensorCropMode.SENSOR_ROI) {
                        val yellowColor = Color(0xFFFCD34D)

                        // Жёлтый прямоугольник
                        drawRoundRect(
                            color = yellowColor,
                            topLeft = Offset(roiLeftPx, roiTopPx),
                            size = Size(roiWpx, roiHpx),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx())
                        )

                        // 4 угловых круговых маркера
                        val handleRadius = 5.dp.toPx()
                        listOf(
                            Offset(roiLeftPx, roiTopPx),
                            Offset(roiLeftPx + roiWpx, roiTopPx),
                            Offset(roiLeftPx, roiTopPx + roiHpx),
                            Offset(roiLeftPx + roiWpx, roiTopPx + roiHpx)
                        ).forEach { pt ->
                            drawCircle(color = Color(0xFF050B0A), radius = handleRadius, center = pt)
                            drawCircle(color = yellowColor, radius = handleRadius, center = pt, style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                }

                // Жёлтый бейдж "ROI 640 × 480 · 1:1" в верхнем левом углу рамки ROI
                if (cropMode == Camera2Session.SensorCropMode.SENSOR_ROI) {
                    val badgeOffsetX = with(density) { roiLeftPx.toDp() } + 4.dp
                    val badgeOffsetY = with(density) { roiTopPx.toDp() } + 4.dp
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFFCD34D),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = badgeOffsetX, y = badgeOffsetY)
                    ) {
                        Text(
                            text = "ROI 640 × 480 · 1:1",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                if (!cameraReady && cameraError == null) {
                    CircularProgressIndicator(
                        color = Color(0xFF00F076),
                        modifier = Modifier.align(Alignment.Center).size(36.dp)
                    )
                }

                if (cameraError != null) {
                    Text(
                        text = "ОШИБКА: $cameraError",
                        color = Color(0xFFFF5252),
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.Center).padding(8.dp)
                    )
                }
            }
        }

        // --- 3. Верхняя строка состояния (Top Bar) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(
                    start = if (isLandscape) 54.dp else 8.dp,
                    end = if (isLandscape) 44.dp else 8.dp,
                    top = 8.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Левая группа: Статус LED, IP:порт, Формат
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 4.dp)
            ) {
                // Светящийся индикатор подключения
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            when {
                                streamFps > 0 || isMvsConnected -> Color(0xFF00F076)
                                isGvcpRunning -> Color(0xFFFFB74D)
                                else -> Color(0xFFFF5252)
                            },
                            shape = CircleShape
                        )
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF091410),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327))
                ) {
                    Text(
                        "${activeNetInfo?.ipString ?: "192.168.1.44"}:3956",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF55E6A5),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = if (isLandscape) 8.dp else 5.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF091410),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327))
                ) {
                    Text(
                        if (isLandscape) "640×480 Mono8" else "640×480",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF7DA596),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = if (isLandscape) 8.dp else 5.dp, vertical = 3.dp)
                    )
                }
            }

            // Правая группа: Режим стенда/авто, Поворот кадра, Стоп, Сброс
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 4.dp)
            ) {
                // Кнопка поворота кадра (согласована с MVS)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF091410),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                    modifier = Modifier.clickable {
                        val nextAngle = when (rotationAngle) {
                            -90f -> 0f
                            0f -> 90f
                            90f -> 180f
                            else -> -90f
                        }
                        rotationAngle = nextAngle
                        session.setStreamRotation(nextAngle)
                        textureViewRef?.let { tv ->
                            if (tv.width > 0 && tv.height > 0) {
                                updateTextureTransform(tv, tv.width, tv.height, nextAngle)
                            }
                        }
                    }
                ) {
                    Text(
                        when (rotationAngle) {
                            -90f -> "⟲ 90° ЛЕВ"
                            0f -> "⟳ 0° ВЕРТ"
                            90f -> "⟳ 90° ПРАВ"
                            180f -> "⟲ 180°"
                            else -> "${rotationAngle.toInt()}°"
                        },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF55E6A5),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = if (isLandscape) 8.dp else 5.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF091410),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                    modifier = Modifier.clickable {
                        if (isGvcpRunning) gvcpServer.stop() else gvcpServer.start()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = if (isLandscape) 8.dp else 5.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            Modifier.size(6.dp).background(
                                if (isGvcpRunning) Color(0xFFFF5252) else Color(0xFF00F076),
                                RoundedCornerShape(1.dp)
                            )
                        )
                        Text(
                            if (isGvcpRunning) "Стоп" else "Старт",
                            fontSize = 10.sp,
                            color = Color(0xFFE1E7E4),
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF091410),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                    modifier = Modifier.clickable {
                        roiNormX = 0f
                        roiNormY = 0f
                        rotationAngle = autoAngle
                        session.setStreamRotation(autoAngle)
                        textureViewRef?.let { tv ->
                            if (tv.width > 0 && tv.height > 0) {
                                updateTextureTransform(tv, tv.width, tv.height, autoAngle)
                            }
                        }
                        session.setRoiOffset(0f, 0f)
                        session.dumpNextMvsFrame = true
                        gvcpServer.resetClientSession("Пользовательский сброс")
                    }
                ) {
                    Text(
                        "Сброс",
                        fontSize = 10.sp,
                        color = Color(0xFFE1E7E4),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = if (isLandscape) 8.dp else 5.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // --- 4. Стек инструментов (КАМЕРА / МАСШТАБ) ---
        val cameraItems = if (availableCameras.isNotEmpty()) availableCameras else listOf(
            CameraDeviceInfo("0", "Основная 0", "0-ОСН", 0, 10f, true, 4000, 3000, 12.0f, 1f, 4f, listOf(1f, 2f, 4f)),
            CameraDeviceInfo("2", "Широкоугольная 2", "2-ШИР", 0, 0f, true, 3264, 2448, 8.0f, 1f, 4f, listOf(1f, 2f, 4f)),
            CameraDeviceInfo("3", "Телефото 3", "3-ТЕЛ", 0, 10f, true, 4000, 3000, 12.0f, 1f, 4f, listOf(1f, 2f, 4f))
        )
        val activeCam = availableCameras.firstOrNull { it.id == selectedCameraId }

        @Composable
        fun CameraCardContent() {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF081410),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                modifier = if (isLandscape) Modifier.width(126.dp) else Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("КАМЕРА", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E8276))
                        Text(
                            activeCam?.shortName ?: "ID $selectedCameraId",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F076)
                        )
                    }
                    Text(
                        "${activeCam?.sensorWidth ?: 1280}×${activeCam?.sensorHeight ?: 960} (${"%.1f".format(activeCam?.megapixels ?: 1.2f)}M)",
                        fontSize = 7.sp,
                        color = Color(0xFF7DA596),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        cameraItems.take(4).forEach { cam ->
                            val isSel = selectedCameraId == cam.id
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSel) Color(0xFF00F076) else Color(0xFF0C1814),
                                border = if (!isSel) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF19382C)) else null,
                                modifier = Modifier.weight(1f).clickable {
                                    if (selectedCameraId != cam.id) {
                                        selectedCameraId = cam.id
                                        scope.launch {
                                            previewSurface?.let { surface ->
                                                session.switchCamera(cam.id, surface)
                                            }
                                        }
                                    }
                                }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 3.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        cam.id,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color.Black else Color(0xFF7DA596),
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        "${"%.0f".format(cam.megapixels)}M",
                                        fontSize = 7.sp,
                                        color = if (isSel) Color(0xFF071B12) else Color(0xFF5E8276),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        @Composable
        fun ZoomCardContent() {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF081410),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                modifier = if (isLandscape) Modifier.width(126.dp) else Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("МАСШТАБ", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5E8276))
                        Text("${currentZoom.toInt()}x", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F076))
                    }
                    Text(
                        "Цифровой кроп видоискателя",
                        fontSize = 7.sp,
                        color = Color(0xFF7DA596),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val steps = activeCam?.zoomSteps ?: listOf(1.0f, 2.0f, 4.0f)
                        steps.forEach { z ->
                            val isSel = kotlin.math.abs(currentZoom - z) < 0.1f
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSel) Color(0xFF00F076) else Color(0xFF0C1814),
                                border = if (!isSel) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF19382C)) else null,
                                modifier = Modifier.weight(1f).clickable {
                                    currentZoom = z
                                    session.setZoom(z)
                                }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 3.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "${z.toInt()}x",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color.Black else Color(0xFF7DA596),
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        "ZOOM",
                                        fontSize = 7.sp,
                                        color = if (isSel) Color(0xFF071B12) else Color(0xFF5E8276),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isLandscape) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 54.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CameraCardContent()
                ZoomCardContent()
            }
        }

        // --- 5. Всплывающая карточка параметров (Floating Settings Pop-up) ---
        val currentPopup = activePopup
        if (currentPopup != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isLandscape) 54.dp else 168.dp)
                    .then(if (isLandscape) Modifier.width(380.dp) else Modifier.fillMaxWidth(0.92f))
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF07130F),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D4133)),
                    shadowElevation = 10.dp
                ) {
                    Column(Modifier.padding(12.dp)) {
                        when (currentPopup) {
                            ActivePopup.EXPOSURE -> {
                                // Заголовок + индикатор значения
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Экспозиция", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE1E7E4))

                                    Row(
                                        modifier = Modifier
                                            .background(Color(0xFF0C1814), RoundedCornerShape(4.dp))
                                            .border(1.dp, Color(0xFF173327), RoundedCornerShape(4.dp))
                                            .padding(1.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = if (isAutoExposure) Color(0xFF00F076) else Color.Transparent,
                                            modifier = Modifier.clickable {
                                                isAutoExposure = true
                                                session.setExposure(null)
                                            }
                                        ) {
                                            Text(
                                                "АВТО",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isAutoExposure) Color.Black else Color(0xFF7DA596),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = if (!isAutoExposure) Color(0xFF00F076) else Color.Transparent,
                                            modifier = Modifier.clickable {
                                                isAutoExposure = false
                                                session.setExposure(exposureUs)
                                            }
                                        ) {
                                            Text(
                                                "РУЧНАЯ",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (!isAutoExposure) Color.Black else Color(0xFF7DA596),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        if (isAutoExposure) "~%.1f ms".format(liveExposureUs / 1000f)
                                        else "%.1f ms".format(exposureUs / 1000f),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00F076)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                // Степпер: [ — ]  Слайдер  [ + ]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF0C1814),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                                        modifier = Modifier.size(32.dp).clickable {
                                            isAutoExposure = false
                                            exposureUs = (exposureUs - 1000L).coerceIn(100L, 33333L)
                                            session.setExposure(exposureUs)
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("—", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F076))
                                        }
                                    }

                                    Slider(
                                        value = (exposureUs / 1000f).coerceIn(0.1f, 33.3f),
                                        onValueChange = { msVal ->
                                            isAutoExposure = false
                                            exposureUs = (msVal * 1000f).toLong().coerceIn(100L, 33333L)
                                            session.setExposure(exposureUs)
                                        },
                                        valueRange = 0.1f..33.3f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF00F076),
                                            activeTrackColor = Color(0xFF00F076),
                                            inactiveTrackColor = Color(0xFF173327)
                                        ),
                                        modifier = Modifier.weight(1f).height(24.dp)
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF0C1814),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                                        modifier = Modifier.size(32.dp).clickable {
                                            isAutoExposure = false
                                            exposureUs = (exposureUs + 1000L).coerceIn(100L, 33333L)
                                            session.setExposure(exposureUs)
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F076))
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                // Быстрые пресеты: [ 1ms ] [ 4 ms ] [ 8 ms ] [ 16 ms ] [ 33 ms ]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf("1ms" to 1000L, "4 ms" to 4000L, "8 ms" to 8000L, "16 ms" to 16000L, "33 ms" to 33333L).forEach { (lbl, usVal) ->
                                        val isSel = !isAutoExposure && kotlin.math.abs(exposureUs - usVal) < 400L
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isSel) Color(0xFF00F076) else Color(0xFF0C1814),
                                            border = if (!isSel) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)) else null,
                                            modifier = Modifier.weight(1f).clickable {
                                                isAutoExposure = false
                                                exposureUs = usVal
                                                session.setExposure(usVal)
                                            }
                                        ) {
                                            Text(
                                                lbl,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSel) Color.Black else Color(0xFF7DA596),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            ActivePopup.GAIN -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Усиление (ISO)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE1E7E4))

                                    Row(
                                        modifier = Modifier
                                            .background(Color(0xFF0C1814), RoundedCornerShape(4.dp))
                                            .border(1.dp, Color(0xFF173327), RoundedCornerShape(4.dp))
                                            .padding(1.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = if (isAutoGain) Color(0xFF00F076) else Color.Transparent,
                                            modifier = Modifier.clickable {
                                                isAutoGain = true
                                                session.setGain(null)
                                            }
                                        ) {
                                            Text("АВТО", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (isAutoGain) Color.Black else Color(0xFF7DA596), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = if (!isAutoGain) Color(0xFF00F076) else Color.Transparent,
                                            modifier = Modifier.clickable {
                                                isAutoGain = false
                                                session.setGain(gainIso)
                                            }
                                        ) {
                                            Text("РУЧНОЙ", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (!isAutoGain) Color.Black else Color(0xFF7DA596), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }

                                    Text(
                                        if (isAutoGain) "ISO ~$liveIso" else "ISO $gainIso",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00F076)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF0C1814),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                                        modifier = Modifier.size(32.dp).clickable {
                                            isAutoGain = false
                                            gainIso = (gainIso - 100).coerceIn(100, 3200)
                                            session.setGain(gainIso)
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("—", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F076))
                                        }
                                    }

                                    Slider(
                                        value = gainIso.toFloat().coerceIn(100f, 3200f),
                                        onValueChange = { newVal ->
                                            isAutoGain = false
                                            gainIso = newVal.toInt()
                                            session.setGain(gainIso)
                                        },
                                        valueRange = 100f..3200f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF00F076),
                                            activeTrackColor = Color(0xFF00F076),
                                            inactiveTrackColor = Color(0xFF173327)
                                        ),
                                        modifier = Modifier.weight(1f).height(24.dp)
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF0C1814),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                                        modifier = Modifier.size(32.dp).clickable {
                                            isAutoGain = false
                                            gainIso = (gainIso + 100).coerceIn(100, 3200)
                                            session.setGain(gainIso)
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F076))
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(100, 200, 400, 750, 1600, 3200).forEach { isoVal ->
                                        val isSel = !isAutoGain && gainIso == isoVal
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isSel) Color(0xFF00F076) else Color(0xFF0C1814),
                                            border = if (!isSel) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)) else null,
                                            modifier = Modifier.weight(1f).clickable {
                                                isAutoGain = false
                                                gainIso = isoVal
                                                session.setGain(isoVal)
                                            }
                                        ) {
                                            Text(
                                                "$isoVal",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSel) Color.Black else Color(0xFF7DA596),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            ActivePopup.FOCUS -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Фокусировка", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE1E7E4))

                                    Row(
                                        modifier = Modifier
                                            .background(Color(0xFF0C1814), RoundedCornerShape(4.dp))
                                            .border(1.dp, Color(0xFF173327), RoundedCornerShape(4.dp))
                                            .padding(1.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = if (isAutoAF) Color(0xFF00F076) else Color.Transparent,
                                            modifier = Modifier.clickable {
                                                isAutoAF = true
                                                session.setAutoFocus(true)
                                            }
                                        ) {
                                            Text("АВТО", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (isAutoAF) Color.Black else Color(0xFF7DA596), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = if (!isAutoAF) Color(0xFF00F076) else Color.Transparent,
                                            modifier = Modifier.clickable {
                                                isAutoAF = false
                                                session.setAutoFocus(false)
                                                session.setFocus(focusDiopters)
                                            }
                                        ) {
                                            Text("РУЧНОЙ", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (!isAutoAF) Color.Black else Color(0xFF7DA596), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }

                                    Text(
                                        if (isAutoAF) "АВТОФОКУС" else "%.2f dpt".format(focusDiopters),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00F076)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF0C1814),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                                        modifier = Modifier.size(32.dp).clickable {
                                            isAutoAF = false
                                            focusDiopters = (focusDiopters - 0.25f).coerceIn(0f, maxFocusDiopters)
                                            session.setAutoFocus(false)
                                            session.setFocus(focusDiopters)
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("—", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F076))
                                        }
                                    }

                                    Slider(
                                        value = focusDiopters,
                                        onValueChange = {
                                            isAutoAF = false
                                            focusDiopters = it
                                            session.setAutoFocus(false)
                                            session.setFocus(it)
                                        },
                                        valueRange = 0.0f..maxFocusDiopters,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF00F076),
                                            activeTrackColor = Color(0xFF00F076),
                                            inactiveTrackColor = Color(0xFF173327)
                                        ),
                                        modifier = Modifier.weight(1f).height(24.dp)
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF0C1814),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                                        modifier = Modifier.size(32.dp).clickable {
                                            isAutoAF = false
                                            focusDiopters = (focusDiopters + 0.25f).coerceIn(0f, maxFocusDiopters)
                                            session.setAutoFocus(false)
                                            session.setFocus(focusDiopters)
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F076))
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf("∞" to 0.0f, "1 м" to 1.0f, "50 см" to 2.0f, "20 см" to 5.0f, "10 см" to 10.0f).forEach { (lbl, dpt) ->
                                        if (dpt <= maxFocusDiopters) {
                                            val isSel = !isAutoAF && kotlin.math.abs(focusDiopters - dpt) < 0.1f
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isSel) Color(0xFF00F076) else Color(0xFF0C1814),
                                                border = if (!isSel) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)) else null,
                                                modifier = Modifier.weight(1f).clickable {
                                                    isAutoAF = false
                                                    focusDiopters = dpt
                                                    session.setAutoFocus(false)
                                                    session.setFocus(dpt)
                                                }
                                            ) {
                                                Text(
                                                    lbl,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSel) Color.Black else Color(0xFF7DA596),
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.padding(vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 6. Нижняя панель управления (Bottom Control Dock) ---
        @Composable
        fun RoiFovToggle(modifier: Modifier = Modifier) {
            val isRoi = cropMode == Camera2Session.SensorCropMode.SENSOR_ROI
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF081410),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                modifier = modifier.height(34.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(2.dp)
                        .then(if (isLandscape) Modifier else Modifier.fillMaxWidth()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isRoi) Color(0xFF00F076) else Color.Transparent,
                        modifier = (if (isLandscape) Modifier else Modifier.weight(1f))
                            .fillMaxHeight()
                            .clickable {
                                cropMode = Camera2Session.SensorCropMode.SENSOR_ROI
                                session.setCropMode(cropMode)
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text(
                                "1:1 ROI",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRoi) Color.Black else Color(0xFF7DA596),
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (!isRoi) Color(0xFF00F076) else Color.Transparent,
                        modifier = (if (isLandscape) Modifier else Modifier.weight(1f))
                            .fillMaxHeight()
                            .clickable {
                                cropMode = Camera2Session.SensorCropMode.FULL_FOV
                                session.setCropMode(cropMode)
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text(
                                "FULL FOV",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isRoi) Color.Black else Color(0xFF7DA596),
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        @Composable
        fun CenterButton(modifier: Modifier = Modifier) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF081410),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCD34D)),
                modifier = modifier
                    .height(34.dp)
                    .clickable {
                        roiNormX = 0f
                        roiNormY = 0f
                        session.setRoiOffset(0f, 0f)
                    }
            ) {
                Box(
                    modifier = (if (!isLandscape) Modifier.fillMaxWidth() else Modifier)
                        .padding(horizontal = 8.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "⌖ ЦЕНТР",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFCD34D),
                        maxLines = 1
                    )
                }
            }
        }

        @Composable
        fun FocusButton(modifier: Modifier = Modifier) {
            val isFocusOpen = activePopup == ActivePopup.FOCUS
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF081410),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isFocusOpen) Color(0xFF00F076) else Color(0xFF173327)),
                modifier = modifier
                    .height(34.dp)
                    .clickable {
                        activePopup = if (isFocusOpen) null else ActivePopup.FOCUS
                    }
            ) {
                Box(
                    modifier = (if (!isLandscape) Modifier.fillMaxWidth() else Modifier)
                        .padding(horizontal = 8.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isAutoAF) "AF АВТО" else "%.2f dpt".format(focusDiopters),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isFocusOpen) Color(0xFF00F076) else Color(0xFFE1E7E4),
                        maxLines = 1
                    )
                }
            }
        }

        @Composable
        fun ExpButton(modifier: Modifier = Modifier) {
            val isExpOpen = activePopup == ActivePopup.EXPOSURE
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF081410),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isExpOpen) Color(0xFF00F076) else Color(0xFF173327)),
                modifier = modifier
                    .height(34.dp)
                    .clickable {
                        activePopup = if (isExpOpen) null else ActivePopup.EXPOSURE
                    }
            ) {
                Box(
                    modifier = (if (!isLandscape) Modifier.fillMaxWidth() else Modifier)
                        .padding(horizontal = 8.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isAutoExposure) "EXP АВТО" else "%.1f ms".format(exposureUs / 1000f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00F076),
                        maxLines = 1
                    )
                }
            }
        }

        @Composable
        fun GainButton(modifier: Modifier = Modifier) {
            val isGainOpen = activePopup == ActivePopup.GAIN
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF081410),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isGainOpen) Color(0xFF00F076) else Color(0xFF173327)),
                modifier = modifier
                    .height(34.dp)
                    .clickable {
                        activePopup = if (isGainOpen) null else ActivePopup.GAIN
                    }
            ) {
                Box(
                    modifier = (if (!isLandscape) Modifier.fillMaxWidth() else Modifier)
                        .padding(horizontal = 8.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isAutoGain) "ISO АВТО" else "ISO $gainIso",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isGainOpen) Color(0xFF00F076) else Color(0xFFE1E7E4),
                        maxLines = 1
                    )
                }
            }
        }

        @Composable
        fun FpsButton(modifier: Modifier = Modifier) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF081410),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF173327)),
                modifier = modifier
                    .height(34.dp)
                    .clickable {
                        val nextFps = if (selectedFps == 30) 20 else 30
                        selectedFps = nextFps
                        session.setTargetFps(nextFps)
                    }
            ) {
                Box(
                    modifier = (if (!isLandscape) Modifier.fillMaxWidth() else Modifier)
                        .padding(horizontal = 8.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (streamFps > 0) "$streamFps FPS" else "$selectedFps FPS",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE1E7E4),
                        maxLines = 1
                    )
                }
            }
        }

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .height(38.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                RoiFovToggle()
                if (cropMode == Camera2Session.SensorCropMode.SENSOR_ROI && (roiNormX != 0f || roiNormY != 0f)) {
                    Spacer(Modifier.width(6.dp))
                    CenterButton()
                }
                Spacer(Modifier.width(8.dp))
                FocusButton()
                Spacer(Modifier.width(8.dp))
                ExpButton()
                Spacer(Modifier.width(8.dp))
                GainButton()
                Spacer(Modifier.width(8.dp))
                FpsButton()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Строка 1: Карточки КАМЕРА и МАСШТАБ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.weight(1f)) { CameraCardContent() }
                    Box(Modifier.weight(1f)) { ZoomCardContent() }
                }

                // Строка 2: Переключатель 1:1 ROI / FULL FOV + (если смещён: ЦЕНТР) + FPS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoiFovToggle(Modifier.weight(1.3f))
                    if (cropMode == Camera2Session.SensorCropMode.SENSOR_ROI && (roiNormX != 0f || roiNormY != 0f)) {
                        CenterButton(Modifier.weight(0.9f))
                    }
                    FpsButton(Modifier.weight(1f))
                }

                // Строка 3: Параметры съемки AF АВТО / EXP АВТО / ISO АВТО
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FocusButton(Modifier.weight(1f))
                    ExpButton(Modifier.weight(1f))
                    GainButton(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050B0A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = null,
                tint = Color(0xFF00F076),
                modifier = Modifier.size(64.dp)
            )
            Text(
                "Camera & MVS Probe",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Text("Требуется доступ к камере", color = Color.Gray)
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F076))
            ) {
                Text("Разрешить", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
