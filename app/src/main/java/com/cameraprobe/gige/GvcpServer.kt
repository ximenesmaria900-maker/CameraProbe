package com.cameraprobe.gige

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "GvcpServer"

data class GevLogEntry(
    val timestamp: String,
    val client: String,
    val command: String,
    val details: String,
)

data class CameraIdentity(
    var manufacturer: String = "Hikrobot",
    var modelName: String = "MV-CS004-10GM",
    var serialNumber: String = "DA9999999",
    var deviceVersion: String = "V1.0.0",
    var manufacturerInfo: String = "Hikrobotics",
    var userDefinedName: String = "", // Пустая строка -> MVS показывает модель MV-CS004-10GM
)

/**
 * GVCP (GigE Vision Control Protocol) UDP сервер.
 * Использует единую память регистрационного пространства (128 КБ),
 * что гарантирует полную консистентность при чтении через READREG_CMD и READMEM_CMD.
 */
class GvcpServer(private val context: Context) {

    private var socket: DatagramSocket? = null
    private var serverJob: Job? = null
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var lastHeartbeatTime = 0L
    @Volatile private var connectedClientAddr: InetAddress? = null

    var identity = CameraIdentity()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _logs = MutableStateFlow<List<GevLogEntry>>(emptyList())
    val logs: StateFlow<List<GevLogEntry>> = _logs.asStateFlow()

    private val _discoveryCount = MutableStateFlow(0)
    val discoveryCount: StateFlow<Int> = _discoveryCount.asStateFlow()

    private val _lastClient = MutableStateFlow<String?>(null)
    val lastClient: StateFlow<String?> = _lastClient.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    val streamer = GvspStreamer()
    var onStreamStart: (() -> Unit)? = null
    var onStreamStop: (() -> Unit)? = null
    var onFocusChangedByMvs: ((Float?, Boolean) -> Unit)? = null
    var onExposureChangedByMvs: ((Long?, Boolean) -> Unit)? = null
    var onGainChangedByMvs: ((Int?, Boolean) -> Unit)? = null

    // Единое 128 КБ адресное пространство камеры (Bootstrap + URLs + Controls + XML)
    private val memory = ByteArray(128 * 1024)

    init {
        initMemory()
        streamer.onPortUnreachable = {
            resetClientSession("ICMP Port Unreachable (MVS закрыт)")
        }
    }

    private fun readReg(addr: Int): Int {
        return if (addr in 0..memory.size - 4) {
            ByteBuffer.wrap(memory, addr, 4).order(ByteOrder.BIG_ENDIAN).int
        } else 0
    }

    private fun writeReg(addr: Int, value: Int) {
        if (addr in 0..memory.size - 4) {
            ByteBuffer.wrap(memory, addr, 4).order(ByteOrder.BIG_ENDIAN).putInt(value)
        }
    }

    private fun readMem(addr: Int, count: Int): ByteArray {
        val result = ByteArray(count)
        if (addr in 0 until memory.size) {
            val available = minOf(count, memory.size - addr)
            System.arraycopy(memory, addr, result, 0, available)
        }
        return result
    }

    private fun writeAsciiString(addr: Int, str: String, maxLen: Int) {
        if (addr >= memory.size) return
        val bytes = str.toByteArray(Charsets.US_ASCII)
        val copyLen = minOf(bytes.size, maxLen)
        System.arraycopy(bytes, 0, memory, addr, copyLen)
        // Остаток заполняем 0x00
        if (copyLen < maxLen) {
            Arrays.fill(memory, addr + copyLen, minOf(addr + maxLen, memory.size), 0.toByte())
        }
    }

