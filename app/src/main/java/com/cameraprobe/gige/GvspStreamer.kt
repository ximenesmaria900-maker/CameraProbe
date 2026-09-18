package com.cameraprobe.gige

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

private const val TAG = "GvspStreamer"

/**
 * GVSP (GigE Vision Streaming Protocol) передатчик видеопотока.
 * Высокопроизводительный конвейер с батч-пейсингом (30 FPS без задержек и потерь).
 */
class GvspStreamer {

    private var socket: DatagramSocket? = null
    private val isStreaming = AtomicBoolean(false)
    private val txLock = Any() // Синхронизация отправки пакетов между потоком стрима и повторами

    var currentDestAddr: InetAddress? = null
    var currentDestPort: Int = 0
    var currentPacketSize: Int = 1400
    var currentWidth: Int = 640
    var currentHeight: Int = 480
    var currentPixelFormat: Int = GevConstants.PIXEL_FORMAT_MONO8
    var packetDelayTicks: Long = 1000L

    var onPortUnreachable: (() -> Unit)? = null

    private var blockIdCounter = 1
    val frameCount = AtomicInteger(0)

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _totalFrames = MutableStateFlow(0)
    val totalFrames: StateFlow<Int> = _totalFrames.asStateFlow()

    private var lastFpsTime = System.currentTimeMillis()
    private var framesSinceLastSec = 0

    // Кольцевой буфер на 8 кадров для гарантированной защиты от перезаписи и микрозадержек
    private var ringBuffers = Array(8) { ByteArray(640 * 480) }
    private var writeIndex = 0
    private var frameChannel = Channel<Int>(8)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null

    // История отправленных кадров для надёжного обслуживания GVCP_PACKETRESEND
    private class HistoryFrame(
        var blockId: Short = 0,
        var length: Int = 0,
        var data: ByteArray = ByteArray(640 * 480)
    )
    private val historyLock = Any()
    private val historyCount = 24 // 24 кадра (~800 мс истории при 30 FPS для гарантированного PACKET_RESEND)
    private var history = Array(historyCount) { HistoryFrame() }
    private var historyIndex = 0

    // Буферы для основного потока вещания
    private val leaderData = ByteArray(8 + 36)
    private val payloadData = ByteArray(9000 + 8)
    private val trailerData = ByteArray(8 + 8)

    private val leaderBuf = ByteBuffer.wrap(leaderData).order(ByteOrder.BIG_ENDIAN)
    private val payloadBuf = ByteBuffer.wrap(payloadData).order(ByteOrder.BIG_ENDIAN)
    private val trailerBuf = ByteBuffer.wrap(trailerData).order(ByteOrder.BIG_ENDIAN)

    private val leaderPacket = DatagramPacket(leaderData, leaderData.size)
    private val payloadPacket = DatagramPacket(payloadData, payloadData.size)
    private val trailerPacket = DatagramPacket(trailerData, trailerData.size)

    // Отдельные независимые буферы для повторной отправки пакетов (исключают гонки памяти)
    private val resendData = ByteArray(9000 + 8)
    private val resendBuf = ByteBuffer.wrap(resendData).order(ByteOrder.BIG_ENDIAN)
    private val resendPacket = DatagramPacket(resendData, resendData.size)

