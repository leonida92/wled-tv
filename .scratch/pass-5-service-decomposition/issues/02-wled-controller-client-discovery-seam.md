# Issue 02: WledControllerClient Discovery Seam

Status: resolved

## Description

1. Define `WledDiscoveryProvider` interface in `com.wled.tv.network`:
   - `fun discover(): Flow<DiscoveredWled>`
2. Implement `NsdDiscoveryProvider` in `com.wled.tv.network` wrapping Android's `NsdManager` DNS-SD resolution queue.
3. Update `WledControllerClient`:
   - Add `fun discoverDevices(): Flow<DiscoveredWled>`
4. Update `DefaultWledControllerClient`:
   - Inject or lazily provide `WledDiscoveryProvider` using Application Context.
5. Update `FakeWledControllerClient`:
   - Add controllable `val discoveryFlow = MutableSharedFlow<DiscoveredWled>()` and verify discovery calls in JVM tests.
6. Unit tests in `DefaultWledControllerClientTest.kt`.

## Answer

Implemented the discovery seam connecting `WledControllerClient` to network discovery:

1. **`WledDiscoveryProvider` & `DiscoveredWled`**:
   - Defined `WledDiscoveryProvider` interface in `app/src/main/java/com/wled/tv/network/WledDiscoveryProvider.kt` with `fun discover(): Flow<DiscoveredWled>`.
   - Maintained `DiscoveredWled` data class in the `com.wled.tv.network` package for cross-layer compatibility.

2. **`NsdDiscoveryProvider` & `WledDiscovery` adapter**:
   - Implemented `NsdDiscoveryProvider` in `app/src/main/java/com/wled/tv/network/NsdDiscoveryProvider.kt`, encapsulating Android `NsdManager` DNS-SD discovery (`_http._tcp.`) and sequential resolution Channel to avoid error 3 (`FAILURE_ALREADY_ACTIVE`).
   - Refactored `WledDiscovery.kt` to delegate to `WledDiscoveryProvider` (`NsdDiscoveryProvider`) for backward compatibility with existing callers.

3. **`WledControllerClient` interface**:
   - Added `fun discoverDevices(): Flow<DiscoveredWled>` to `WledControllerClient` in `app/src/main/java/com/wled/tv/network/WledControllerClient.kt`.

4. **`DefaultWledControllerClient`**:
   - Added `discoveryProvider: WledDiscoveryProvider? = null` constructor parameter and `setDiscoveryProvider(provider)` mutator.
   - Implemented `discoverDevices()` returning `discoveryProvider?.discover() ?: emptyFlow()`.
   - Updated companion object with `getInstance(context: Context? = null)`, `initialize(context: Context)`, and `initDiscovery(context: Context)` to attach `NsdDiscoveryProvider` without breaking headless JVM tests.

5. **`WledTvApplication`**:
   - Attached application context to `DefaultWledControllerClient` in `WledTvApplication.onCreate()` via `DefaultWledControllerClient.initialize(this)`.

6. **`FakeWledControllerClient`**:
   - Added `val discoveryFlow = MutableSharedFlow<DiscoveredWled>()` and implemented `override fun discoverDevices(): Flow<DiscoveredWled> = discoveryFlow`.

7. **Unit Tests**:
   - Added unit tests in `DefaultWledControllerClientTest.kt`:
     - Verified `discoverDevices()` returns `emptyFlow()` when no provider is configured.
     - Verified `discoverDevices()` delegates to an injected `WledDiscoveryProvider`.
     - Verified `setDiscoveryProvider` dynamically updates the active discovery provider.
     - Verified `FakeWledControllerClient.discoverDevices()` emits fixtures to collectors via `discoveryFlow`.
   - All 158 JVM unit tests pass via `gradlew.bat testDebugUnitTest`.