    private fun initMemory() {
        Arrays.fill(memory, 0.toByte())

        // 0x0000: Spec Version 1.2
        writeReg(GevConstants.REG_VERSION, 0x00010002)

        // 0x0004: Device Mode (Transmitter, Big-Endian)
        writeReg(GevConstants.REG_DEVICE_MODE, 0x80000001.toInt())

        // 0x0010: Supported IP Config (Persistent + DHCP + LLA)
        writeReg(GevConstants.REG_SUPPORTED_IP_CONFIG, 0x80000007.toInt())

        // 0x0014: Current IP Config (DHCP)
        writeReg(GevConstants.REG_CURRENT_IP_CONFIG, 0x80000004.toInt())

        // Сетевые регистры из активного адаптера
        val netInfo = NetworkUtils.getActiveNetworkInfo()
        val macHigh = netInfo?.macHigh ?: 0x0021
        val macLow = netInfo?.macLow ?: 0x44DA3827
        val ipInt: Int = netInfo?.let { ByteBuffer.wrap(it.ipBytes).int } ?: 0xC0A80164.toInt()
        val subnetInt: Int = netInfo?.let { ByteBuffer.wrap(it.subnetMaskBytes).int } ?: -256
        val gatewayInt: Int = netInfo?.let { ByteBuffer.wrap(it.gatewayBytes).int } ?: 0xC0A80101.toInt()

        writeReg(GevConstants.REG_DEVICE_MAC_HIGH, macHigh)
        writeReg(GevConstants.REG_DEVICE_MAC_LOW, macLow)
        writeReg(GevConstants.REG_CURRENT_IP_ADDRESS, ipInt)
        writeReg(GevConstants.REG_CURRENT_SUBNET_MASK, subnetInt)
        writeReg(GevConstants.REG_CURRENT_DEFAULT_GATEWAY, gatewayInt)

        // Строковые регистры Bootstrap
        writeAsciiString(GevConstants.REG_MANUFACTURER_NAME, identity.manufacturer, 32)
        writeAsciiString(GevConstants.REG_MODEL_NAME, identity.modelName, 32)
        writeAsciiString(GevConstants.REG_DEVICE_VERSION, identity.deviceVersion, 32)
        writeAsciiString(GevConstants.REG_MANUFACTURER_INFO, identity.manufacturerInfo, 48)
        writeAsciiString(GevConstants.REG_SERIAL_NUMBER, identity.serialNumber, 16)
        writeAsciiString(GevConstants.REG_USER_DEFINED_NAME, identity.userDefinedName, 16)

        // 0x0200: FIRST_URL (512 байт)
        // Формат AIA: Local:<filename>;<hex_address>;<hex_size>
        val hexAddr = "10000"
        val hexLen = GenicamXml.XML_BYTES.size.toString(16)
        val urlString = "Local:camera.xml;$hexAddr;$hexLen"
        writeAsciiString(GevConstants.REG_FIRST_URL, urlString, 512)

        // Канал управления и каналы стрима
        writeReg(GevConstants.REG_NUMBER_OF_INTERFACES, 1)
        writeReg(GevConstants.REG_NUMBER_OF_MESSAGE_CHANNELS, 1)
        writeReg(GevConstants.REG_NUMBER_OF_STREAM_CHANNELS, 1)
        writeReg(GevConstants.REG_CAPABILITY, 0xF0400007.toInt())
        writeReg(GevConstants.REG_HEARTBEAT_TIMEOUT, 10000) // 10 сек тайм-аут по стандарту GigE Vision
        writeReg(GevConstants.REG_CCP, 0x00000000)

        // Message Channel 0
        writeReg(0x00000B1C, 9002) // MC0 Source Port

        // Stream Channel 0
        writeReg(GevConstants.REG_SC0_PACKET_SIZE, 1400)
        writeReg(GevConstants.REG_SC0_PACKET_DELAY, 10000) // Default 10000 тиков (100 мкс) для 0 потерь по Wi-Fi
        writeReg(GevConstants.REG_SC0_SOURCE_PORT, 9001) // SC0 Source Port
        writeReg(GevConstants.REG_SC0_CONFIGURATION, 0x00000000)

        // Пользовательские регистры камеры (из GenICam XML)
        writeReg(0xA000, 640)  // Width
        writeReg(0xA004, 480)  // Height
        writeReg(0xA008, GevConstants.PIXEL_FORMAT_MONO8) // PixelFormat
        writeReg(0xA00C, 2)    // AcquisitionMode (2 = Continuous)
        writeReg(0xA010, 0)    // AcquisitionStart
        writeReg(0xA014, 0)    // AcquisitionStop
        writeReg(0xA018, 0)    // TriggerMode (0 = Off)
        writeReg(0xA01C, 0)    // DeviceScanType (0 = Areascan)
        writeReg(0xA020, 640 * 480) // PayloadSize
        writeReg(0xA030, 0)    // FocusDistance (0 = Infinity)
        writeReg(0xA034, 0)    // FocusAuto (0 = Off)
        writeReg(0xA040, 1)    // ExposureAuto (1 = Continuous/Auto, 0 = Off)
        writeReg(0xA044, 8000) // ExposureTime in microseconds (8000 us = 8 ms)
        writeReg(0xA048, 1)    // GainAuto (1 = Continuous/Auto, 0 = Off)
        writeReg(0xA04C, 200)  // Gain (ISO 200)

        // Запись GenICam XML по адресу 0x00010000
        val xmlBytes = GenicamXml.XML_BYTES
        System.arraycopy(xmlBytes, 0, memory, GenicamXml.XML_START_ADDRESS, xmlBytes.size)
    }