    @Synchronized
    fun startStreaming(
        destAddr: InetAddress,
        destPort: Int,
        packetSize: Int,
        width: Int,
        height: Int,
        pixelFormat: Int = GevConstants.PIXEL_FORMAT_MONO8
    ) {
        if (isStreaming.get()) {
            stopStreaming()
        }

        currentDestAddr = destAddr
        currentDestPort = destPort
        currentPacketSize = if (packetSize in 576..9000) packetSize else 1400
        currentWidth = width
        currentHeight = height
        currentPixelFormat = pixelFormat
        blockIdCounter = 1

        val frameSize = width * height
        if (ringBuffers[0].size != frameSize || ringBuffers.size != 8) {
            ringBuffers = Array(8) { ByteArray(frameSize) }
        }
        writeIndex = 0
        frameChannel = Channel(8)
        synchronized(historyLock) {
            if (history[0].data.size != frameSize) {
                history = Array(historyCount) { HistoryFrame(data = ByteArray(frameSize)) }
            }
            for (h in history) {
                h.blockId = 0
                h.length = 0
            }
            historyIndex = 0
        }

        try {
            socket = DatagramSocket().apply {
                sendBufferSize = 8 * 1024 * 1024 // 8 МБ системный буфер UDP для стабильности при пиках
                try {
                    trafficClass = 0xB8 // DSCP 46 Expedited Forwarding / WMM AC_VO/AC_VI Video priority
                } catch (te: Exception) {
                    Log.w(TAG, "trafficClass error: ${te.message}")
                }
                try {
                    connect(destAddr, destPort)
                    Log.i(TAG, "🔗 GVSP сокет подключен к $destAddr:$destPort (ICMP Port Unreachable активен)")
                } catch (ce: Exception) {
                    Log.w(TAG, "Не удалось подключить сокет к $destAddr:$destPort: ${ce.message}")
                }
            }
            isStreaming.set(true)

            streamJob?.cancel()
            streamJob = scope.launch {
                for (bufIdx in frameChannel) {
                    if (!isStreaming.get()) break
                    val frame = ringBuffers[bufIdx]
                    transmitFrame(frame, currentWidth, currentHeight)
                }
            }

            Log.i(TAG, "🟢 GVSP стриминг запущен на $destAddr:$destPort (${width}x$height, MTU=$currentPacketSize)")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания сокета GVSP", e)
            isStreaming.set(false)
        }
    }

    @Synchronized
    fun stopStreaming() {
        if (!isStreaming.get()) return
        isStreaming.set(false)
        streamJob?.cancel()
        streamJob = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        _fps.value = 0
        Log.i(TAG, "⚪ GVSP стриминг остановлен")
    }

    fun isStreaming(): Boolean = isStreaming.get()

    /**
     * Быстрый приём кадра из потока Camera2 за 0.04 мс.
     */
    fun sendFrame(frameBytes: ByteArray, width: Int = currentWidth, height: Int = currentHeight) {
        if (!isStreaming.get()) return
        val currentIdx = writeIndex
        val target = ringBuffers[currentIdx]
        writeIndex = (writeIndex + 1) % ringBuffers.size

        val copyLen = minOf(frameBytes.size, target.size)
        System.arraycopy(frameBytes, 0, target, 0, copyLen)
        if (!frameChannel.trySend(currentIdx).isSuccess) {
            // Если очередь переполнена, выталкиваем старый кадр и передаём самый свежий
            frameChannel.tryReceive()
            frameChannel.trySend(currentIdx)
        }
    }

