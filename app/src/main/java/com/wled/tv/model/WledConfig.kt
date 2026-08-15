package com.wled.tv.model

data class WledConfig(
    val ip: String = "192.168.1.66",
    val port: Int = 21324, // Standard WLED UDP DRGB realtime port
    val perimeter: PerimeterConfig = PerimeterConfig(),
    val calibration: ColorCalibration = ColorCalibration(),
    val autoStartOnBoot: Boolean = false
)
