package `in`.aboobacker.airrelay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import `in`.aboobacker.airrelay.sync.Permissions
import `in`.aboobacker.airrelay.sync.SyncService
import `in`.aboobacker.airrelay.theme.AirRelayTheme

class MainActivity : ComponentActivity() {

  /** Ask for camera only when the Mac opens the preview, never at first launch. */
  private val cameraPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted -> if (granted) SyncService.instance?.startPendingCamera() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      AirRelayTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
    handleShareIntent(intent)
    handleCameraRequest(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleShareIntent(intent)
    handleCameraRequest(intent)
  }

  private fun handleCameraRequest(intent: Intent?) {
    if (intent?.getBooleanExtra(SyncService.EXTRA_ALLOW_CAMERA, false) != true) return
    if (Permissions.hasCamera(this)) {
      SyncService.instance?.startPendingCamera()
    } else {
      cameraPermission.launch(android.Manifest.permission.CAMERA)
    }
  }

  private fun handleShareIntent(intent: Intent?) {
    @Suppress("DEPRECATION")
    val uris: List<Uri> = when (intent?.action) {
      Intent.ACTION_SEND ->
        listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
      Intent.ACTION_SEND_MULTIPLE ->
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
      else -> return
    }
    if (uris.isEmpty()) return
    val service = SyncService.instance
    if (service == null) {
      uris.forEach { SyncService.queueShare(it) }
      SyncService.start(this)
      Toast.makeText(this, "Connecting to Mac — will send when connected", Toast.LENGTH_SHORT).show()
      return
    }
    val sent = uris.mapNotNull { service.fileTransfer.offer(it) }
    Toast.makeText(
      this,
      when {
        sent.isEmpty() -> "Could not read files"
        sent.size == 1 -> "Sending ${sent.first().name} to Mac…"
        else -> "Sending ${sent.size} files to Mac…"
      },
      Toast.LENGTH_SHORT,
    ).show()
  }
}
