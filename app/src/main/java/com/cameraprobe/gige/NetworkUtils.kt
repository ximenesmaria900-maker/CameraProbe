package com.cameraprobe.gige

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "NetworkUtils"

data class NetworkInfo(
    val ipAddress: Inet4Address,
    val ipBytes: ByteArray,
    val subnetMaskBytes: ByteArray,
    val gatewayBytes: ByteArray,
    val macBytes: ByteArray,
    val broadcastAddress: InetAddress,
    val interfaceName: String,
) {
    val ipString: String get() = ipAddress.hostAddress ?: "0.0.0.0"
    val macString: String get() = macBytes.joinToString(":") { "%02X".format(it) }

    val macHigh: Int get() = if (macBytes.size >= 6) {
        ((macBytes[0].toInt() and 0xFF) shl 8) or (macBytes[1].toInt() and 0xFF)
    } else 0

    val macLow: Int get() = if (macBytes.size >= 6) {
        ((macBytes[2].toInt() and 0xFF) shl 24) or
        ((macBytes[3].toInt() and 0xFF) shl 16) or
        ((macBytes[4].toInt() and 0xFF) shl 8) or
        (macBytes[5].toInt() and 0xFF)
    } else 0
}

object NetworkUtils {

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * Захват MulticastLock для Wi-Fi (обязателен на Android для приёма UDP Broadcast).
     */
    fun acquireMulticastLock(context: Context) {
        try {
            if (multicastLock == null) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("GigEVisionDiscoveryLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld != true) {
                multicastLock?.acquire()
                Log.i(TAG, "MulticastLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.i(TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MulticastLock: ${e.message}")
        }
    }

    /**
     * Захват высокопроизводительного WifiLock с низким джиттером (отключает энергосбережение 802.11).
     */
    fun acquireWifiLock(context: Context) {
        try {
            if (wifiLock == null) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifi?.createWifiLock(mode, "GigEVisionWifiLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
                Log.i(TAG, "WifiLock (LOW_LATENCY) acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }
    }

    fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.i(TAG, "WifiLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WifiLock: ${e.message}")
        }
    }

    /**
     * Захват WakeLock (PARTIAL_WAKE_LOCK), предотвращающего переход CPU в спящий режим.
     */
    fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null) {
                val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = powerManager?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "CameraProbe::GigEVisionWakeLock"
                )?.apply {
                    setReferenceCounted(true)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(3600 * 1000L) // 1 час максимум с авто-освобождением для безопасности
                Log.i(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    /**
     * Поиск активного сетевого интерфейса (Wi-Fi wlan0 или USB rndis0/eth0).
     */
    fun getActiveNetworkInfo(): NetworkInfo? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            // Приоритет интерфейсов: rndis0 / usb0 (USB-модем), eth0, wlan0 (Wi-Fi)
            val sortedInterfaces = interfaces.filter { !it.isLoopback && it.isUp }.sortedBy { intf ->
                when {
                    intf.name.startsWith("rndis") || intf.name.startsWith("usb") -> 0
                    intf.name.startsWith("eth") -> 1
                    intf.name.startsWith("wlan") -> 2
                    else -> 9
                }
            }

            for (intf in sortedInterfaces) {
                for (addr in intf.interfaceAddresses) {
                    val inetAddr = addr.address
                    if (inetAddr is Inet4Address && !inetAddr.isLoopbackAddress) {
                        val ipBytes = inetAddr.address
                        val prefix = addr.networkPrefixLength.toInt()
                        val subnetMaskBytes = prefixToSubnetMask(prefix)

                        // Gateway: обычно .1 в подсети
                        val gatewayBytes = byteArrayOf(
                            ipBytes[0],
                            ipBytes[1],
                            ipBytes[2],
                            1.toByte()
                        )

                        // MAC адрес интерфейса (или дефолтный если недоступен)
                        val mac = intf.hardwareAddress ?: byteArrayOf(
                            0xDA.toByte(), 0x38.toByte(), 0x27.toByte(),
                            0x06.toByte(), 0x05.toByte(), 0x01.toByte()
                        )

                        val broadcast = addr.broadcast ?: InetAddress.getByName("255.255.255.255")

                        return NetworkInfo(
                            ipAddress = inetAddr,
                            ipBytes = ipBytes,
                            subnetMaskBytes = subnetMaskBytes,
                            gatewayBytes = gatewayBytes,
                            macBytes = mac,
                            broadcastAddress = broadcast,
                            interfaceName = intf.name
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving network info", e)
        }
        return null
    }

    fun getNetworkInfoForClient(clientAddr: InetAddress?): NetworkInfo? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val upInterfaces = interfaces.filter { !it.isLoopback && it.isUp }

            // Если передан clientAddr, ищем интерфейс в той же подсети
            if (clientAddr is Inet4Address) {
                val clientBytes = clientAddr.address
                val clientInt = ByteBuffer.wrap(clientBytes).int
                for (intf in upInterfaces) {
                    for (addr in intf.interfaceAddresses) {
                        val inetAddr = addr.address
                        if (inetAddr is Inet4Address && !inetAddr.isLoopbackAddress) {
                            val ipBytes = inetAddr.address
                            val prefix = addr.networkPrefixLength.toInt()
                            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                            val myInt = ByteBuffer.wrap(ipBytes).int
                            if ((clientInt and mask) == (myInt and mask)) {
                                val subnetMaskBytes = prefixToSubnetMask(prefix)
                                val gatewayBytes = byteArrayOf(ipBytes[0], ipBytes[1], ipBytes[2], 1.toByte())
                                val mac = intf.hardwareAddress ?: byteArrayOf(
                                    0xDA.toByte(), 0x38.toByte(), 0x27.toByte(),
                                    0x06.toByte(), 0x05.toByte(), 0x01.toByte()
                                )
                                return NetworkInfo(
                                    ipAddress = inetAddr,
                                    ipBytes = ipBytes,
                                    subnetMaskBytes = subnetMaskBytes,
                                    gatewayBytes = gatewayBytes,
                                    macBytes = mac,
                                    broadcastAddress = addr.broadcast ?: InetAddress.getByName("255.255.255.255"),
                                    interfaceName = intf.name
                                )
                            }
                        }
                    }
                }
            }
            return getActiveNetworkInfo()
        } catch (e: Exception) {
            Log.e(TAG, "Error in getNetworkInfoForClient", e)
            return getActiveNetworkInfo()
        }
    }

    private fun prefixToSubnetMask(prefix: Int): ByteArray {
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(mask).array()
    }
}
