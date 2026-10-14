package com.wled.tv.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class WledDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Discovers WLED instances on the local network via mDNS (_wled._tcp or _http._tcp).
     * Resolves discovered services sequentially through a Channel to eliminate FAILURE_ALREADY_ACTIVE (code 3).
     */
    fun discoverDevices(): Flow<DiscoveredWled> = callbackFlow {
        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        val resolverJob = launch {
            for (service in resolveQueue) {
                try {
                    val resolved = withTimeoutOrNull(3000L) {
                        suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                                    if (cont.isActive) cont.resume(null)
                                }

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    if (cont.isActive) cont.resume(serviceInfo)
                                }
                            })
                        }
                    }
                    if (resolved != null) {
                        val host = resolved.host?.hostAddress
                        val port = resolved.port
                        if (host != null) {
                            trySend(
                                DiscoveredWled(
                                    name = resolved.serviceName,
                                    ip = host,
                                    port = port
                                )
                            )
                        }
                    } else {
                        Log.w(TAG, "Resolve timed out or returned null for ${service.serviceName}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error resolving service ${service.serviceName}", e)
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS Service discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                val name = service.serviceName.lowercase()
                if (name.contains("wled") || service.serviceType.contains("_wled")) {
                    resolveQueue.trySend(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(
                "_http._tcp.",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS discovery", e)
            close(e)
        }

        awaitClose {
            resolverJob.cancel()
            resolveQueue.close()
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    companion object {
        private const val TAG = "WledDiscovery"
    }
}

data class DiscoveredWled(
    val name: String,
    val ip: String,
    val port: Int
)
