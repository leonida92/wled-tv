package com.wled.tv.network

import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DefaultWledControllerClientTest {

    private lateinit var fakeUdpSender: TestableUdpSender
    private lateinit var fakeHttpClient: TestableHttpClient
    private lateinit var client: DefaultWledControllerClient

    private class TestableUdpSender : WledUdpSender() {
        val sentFrames = mutableListOf<SentFrame>()
        var closeCalled = false

        data class SentFrame(
            val device: WledDevice,
            val rgb: ByteArray,
            val ledCount: Int,
            val timeoutSeconds: Byte
        )

        override fun sendDeviceFrame(
            device: WledDevice,
            rgb: ByteArray,
            ledCount: Int,
            timeoutSeconds: Byte
        ): Int {
            sentFrames.add(SentFrame(device, rgb.copyOf(), ledCount, timeoutSeconds))
            return 1
        }

        override fun close() {
            closeCalled = true
            super.close()
        }
    }

    private class TestableHttpClient : WledHttpClient() {
        val wakeCalls = mutableListOf<Pair<String, Int>>()
        val turnOffCalls = mutableListOf<String>()
        val checkConnectionCalls = mutableListOf<String>()
        val fetchDeviceInfoCalls = mutableListOf<String>()

        var checkConnectionResult = true
        var fetchDeviceInfoResult: WledDeviceInfo? = null
        var turnOffDelayMs = 0L
        var turnOffException: Exception? = null

        override suspend fun wakeAndSetBrightness(ip: String, brightness: Int): Boolean {
            wakeCalls.add(ip to brightness)
            return true
        }

        override suspend fun turnOff(ip: String): Boolean {
            turnOffCalls.add(ip)
            turnOffException?.let { throw it }
            if (turnOffDelayMs > 0) {
                delay(turnOffDelayMs)
            }
            return true
        }

        override suspend fun checkConnection(ip: String): Boolean {
            checkConnectionCalls.add(ip)
            return checkConnectionResult
        }

        override suspend fun fetchDeviceInfo(ip: String): WledDeviceInfo? {
            fetchDeviceInfoCalls.add(ip)
            return fetchDeviceInfoResult
        }
    }

    private fun createDevice(
        id: String = "dev1",
        ip: String = "192.168.1.100",
        port: Int = 4048,
        protocol: StreamingProtocol = StreamingProtocol.DDP,
        totalLeds: Int = 10,
        maxBrightness: Int = 200
    ): WledDevice {
        return WledDevice(
            id = id,
            name = "Test Device $id",
            ip = ip,
            port = port,
            protocol = protocol,
            type = DeviceType.PERIMETER,
            perimeter = PerimeterConfig(topLeds = totalLeds, rightLeds = 0, bottomLeds = 0, leftLeds = 0),
            calibration = ColorCalibration(maxBrightness = maxBrightness)
        )
    }

    @Before
    fun setUp() {
        fakeUdpSender = TestableUdpSender()
        fakeHttpClient = TestableHttpClient()
        client = DefaultWledControllerClient(
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient
        )
    }

    @After
    fun tearDown() {
        DefaultWledControllerClient.setInstanceForTesting(null)
    }

    @Test
    fun sendFrame_delegatesDirectlyToUdpSender() {
        val device = createDevice(totalLeds = 12)
        val rgb = ByteArray(36) { it.toByte() }

        val packetsSent = client.sendFrame(device, rgb, 12)

        assertEquals(1, packetsSent)
        assertEquals(1, fakeUdpSender.sentFrames.size)
        val frame = fakeUdpSender.sentFrames[0]
        assertEquals(device, frame.device)
        assertArrayEquals(rgb, frame.rgb)
        assertEquals(12, frame.ledCount)
    }

    @Test
    fun wakeAndSetBrightness_withDevice_delegatesToHttpClientWithCalibrationBrightness() = runTest {
        val device = createDevice(ip = "192.168.1.101", maxBrightness = 185)

        val result = client.wakeAndSetBrightness(device)

        assertTrue(result)
        assertEquals(1, fakeHttpClient.wakeCalls.size)
        assertEquals("192.168.1.101" to 185, fakeHttpClient.wakeCalls[0])
    }

    @Test
    fun wakeAndSetBrightness_withIpAndBrightness_delegatesToHttpClient() = runTest {
        val result = client.wakeAndSetBrightness("192.168.1.102", 220)

        assertTrue(result)
        assertEquals(1, fakeHttpClient.wakeCalls.size)
        assertEquals("192.168.1.102" to 220, fakeHttpClient.wakeCalls[0])
    }

    @Test
    fun turnOff_delegatesToHttpClient() = runTest {
        val result = client.turnOff("192.168.1.103")

        assertTrue(result)
        assertEquals(1, fakeHttpClient.turnOffCalls.size)
        assertEquals("192.168.1.103", fakeHttpClient.turnOffCalls[0])
    }

    @Test
    fun checkConnection_delegatesToHttpClient() = runTest {
        fakeHttpClient.checkConnectionResult = true
        assertTrue(client.checkConnection("192.168.1.104"))

        fakeHttpClient.checkConnectionResult = false
        assertFalse(client.checkConnection("192.168.1.104"))

        assertEquals(2, fakeHttpClient.checkConnectionCalls.size)
    }

    @Test
    fun fetchDeviceInfo_delegatesToHttpClient() = runTest {
        val expectedInfo = WledDeviceInfo(
            name = "Living Room TV",
            version = "0.14.4",
            ledCount = 120,
            isRgbw = true,
            maxPowerMa = 1500
        )
        fakeHttpClient.fetchDeviceInfoResult = expectedInfo

        val info = client.fetchDeviceInfo("192.168.1.105")

        assertEquals(expectedInfo, info)
        assertEquals(1, fakeHttpClient.fetchDeviceInfoCalls.size)
        assertEquals("192.168.1.105", fakeHttpClient.fetchDeviceInfoCalls[0])
    }

    @Test
    fun blackoutFleet_sendsThreeZeroedBurstsPerDevice() = runTest {
        val dev1 = createDevice(id = "dev1", ip = "192.168.1.51", totalLeds = 4)
        val dev2 = createDevice(id = "dev2", ip = "192.168.1.52", totalLeds = 6)
        val devices = listOf(dev1, dev2)

        client.blackoutFleet(devices, sendPowerOff = false)

        // 3 bursts * 2 devices = 6 frames
        assertEquals(6, fakeUdpSender.sentFrames.size)

        val dev1Frames = fakeUdpSender.sentFrames.filter { it.device.id == "dev1" }
        val dev2Frames = fakeUdpSender.sentFrames.filter { it.device.id == "dev2" }

        assertEquals(3, dev1Frames.size)
        assertEquals(3, dev2Frames.size)

        for (frame in dev1Frames) {
            assertEquals(12, frame.rgb.size)
            assertTrue(frame.rgb.all { it == 0.toByte() })
            assertEquals(4, frame.ledCount)
        }

        for (frame in dev2Frames) {
            assertEquals(18, frame.rgb.size)
            assertTrue(frame.rgb.all { it == 0.toByte() })
            assertEquals(6, frame.ledCount)
        }

        // sendPowerOff = false should not invoke turnOff
        assertTrue(fakeHttpClient.turnOffCalls.isEmpty())
    }

    @Test
    fun blackoutFleet_withSendPowerOff_concurrentlyDispatchesTurnOff() = runTest {
        val dev1 = createDevice(id = "dev1", ip = "192.168.1.61")
        val dev2 = createDevice(id = "dev2", ip = "192.168.1.62")
        val devices = listOf(dev1, dev2)

        client.blackoutFleet(devices, sendPowerOff = true)

        assertEquals(6, fakeUdpSender.sentFrames.size)
        assertEquals(2, fakeHttpClient.turnOffCalls.size)
        assertTrue(fakeHttpClient.turnOffCalls.contains("192.168.1.61"))
        assertTrue(fakeHttpClient.turnOffCalls.contains("192.168.1.62"))
    }

    @Test
    fun blackoutFleet_deduplicatesIpAddressesForPowerOff() = runTest {
        // Two fixtures sharing the same WLED controller IP (e.g., perimeter + ambient lamp segment)
        val dev1 = createDevice(id = "dev1", ip = "192.168.1.70", totalLeds = 10)
        val dev2 = createDevice(id = "dev2", ip = "192.168.1.70", totalLeds = 20)
        val devices = listOf(dev1, dev2)

        client.blackoutFleet(devices, sendPowerOff = true)

        // UDP bursts still sent to both devices
        assertEquals(6, fakeUdpSender.sentFrames.size)
        // HTTP turnOff called exactly once for the shared IP
        assertEquals(1, fakeHttpClient.turnOffCalls.size)
        assertEquals("192.168.1.70", fakeHttpClient.turnOffCalls[0])
    }

    @Test
    fun blackoutFleet_handlesTurnOffExceptionSafelyWithoutThrowing() = runTest {
        val dev = createDevice(ip = "192.168.1.80")
        fakeHttpClient.turnOffException = RuntimeException("Connection refused")

        // Should complete gracefully without propagating exception
        client.blackoutFleet(listOf(dev), sendPowerOff = true)

        assertEquals(3, fakeUdpSender.sentFrames.size)
        assertEquals(1, fakeHttpClient.turnOffCalls.size)
    }

    @Test
    fun flashDevice_sendsFullWhiteThenFullBlackFrames() = runTest {
        val device = createDevice(totalLeds = 5)

        client.flashDevice(device, durationMs = 10L)

        assertEquals(2, fakeUdpSender.sentFrames.size)

        val whiteFrame = fakeUdpSender.sentFrames[0]
        assertEquals(device, whiteFrame.device)
        assertEquals(15, whiteFrame.rgb.size)
        assertTrue(whiteFrame.rgb.all { it == 255.toByte() })
        assertEquals(5, whiteFrame.ledCount)

        val blackFrame = fakeUdpSender.sentFrames[1]
        assertEquals(device, blackFrame.device)
        assertEquals(15, blackFrame.rgb.size)
        assertTrue(blackFrame.rgb.all { it == 0.toByte() })
        assertEquals(5, blackFrame.ledCount)
    }

    @Test
    fun flashDevice_withZeroLeds_doesNotSend() = runTest {
        val device = createDevice(totalLeds = 0)

        client.flashDevice(device, durationMs = 10L)

        assertTrue(fakeUdpSender.sentFrames.isEmpty())
    }

    @Test
    fun close_closesUnderlyingUdpSender() {
        assertFalse(fakeUdpSender.closeCalled)
        client.close()
        assertTrue(fakeUdpSender.closeCalled)
    }

    @Test
    fun singletonInstance_getInstanceAndSetInstanceForTesting() {
        val defaultClient1 = DefaultWledControllerClient.getInstance()
        val defaultClient2 = DefaultWledControllerClient.getInstance()
        assertSame("getInstance() must return singleton", defaultClient1, defaultClient2)

        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)
        assertSame("getInstance() must return injected fake", fakeClient, DefaultWledControllerClient.getInstance())

        DefaultWledControllerClient.setInstanceForTesting(null)
        val defaultClient3 = DefaultWledControllerClient.getInstance()
        assertNotSame("getInstance() must recreate instance after reset", fakeClient, defaultClient3)
    }

    @Test
    fun fakeWledControllerClient_recordsOperationsCorrectly() = runTest {
        val fake = FakeWledControllerClient()
        val device = createDevice(ip = "192.168.1.90", totalLeds = 3)
        val rgb = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)

        fake.sendFrame(device, rgb, 3)
        assertEquals(1, fake.sentFrames.size)
        assertEquals(device, fake.sentFrames[0].device)
        assertArrayEquals(rgb, fake.sentFrames[0].rgb)
        assertEquals(3, fake.sentFrames[0].ledCount)

        fake.wakeAndSetBrightness(device)
        assertEquals(true, fake.powerState["192.168.1.90"])

        fake.turnOff("192.168.1.90")
        assertEquals(false, fake.powerState["192.168.1.90"])

        fake.blackoutFleet(listOf(device), sendPowerOff = true)
        assertEquals(1, fake.blackoutCalls)
        assertEquals(false, fake.powerState["192.168.1.90"])

        fake.flashDevice(device)
        assertEquals(1, fake.flashedDevices.size)
        assertEquals(device, fake.flashedDevices[0])

        fake.connectionCheckResult = false
        assertFalse(fake.checkConnection("192.168.1.90"))

        val mockInfo = WledDeviceInfo("Mock", "0.14", 3, false, 500)
        fake.deviceInfoResult = mockInfo
        assertEquals(mockInfo, fake.fetchDeviceInfo("192.168.1.90"))

        fake.close()
        assertTrue(fake.isClosed)

        fake.clear()
        assertEquals(0, fake.sentFrames.size)
        assertEquals(0, fake.powerState.size)
        assertEquals(0, fake.blackoutCalls)
        assertFalse(fake.isClosed)
    }
}
