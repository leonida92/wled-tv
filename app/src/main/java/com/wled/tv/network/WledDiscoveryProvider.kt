package com.wled.tv.network

import kotlinx.coroutines.flow.Flow

/**
 * Seam for WLED fixture discovery on the local network.
 */
interface WledDiscoveryProvider {
    /**
     * Discovers WLED instances on the local network.
     * Emits discovered fixtures as they are identified and resolved.
     */
    fun discover(): Flow<DiscoveredWled>
}

data class DiscoveredWled(
    val name: String,
    val ip: String,
    val port: Int
)
