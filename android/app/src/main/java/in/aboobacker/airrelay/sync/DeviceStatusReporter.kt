package `in`.aboobacker.airrelay.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import `in`.aboobacker.airrelay.protocol.DeviceStatus
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Periodically reports battery and Wi-Fi state to the Mac while connected. */
class DeviceStatusReporter(private val context: Context) {

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                send()
                delay(60_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun send() {
        val battery = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        if (level < 0) return

        @Suppress("DEPRECATION", "InlinedApi")
        val ssid = runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.connectionInfo?.ssid
                ?.removeSurrounding("\"")
                ?.takeUnless { it == WifiManager.UNKNOWN_SSID }
        }.getOrNull()

        val payload = DeviceStatus(
            battery = level * 100 / scale,
            charging = plugged != 0,
            wifiSsid = ssid,
        )
        SyncService.instance?.send(
            FrameType.DEVICE_STATUS,
            ProtocolJson.encodeToString(payload).encodeToByteArray(),
        )
    }
}
