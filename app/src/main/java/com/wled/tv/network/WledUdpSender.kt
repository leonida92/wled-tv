package com.wled.tv.network

import android.util.Log
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

class WledUdpSender {

    @Volatile
    private var socket: DatagramSocket? = null
    private val addressMap = ConcurrentHashMap<String, InetAddress>()
    private val packetBuffers = ConcurrentHashMap<String, ByteArray>()
    private val datagramPackets = ConcurrentHashMap<String, DatagramPacket>()
    private val sequenceMap = ConcurrentHashMap<String, Int>()
    private var lastErrorTime = 0L

    /**
     * Universal frame dispatcher for a configured WledDevice.
     */
    fun sendDeviceFrame(
        device: WledDevice,
        rgb: ByteArray,
        ledCount: Int = device.totalLeds,
        timeoutSeconds: Byte = 2
    ) {
        sendFrame(
            ip = device.ip,
            port = device.port,
            protocol = device.protocol,
            rgb = rgb,
            ledCount = ledCount,
            colorOrder = device.calibration.colorOrder,
            isRgbw = device.calibration.isRgbw,
            rgbwMode = device.calibration.rgbwMode,
            timeoutSeconds = timeoutSeconds
        )
    }

    /**
     * Universal frame dispatcher by protocol.
     */
    fun sendFrame(
        ip: String,
        port: Int,
        protocol: StreamingProtocol,
        rgb: ByteArray,
        ledCount: Int,
        colorOrder: String = "RGB",
        isRgbw: Boolean = false,
        rgbwMode: String = "AUTO_SUBTRACT",
        timeoutSeconds: Byte = 2
    ) {
        when (protocol) {
            StreamingProtocol.DDP -> sendDdpFrame(
                ip = ip,
                port = port,
                rgb = rgb,
                ledCount = ledCount,
                colorOrder = colorOrder,
                isRgbw = isRgbw,
                rgbwMode = rgbwMode
            )
            StreamingProtocol.DRGB -> sendDrgbFrame(
                ip = ip,
                port = port,
                timeoutSeconds = timeoutSeconds,
                rgb = rgb,
                ledCount = ledCount,
                colorOrder = colorOrder
            )
            StreamingProtocol.WARLS -> sendWarlsFrame(
                ip = ip,
                port = port,
                timeoutSeconds = timeoutSeconds,
                rgb = rgb,
                ledCount = ledCount,
                colorOrder = colorOrder
            )
        }
    }

    /**
     * Sends DDP (Distributed Display Protocol, UDP Port 4048) frames to WLED.
     * Features:
     *   - Multi-packet atomic synchronization: Push flag on final packet prevents visual tearing on long strips (>480 LEDs).
     *   - Native 4-channel RGBW support (SK6812 gamut extraction: Auto White Subtract or Add).
     *   - Zero GC allocations in the 60Hz loop via pre-allocated datagram buffers.
     */
    fun sendDdpFrame(
        ip: String,
        port: Int = DDP_PORT,
        rgb: ByteArray,
        ledCount: Int,
        colorOrder: String = "RGB",
        isRgbw: Boolean = false,
        rgbwMode: String = "AUTO_SUBTRACT"
    ) {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank() || ledCount <= 0 || rgb.size < ledCount * 3) return

