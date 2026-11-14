# Issue 03: AmbientCaptureService Decomposition

Status: resolved

## Description

1. Extract `ScreenCaptureSession`:
   - Handles `MediaProjection`, `VirtualDisplay`, `ImageReader`, background thread handler, resolution re-initialization, and frame dispatch to `AmbientStreamingEngine.onFrameAvailable`.
2. Extract `SamplingOverlayManager`:
   - Handles `WindowManager` floating overlay lifecycle, window parameters, broadcast filters, and HUD data sampling (`latestSystemStatsString`, `updateOverlayData`).
3. Extract `DisplayPowerMonitor`:
   - Handles `DisplayManager.DisplayListener`, `PowerManager.isInteractive`, screen-off blackout coordination, and screen-on wake restore.
4. Refactor `AmbientCaptureService`:
   - Remove double-spacing formatting artifacts.
   - Compose the three collaborators cleanly.
   - Focus strictly on foreground service lifecycle, notification updates, and wake/wifi locks.
5. Update `AmbientCaptureServiceAdapterTest.kt` to verify collaborator orchestration.

## Answer

`AmbientCaptureService.kt` has been decomposed from a 3,400-line double-spaced monolith into a focused, single-spaced foreground service adapter (~350 lines) composing three dedicated collaborators in `com.wled.tv.service`:

1. [`ScreenCaptureSession`](file:///c:/Users/user/Desktop/git/wled-tv/app/src/main/java/com/wled/tv/service/ScreenCaptureSession.kt):
   - Encapsulates `MediaProjection`, `VirtualDisplay`, `ImageReader`, `HandlerThread("WledCaptureThread")`, and the background capture `Handler`.
   - Dispatches incoming image planes to `AmbientStreamingEngine.onFrameAvailable(buffer, width, height, rowStride, pixelStride)`.
   - Drives the cadence ticker loop (`streamingTickerRunnable`) invoking `engine.tickStreaming()`.
   - Exposes clean lifecycle methods: `start(width, height)`, `reinitResolution(width, height)`, `pauseStreaming()`, `resumeStreaming()`, `stop()`, and `release()`.

2. [`SamplingOverlayManager`](file:///c:/Users/user/Desktop/git/wled-tv/app/src/main/java/com/wled/tv/service/SamplingOverlayManager.kt):
   - Encapsulates `WindowManager` floating HUD overlay view (`SamplingOverlayView`), layout parameters (`TYPE_APPLICATION_OVERLAY`), and visibility state.
   - Manages sampling cadence of `SystemStatsCollector` (1-second cadence) and pushes updates to `SamplingOverlayView.updateFromEngine()`.
   - Exposes `isOverlayVisible: Boolean`, `show()`, `hide()`, `toggle()`, `update()`, and `release()`.

3. [`DisplayPowerMonitor`](file:///c:/Users/user/Desktop/git/wled-tv/app/src/main/java/com/wled/tv/service/DisplayPowerMonitor.kt):
   - Encapsulates `DisplayManager.DisplayListener` (`STATE_OFF`, `STATE_DOZE`, `STATE_ON`), `PowerManager` interactive state (`powerManager.isInteractive`), and screen broadcasts (`ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON`, `ACTION_DREAMING_STARTED`, `ACTION_DEVICE_IDLE_MODE_CHANGED`).
   - Dispatches idempotent callbacks `onScreenOff()` and `onScreenOn()`.
   - Exposes `start()`, `stop()`, and `isInteractive: Boolean`.

4. [`AmbientCaptureService`](file:///c:/Users/user/Desktop/git/wled-tv/app/src/main/java/com/wled/tv/service/AmbientCaptureService.kt):
   - Eliminated all double-spacing artifacts into clean, single-spaced Kotlin.
   - Composes `ScreenCaptureSession`, `SamplingOverlayManager`, and `DisplayPowerMonitor`.
   - Retains foreground service notification lifecycle (`startForeground`), `PowerManager.WakeLock`, `WifiManager.WifiLock`, connectivity callback, and `audioManager` DRM detection (`isAudioActive = { isAudioActive() }`).
   - Maintains 100% backward compatibility for all companion object constants, state flows, and static test helpers (`CHANNEL_ID`, `NOTIFICATION_ID`, `ACTION_*`, `EXTRA_*`, `isRunning`, `activeEngine`, `isOverlayShowing`, `setActiveEngineForTesting`).

5. Verification:
   - Added unit tests in [`AmbientCaptureServiceAdapterTest`](file:///c:/Users/user/Desktop/git/wled-tv/app/src/test/java/com/wled/tv/service/AmbientCaptureServiceAdapterTest.kt) verifying DRM blackout audio active transitions, display power state callbacks and idempotency, overlay manager state reporting, and service adapter behaviors.
   - All 162 unit tests across the test suite pass cleanly (`gradlew.bat testDebugUnitTest`).
