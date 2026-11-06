# 01: Reactive ConfigRepository

**What to build:** An observable, thread-safe configuration repository that exposes the active lighting configuration as a reactive state stream and provides atomic mutations with asynchronous disk persistence. It guarantees complete backward compatibility with existing saved settings and legacy single-device configurations, while replacing manual reload signals with automated subscriber notifications.

**Blocked by:** None (can start immediately)

**Status:** completed

- [x] `ConfigRepository` exposes `val configFlow: StateFlow<WledConfig>` emitting immutable configuration snapshots.
- [x] `ConfigRepository` exposes `val currentConfig: WledConfig` for synchronous immediate reads.
- [x] `ConfigRepository` provides `suspend fun updateConfig(transform: (WledConfig) -> WledConfig)` that atomically mutates in-memory state and triggers disk persistence in the background.
- [x] Initial configuration loading correctly migrates existing JSON device definitions and legacy flat preference keys.
- [x] Comprehensive JVM unit tests verify loading, legacy migration, atomic mutations, and concurrent subscriber notifications without Android framework dependencies.
