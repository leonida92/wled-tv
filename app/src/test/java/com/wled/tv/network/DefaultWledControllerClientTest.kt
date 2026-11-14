package com.wled.tv.network

import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultWledControllerClientTest {

    private lateinit var fakeUdpSender: TestableUdpSender
    private lateinit var fakeHttpClient: TestableHttpClient
    private lateinit var client: DefaultWledControllerClient

    private class TestableUdpSender : WledUdpSender() {
        val sentFrames = mutableListOf<SentFrame>()
        var closeCalled = false

        data class SentFrame(
            val device: WledDevice,
            val wirePayload: ByteArray,
            val ledCount: Int,
            val timeoutSeconds: Byte
        ) {
            val rgb: ByteArray
                get() = wirePayload
        }

        override fun sendDeviceFrame(
            device: WledDevice,
            wirePayload: ByteArray,
            ledCount: Int,
            timeoutSeconds: Byte
        ): Int {
            sentFrames.add(SentFrame(device, wirePayload.copyOf(), ledCount, timeoutSeconds))
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
        val checkConnectionCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val fetchDeviceInfoCalls = mutableListOf<String>()

        var checkConnectionResult = true
        val checkConnectionMap = mutableMapOf<String, Boolean>()
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
            return checkConnectionMap[ip] ?: checkConnectionResult
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
        maxBrightness: Int = 200,
        isRgbw: Boolean = false
    ): WledDevice {
        return WledDevice(
            id = id,
            name = "Test Device $id",
            ip = ip,
            port = port,
            protocol = protocol,
            type = DeviceType.PERIMETER,
            perimeter = PerimeterConfig(topLeds = totalLeds, rightLeds = 0, bottomLeds = 0, leftLeds = 0),
            calibration = ColorCalibration(maxBrightness = maxBrightness),
            isRgbw = isRgbw
        )
    }

    @Before
    fun setUp() {
        fakeUdpSender = TestableUdpSender()
        fakeHttpClient = TestableHttpClient()
        client = DefaultWledControllerClient(
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        DefaultWledControllerClient.setInstanceForTesting(null)
    }

    @Test
    fun sendFrame_delegatesDirectlyToUdpSender() {
        val device = createDevice(totalLeds = 12)
        val wirePayload = ByteArray(36) { it.toByte() }

        val packetsSent = client.sendFrame(device, wirePayload, 12)

        assertEquals(1, packetsSent)
        assertEquals(1, fakeUdpSender.sentFrames.size)
        val frame = fakeUdpSender.sentFrames[0]
        assertEquals(device, frame.device)
        assertArrayEquals(wirePayload, frame.wirePayload)
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
    fun blackoutFleet_sendsThreeZeroedBurstsPerDevice_withChannelWidthAwarePayloads() = runTest {
        val dev1Rgb = createDevice(id = "dev1", ip = "192.168.1.51", totalLeds = 4, isRgbw = false)
        val dev2Rgb = createDevice(id = "dev2", ip = "192.168.1.52", totalLeds = 6, isRgbw = false)
        val dev3Rgbw = createDevice(id = "dev3", ip = "192.168.1.53", totalLeds = 5, isRgbw = true)
        val devices = listOf(dev1Rgb, dev2Rgb, dev3Rgbw)

        client.blackoutFleet(devices, sendPowerOff = false)

        // 3 bursts * 3 devices = 9 frames
        assertEquals(9, fakeUdpSender.sentFrames.size)

        val dev1Frames = fakeUdpSender.sentFrames.filter { it.device.id == "dev1" }
        val dev2Frames = fakeUdpSender.sentFrames.filter { it.device.id == "dev2" }
        val dev3Frames = fakeUdpSender.sentFrames.filter { it.device.id == "dev3" }

        assertEquals(3, dev1Frames.size)
        assertEquals(3, dev2Frames.size)
        assertEquals(3, dev3Frames.size)

        // RGB: 4 LEDs * 3 channels = 12 bytes
        for (frame in dev1Frames) {
            assertEquals(12, frame.wirePayload.size)
            assertTrue(frame.wirePayload.all { it == 0.toByte() })
            assertEquals(4, frame.ledCount)
        }

        // RGB: 6 LEDs * 3 channels = 18 bytes
        for (frame in dev2Frames) {
            assertEquals(18, frame.wirePayload.size)
            assertTrue(frame.wirePayload.all { it == 0.toByte() })
            assertEquals(6, frame.ledCount)
        }

        // RGBW: 5 LEDs * 4 channels = 20 bytes
        for (frame in dev3Frames) {
            assertEquals(20, frame.wirePayload.size)
            assertTrue(frame.wirePayload.all { it == 0.toByte() })
            assertEquals(5, frame.ledCount)
        }

        // Sequential bursts grouping: dev1, dev1, dev1, dev2, dev2, dev2, dev3, dev3, dev3
        assertEquals("dev1", fakeUdpSender.sentFrames[0].device.id)
        assertEquals("dev1", fakeUdpSender.sentFrames[1].device.id)
        assertEquals("dev1", fakeUdpSender.sentFrames[2].device.id)
        assertEquals("dev2", fakeUdpSender.sentFrames[3].device.id)
        assertEquals("dev2", fakeUdpSender.sentFrames[4].device.id)
        assertEquals("dev2", fakeUdpSender.sentFrames[5].device.id)
        assertEquals("dev3", fakeUdpSender.sentFrames[6].device.id)
        assertEquals("dev3", fakeUdpSender.sentFrames[7].device.id)
        assertEquals("dev3", fakeUdpSender.sentFrames[8].device.id)

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
    fun flashDevice_rgbDevice_sendsFullWhiteThenFullBlackFrames() = runTest {
        val device = createDevice(totalLeds = 5, isRgbw = false)

        client.flashDevice(device, durationMs = 10L)

        assertEquals(2, fakeUdpSender.sentFrames.size)

        val whiteFrame = fakeUdpSender.sentFrames[0]
        assertEquals(device, whiteFrame.device)
        assertEquals(15, whiteFrame.wirePayload.size)
        assertTrue(whiteFrame.wirePayload.all { it == 255.toByte() })
        assertEquals(5, whiteFrame.ledCount)

        val blackFrame = fakeUdpSender.sentFrames[1]
        assertEquals(device, blackFrame.device)
        assertEquals(15, blackFrame.wirePayload.size)
        assertTrue(blackFrame.wirePayload.all { it == 0.toByte() })
        assertEquals(5, blackFrame.ledCount)
    }

    @Test
    fun flashDevice_rgbwDevice_sendsFourChannelWhiteThenBlackFrames() = runTest {
        val device = createDevice(totalLeds = 5, isRgbw = true)

        client.flashDevice(device, durationMs = 10L)

        assertEquals(2, fakeUdpSender.sentFrames.size)

        val whiteFrame = fakeUdpSender.sentFrames[0]
        assertEquals(device, whiteFrame.device)
        // 5 LEDs * 4 channels = 20 bytes
        assertEquals(20, whiteFrame.wirePayload.size)
        assertTrue(whiteFrame.wirePayload.all { it == 255.toByte() })
        assertEquals(5, whiteFrame.ledCount)

        val blackFrame = fakeUdpSender.sentFrames[1]
        assertEquals(device, blackFrame.device)
        assertEquals(20, blackFrame.wirePayload.size)
        assertTrue(blackFrame.wirePayload.all { it == 0.toByte() })
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
        val wirePayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)

        fake.sendFrame(device, wirePayload, 3)
        assertEquals(1, fake.sentFrames.size)
        assertEquals(device, fake.sentFrames[0].device)
        assertArrayEquals(wirePayload, fake.sentFrames[0].wirePayload)
        assertEquals(3, fake.sentFrames[0].ledCount)

        fake.wakeAndSetBrightness(device)
        assertEquals(true, fake.powerState["192.168.1.90"])

        fake.turnOff("192.168.1.90")
        assertEquals(false, fake.powerState["192.168.1.90"])

        fake.blackoutFleet(listOf(device), sendPowerOff = true)
        assertEquals(1, fake.blackoutCalls)
        assertEquals(false, fake.powerState["192.168.1.90"])

        // Verify sequential bursts grouping for multiple devices
        val fakeDev1 = createDevice(id = "fd1", ip = "192.168.1.101", totalLeds = 4)
        val fakeDev2 = createDevice(id = "fd2", ip = "192.168.1.102", totalLeds = 4)
        val multiFake = FakeWledControllerClient()
        multiFake.blackoutFleet(listOf(fakeDev1, fakeDev2), sendPowerOff = false)
        assertEquals(6, multiFake.sentFrames.size)
        assertEquals("fd1", multiFake.sentFrames[0].device.id)
        assertEquals("fd1", multiFake.sentFrames[1].device.id)
        assertEquals("fd1", multiFake.sentFrames[2].device.id)
        assertEquals("fd2", multiFake.sentFrames[3].device.id)
        assertEquals("fd2", multiFake.sentFrames[4].device.id)
        assertEquals("fd2", multiFake.sentFrames[5].device.id)

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

    @Test
    fun fakeWledControllerClient_rgbwFlashAndBlackout_sizesPayloadsProperly() = runTest {
        val fake = FakeWledControllerClient()
        val rgbwDevice = createDevice(id = "rgbw-dev", totalLeds = 4, isRgbw = true)

        fake.flashDevice(rgbwDevice)
        assertEquals(2, fake.sentFrames.size)
        val whiteFrame = fake.sentFrames[0]
        val blackFrame = fake.sentFrames[1]

        assertEquals(16, whiteFrame.wirePayload.size)
        assertTrue(whiteFrame.wirePayload.all { it == 255.toByte() })
        assertEquals(16, blackFrame.wirePayload.size)
        assertTrue(blackFrame.wirePayload.all { it == 0.toByte() })

        fake.blackoutFleet(listOf(rgbwDevice), sendPowerOff = false)
        assertEquals(5, fake.sentFrames.size)
        val blackoutFrame = fake.sentFrames[2]
        assertEquals(16, blackoutFrame.wirePayload.size)
        assertTrue(blackoutFrame.wirePayload.all { it == 0.toByte() })
    }

    @Test
    fun suspendFunctions_runMainSafelyOffCallingThread() = runTest {
        val dev = createDevice(id = "dev-main", totalLeds = 3)
        client.blackoutFleet(listOf(dev), sendPowerOff = false)
        client.flashDevice(dev, durationMs = 1L)
        assertEquals(5, fakeUdpSender.sentFrames.size)
    }

    @Test
    fun discoverDevices_withoutProvider_returnsEmptyFlow() = runTest {
        val collected = client.discoverDevices().toList()
        assertTrue(collected.isEmpty())
    }

    @Test
    fun discoverDevices_delegatesToInjectedDiscoveryProvider() = runTest {
        val fakeDevice = DiscoveredWled("wled-livingroom", "192.168.1.150", 80)
        val testProvider = object : WledDiscoveryProvider {
            override fun discover(): Flow<DiscoveredWled> = flowOf(fakeDevice)
        }
        val clientWithDiscovery = DefaultWledControllerClient(
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
            discoveryProvider = testProvider
        )

        val results = clientWithDiscovery.discoverDevices().toList()
        assertEquals(1, results.size)
        assertEquals(fakeDevice, results[0])
    }

    @Test
    fun setDiscoveryProvider_updatesActiveProvider() = runTest {
        val fakeDevice = DiscoveredWled("wled-kitchen", "192.168.1.151", 80)
        val testProvider = object : WledDiscoveryProvider {
            override fun discover(): Flow<DiscoveredWled> = flowOf(fakeDevice)
        }

        assertEquals(0, client.discoverDevices().toList().size)

        client.setDiscoveryProvider(testProvider)
        val results = client.discoverDevices().toList()
        assertEquals(1, results.size)
        assertEquals(fakeDevice, results[0])
    }

    @Test
    fun fakeWledControllerClient_discoverDevices_emitsFixtures() = runTest {
        val fake = FakeWledControllerClient()
        val emitted = mutableListOf<DiscoveredWled>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.discoverDevices().collect { emitted.add(it) }
        }

        val dev1 = DiscoveredWled("wled-1", "192.168.1.201", 80)
        val dev2 = DiscoveredWled("wled-2", "192.168.1.202", 80)

        fake.discoveryFlow.emit(dev1)
        fake.discoveryFlow.emit(dev2)

        assertEquals(listOf(dev1, dev2), emitted)
        collectJob.cancel()
    }

    @Test
    fun checkConnection_updatesReachabilityFlowEntryAndTrimsIp() = runTest {
        fakeHttpClient.checkConnectionMap["192.168.1.110"] = true
        assertTrue(client.checkConnection(" 192.168.1.110 "))
        assertEquals(mapOf("192.168.1.110" to true), client.reachabilityFlow.value)

        fakeHttpClient.checkConnectionMap["192.168.1.110"] = false
        assertFalse(client.checkConnection("192.168.1.110"))
        assertEquals(mapOf("192.168.1.110" to false), client.reachabilityFlow.value)
    }

    @Test
    fun probeReachability_deduplicatesUniqueIps_andPrunesRemovedIps() = runTest {
        val dev1 = createDevice(id = "d1", ip = "192.168.1.10")
        val dev2 = createDevice(id = "d2", ip = " 192.168.1.10 ")
        val dev3 = createDevice(id = "d3", ip = "192.168.1.20")
        val blankDev = createDevice(id = "d4", ip = "   ")

        fakeHttpClient.checkConnectionMap["192.168.1.10"] = true
        fakeHttpClient.checkConnectionMap["192.168.1.20"] = false

        val firstSnapshot = client.probeReachability(listOf(dev1, dev2, dev3, blankDev))

        assertEquals(
            mapOf("192.168.1.10" to true, "192.168.1.20" to false),
            firstSnapshot
        )
        assertEquals(firstSnapshot, client.reachabilityFlow.value)
        // Unique IPs only: 192.168.1.10 and 192.168.1.20
        assertEquals(2, fakeHttpClient.checkConnectionCalls.size)

        // Remove dev3 and probe again: 192.168.1.20 must be pruned from reachabilityFlow
        val prunedSnapshot = client.probeReachability(listOf(dev1))
        assertEquals(mapOf("192.168.1.10" to true), prunedSnapshot)
        assertEquals(mapOf("192.168.1.10" to true), client.reachabilityFlow.value)

        // Empty device list clears reachabilityFlow
        val emptySnapshot = client.probeReachability(emptyList())
        assertTrue(emptySnapshot.isEmpty())
        assertTrue(client.reachabilityFlow.value.isEmpty())
    }

    @Test
    fun reachabilityFlow_isInstanceScopedAcrossClients() = runTest {
        val otherHttpClient = TestableHttpClient().apply { checkConnectionResult = false }
        val otherClient = DefaultWledControllerClient(
            udpSender = TestableUdpSender(),
            httpClient = otherHttpClient
        )

        fakeHttpClient.checkConnectionResult = true
        client.checkConnection("192.168.1.55")
        otherClient.checkConnection("192.168.1.66")

        assertEquals(mapOf("192.168.1.55" to true), client.reachabilityFlow.value)
        assertEquals(mapOf("192.168.1.66" to false), otherClient.reachabilityFlow.value)
    }

    @Test
    fun observeReachability_pollsOnInterval_reactsToIpChanges_andStopsOnCancel() = runTest {
        val dev1 = createDevice(id = "d1", ip = "192.168.1.30")
        val dev2 = createDevice(id = "d2", ip = "192.168.1.31")
        val devicesFlow = MutableStateFlow(listOf(dev1))
        val snapshots = mutableListOf<Map<String, Boolean>>()

        fakeHttpClient.checkConnectionMap[dev1.ip] = true
        fakeHttpClient.checkConnectionMap[dev2.ip] = false

        val pollJob = client.observeReachability(devicesFlow, intervalMs = 5000L)
            .onEach { snapshots.add(it) }
            .launchIn(this)

        runCurrent()
        assertEquals(1, snapshots.size)
        assertEquals(mapOf(dev1.ip to true), client.reachabilityFlow.value)

        // Non-IP field change (e.g. name) should NOT trigger extra immediate probe
        devicesFlow.value = listOf(dev1.copy(name = "Renamed"))
        runCurrent()
        assertEquals(1, snapshots.size)

        // Advance past polling interval -> triggers second poll
        fakeHttpClient.checkConnectionMap[dev1.ip] = false
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(2, snapshots.size)
        assertEquals(mapOf(dev1.ip to false), client.reachabilityFlow.value)

        // Replace device list -> immediately probes new IP and prunes old IP
        devicesFlow.value = listOf(dev2)
        runCurrent()
        assertEquals(3, snapshots.size)
        assertEquals(mapOf(dev2.ip to false), client.reachabilityFlow.value)

        pollJob.cancel()
        val countAfterCancel = fakeHttpClient.checkConnectionCalls.size
        advanceTimeBy(15000L)
        runCurrent()
        assertEquals(countAfterCancel, fakeHttpClient.checkConnectionCalls.size)
    }

    @Test
    fun singleton_collapsesInitializeAndInitDiscoveryMethods() {
        val companionMethods = DefaultWledControllerClient.Companion::class.java.declaredMethods.map { it.name }
        assertFalse("initialize must be collapsed into getInstance(context)", companionMethods.contains("initialize"))
        assertFalse("initDiscovery must be collapsed into getInstance(context)", companionMethods.contains("initDiscovery"))
    }
}