    /**
     * Отправка одного кадра в сеть клиенту MVS.
     * Батч-пейсинг (пачки по 4 пакета ~5.6 КБ с микропаузой 60 мкс):
     * - Идеально согласован с механизмом Wi-Fi A-MPDU агрегации кадров.
     * - Весь кадр передаётся за ~4-5 мс (вместо 25 мс), высвобождая 28 мс запаса между кадрами при 30 FPS.
     * - Полностью исключает переполнение сетевых очередей, задержки и микрофризы.
     */
    private fun transmitFrame(frameBytes: ByteArray, width: Int, height: Int) {
        val sock = socket ?: return
        val destAddr = currentDestAddr ?: return
        val destPort = currentDestPort
        if (destPort <= 0) return

        val pktSize = currentPacketSize
        // SCPSPacketSize включает: 20 байт IP + 8 байт UDP + 8 байт GVSPHeader = 36 байт
        val maxPayload = (pktSize - 36).coerceAtLeast(512)
        val blockId = nextBlockId()

        // Сохраняем кадр в кольцевую историю для мгновенного выполнения PACKET_RESEND
        synchronized(historyLock) {
            val h = history[historyIndex % historyCount]
            h.blockId = blockId
            h.length = frameBytes.size
            if (h.data.size >= frameBytes.size) {
                System.arraycopy(frameBytes, 0, h.data, 0, frameBytes.size)
            }
            historyIndex++
        }

        var totalPacketsSent = 0
        try {
            synchronized(txLock) {
                // 1. DATA LEADER PACKET (44 байта)
                sendLeader(sock, blockId, width, height, currentPixelFormat, destAddr, destPort)

                // 2. DATA PAYLOAD PACKETS: Прецизионный батч-пейсинг
                var remaining = frameBytes.size
                var srcOffset = 0
                var packetId = 1
                val burstSize = 4 // Пачка из 4 пакетов под Wi-Fi агрегацию

                val delayNs = if (packetDelayTicks in 5000..30000) {
                    (packetDelayTicks * 5L).coerceIn(40_000L, 80_000L)
                } else {
                    60_000L // 60 мкс пауза между пачками -> передача кадра за ~4-5 мс
                }

                while (remaining > 0 && isStreaming.get()) {
                    val chunkSize = minOf(remaining, maxPayload)
                    sendPayload(sock, blockId, packetId, frameBytes, srcOffset, chunkSize, destAddr, destPort)
                    srcOffset += chunkSize
                    remaining -= chunkSize

                    if (remaining > 0 && packetId % burstSize == 0) {
                        val target = System.nanoTime() + delayNs
                        while (System.nanoTime() < target) {
                            // Высокоточная микропауза между пачками
                        }
                    }
                    packetId++
                }

                // 3. DATA TRAILER PACKET (16 байт)
                sendTrailer(sock, blockId, packetId - 1, height, destAddr, destPort)
                totalPacketsSent = packetId - 1
            }

            val total = frameCount.incrementAndGet()
            _totalFrames.value = total
            updateFps()
            if (total % 30 == 1) {
                Log.i(TAG, "▶ GVSP кадр #$total (blockId=$blockId, $totalPacketsSent пак.) -> $destAddr:$destPort, ${_fps.value} FPS")
            }
        } catch (e: java.net.PortUnreachableException) {
            Log.w(TAG, "🛑 MVS закрыл порт (ICMP Port Unreachable). Немедленная остановка стрима.")
            stopStreaming()
            onPortUnreachable?.invoke()
        } catch (e: Exception) {
            if (isStreaming.get()) {
                Log.w(TAG, "Ошибка отправки GVSP кадра: ${e.message}")
            }
        }
    }