    fun start(): Boolean {
        if (_isRunning.value) return true
        try {
            NetworkUtils.acquireMulticastLock(context)
            initMemory() // Обновляем память свежими сетевыми параметрами

            val s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                sendBufferSize = 1024 * 1024
                receiveBufferSize = 1024 * 1024
                bind(InetSocketAddress(GevConstants.GEV_PORT))
            }
            socket = s
            _isRunning.value = true
            addLog("LOCAL", "START", "Слушаем UDP порт ${GevConstants.GEV_PORT}")

            serverJob = scope.launch {
                runServerLoop(s)
            }

            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                while (isActive && _isRunning.value) {
                    delay(1000)
                    if (_isConnected.value) {
                        val baseTimeout = (readReg(GevConstants.REG_HEARTBEAT_TIMEOUT)).toLong().coerceIn(3000L, 60000L)
                        val elapsed = System.currentTimeMillis() - lastHeartbeatTime
                        val effectiveTimeout = if (streamer.isStreaming()) maxOf(baseTimeout, 15000L) else baseTimeout
                        if (elapsed > effectiveTimeout) {
                            Log.w(TAG, "Heartbeat watchdog: нет активности $elapsed мс (тайм-аут $effectiveTimeout мс).")
                            resetClientSession("Heartbeat таймаут (${effectiveTimeout}мс)")
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GVCP server", e)
            addLog("LOCAL", "ERROR", "Ошибка запуска: ${e.message}")
            stop()
            return false
        }
    }

    fun resetClientSession(reason: String) {
        writeReg(GevConstants.REG_CCP, 0)
        writeReg(GevConstants.REG_PRIMARY_APP_PORT, 0)
        writeReg(GevConstants.REG_PRIMARY_APP_IP, 0)
        connectedClientAddr = null
        _isConnected.value = false
        streamer.stopStreaming()
        onStreamStop?.invoke()
        addLog("LOCAL", "RESET", "Сброс сессии: $reason")
    }

    fun stop() {
        _isRunning.value = false
        watchdogJob?.cancel()
        watchdogJob = null
        resetClientSession("Остановка сервера")
        serverJob?.cancel()
        serverJob = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        NetworkUtils.releaseMulticastLock()
        addLog("LOCAL", "STOP", "Сервер остановлен")
    }

    private suspend fun runServerLoop(socket: DatagramSocket) = withContext(Dispatchers.IO) {
        val rxBuffer = ByteArray(2048)
        while (isActive && _isRunning.value) {
            try {
                val packet = DatagramPacket(rxBuffer, rxBuffer.size)
                socket.receive(packet)

                if (packet.length >= 8) {
                    handleIncomingPacket(socket, packet)
                }
            } catch (e: Exception) {
                if (_isRunning.value) {
                    Log.w(TAG, "Socket receive error: ${e.message}")
                }
            }
        }
    }

    private fun handleIncomingPacket(socket: DatagramSocket, packet: DatagramPacket) {
        val data = packet.data
        val length = packet.length
        val clientIp = packet.address.hostAddress ?: "unknown"
        val clientPort = packet.port

        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

        val magic = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        val command = buf.short.toInt() and 0xFFFF
        val payloadLen = buf.short.toInt() and 0xFFFF
        val reqId = buf.short

        // Обновляем таймер Heartbeat только от подключенного клиента (не от широковещательных Discovery)
        if (_isConnected.value && packet.address == connectedClientAddr && command != GevConstants.GVCP_DISCOVERY_CMD) {
            lastHeartbeatTime = System.currentTimeMillis()
        }

        when (command) {
            GevConstants.GVCP_DISCOVERY_CMD -> {
                _discoveryCount.value += 1
                _lastClient.value = "$clientIp:$clientPort"
                addLog("$clientIp:$clientPort", "DISCOVERY", "MVS ищет камеры -> DISCOVERY_ACK")
                sendDiscoveryAck(socket, packet.address, clientPort, reqId)
            }
            GevConstants.GVCP_FORCEIP_CMD -> {
                handleForceIp(socket, packet.address, clientPort, reqId, buf, payloadLen)
            }
            GevConstants.GVCP_READREG_CMD -> {
                handleReadReg(socket, packet.address, clientPort, reqId, buf, payloadLen)
            }
            GevConstants.GVCP_WRITEREG_CMD -> {
                handleWriteReg(socket, packet.address, clientPort, reqId, buf, payloadLen)
            }
            GevConstants.GVCP_READMEM_CMD -> {
                handleReadMem(socket, packet.address, clientPort, reqId, buf)
            }
            GevConstants.GVCP_PACKETRESEND_CMD -> {
                handlePacketResend(socket, packet.address, clientPort, reqId, buf, payloadLen)
            }
            else -> {
                addLog("$clientIp:$clientPort", "CMD_0x%04X".format(command), "Команда не поддержана")
                Log.d(TAG, "Unknown GVCP command 0x%04X from %s:%d".format(command, clientIp, clientPort))
            }
        }
    }

    /**
     * Отправляет ответ DISCOVERY_ACK.
     * По стандарту payload = первые 248 байт области bootstrap регистров (0x0000..0x00F7).
     * Динамически синхронизирует IP камеры под подсеть запрашивающего клиента.
     */
    private fun sendDiscoveryAck(
        socket: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int,
        reqId: Short,
    ) {
        // Гарантируем, что сообщаемый IP камеры соответствует подсети клиента (hotspot или роутер)
        val netInfo = NetworkUtils.getNetworkInfoForClient(clientAddr)
        if (netInfo != null) {
            val ipInt = ByteBuffer.wrap(netInfo.ipBytes).int
            val subnetInt = ByteBuffer.wrap(netInfo.subnetMaskBytes).int
            val gatewayInt = ByteBuffer.wrap(netInfo.gatewayBytes).int
            writeReg(GevConstants.REG_DEVICE_MAC_HIGH, netInfo.macHigh)
            writeReg(GevConstants.REG_DEVICE_MAC_LOW, netInfo.macLow)
            writeReg(GevConstants.REG_CURRENT_IP_ADDRESS, ipInt)
            writeReg(GevConstants.REG_CURRENT_SUBNET_MASK, subnetInt)
            writeReg(GevConstants.REG_CURRENT_DEFAULT_GATEWAY, gatewayInt)
        }

        val respBuf = ByteBuffer.allocate(8 + 248).order(ByteOrder.BIG_ENDIAN)
        respBuf.putShort(GevConstants.GEV_STATUS_SUCCESS.toShort())
        respBuf.putShort(GevConstants.GVCP_DISCOVERY_ACK.toShort())
        respBuf.putShort(248.toShort())
        respBuf.putShort(reqId)
        // Копируем первые 248 байт памяти напрямую
        respBuf.put(memory, 0, 248)

        val respData = respBuf.array()
        try {
            socket.send(DatagramPacket(respData, respData.size, clientAddr, clientPort))
        } catch (e: Exception) {
            Log.e(TAG, "Error sending DISCOVERY_ACK", e)
        }
    }

    private fun handleForceIp(
        socket: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int,
        reqId: Short,
        payloadBuf: ByteBuffer,
        payloadLen: Int
    ) {
        if (payloadLen >= 24) {
            val macHigh = payloadBuf.short.toInt() and 0xFFFF
            val macLow = payloadBuf.int
            payloadBuf.short // reserved
            val forcedIp = payloadBuf.int
            payloadBuf.int // reserved
            val forcedMask = payloadBuf.int
            val forcedGw = payloadBuf.int

            writeReg(GevConstants.REG_CURRENT_IP_ADDRESS, forcedIp)
            writeReg(GevConstants.REG_CURRENT_SUBNET_MASK, forcedMask)
            writeReg(GevConstants.REG_CURRENT_DEFAULT_GATEWAY, forcedGw)

            val ipStr = "%d.%d.%d.%d".format(
                (forcedIp ushr 24) and 0xFF,
                (forcedIp ushr 16) and 0xFF,
                (forcedIp ushr 8) and 0xFF,
                forcedIp and 0xFF
            )
            addLog("$clientAddr:$clientPort", "FORCEIP", "Установлен IP: $ipStr")
            Log.i(TAG, "FORCEIP_CMD: применён IP $ipStr")
        }

        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(GevConstants.GEV_STATUS_SUCCESS.toShort())
        buf.putShort(GevConstants.GVCP_FORCEIP_ACK.toShort())
        buf.putShort(0.toShort())
        buf.putShort(reqId)
        val data = buf.array()
        try {
            socket.send(DatagramPacket(data, data.size, clientAddr, clientPort))
        } catch (e: Exception) {
            Log.w(TAG, "Error sending FORCEIP_ACK", e)
        }
    }

    private fun handleReadReg(
        socket: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int,
        reqId: Short,
        payloadBuf: ByteBuffer,
        payloadLen: Int,
    ) {
        val numRegs = payloadLen / 4
        val respLen = numRegs * 4
        val respBuf = ByteBuffer.allocate(8 + respLen).order(ByteOrder.BIG_ENDIAN)

        respBuf.putShort(GevConstants.GEV_STATUS_SUCCESS.toShort())
        respBuf.putShort(GevConstants.GVCP_READREG_ACK.toShort())
        respBuf.putShort(respLen.toShort())
        respBuf.putShort(reqId)

        val regAddresses = mutableListOf<String>()
        repeat(numRegs) {
            val addr = payloadBuf.int
            val value = readReg(addr)
            respBuf.putInt(value)
            regAddresses.add("0x%04X=0x%08X".format(addr, value))
        }

        addLog("$clientAddr:$clientPort", "READREG", regAddresses.joinToString(", "))

        val data = respBuf.array()
        socket.send(DatagramPacket(data, data.size, clientAddr, clientPort))
    }

    private fun handleWriteReg(
        socket: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int,
        reqId: Short,
        payloadBuf: ByteBuffer,
        payloadLen: Int,
    ) {
        val numPairs = payloadLen / 8
        val written = mutableListOf<String>()
        repeat(numPairs) {
            val addr = payloadBuf.int
            val value = payloadBuf.int
            writeReg(addr, value)
            written.add("0x%04X=0x%08X".format(addr, value))

            if (addr == GevConstants.REG_CCP) {
                val isExclusive = (value and 0x02) != 0
                val isControl = (value and 0x01) != 0
                if (value == 0) {
                    resetClientSession("MVS отключился (CCP=0)")
                } else {
                    _isConnected.value = isExclusive || isControl
                    connectedClientAddr = clientAddr
                    lastHeartbeatTime = System.currentTimeMillis()
                    writeReg(GevConstants.REG_PRIMARY_APP_PORT, clientPort)
                    writeReg(GevConstants.REG_PRIMARY_APP_IP, ByteBuffer.wrap(clientAddr.address).int)
                    addLog(
                        "$clientAddr:$clientPort",
                        "CCP_ACCESS",
                        "🟢 MVS ПОДКЛЮЧИЛСЯ (Privilege 0x%08X)".format(value)
                    )
                }
            } else if (addr == GevConstants.REG_SC0_PACKET_DELAY) {
                val delayTicks = value.toLong() and 0xFFFFFFFFL
                streamer.packetDelayTicks = delayTicks
                addLog("$clientAddr:$clientPort", "SET_PACKET_DELAY", "Задержка пакетов: $delayTicks тиков")
            } else if (addr == GevConstants.REG_SC0_PACKET_SIZE) {
                val clamped = value.coerceIn(576, 1440)
                writeReg(GevConstants.REG_SC0_PACKET_SIZE, clamped)
                streamer.currentPacketSize = clamped
                addLog("$clientAddr:$clientPort", "SET_PACKET_SIZE", "Размер пакета: $clamped байт")
            } else if (addr == 0xA010) { // AcquisitionStart
                val destIpInt = readReg(GevConstants.REG_SC0_DESTINATION_IP)
                val destAddr = if (destIpInt != 0) {
                    val ipBytes = byteArrayOf(
                        ((destIpInt ushr 24) and 0xFF).toByte(),
                        ((destIpInt ushr 16) and 0xFF).toByte(),
                        ((destIpInt ushr 8) and 0xFF).toByte(),
                        (destIpInt and 0xFF).toByte()
                    )
                    try { InetAddress.getByAddress(ipBytes) } catch (_: Exception) { clientAddr }
                } else {
                    clientAddr
                }
                val destPort = readReg(GevConstants.REG_SC0_DESTINATION_PORT) and 0xFFFF
                val targetPort = if (destPort > 0) destPort else clientPort
                val pktSize = readReg(GevConstants.REG_SC0_PACKET_SIZE).takeIf { it in 576..1440 } ?: 1400
                val delayTicks = readReg(GevConstants.REG_SC0_PACKET_DELAY).toLong() and 0xFFFFFFFFL
                streamer.packetDelayTicks = delayTicks
                val w = readReg(0xA000).takeIf { it > 0 } ?: 640
                val h = readReg(0xA004).takeIf { it > 0 } ?: 480
                val fmt = readReg(0xA008).takeIf { it != 0 } ?: GevConstants.PIXEL_FORMAT_MONO8

                addLog("$destAddr:$targetPort", "STREAM_START", "Старт вещания GVSP: ${w}x${h}, pkt=$pktSize, delay=$delayTicks")
                streamer.startStreaming(destAddr, targetPort, pktSize, w, h, fmt)
                onStreamStart?.invoke()
            } else if (addr == 0xA014) { // AcquisitionStop
                addLog("$clientAddr:$clientPort", "STREAM_STOP", "Остановка вещания GVSP")
                streamer.stopStreaming()
                onStreamStop?.invoke()
            } else if (addr == 0xA030) { // FocusDistance (0..2000 centidiopters)
                val diopters = (value and 0xFFFF) / 100.0f
                onFocusChangedByMvs?.invoke(diopters, false)
                addLog("$clientAddr:$clientPort", "SET_FOCUS", "Фокус MVS: %.2f дптр".format(diopters))
            } else if (addr == 0xA034) { // FocusAuto (0 = Off, 1 = Continuous)
                val isAuto = (value and 0x01) == 1
                onFocusChangedByMvs?.invoke(null, isAuto)
                addLog("$clientAddr:$clientPort", "SET_AF", "Автофокус MVS: ${if (isAuto) "ON" else "OFF"}")
            } else if (addr == 0xA040) { // ExposureAuto (0 = Off, 1 = Continuous)
                val isAuto = (value and 0x01) == 1
                onExposureChangedByMvs?.invoke(null, isAuto)
                addLog("$clientAddr:$clientPort", "SET_EXPOSURE_AUTO", "Автоэкспозиция MVS: ${if (isAuto) "ON" else "OFF"}")
            } else if (addr == 0xA044) { // ExposureTime in microseconds
                val expUs = (value.toLong() and 0xFFFFFFFFL).coerceIn(100L, 50000L)
                onExposureChangedByMvs?.invoke(expUs, false)
                addLog("$clientAddr:$clientPort", "SET_EXPOSURE", "Выдержка MVS: ${expUs / 1000.0} мс")
            } else if (addr == 0xA048) { // GainAuto (0 = Off, 1 = Continuous)
                val isAuto = (value and 0x01) == 1
                onGainChangedByMvs?.invoke(null, isAuto)
                addLog("$clientAddr:$clientPort", "SET_GAIN_AUTO", "Автоусиление MVS: ${if (isAuto) "ON" else "OFF"}")
            } else if (addr == 0xA04C) { // Gain (ISO value)
                val iso = value.coerceIn(100, 6400)
                onGainChangedByMvs?.invoke(iso, false)
                addLog("$clientAddr:$clientPort", "SET_GAIN", "Усиление MVS: ISO $iso")
            }
        }

        addLog("$clientAddr:$clientPort", "WRITEREG", written.joinToString(", "))

        // В GigE Vision payload WRITEREG_ACK = 4 байта (2 байта reserved + 2 байта index)
        // index = 0 при успешной записи всех регистров (AIA Standard: ненулевой индекс означает ошибку!)
        val respBuf = ByteBuffer.allocate(8 + 4).order(ByteOrder.BIG_ENDIAN)
        respBuf.putShort(GevConstants.GEV_STATUS_SUCCESS.toShort())
        respBuf.putShort(GevConstants.GVCP_WRITEREG_ACK.toShort())
        respBuf.putShort(4.toShort())
        respBuf.putShort(reqId)
        respBuf.putShort(0.toShort()) // Reserved
        respBuf.putShort(0.toShort()) // Index = 0 (успех)

        val data = respBuf.array()
        socket.send(DatagramPacket(data, data.size, clientAddr, clientPort))
    }

    private var lastResendBlockId: Short = -1
    private var lastResendFirstPid: Int = -1
    private var lastResendTime = 0L

    private fun handlePacketResend(
        socket: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int,
        reqId: Short,
        payloadBuf: ByteBuffer,
        payloadLen: Int
    ) {
        val streamChannelIndex = if (payloadLen >= 2) payloadBuf.short.toInt() and 0xFFFF else 0
        val blockId = if (payloadLen >= 4) payloadBuf.short else 0.toShort()
        val firstPacketId = if (payloadLen >= 8) payloadBuf.int else 0
        val lastPacketId = if (payloadLen >= 12) payloadBuf.int else firstPacketId

        val now = System.currentTimeMillis()
        val isDuplicate = (blockId == lastResendBlockId && firstPacketId == lastResendFirstPid && (now - lastResendTime) < 120L)

        addLog("$clientAddr:$clientPort", "PACKET_RESEND", "Block=$blockId, Packets=$firstPacketId..$lastPacketId${if (isDuplicate) " [DUP]" else ""}")

        val respBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        respBuf.putShort(GevConstants.GEV_STATUS_SUCCESS.toShort())
        respBuf.putShort(GevConstants.GVCP_PACKETRESEND_ACK.toShort())
        respBuf.putShort(0.toShort())
        respBuf.putShort(reqId)
        val data = respBuf.array()
        try {
            socket.send(DatagramPacket(data, data.size, clientAddr, clientPort))
        } catch (e: Exception) {
            Log.w(TAG, "Error sending PACKETRESEND_ACK", e)
        }

        if (!isDuplicate) {
            lastResendBlockId = blockId
            lastResendFirstPid = firstPacketId
            lastResendTime = now
            // Ретрансляция пакетов в фоновой корутине
            scope.launch(Dispatchers.IO) {
                streamer.resendPackets(blockId, firstPacketId, lastPacketId)
            }
        } else {
            Log.d(TAG, "PACKET_RESEND: пропущен дублирующий запрос Block=$blockId, $firstPacketId..$lastPacketId (интервал ${now - lastResendTime}мс)")
        }
    }

    private fun handleReadMem(
        socket: DatagramSocket,
        clientAddr: InetAddress,
        clientPort: Int,
        reqId: Short,
        payloadBuf: ByteBuffer,
    ) {
        val addr = payloadBuf.int
        val size = payloadBuf.int // В GVCP размер в READMEM_CMD — 32-битное число

        val memData = readMem(addr, size)

        if (addr >= GenicamXml.XML_START_ADDRESS) {
            addLog("$clientAddr:$clientPort", "READ_XML", "Отправлен XML: $size б. (addr 0x%X)".format(addr))
        } else if (addr == GevConstants.REG_FIRST_URL) {
            val url = String(memData).trimEnd { it == '\u0000' }
            addLog("$clientAddr:$clientPort", "READ_URL", "FIRST_URL: $url")
        } else {
            addLog("$clientAddr:$clientPort", "READMEM", "addr=0x%X, size=%d".format(addr, size))
        }

        // По спецификации AIA: payload = 4 байта addr + size байт data
        val respBuf = ByteBuffer.allocate(8 + 4 + size).order(ByteOrder.BIG_ENDIAN)
        respBuf.putShort(GevConstants.GEV_STATUS_SUCCESS.toShort())
        respBuf.putShort(GevConstants.GVCP_READMEM_ACK.toShort())
        respBuf.putShort((4 + size).toShort())
        respBuf.putShort(reqId)
        respBuf.putInt(addr)
        respBuf.put(memData)

        val data = respBuf.array()
        socket.send(DatagramPacket(data, data.size, clientAddr, clientPort))
    }

    fun updateFocusRegister(diopters: Float?, isAuto: Boolean) {
        writeReg(0xA034, if (isAuto) 1 else 0)
        if (diopters != null) {
            val centiDiopters = (diopters * 100).toInt().coerceIn(0, 65535)
            writeReg(0xA030, centiDiopters)
        }
    }

    fun updateExposureRegister(exposureUs: Long?, isAuto: Boolean) {
        writeReg(0xA040, if (isAuto) 1 else 0)
        if (exposureUs != null) {
            writeReg(0xA044, exposureUs.toInt().coerceIn(100, 50000))
        }
    }

    fun updateGainRegister(iso: Int?, isAuto: Boolean) {
        writeReg(0xA048, if (isAuto) 1 else 0)
        if (iso != null) {
            writeReg(0xA04C, iso.coerceIn(100, 6400))
        }
    }

    private fun addLog(client: String, command: String, details: String) {
        Log.i(TAG, "[$client] $command: $details")
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = GevLogEntry(time, client, command, details)
        _logs.value = (_logs.value + entry).takeLast(100)
    }
}
