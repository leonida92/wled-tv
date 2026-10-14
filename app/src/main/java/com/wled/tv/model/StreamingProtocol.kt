package com.wled.tv.model

enum class StreamingProtocol(val displayName: String, val defaultPort: Int) {
    DDP("DDP (Distributed Display Protocol)", 4048),
    DRGB("DRGB (WLED Realtime)", 21324),
    WARLS("WARLS (WLED Realtime)", 21324)
}