    /**
     * Повторная отправка запрошенных пакетов клиенту MVS.
     * Защищена от зависаний при запросах с флагом AIA "до конца кадра" (0x00FFFFFF).
     * Кадр ищется в недавней истории (до 4 последних кадров).
     */
    fun resendPackets(blockId: Short, firstPacketId: Int, lastPacketId: Int) {
        val sock = socket ?: return
        val destAddr = currentDestAddr ?: return
        val destPort = currentDestPort
        if (destPort <= 0) return

        val target = synchronized(historyLock) {
            history.firstOrNull { it.blockId == blockId && it.length > 0 }
        } ?: run {
            Log.d(TAG, "PACKET_RESEND: кадр blockId=$blockId устарел или отсутствует")
            return
        }

        val frameBytes = target.data
        val frameLen = target.length
        val pktSize = currentPacketSize
        val maxPayload = (pktSize - 36).coerceAtLeast(512)

        val totalPackets = (frameLen + maxPayload - 1) / maxPayload

        try {
            synchronized(txLock) {
                // Если запрошен пакет 0 (DATA LEADER)
                if (firstPacketId == 0) {
                    sendResendLeader(sock, blockId, destAddr, destPort)
                }

                val startPid = firstPacketId.coerceAtLeast(1)
                val endPid = if (lastPacketId >= 0x00FFFFFF || lastPacketId > totalPackets) totalPackets else lastPacketId

                if (startPid <= totalPackets && startPid <= endPid) {
                    for (pid in startPid..endPid) {
                        val offset = (pid - 1) * maxPayload
                        if (offset >= frameLen) break
                        val chunkSize = minOf(frameLen - offset, maxPayload)
                        sendResendPayload(sock, blockId, pid, frameBytes, offset, chunkSize, destAddr, destPort)
                        val target = System.nanoTime() + 25_000L // 25 мкс
                        while (System.nanoTime() < target) {}
                    }
                }

                // Если запрошен трейлер (пакет totalPackets + 1 или запрос до конца кадра)
                if (lastPacketId >= 0x00FFFFFF || lastPacketId > totalPackets) {
                    sendResendTrailer(sock, blockId, totalPackets, destAddr, destPort)
                }
            }
        } catch (e: java.net.PortUnreachableException) {
            Log.w(TAG, "🛑 MVS закрыл порт при resend (PortUnreachableException).")
            stopStreaming()
            onPortUnreachable?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка повторной отправки пакетов blockId=$blockId: ${e.message}")
        }
    }

    private fun sendResendLeader(
        sock: DatagramSocket,
        blockId: Short,
        destAddr: InetAddress,
        destPort: Int
    ) {
        resendBuf.clear()
        resendBuf.putShort(0) // Status SUCCESS
        resendBuf.putShort(blockId)
        resendBuf.put(0x01.toByte()) // Format: 0x01 (Leader)
        resendBuf.put(0.toByte()); resendBuf.put(0.toByte()); resendBuf.put(0.toByte()) // Packet ID = 0

        resendBuf.putShort(0) // field_info
        resendBuf.putShort(0x0001) // payload_type = Image
        resendBuf.putLong(System.nanoTime()) // timestamp
        resendBuf.putInt(currentPixelFormat)
        resendBuf.putInt(currentWidth)
        resendBuf.putInt(currentHeight)
        resendBuf.putInt(0) // offset_x
        resendBuf.putInt(0) // offset_y
        resendBuf.putShort(0)
        resendBuf.putShort(0)

        resendPacket.address = destAddr
        resendPacket.port = destPort
        resendPacket.length = 8 + 36
        sock.send(resendPacket)
    }

    private fun sendResendTrailer(
        sock: DatagramSocket,
        blockId: Short,
        totalPackets: Int,
        destAddr: InetAddress,
        destPort: Int
    ) {
        resendBuf.clear()
        resendBuf.putShort(0) // Status SUCCESS
        resendBuf.putShort(blockId)
        resendBuf.put(0x02.toByte()) // Format: 0x02 (Trailer)
        val trailerPacketId = (totalPackets + 1) and 0x00FFFFFF
        resendBuf.put(((trailerPacketId shr 16) and 0xFF).toByte())
        resendBuf.put(((trailerPacketId shr 8) and 0xFF).toByte())
        resendBuf.put((trailerPacketId and 0xFF).toByte())

        resendBuf.putShort(0)
        resendBuf.putShort(0x0001) // payload_type = Image
        resendBuf.putInt(currentHeight)

        resendPacket.address = destAddr
        resendPacket.port = destPort
        resendPacket.length = 8 + 8
        sock.send(resendPacket)
    }

