package com.cameraprobe.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraMetadata

/**
 * Параметры ручного режима камеры.
 * Все значения null означают "авто".
 */
data class ManualParams(
    /** ISO (sensitivity), например 100, 200, 800 */
    val iso: Int? = null,

    /** Выдержка в наносекундах, например 10_000_000L = 10 мс */
    val exposureNs: Long? = null,

    /** Фокусное расстояние в диоптриях (0.0 = бесконечность) */
    val focusDiopters: Float? = null,

    /** Заблокировать AWB */
    val awbLocked: Boolean = false,

    /** Заблокировать AE */
    val aeLocked: Boolean = false,
) {
    companion object {
        /** Параметры по умолчанию для лазерной съёмки */
        val DEFAULT_LASER = ManualParams(
            iso = 200,
            exposureNs = 8_000_000L, // 8 мс
            focusDiopters = 1.0f,    // ~1 м
            awbLocked = true,
            aeLocked = true,
        )

        /** Авто-режим для начального просмотра */
        val AUTO = ManualParams()
    }

    /** Применить параметры к CaptureRequest.Builder */
    fun applyTo(builder: CaptureRequest.Builder) {
        if (aeLocked || iso != null || exposureNs != null) {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF
            )
            iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            exposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        }

        if (awbLocked) {
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_OFF
            )
        }

        focusDiopters?.let {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
    }

    val exposureMs: Float? get() = exposureNs?.let { it / 1_000_000f }
    val exposureLabel: String get() = exposureMs?.let { "%.1f мс".format(it) } ?: "авто"
    val isoLabel: String get() = iso?.toString() ?: "авто"
    val focusLabel: String get() = focusDiopters?.let {
        if (it == 0f) "∞" else "%.2f дптр (~%.0f см)".format(it, 100f / it)
    } ?: "авто"
}
