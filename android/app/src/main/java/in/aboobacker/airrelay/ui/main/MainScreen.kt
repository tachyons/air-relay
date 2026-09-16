package `in`.aboobacker.airrelay.ui.main

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.PhonelinkOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import `in`.aboobacker.airrelay.Help
import `in`.aboobacker.airrelay.SharedFiles
import `in`.aboobacker.airrelay.net.PairingStore
import `in`.aboobacker.airrelay.sync.ClipboardBridge
import `in`.aboobacker.airrelay.sync.FeaturePrefs
import `in`.aboobacker.airrelay.sync.Permissions
import `in`.aboobacker.airrelay.sync.ShortcutPublisher
import `in`.aboobacker.airrelay.sync.SyncService
import `in`.aboobacker.airrelay.theme.AirRelayTheme

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val connectionState by SyncService.connectionState.collectAsStateWithLifecycle()
  MainScreenContent(
    connectionState = connectionState,
    onNavigate = onItemClick,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreenContent(
  connectionState: SyncService.ConnectionState,
  onNavigate: (NavKey) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val isPreview = LocalInspectionMode.current
  var missingRuntime by remember { mutableStateOf(if (isPreview) emptyList() else Permissions.missingRuntime(context)) }
  var hasNotifAccess by remember { mutableStateOf(if (isPreview) true else Permissions.hasNotificationAccess(context)) }
  var batteryExempt by remember { mutableStateOf(if (isPreview) true else Permissions.isIgnoringBatteryOptimizations(context)) }
  var showSettings by remember { mutableStateOf(false) }

  val batteryLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) {
    batteryExempt = Permissions.isIgnoringBatteryOptimizations(context)
  }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) {
    missingRuntime = Permissions.missingRuntime(context)
  }
  val settingsLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) {
    hasNotifAccess = Permissions.hasNotificationAccess(context)
  }
  LaunchedEffect(Unit) {
    SyncService.reconcileState()
    if (missingRuntime.isNotEmpty() && !isPreview) permissionLauncher.launch(missingRuntime.toTypedArray())
    // Auto-start: if we're paired, the link should just work without a button.
    if (!isPreview && PairingStore(context).isPaired && SyncService.instance == null) {
      SyncService.start(context)
    }
  }

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("Air Relay", style = MaterialTheme.typography.titleLarge) },
        actions = {
          IconButton(onClick = { onNavigate(Help) }) {
            Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = "Help")
          }
          if (connectionState is SyncService.ConnectionState.Connected || connectionState is SyncService.ConnectionState.Disconnected) {
            IconButton(onClick = { showSettings = true }) {
              Icon(Icons.Rounded.Settings, contentDescription = "Settings")
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        )
      )
    }
  ) { innerPadding ->
    Column(
      modifier = modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      when (connectionState) {
        is SyncService.ConnectionState.Pairing -> PairingContent(connectionState)
        is SyncService.ConnectionState.PairingDeclined -> DeclinedContent(connectionState)
        is SyncService.ConnectionState.Connected -> ConnectedContent(
          state = connectionState,
          hasNotifAccess = hasNotifAccess,
          batteryExempt = batteryExempt,
          missingRuntime = missingRuntime,
          onFixNotifications = {
            settingsLauncher.launch(Permissions.notificationAccessIntent(context))
          },
          onFixBattery = {
            batteryLauncher.launch(Permissions.batteryOptimizationIntent(context))
          },
          onFixPermissions = {
            permissionLauncher.launch(missingRuntime.toTypedArray())
          },
          onSendClipboard = { ClipboardBridge.launchReadActivity(context) },
          onShowFiles = { onNavigate(SharedFiles) },
        )
        SyncService.ConnectionState.Disconnected -> DisconnectedContent(
          isPaired = PairingStore(context).isPaired,
        )
      }
    }
  }

  if (showSettings) {
    SettingsSheet(
      onDismiss = { showSettings = false },
      onUnpair = {
        SyncService.stop(context)
        PairingStore(context).clear()
        ShortcutPublisher.removeShareTarget(context)
        showSettings = false
        Toast.makeText(context, "Unpaired", Toast.LENGTH_SHORT).show()
      }
    )
  }
}