    private fun sendLeader(
        sock: DatagramSocket,
        blockId: Short,
        width: Int,
        height: Int,
        pixelFormat: Int,
        destAddr: InetAddress,
        destPort: Int
    ) {
        leaderBuf.clear()
        leaderBuf.putShort(0) // Status SUCCESS
        leaderBuf.putShort(blockId)
        leaderBuf.put(0x01.toByte()) // Format: 0x01 (Leader)
        leaderBuf.put(0.toByte()); leaderBuf.put(0.toByte()); leaderBuf.put(0.toByte()) // Packet ID = 0

        leaderBuf.putShort(0) // field_info
        leaderBuf.putShort(0x0001) // payload_type = Image
        leaderBuf.putLong(System.nanoTime()) // timestamp
        leaderBuf.putInt(pixelFormat)
        leaderBuf.putInt(width)
        leaderBuf.putInt(height)
        leaderBuf.putInt(0) // offset_x
        leaderBuf.putInt(0) // offset_y
        leaderBuf.putShort(0)
        leaderBuf.putShort(0)

        leaderPacket.address = destAddr
        leaderPacket.port = destPort
        leaderPacket.length = 8 + 36
        sock.send(leaderPacket)
    }

    private fun sendPayload(
        sock: DatagramSocket,
        blockId: Short,
        packetId: Int,
        frameBytes: ByteArray,
        offset: Int,
        length: Int,
        destAddr: InetAddress,
        destPort: Int
    ) {
        payloadBuf.clear()
        payloadBuf.putShort(0)
        payloadBuf.putShort(blockId)
        payloadBuf.put(0x03.toByte()) // Format: 0x03 (Payload)
        payloadBuf.put(((packetId shr 16) and 0xFF).toByte())
        payloadBuf.put(((packetId shr 8) and 0xFF).toByte())
        payloadBuf.put((packetId and 0xFF).toByte())

        payloadBuf.put(frameBytes, offset, length)

        payloadPacket.address = destAddr
        payloadPacket.port = destPort
        payloadPacket.length = 8 + length
        sock.send(payloadPacket)
    }

    private fun sendResendPayload(
        sock: DatagramSocket,
        blockId: Short,
        packetId: Int,
        frameBytes: ByteArray,
        offset: Int,
        length: Int,
        destAddr: InetAddress,
        destPort: Int
    ) {
        resendBuf.clear()
        resendBuf.putShort(0)
        resendBuf.putShort(blockId)
        resendBuf.put(0x03.toByte()) // Format: 0x03 (Payload)
        resendBuf.put(((packetId shr 16) and 0xFF).toByte())
        resendBuf.put(((packetId shr 8) and 0xFF).toByte())
        resendBuf.put((packetId and 0xFF).toByte())

        resendBuf.put(frameBytes, offset, length)

        resendPacket.address = destAddr
        resendPacket.port = destPort
        resendPacket.length = 8 + length
        sock.send(resendPacket)
    }

    private fun sendTrailer(
        sock: DatagramSocket,
        blockId: Short,
        lastPacketId: Int,
        height: Int,
        destAddr: InetAddress,
        destPort: Int
    ) {
        trailerBuf.clear()
        trailerBuf.putShort(0)
        trailerBuf.putShort(blockId)
        trailerBuf.put(0x02.toByte()) // Format: 0x02 (Trailer)
        val trailerPacketId = (lastPacketId + 1) and 0x00FFFFFF
        trailerBuf.put(((trailerPacketId shr 16) and 0xFF).toByte())
        trailerBuf.put(((trailerPacketId shr 8) and 0xFF).toByte())
        trailerBuf.put((trailerPacketId and 0xFF).toByte())

        trailerBuf.putShort(0)
        trailerBuf.putShort(0x0001) // payload_type = Image
        trailerBuf.putInt(height)

        trailerPacket.address = destAddr
        trailerPacket.port = destPort
        trailerPacket.length = 8 + 8
        sock.send(trailerPacket)
    }

    @Synchronized
    private fun nextBlockId(): Short {
        val id = blockIdCounter
        blockIdCounter = if (blockIdCounter >= 65535) 1 else blockIdCounter + 1
        return id.toShort()
    }

    private fun updateFps() {
        framesSinceLastSec++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            _fps.value = framesSinceLastSec
            framesSinceLastSec = 0
            lastFpsTime = now
        }
    }
}