        try {
            val currentSocket = ensureSocket()

            var address = addressMap[cleanIp]
            if (address == null) {
                address = InetAddress.getByName(cleanIp)
                addressMap[cleanIp] = address
            }

            val bytesPerLed = if (isRgbw) 4 else 3
            val totalChannels = ledCount * bytesPerLed
            val packetCount = ((totalChannels - 1) / DDP_MAX_CHANNELS_PER_PACKET) + 1

            // Cycle sequence number per destination IP + Port (1..15)
            val seqKey = "$cleanIp:$port"
            var seq = (sequenceMap[seqKey] ?: 0) + 1
            if (seq > 15) seq = 1
            sequenceMap[seqKey] = seq

            var channelOffset = 0
            var remainingChannels = totalChannels
            var currentLedIndex = 0

            // Max single packet payload is 1440 bytes. Total packet buffer needed is 10 + minOf(totalChannels, 1440)
            val maxPacketBufferSize = DDP_HEADER_LEN + minOf(totalChannels, DDP_MAX_CHANNELS_PER_PACKET)
            val cacheKey = "$cleanIp:$port:ddp"
            var packetBuffer = packetBuffers[cacheKey]
            var datagramPacket = datagramPackets[cacheKey]

            if (packetBuffer == null || packetBuffer.size < maxPacketBufferSize) {
                packetBuffer = ByteArray(maxPacketBufferSize)
                packetBuffers[cacheKey] = packetBuffer
                datagramPacket = DatagramPacket(packetBuffer, maxPacketBufferSize, address, port)
                datagramPackets[cacheKey] = datagramPacket
            } else {
                datagramPacket?.address = address
                datagramPacket?.port = port
            }

            for (packetIndex in 0 until packetCount) {
                val isFinalPacket = (packetIndex == packetCount - 1)
                val chunkChannels = minOf(remainingChannels, DDP_MAX_CHANNELS_PER_PACKET)
                val ledsInChunk = chunkChannels / bytesPerLed

                // 1. DDP 10-byte header
                // Byte 0: Flags (Version 1 = 0x40, Push = 0x01 on final packet to commit frame)
                packetBuffer[0] = (DDP_FLAG_VER1 or (if (isFinalPacket) DDP_FLAG_PUSH else 0x00)).toByte()
                // Byte 1: Sequence number (1..15)
                packetBuffer[1] = seq.toByte()
                // Byte 2: Data type (0x01 = RGB, 0x02 = RGBW)
                packetBuffer[2] = (if (isRgbw) DDP_TYPE_RGBW else DDP_TYPE_RGB).toByte()
                // Byte 3: Destination ID (0x01 = default display device)
                packetBuffer[3] = DDP_ID_DEFAULT.toByte()
                // Bytes 4-7: Offset in channels (32-bit big-endian)
                packetBuffer[4] = ((channelOffset ushr 24) and 0xFF).toByte()
                packetBuffer[5] = ((channelOffset ushr 16) and 0xFF).toByte()
                packetBuffer[6] = ((channelOffset ushr 8) and 0xFF).toByte()
                packetBuffer[7] = (channelOffset and 0xFF).toByte()
                // Bytes 8-9: Data length in bytes (16-bit big-endian)
                packetBuffer[8] = ((chunkChannels ushr 8) and 0xFF).toByte()
                packetBuffer[9] = (chunkChannels and 0xFF).toByte()

                // 2. Pixel payload
                var dst = DDP_HEADER_LEN
                val isOrderRgb = colorOrder.equals("RGB", ignoreCase = true)

                if (!isRgbw) {
                    // 3-channel RGB
                    if (isOrderRgb) {
                        System.arraycopy(rgb, currentLedIndex * 3, packetBuffer, dst, chunkChannels)
                        currentLedIndex += ledsInChunk
                    } else {
                        for (i in 0 until ledsInChunk) {
                            val src = (currentLedIndex + i) * 3
                            val r = rgb[src]
                            val g = rgb[src + 1]
                            val b = rgb[src + 2]
                            when (colorOrder.uppercase()) {
                                "GRB" -> { packetBuffer[dst++] = g; packetBuffer[dst++] = r; packetBuffer[dst++] = b }
                                "BGR" -> { packetBuffer[dst++] = b; packetBuffer[dst++] = g; packetBuffer[dst++] = r }
                                "BRG" -> { packetBuffer[dst++] = b; packetBuffer[dst++] = r; packetBuffer[dst++] = g }
                                "RBG" -> { packetBuffer[dst++] = r; packetBuffer[dst++] = b; packetBuffer[dst++] = g }
                                "GBR" -> { packetBuffer[dst++] = g; packetBuffer[dst++] = b; packetBuffer[dst++] = r }
                                else -> { packetBuffer[dst++] = r; packetBuffer[dst++] = g; packetBuffer[dst++] = b }
                            }
                        }
                        currentLedIndex += ledsInChunk
                    }
                } else {
                    // 4-channel RGBW gamut mapping
                    for (i in 0 until ledsInChunk) {
                        val src = (currentLedIndex + i) * 3
                        val rInt = rgb[src].toInt() and 0xFF
                        val gInt = rgb[src + 1].toInt() and 0xFF
                        val bInt = rgb[src + 2].toInt() and 0xFF

                        val wInt = when (rgbwMode) {
                            "AUTO_SUBTRACT", "AUTO_ADD" -> minOf(rInt, minOf(gInt, bInt))
                            else -> 0
                        }

                        val rOut = if (rgbwMode == "AUTO_SUBTRACT") (rInt - wInt).coerceIn(0, 255) else rInt
                        val gOut = if (rgbwMode == "AUTO_SUBTRACT") (gInt - wInt).coerceIn(0, 255) else gInt
                        val bOut = if (rgbwMode == "AUTO_SUBTRACT") (bInt - wInt).coerceIn(0, 255) else bInt
                        val wOut = wInt.coerceIn(0, 255)

                        val rByte = rOut.toByte()
                        val gByte = gOut.toByte()
                        val bByte = bOut.toByte()
                        val wByte = wOut.toByte()

                        when (colorOrder.uppercase()) {
                            "GRB" -> { packetBuffer[dst++] = gByte; packetBuffer[dst++] = rByte; packetBuffer[dst++] = bByte; packetBuffer[dst++] = wByte }
                            "BGR" -> { packetBuffer[dst++] = bByte; packetBuffer[dst++] = gByte; packetBuffer[dst++] = rByte; packetBuffer[dst++] = wByte }
                            "BRG" -> { packetBuffer[dst++] = bByte; packetBuffer[dst++] = rByte; packetBuffer[dst++] = gByte; packetBuffer[dst++] = wByte }
                            "RBG" -> { packetBuffer[dst++] = rByte; packetBuffer[dst++] = bByte; packetBuffer[dst++] = gByte; packetBuffer[dst++] = wByte }
                            "GBR" -> { packetBuffer[dst++] = gByte; packetBuffer[dst++] = bByte; packetBuffer[dst++] = rByte; packetBuffer[dst++] = wByte }
                            else -> { packetBuffer[dst++] = rByte; packetBuffer[dst++] = gByte; packetBuffer[dst++] = bByte; packetBuffer[dst++] = wByte }
                        }
                    }
                    currentLedIndex += ledsInChunk
                }

                // 3. Transmit chunk
                datagramPacket?.let {
                    it.length = DDP_HEADER_LEN + chunkChannels
                    currentSocket.send(it)
                }

                channelOffset += chunkChannels
                remainingChannels -= chunkChannels
            }
        } catch (e: SocketException) {
            handleSocketException(e, cleanIp)
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastErrorTime > 3000L) {
                Log.w(TAG, "Failed to send DDP UDP frame to $cleanIp: ${e.message}")
                lastErrorTime = now
            }
        }
    }

    /**
     * Sends raw DRGB (0x02) realtime packet directly to WLED.
     * DRGB format:
     *   Byte 0: 0x02 (DRGB protocol)
     *   Byte 1: timeout in seconds (1-255, e.g., 2)
     *   Byte 2..N: C0_0, C0_1, C0_2, C1_0, C1_1, C1_2, ... based on colorOrder
     */
    fun sendDrgbFrame(
        ip: String,
        port: Int = DRGB_PORT,
        timeoutSeconds: Byte = 2,
        rgb: ByteArray,
        ledCount: Int,
        colorOrder: String = "RGB"
    ) {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank() || ledCount <= 0 || rgb.size < ledCount * 3) return

        try {
            val currentSocket = ensureSocket()

            var address = addressMap[cleanIp]
            if (address == null) {
                address = InetAddress.getByName(cleanIp)
                addressMap[cleanIp] = address
            }

            val requiredSize = 2 + ledCount * 3
            val cacheKey = "$cleanIp:$port:drgb"
            var packetBuffer = packetBuffers[cacheKey]
            var datagramPacket = datagramPackets[cacheKey]

            if (packetBuffer == null || packetBuffer.size != requiredSize) {
                packetBuffer = ByteArray(requiredSize)
                packetBuffers[cacheKey] = packetBuffer
                datagramPacket = DatagramPacket(packetBuffer, requiredSize, address, port)
                datagramPackets[cacheKey] = datagramPacket
            } else {
                datagramPacket?.address = address
                datagramPacket?.port = port
            }

            // WLED DRGB protocol header
            packetBuffer[0] = 0x02 // DRGB protocol identifier
            packetBuffer[1] = timeoutSeconds // Timeout in seconds

            // Remap color order if needed
            when (colorOrder.uppercase()) {
                "GRB" -> {
                    for (i in 0 until ledCount) {
                        val src = i * 3
                        val dst = 2 + i * 3
                        packetBuffer[dst] = rgb[src + 1]     // G
                        packetBuffer[dst + 1] = rgb[src]     // R
                        packetBuffer[dst + 2] = rgb[src + 2] // B
                    }
                }
                "BGR" -> {
                    for (i in 0 until ledCount) {
                        val src = i * 3
                        val dst = 2 + i * 3
                        packetBuffer[dst] = rgb[src + 2]     // B
                        packetBuffer[dst + 1] = rgb[src + 1] // G
                        packetBuffer[dst + 2] = rgb[src]     // R
                    }
                }
                "BRG" -> {
                    for (i in 0 until ledCount) {
                        val src = i * 3
                        val dst = 2 + i * 3
                        packetBuffer[dst] = rgb[src + 2]     // B
                        packetBuffer[dst + 1] = rgb[src]     // R
                        packetBuffer[dst + 2] = rgb[src + 1] // G
                    }
                }
                "RBG" -> {
                    for (i in 0 until ledCount) {
                        val src = i * 3
                        val dst = 2 + i * 3
                        packetBuffer[dst] = rgb[src]         // R
                        packetBuffer[dst + 1] = rgb[src + 2] // B
                        packetBuffer[dst + 2] = rgb[src + 1] // G
                    }
                }
                "GBR" -> {
                    for (i in 0 until ledCount) {
                        val src = i * 3
                        val dst = 2 + i * 3
                        packetBuffer[dst] = rgb[src + 1]     // G
                        packetBuffer[dst + 1] = rgb[src + 2] // B
                        packetBuffer[dst + 2] = rgb[src]     // R
                    }
                }
                else -> {
                    // Standard RGB
                    System.arraycopy(rgb, 0, packetBuffer, 2, ledCount * 3)
                }
            }

            datagramPacket?.let {
                it.length = requiredSize
                currentSocket.send(it)
            }
        } catch (e: SocketException) {
            handleSocketException(e, cleanIp)
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastErrorTime > 3000L) {
                Log.w(TAG, "Failed to send DRGB UDP frame to $cleanIp: ${e.message}")
                lastErrorTime = now
            }
        }
    }

    /**
     * Sends WARLS (0x01) realtime packets to WLED.
     * WARLS format:
     *   Byte 0: 0x01 (WARLS protocol)
     *   Byte 1: timeout in seconds
     *   Byte 2..N: [Index, R, G, B, Index, R, G, B, ...]
     */
    fun sendWarlsFrame(
        ip: String,
        port: Int = DRGB_PORT,
        timeoutSeconds: Byte = 2,
        rgb: ByteArray,
        ledCount: Int,
        colorOrder: String = "RGB"
    ) {
        val cleanIp = ip.trim()
        val count = ledCount.coerceAtMost(255)
        if (cleanIp.isBlank() || count <= 0 || rgb.size < count * 3) return

        try {
            val currentSocket = ensureSocket()

            var address = addressMap[cleanIp]
            if (address == null) {
                address = InetAddress.getByName(cleanIp)
                addressMap[cleanIp] = address
            }

            val requiredSize = 2 + count * 4
            val cacheKey = "$cleanIp:$port:warls"
            var packetBuffer = packetBuffers[cacheKey]
            var datagramPacket = datagramPackets[cacheKey]

            if (packetBuffer == null || packetBuffer.size != requiredSize) {
                packetBuffer = ByteArray(requiredSize)
                packetBuffers[cacheKey] = packetBuffer
                datagramPacket = DatagramPacket(packetBuffer, requiredSize, address, port)
                datagramPackets[cacheKey] = datagramPacket
            } else {
                datagramPacket?.address = address
                datagramPacket?.port = port
            }

            packetBuffer[0] = 0x01 // WARLS protocol identifier
            packetBuffer[1] = timeoutSeconds

            var dst = 2
            for (i in 0 until count) {
                val src = i * 3
                packetBuffer[dst++] = i.toByte()
                val r = rgb[src]
                val g = rgb[src + 1]
                val b = rgb[src + 2]
                when (colorOrder.uppercase()) {
                    "GRB" -> { packetBuffer[dst++] = g; packetBuffer[dst++] = r; packetBuffer[dst++] = b }
                    "BGR" -> { packetBuffer[dst++] = b; packetBuffer[dst++] = g; packetBuffer[dst++] = r }
                    "BRG" -> { packetBuffer[dst++] = b; packetBuffer[dst++] = r; packetBuffer[dst++] = g }
                    "RBG" -> { packetBuffer[dst++] = r; packetBuffer[dst++] = b; packetBuffer[dst++] = g }
                    "GBR" -> { packetBuffer[dst++] = g; packetBuffer[dst++] = b; packetBuffer[dst++] = r }
                    else -> { packetBuffer[dst++] = r; packetBuffer[dst++] = g; packetBuffer[dst++] = b }
                }
            }

            datagramPacket?.let {
                it.length = requiredSize
                currentSocket.send(it)
            }
        } catch (e: SocketException) {
            handleSocketException(e, cleanIp)
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastErrorTime > 3000L) {
                Log.w(TAG, "Failed to send WARLS UDP frame to $cleanIp: ${e.message}")
                lastErrorTime = now
            }
        }
    }

    @Synchronized
    private fun ensureSocket(): DatagramSocket {
        var s = socket
        if (s == null || s.isClosed) {
            s = DatagramSocket().apply {
                soTimeout = 1000
                reuseAddress = true
            }
            socket = s
        }
        return s
    }

    @Synchronized
    private fun handleSocketException(e: SocketException, ip: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorTime > 3000L) {
            Log.w(TAG, "Socket error sending to $ip (recreating socket): ${e.message}")
            lastErrorTime = now
        }
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }

    @Synchronized
    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        addressMap.clear()
        packetBuffers.clear()
        datagramPackets.clear()
        sequenceMap.clear()
    }

    companion object {
        private const val TAG = "WledUdpSender"

        const val DDP_PORT = 4048
        const val DRGB_PORT = 21324

        const val DDP_HEADER_LEN = 10
        const val DDP_MAX_CHANNELS_PER_PACKET = 1440 // 480 RGB or 360 RGBW LEDs per packet

        const val DDP_FLAG_VER1 = 0x40
        const val DDP_FLAG_PUSH = 0x01
        const val DDP_TYPE_RGB = 0x01
        const val DDP_TYPE_RGBW = 0x02
        const val DDP_ID_DEFAULT = 0x01
    }
}