@Composable
private fun StatusBadge(connected: Boolean, pairing: Boolean = false, error: Boolean = false) {
  val containerColor = when {
    error -> MaterialTheme.colorScheme.errorContainer
    pairing -> MaterialTheme.colorScheme.tertiaryContainer
    connected -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
  }
  val contentColor = when {
    error -> MaterialTheme.colorScheme.onErrorContainer
    pairing -> MaterialTheme.colorScheme.onTertiaryContainer
    connected -> MaterialTheme.colorScheme.onPrimaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val icon = when {
    error -> Icons.Rounded.ErrorOutline
    pairing -> Icons.Rounded.Refresh
    connected -> Icons.Rounded.Phonelink
    else -> Icons.Rounded.PhonelinkOff
  }

  Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = containerColor,
    modifier = Modifier.size(120.dp)
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(56.dp),
        tint = contentColor,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
  onDismiss: () -> Unit,
  onUnpair: () -> Unit,
) {
  val context = LocalContext.current
  val isPreview = LocalInspectionMode.current
  val features = remember { if (isPreview) null else FeaturePrefs(context) }
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Text("Settings", style = MaterialTheme.typography.headlineSmall)

      FeatureToggleRow(
        title = "Notifications",
        subtitle = "Show phone notifications on your Mac",
        initial = features?.notificationSync ?: true,
        onChange = { features?.notificationSync = it },
      )
      FeatureToggleRow(
        title = "Phone calls",
        subtitle = "Answer and decline calls from your Mac",
        initial = features?.callSync ?: true,
        onChange = { features?.callSync = it },
      )
      FeatureToggleRow(
        title = "Clipboard",
        subtitle = "Share what you copy between devices",
        initial = features?.clipboardSync ?: true,
        onChange = { features?.clipboardSync = it },
      )
      FeatureToggleRow(
        title = "Files",
        subtitle = "Send and receive files",
        initial = features?.fileSharing ?: true,
        onChange = { features?.fileSharing = it },
      )

      var confirmUnpair by remember { mutableStateOf(false) }
      TextButton(
        onClick = { confirmUnpair = true },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = ButtonDefaults.TextButtonWithIconContentPadding
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Rounded.PhonelinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
          Text("Unpair Mac", color = MaterialTheme.colorScheme.error)
        }
      }
      if (confirmUnpair) {
        androidx.compose.material3.AlertDialog(
          onDismissRequest = { confirmUnpair = false },
          title = { Text("Unpair Mac?") },
          text = { Text("You'll need to scan the QR code again to reconnect.") },
          confirmButton = { TextButton(onClick = { confirmUnpair = false; onUnpair() }) { Text("Unpair") } },
          dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } }
        )
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun FeatureToggleRow(
  title: String,
  subtitle: String,
  initial: Boolean,
  onChange: (Boolean) -> Unit,
) {
  var checked by remember { mutableStateOf(initial) }
  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = {
        checked = it
        onChange(it)
      },
    )
  }
}

