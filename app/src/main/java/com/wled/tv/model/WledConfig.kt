package com.wled.tv.model

data class WledConfig(
    val devices: List<WledDevice> = listOf(WledDevice()),
    val calibration: ColorCalibration = ColorCalibration(),
    val capture: CaptureSettings = CaptureSettings(),
    val autoStartOnBoot: Boolean = false,
    val activeProfileId: String = PresetProfile.DEFAULT.id
) {
    val activeProfile: PresetProfile
        get() = PresetProfile.fromId(activeProfileId)

    /**
     * Applies [preset] to global calibration and every device's optical calibration
     * without altering [capture] settings or per-device hardware wire format.
     */
    fun applyPreset(preset: PresetProfile): WledConfig {
        val updatedDevices = devices.map { dev ->
            dev.copy(calibration = preset.calibration)
        }
        return copy(
            activeProfileId = preset.id,
            calibration = preset.calibration,
            devices = updatedDevices
        )
    }

    /**
     * Updates the optical calibration of the device matching [deviceId] (or [primaryDevice]
     * when [deviceId] is null), mirrors it to global [calibration] when the target is
     * [DeviceType.PERIMETER], and transitions [activeProfileId] to [PresetProfile.CUSTOM].
     */
    fun updateDeviceCalibration(
        deviceId: String? = null,
        transform: (ColorCalibration) -> ColorCalibration
    ): WledConfig {
        val target = if (deviceId != null) {
            devices.firstOrNull { it.id == deviceId } ?: primaryDevice
        } else {
            primaryDevice
        }
        val newCal = transform(target.calibration)
        val updatedDevices = devices.map { if (it.id == target.id) it.copy(calibration = newCal) else it }
        return copy(
            devices = updatedDevices,
            calibration = if (target.type == DeviceType.PERIMETER) newCal else calibration,
            activeProfileId = PresetProfile.CUSTOM.id
        )
    }

    /**
     * Updates global screen capture settings via [transform].
     */
    fun updateCaptureSettings(transform: (CaptureSettings) -> CaptureSettings): WledConfig {
        return copy(capture = transform(capture))
    }

    // Backward compatibility helpers
    val primaryDevice: WledDevice
        get() = devices.firstOrNull { it.type == DeviceType.PERIMETER }
            ?: devices.firstOrNull()
            ?: WledDevice()

    val ip: String
        get() = primaryDevice.ip

    val port: Int
        get() = primaryDevice.port

    val perimeter: PerimeterConfig
        get() = primaryDevice.perimeter

    val enabledDevices: List<WledDevice>
        get() = devices.filter { it.enabled && it.ip.isNotBlank() }

    val totalActiveLeds: Int
        get() = enabledDevices.sumOf { it.totalLeds }

    fun updatePrimaryPerimeter(newPerimeter: PerimeterConfig): WledConfig {
        val primary = primaryDevice
        val updatedPrimary = primary.copy(perimeter = newPerimeter)
        val updatedDevices = devices.map { if (it.id == primary.id) updatedPrimary else it }
        return copy(devices = updatedDevices)
    }

    fun updatePrimaryIp(newIp: String): WledConfig {
        val primary = primaryDevice
        val updatedPrimary = primary.copy(ip = newIp)
        val updatedDevices = devices.map { if (it.id == primary.id) updatedPrimary else it }
        return copy(devices = updatedDevices)
    }

    fun updateDevice(updatedDevice: WledDevice): WledConfig {
        val updatedDevices = devices.map { if (it.id == updatedDevice.id) updatedDevice else it }
        return copy(devices = updatedDevices)
    }
}
