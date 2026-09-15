package com.wled.tv.model

data class WledConfig(
    val devices: List<WledDevice> = listOf(WledDevice()),
    val calibration: ColorCalibration = ColorCalibration(),
    val autoStartOnBoot: Boolean = false,
    val homeAssistant: HomeAssistantConfig = HomeAssistantConfig()
) {
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