@Composable
private fun DisconnectedContent(isPaired: Boolean) {
  val context = LocalContext.current
  val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
    val text = result.contents ?: return@rememberLauncherForActivityResult
    val uri = runCatching { Uri.parse(text) }.getOrNull()
    val fp = uri?.getQueryParameter("fp")
    val name = uri?.getQueryParameter("name")
    if (uri?.scheme == "airrelay" && !fp.isNullOrBlank()) {
      PairingStore(context).apply {
        peerFingerprint = fp
        peerName = name
      }
      ShortcutPublisher.publishShareTarget(context, name ?: "Mac")
      Toast.makeText(context, "Paired with ${name ?: "Mac"}", Toast.LENGTH_SHORT).show()
      SyncService.stop(context)
      SyncService.start(context)
    } else {
      Toast.makeText(context, "That's not an Air Relay code", Toast.LENGTH_SHORT).show()
    }
  }

  Spacer(modifier = Modifier.height(32.dp))
  StatusBadge(connected = false)
  
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text(
      text = if (isPaired) "Looking for your Mac" else "Connect your Mac",
      style = MaterialTheme.typography.headlineMedium,
      textAlign = TextAlign.Center,
    )
    Text(
      text = if (isPaired) "Make sure your Mac is awake and on the same Wi-Fi"
      else "Open Air Relay on your Mac and scan its QR code to start syncing.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }

  Spacer(modifier = Modifier.height(16.dp))
  
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    if (isPaired) {
      Button(
        onClick = { SyncService.start(context) },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().height(56.dp),
      ) {
        Icon(Icons.Rounded.Refresh, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Reconnect")
      }
    }
    
    OutlinedButton(
      onClick = {
        scanLauncher.launch(
          ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the QR code shown in Air Relay on your Mac")
            .setBeepEnabled(false)
            .setOrientationLocked(true),
        )
      },
      shape = MaterialTheme.shapes.large,
      modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
      Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
      Spacer(Modifier.size(8.dp))
      Text("Scan QR code")
    }

    if (!isPaired) {
      TextButton(
        onClick = { SyncService.start(context) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Find automatically")
      }
    }
  }
}

@Composable
private fun DeclinedContent(state: SyncService.ConnectionState.PairingDeclined) {
  Spacer(modifier = Modifier.height(32.dp))
  StatusBadge(connected = false, error = true)
  
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text(
      text = "Pairing didn't finish",
      style = MaterialTheme.typography.headlineMedium,
      textAlign = TextAlign.Center,
    )
    Text(
      text = "${state.peerName} declined or the code expired. Try again when you're ready.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }

  Spacer(modifier = Modifier.height(16.dp))

  Button(
    onClick = { SyncService.retryPairing() },
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth().height(56.dp),
  ) {
    Icon(Icons.Rounded.Refresh, contentDescription = null)
    Spacer(Modifier.size(8.dp))
    Text("Try again")
  }
}

@Composable
private fun PairingContent(state: SyncService.ConnectionState.Pairing) {
  Spacer(modifier = Modifier.height(32.dp))
  StatusBadge(connected = false, pairing = true)

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text(
      text = "Pairing in progress",
      style = MaterialTheme.typography.headlineMedium,
      textAlign = TextAlign.Center,
    )
  }
  
  Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.tertiaryContainer,
    tonalElevation = 4.dp
  ) {
    Text(
      text = state.sasCode,
      style = MaterialTheme.typography.displayLarge,
      color = MaterialTheme.colorScheme.onTertiaryContainer,
      modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp),
    )
  }
  
  Text(
    text = "Confirm this code matches on ${state.peerName}",
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
  )

  Row(
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    modifier = Modifier.fillMaxWidth()
  ) {
    OutlinedButton(
      onClick = { SyncService.resolvePairing(false) },
      modifier = Modifier.weight(1f).height(56.dp),
      shape = MaterialTheme.shapes.large
    ) {
      Text("Cancel")
    }
    Button(
      onClick = { SyncService.resolvePairing(true) },
      modifier = Modifier.weight(1f).height(56.dp),
      shape = MaterialTheme.shapes.large
    ) {
      Text("Match")
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectedContent(
  state: SyncService.ConnectionState.Connected,
  hasNotifAccess: Boolean,
  batteryExempt: Boolean,
  missingRuntime: List<String>,
  onFixNotifications: () -> Unit,
  onFixBattery: () -> Unit,
  onFixPermissions: () -> Unit,
  onSendClipboard: () -> Unit,
  onShowFiles: () -> Unit,
) {
  val context = LocalContext.current
  val hasFixIt = !hasNotifAccess || missingRuntime.isNotEmpty() || !batteryExempt
  val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    val service = SyncService.instance
    if (uris.isEmpty()) return@rememberLauncherForActivityResult
    if (service == null) {
      uris.forEach { SyncService.queueShare(it) }
      SyncService.start(context)
      Toast.makeText(context, "Connecting — will send when ready", Toast.LENGTH_SHORT).show()
    } else {
      uris.forEach { service.fileTransfer.offer(it) }
      Toast.makeText(context, if (uris.size == 1) "Sending to Mac…" else "Sending ${uris.size} files…", Toast.LENGTH_SHORT).show()
    }
  }

  StatusBadge(connected = true)

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Text(
      text = state.peerName,
      style = MaterialTheme.typography.headlineMedium,
      textAlign = TextAlign.Center,
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Icon(
        Icons.Rounded.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(16.dp)
      )
      Text(
        text = "Connected",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }

  if (hasFixIt) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      if (!hasNotifAccess) FixItCard(icon = Icons.Rounded.Notifications, title = "Notifications need permission", action = "Fix", onClick = onFixNotifications)
      if (missingRuntime.isNotEmpty()) FixItCard(icon = Icons.Rounded.Phonelink, title = "Phone calls need permission", action = "Grant", onClick = onFixPermissions)
      if (!batteryExempt) FixItCard(icon = Icons.Rounded.BatteryAlert, title = "Keep connection in background", action = "Allow", onClick = onFixBattery)
    }
  } else {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(
        onClick = onSendClipboard,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().height(56.dp),
      ) {
        Icon(Icons.Rounded.Computer, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Push clipboard to Mac")
      }
      OutlinedButton(
        onClick = { filePicker.launch(arrayOf("*/*")) },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().height(56.dp),
      ) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Send files to Mac")
      }
      TextButton(onClick = onShowFiles, modifier = Modifier.fillMaxWidth()) {
        Text("Files from your Mac")
      }
      SpeakerToggleItem(context)
    }
  }
}

