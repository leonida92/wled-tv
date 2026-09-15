package com.wled.tv.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class WledDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Discovers WLED instances on the local network via mDNS (_wled._tcp or _http._tcp).
     */
    fun discoverDevices(): Flow<DiscoveredWled> = callbackFlow {
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var isResolving = false
        val resolveLock = Any()

        fun processNextResolve() {
            val nextService: NsdServiceInfo?
            synchronized(resolveLock) {
                if (isResolving || resolveQueue.isEmpty()) return
                isResolving = true
                nextService = resolveQueue.removeFirstOrNull()
            }
            if (nextService == null) return

            try {
                nsdManager.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        synchronized(resolveLock) {
                            isResolving = false
                        }
                        processNextResolve()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        val port = serviceInfo.port
                        if (host != null) {
                            trySend(
                                DiscoveredWled(
                                    name = serviceInfo.serviceName,
                                    ip = host,
                                    port = port
                                )
                            )
                        }
                        synchronized(resolveLock) {
                            isResolving = false
                        }
                        processNextResolve()
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Exception initiating resolveService", e)
                synchronized(resolveLock) {
                    isResolving = false
                }
                processNextResolve()
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
                    synchronized(resolveLock) {
                        resolveQueue.addLast(service)
                    }
                    processNextResolve()
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
            synchronized(resolveLock) {
                resolveQueue.clear()
            }
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
