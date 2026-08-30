package `in`.aboobacker.airrelay.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.aboobacker.airrelay.theme.AirRelayTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("What Air Relay does", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        "Everything happens directly between your phone and your Mac on your Wi-Fi. Nothing goes through the internet.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      HelpCard(
        icon = Icons.Rounded.Notifications,
        title = "Notifications",
        body = "Your phone's notifications appear on your Mac. Reply to messages and tap actions right from the Mac.",
      )
      HelpCard(
        icon = Icons.Rounded.Phone,
        title = "Phone calls",
        body = "See who's calling on your Mac and answer or decline from there. When you answer on the Mac, the phone switches to speakerphone so you can talk hands-free.",
      )
      HelpCard(
        icon = Icons.Rounded.ContentPaste,
        title = "Clipboard",
        body = "Anything you copy on the Mac lands on your phone automatically. To send your phone's clipboard, tap \"Push clipboard to Mac\" or use the Quick Settings tile.",
      )
      HelpCard(
        icon = Icons.Rounded.Folder,
        title = "Files",
        body = "Share any file to Air Relay to send it to your Mac. Files from your Mac arrive in Downloads › AirRelay.",
      )
      HelpCard(
        icon = Icons.Rounded.PhotoCamera,
        title = "Camera",
        body = "Your Mac can use the phone's camera for a quick live preview.",
      )

      Text(
        "You can turn features off any time from Settings on the main screen.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun HelpCard(
  icon: ImageVector,
  title: String,
  body: String,
) {
  Card(
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(20.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
          body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun HelpScreenPreview() {
  AirRelayTheme {
    HelpScreen(onBack = {})
  }
}