@Composable
private fun FixItCard(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  action: String,
  onClick: () -> Unit,
) {
  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
      Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
      TextButton(onClick = onClick) { Text(action) }
    }
  }
}

@Composable
private fun FeatureItem(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  name: String,
  enabled: Boolean,
  description: String,
  onClick: (() -> Unit)? = null
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
  ) {
    Surface(
      shape = MaterialTheme.shapes.medium,
      color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
      modifier = Modifier.size(40.dp)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          icon,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
          tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
        )
      }
    }
    
    Column(modifier = Modifier.weight(1f)) {
      Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
      Text(description, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
    
    if (enabled) {
      Icon(
        Icons.Rounded.CheckCircle,
        contentDescription = "Enabled",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp)
      )
    } else {
      Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = "Action needed",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}

@Composable
private fun SpeakerToggleItem(context: android.content.Context) {
  val prefs = remember { context.getSharedPreferences("call_settings", android.content.Context.MODE_PRIVATE) }
  var enabled by remember { mutableStateOf(prefs.getBoolean("autoSpeaker", true)) }
  
  Row(
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Surface(
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.secondaryContainer,
      modifier = Modifier.size(40.dp)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          Icons.AutoMirrored.Rounded.VolumeUp,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
          tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      Text("Auto Speakerphone", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
      Text(
        "For calls answered on Mac",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = enabled,
      onCheckedChange = {
        enabled = it
        prefs.edit { putBoolean("autoSpeaker", it) }
      },
    )
  }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  AirRelayTheme {
    MainScreenContent(connectionState = SyncService.ConnectionState.Disconnected)
  }
}

@Preview(showBackground = true)
@Composable
fun MainScreenConnectedPreview() {
  AirRelayTheme {
    MainScreenContent(
      connectionState = SyncService.ConnectionState.Connected("Tachyons's Mac"),
    )
  }
}

@Preview(showBackground = true)
@Composable
fun MainScreenDeclinedPreview() {
  AirRelayTheme {
    MainScreenContent(
      connectionState = SyncService.ConnectionState.PairingDeclined("Tachyons's Mac"),
    )
  }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPairingPreview() {
  AirRelayTheme {
    MainScreenContent(
      connectionState = SyncService.ConnectionState.Pairing("Tachyons's Mac", "482913"),
    )
  }
}
