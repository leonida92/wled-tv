package com.wled.tv.network

import android.util.Log
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

internal open class WledUdpSender {

    @Volatile
    private var socket: DatagramSocket? = null
    private val addressMap = ConcurrentHashMap<String, InetAddress>()
    private val packetBuffers = ConcurrentHashMap<String, ByteArray>()
    private val datagramPackets = ConcurrentHashMap<String, DatagramPacket>()
    private val sequenceMap = ConcurrentHashMap<String, Int>()
    private var lastErrorTime = 0L

    /**
     * Universal frame dispatcher for a configured WledDevice.
     * Returns the number of UDP datagrams successfully sent (0 on failure).
     */
    open fun sendDeviceFrame(
        device: WledDevice,
        wirePayload: ByteArray,
        ledCount: Int = device.totalLeds,
        timeoutSeconds: Byte = 2
    ): Int {
        return sendFrame(
            ip = device.ip,
            port = device.port,
            protocol = device.protocol,
            wirePayload = wirePayload,
            ledCount = ledCount,
            isRgbw = device.calibration.isRgbw,
            timeoutSeconds = timeoutSeconds
        )
    }

    /**
     * Universal frame dispatcher by protocol.
     * Returns the number of UDP datagrams successfully sent (0 on failure).
     */
    open fun sendFrame(
        ip: String,
        port: Int,
        protocol: StreamingProtocol,
        wirePayload: ByteArray,
        ledCount: Int,
        isRgbw: Boolean = false,
        timeoutSeconds: Byte = 2
    ): Int {
        return when (protocol) {
            StreamingProtocol.DDP -> sendDdpFrame(
                ip = ip,
                port = port,
                wirePayload = wirePayload,
                ledCount = ledCount,
                isRgbw = isRgbw
            )
            StreamingProtocol.DRGB -> sendDrgbFrame(
                ip = ip,
                port = port,
                timeoutSeconds = timeoutSeconds,
                wirePayload = wirePayload,
                ledCount = ledCount
            )
            StreamingProtocol.WARLS -> sendWarlsFrame(
                ip = ip,
                port = port,
                timeoutSeconds = timeoutSeconds,
                wirePayload = wirePayload,
                ledCount = ledCount
            )
        }
    }

