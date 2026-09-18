package com.cameraprobe.gige

/**
 * Константы протокола GigE Vision (GVCP / GVSP) и GenICam.
 * Основано на спецификации AIA GigE Vision Standard v1.2 / v2.0.
 */
object GevConstants {

    /** Стандартный порт GVCP для команд управления и обнаружения */
    const val GEV_PORT = 3956

    // --- GVCP Command IDs ---
    const val GVCP_DISCOVERY_CMD = 0x0002
    const val GVCP_DISCOVERY_ACK = 0x0003
    const val GVCP_FORCEIP_CMD   = 0x0004
    const val GVCP_FORCEIP_ACK   = 0x0005
    const val GVCP_PACKETRESEND_CMD = 0x0040
    const val GVCP_PACKETRESEND_ACK = 0x0041
    const val GVCP_READREG_CMD   = 0x0080
    const val GVCP_READREG_ACK   = 0x0081
    const val GVCP_WRITEREG_CMD  = 0x0082
    const val GVCP_WRITEREG_ACK  = 0x0083
    const val GVCP_READMEM_CMD   = 0x0084
    const val GVCP_READMEM_ACK   = 0x0085
    const val GVCP_WRITEMEM_CMD  = 0x0086
    const val GVCP_WRITEMEM_ACK  = 0x0087
    const val GVCP_PENDING_ACK   = 0x0089

    // --- GVCP Status Codes ---
    const val GEV_STATUS_SUCCESS            = 0x0000
    const val GEV_STATUS_PACKET_RESEND      = 0x0100
    const val GEV_STATUS_NOT_IMPLEMENTED    = 0x8001
    const val GEV_STATUS_INVALID_PARAMETER  = 0x8002
    const val GEV_STATUS_INVALID_ADDRESS    = 0x8003
    const val GEV_STATUS_WRITE_PROTECT      = 0x8004
    const val GEV_STATUS_ACCESS_DENIED      = 0x8006
    const val GEV_STATUS_ERROR              = 0x8FFF

    // --- GVCP Bootstrap Registers (Addresses) ---
    const val REG_VERSION                    = 0x00000000
    const val REG_DEVICE_MODE                = 0x00000004
    const val REG_DEVICE_MAC_HIGH            = 0x00000008
    const val REG_DEVICE_MAC_LOW             = 0x0000000C
    const val REG_SUPPORTED_IP_CONFIG        = 0x00000010
    const val REG_CURRENT_IP_CONFIG          = 0x00000014
    const val REG_CURRENT_IP_ADDRESS         = 0x00000024
    const val REG_CURRENT_SUBNET_MASK        = 0x00000034
    const val REG_CURRENT_DEFAULT_GATEWAY    = 0x00000044
    const val REG_MANUFACTURER_NAME          = 0x00000048 // 32 bytes
    const val REG_MODEL_NAME                 = 0x00000068 // 32 bytes
    const val REG_DEVICE_VERSION             = 0x00000088 // 32 bytes
    const val REG_MANUFACTURER_INFO          = 0x000000A8 // 48 bytes
    const val REG_SERIAL_NUMBER              = 0x000000D8 // 16 bytes
    const val REG_USER_DEFINED_NAME          = 0x000000E8 // 16 bytes

    const val REG_FIRST_URL                  = 0x00000200 // 512 bytes
    const val REG_SECOND_URL                 = 0x00000400 // 512 bytes

    const val REG_NUMBER_OF_INTERFACES       = 0x00000600
    const val REG_NUMBER_OF_MESSAGE_CHANNELS = 0x00000900
    const val REG_NUMBER_OF_STREAM_CHANNELS  = 0x00000904
    const val REG_CAPABILITY                 = 0x00000934
    const val REG_HEARTBEAT_TIMEOUT          = 0x00000938

    const val REG_CCP                        = 0x00000A00 // Control Channel Privilege
    const val REG_PRIMARY_APP_PORT           = 0x00000A04
    const val REG_PRIMARY_APP_IP             = 0x00000A14

    // Stream Channel 0 (SC0)
    const val REG_SC0_DESTINATION_PORT       = 0x00000D00
    const val REG_SC0_PACKET_SIZE            = 0x00000D04
    const val REG_SC0_PACKET_DELAY           = 0x00000D08
    const val REG_SC0_DESTINATION_IP         = 0x00000D18
    const val REG_SC0_SOURCE_PORT            = 0x00000D1C
    const val REG_SC0_CAPABILITY             = 0x00000D20
    const val REG_SC0_CONFIGURATION          = 0x00000D24

    // GVSP Streaming Packet Types
    const val GVSP_PACKET_TYPE_DATA_LEADER   = 0x01
    const val GVSP_PACKET_TYPE_DATA_TRAILER  = 0x02
    const val GVSP_PACKET_TYPE_DATA_PAYLOAD  = 0x03

    // Pixel Formats (GenICam PFNC)
    const val PIXEL_FORMAT_MONO8             = 0x01080001
    const val PIXEL_FORMAT_RGB8_PACKED       = 0x02180014
    const val PIXEL_FORMAT_BAYER_RG8         = 0x0108000A
}
