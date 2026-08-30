package `in`.aboobacker.airrelay.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredPeer(
    val name: String,
    val host: String,
    val port: Int,
    val fingerprintPrefix: String?,
)

/** Browses for `_syncbridge._tcp` services published by the macOS app. */
class DiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discoverPeers(): Flow<DiscoveredPeer> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery start failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            Log.w(TAG, "Info callback registration failed: $errorCode")
                        }

                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            val host = info.hostAddresses.firstOrNull()?.hostAddress ?: return
                            val fp = info.attributes["fp"]?.decodeToString()
                            trySend(DiscoveredPeer(info.serviceName, host, info.port, fp))
                            runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                        }

                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                    nsdManager.registerServiceInfoCallback(
                        serviceInfo,
                        Runnable::run,
                        callback,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = info.host?.hostAddress ?: return
                            val fp = info.attributes["fp"]?.decodeToString()
                            trySend(DiscoveredPeer(info.serviceName, host, info.port, fp))
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsdManager.stopServiceDiscovery(listener) } }
    }

    companion object {
        const val SERVICE_TYPE = "_syncbridge._tcp"
        private const val TAG = "DiscoveryManager"
    }
}
