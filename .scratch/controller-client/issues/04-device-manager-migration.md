# 04: Migrate Device Management Activities & Domain Flashing

**What to build:** Refactor `DeviceManagerActivity` and `EditDeviceActivity` to use `WledControllerClient` for `flashDevice`, `checkConnection`, `wakeAndSetBrightness`, `turnOff`, and `fetchDeviceInfo`, eliminating duplicated UI-side flash loops and private socket/HTTP instances.

**Blocked by:** 01 (Unified WledControllerClient Interface, Default Implementation & Test Double)

**Status:** ready-for-agent

- [ ] `DeviceManagerActivity` replaces local `udpSender` and `httpClient` with `WledControllerClient`.
- [ ] `DeviceManagerActivity` replaces the manual white/delay/black frame loop with `controllerClient.flashDevice(device)`.
- [ ] `EditDeviceActivity` replaces local `udpSender`, `httpClient`, and one-shot senders with `WledControllerClient`.
- [ ] `EditDeviceActivity` replaces manual device flashing and turn-off loops with `controllerClient.flashDevice(device)` and `controllerClient.turnOff(device.ip)`.
- [ ] Manual `udpSender.close()` calls in `onDestroy()` are removed.
- [ ] UI migration unit tests pass verifying device management flows.