    /**
     * Sends DDP (Distributed Display Protocol, UDP Port 4048) frames to WLED.
     * Features:
     *   - Multi-packet atomic synchronization: Push flag on final packet prevents visual tearing on long strips (>480 LEDs).
     *   - Native 4-channel RGBW support via header data type (0x01 RGB vs 0x02 RGBW).
     *   - Zero GC allocations in the 60Hz loop via pre-allocated datagram buffers and chunked System.arraycopy memory transfers.
     * Returns number of packets transmitted (0 on error).
     */
    fun sendDdpFrame(
        ip: String,
        port: Int = DDP_PORT,
        wirePayload: ByteArray,
        ledCount: Int,
        isRgbw: Boolean = false
    ): Int {
        val cleanIp = ip.trim()
        val bytesPerLed = if (isRgbw) 4 else 3
        val totalChannels = ledCount * bytesPerLed
        if (cleanIp.isBlank() || ledCount <= 0 || wirePayload.size < totalChannels) return 0

        try {
            val currentSocket = ensureSocket()

            var address = addressMap[cleanIp]
            if (address == null) {
                address = InetAddress.getByName(cleanIp)
                addressMap[cleanIp] = address
            }

            val packetCount = ((totalChannels - 1) / DDP_MAX_CHANNELS_PER_PACKET) + 1

            // Cycle sequence number per destination IP + Port (1..15)
            val seqKey = "$cleanIp:$port"
            var seq = (sequenceMap[seqKey] ?: 0) + 1
            if (seq > 15) seq = 1
            sequenceMap[seqKey] = seq

            var channelOffset = 0
            var remainingChannels = totalChannels

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

            var packetsSent = 0

            for (packetIndex in 0 until packetCount) {
                val isFinalPacket = (packetIndex == packetCount - 1)
                val chunkChannels = minOf(remainingChannels, DDP_MAX_CHANNELS_PER_PACKET)

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

                // 2. Pixel payload: Direct block memory copy
                System.arraycopy(wirePayload, channelOffset, packetBuffer, DDP_HEADER_LEN, chunkChannels)

                // 3. Transmit chunk
                datagramPacket?.let {
                    it.length = DDP_HEADER_LEN + chunkChannels
                    currentSocket.send(it)
                    packetsSent++
                }

                channelOffset += chunkChannels
                remainingChannels -= chunkChannels
            }
            return packetsSent
        } catch (e: SocketException) {
            handleSocketException(e, cleanIp)
            return 0
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastErrorTime > 3000L) {
                Log.w(TAG, "Failed to send DDP UDP frame to $cleanIp: ${e.message}")
                lastErrorTime = now
            }
            return 0
        }
    }

    /**
     * Sends raw DRGB (0x02) realtime packet directly to WLED.
     * Clamped to 490 LEDs (1472 bytes) to respect Ethernet MTU without fragmentation.
     * Returns 1 on success, 0 on failure.
     */
    fun sendDrgbFrame(
        ip: String,
        port: Int = DRGB_PORT,
        timeoutSeconds: Byte = 2,
        wirePayload: ByteArray,
        ledCount: Int
    ): Int {
        val cleanIp = ip.trim()
        val count = ledCount.coerceAtMost(MAX_DRGB_LEDS)
        val payloadBytes = count * 3
        if (cleanIp.isBlank() || count <= 0 || wirePayload.size < payloadBytes) return 0

        try {
            val currentSocket = ensureSocket()

            var address = addressMap[cleanIp]
            if (address == null) {
                address = InetAddress.getByName(cleanIp)
                addressMap[cleanIp] = address
            }

            val requiredSize = 2 + payloadBytes
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

            System.arraycopy(wirePayload, 0, packetBuffer, 2, payloadBytes)

            datagramPacket?.let {
                it.length = requiredSize
                currentSocket.send(it)
            }
            return 1
        } catch (e: SocketException) {
            handleSocketException(e, cleanIp)
            return 0
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastErrorTime > 3000L) {
                Log.w(TAG, "Failed to send DRGB UDP frame to $cleanIp: ${e.message}")
                lastErrorTime = now
            }
            return 0
        }
    }

    /**
     * Sends WARLS (0x01) realtime packets to WLED.
     * Accepts pre-ordered wire channels and writes WARLS index prefixes followed by 3 bytes from wirePayload.
     * WARLS format:
     *   Byte 0: 0x01 (WARLS protocol)
     *   Byte 1: timeout in seconds
     *   Byte 2..N: [Index, B1, B2, B3, Index, B1, B2, B3, ...]
     * Returns 1 on success, 0 on failure.
     */
    fun sendWarlsFrame(
        ip: String,
        port: Int = DRGB_PORT,
        timeoutSeconds: Byte = 2,
        wirePayload: ByteArray,
        ledCount: Int
    ): Int {
        val cleanIp = ip.trim()
        val count = ledCount.coerceAtMost(255)
        val payloadBytes = count * 3
        if (cleanIp.isBlank() || count <= 0 || wirePayload.size < payloadBytes) return 0

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
                packetBuffer[dst++] = wirePayload[src]
                packetBuffer[dst++] = wirePayload[src + 1]
                packetBuffer[dst++] = wirePayload[src + 2]
            }

            datagramPacket?.let {
                it.length = requiredSize
                currentSocket.send(it)
            }
            return 1
        } catch (e: SocketException) {
            handleSocketException(e, cleanIp)
            return 0
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastErrorTime > 3000L) {
                Log.w(TAG, "Failed to send WARLS UDP frame to $cleanIp: ${e.message}")
                lastErrorTime = now
            }
            return 0
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
    open fun close() {
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
        const val MAX_DRGB_LEDS = 490 // Max LEDs in 1 UDP packet (1472 bytes <= 1500 MTU)

        const val DDP_HEADER_LEN = 10
        const val DDP_MAX_CHANNELS_PER_PACKET = 1440 // 480 RGB or 360 RGBW LEDs per packet

        const val DDP_FLAG_VER1 = 0x40
        const val DDP_FLAG_PUSH = 0x01
        const val DDP_TYPE_RGB = 0x01
        const val DDP_TYPE_RGBW = 0x02
        const val DDP_ID_DEFAULT = 0x01
    }
}
