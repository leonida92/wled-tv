package com.wled.tv.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

class WledUdpSender {

    private var socket: DatagramSocket? = null
    private var packetBuffer = ByteArray(0)
    private var datagramPacket: DatagramPacket? = null
    private var cachedIp: String? = null
    private var cachedAddress: InetAddress? = null
    private var lastErrorTime = 0L

    /**
     * Sends raw DRGB (0x02) realtime packet directly to WLED.
     * DRGB format:
     *   Byte 0: 0x02 (DRGB protocol)
     *   Byte 1: timeout in seconds (1-255, e.g., 2)
     *   Byte 2..N: C0_0, C0_1, C0_2, C1_0, C1_1, C1_2, ... based on colorOrder
     *
     * @param ip Target WLED controller IP address
     * @param port Target UDP port (typically 21324)
     * @param timeoutSeconds Timeout in seconds before WLED reverts to previous effect
     * @param rgb RGB data [R0, G0, B0, R1, G1, B1, ...]
     * @param ledCount Number of LEDs to send
     * @param colorOrder Byte order: "RGB", "GRB", "BRG", "BGR", "RBG", "GBR"
     */
    fun sendDrgbFrame(
        ip: String,
        port: Int = 21324,
        timeoutSeconds: Byte = 2,
        rgb: ByteArray,
        ledCount: Int,
        colorOrder: String = "RGB"
    ) {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank() || ledCount <= 0 || rgb.size < ledCount * 3) return

        try {
            ensureSocket()

            if (cachedIp != cleanIp || cachedAddress == null) {
                cachedAddress = InetAddress.getByName(cleanIp)
                cachedIp = cleanIp
            }

            val requiredSize = 2 + ledCount * 3
            if (packetBuffer.size != requiredSize) {
                packetBuffer = ByteArray(requiredSize)
                datagramPacket = DatagramPacket(packetBuffer, requiredSize, cachedAddress, port)
            } else {
                datagramPacket?.address = cachedAddress
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
                socket?.send(it)
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

    private fun ensureSocket() {
        if (socket == null || socket?.isClosed == true) {
            socket = DatagramSocket().apply {
                soTimeout = 1000
                reuseAddress = true
            }
        }
    }

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

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        cachedAddress = null
        cachedIp = null
    }

    companion object {
        private const val TAG = "WledUdpSender"
    }
}
