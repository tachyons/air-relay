package `in`.aboobacker.airrelay.ui.files

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.FolderOpen
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReceivedFile(
  val uri: Uri,
  val name: String,
  val size: Long,
  val mime: String,
  val addedAtMs: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedFilesScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val isPreview = LocalInspectionMode.current
  var files by remember { mutableStateOf<List<ReceivedFile>?>(if (isPreview) emptyList() else null) }

  LaunchedEffect(Unit) {
    if (!isPreview) {
      files = withContext(Dispatchers.IO) { queryReceivedFiles(context) }
    }
  }

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("Files from your Mac", style = MaterialTheme.typography.titleLarge) },
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
    val list = files
    when {
      list == null -> Box(
        modifier = modifier.fillMaxSize().padding(innerPadding),
        contentAlignment = Alignment.Center,
      ) {}
      list.isEmpty() -> Box(
        modifier = modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(
            Icons.Rounded.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            "No files yet",
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            "Files sent from your Mac will show up here. They're also in your Downloads, in the AirRelay folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
      else -> LazyColumn(
        modifier = modifier.fillMaxSize().padding(innerPadding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
      ) {
        items(list, key = { it.uri }) { file ->
          FileRow(file = file, onClick = { openFile(context, file) })
        }
      }
    }
  }
}

@Composable
private fun FileRow(file: ReceivedFile, onClick: () -> Unit) {
  val context = LocalContext.current
  Row(
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 24.dp, vertical = 12.dp),
  ) {
    Surface(
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.secondaryContainer,
      modifier = Modifier.size(40.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          Icons.AutoMirrored.Rounded.InsertDriveFile,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
          tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        file.name,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "${Formatter.formatShortFileSize(context, file.size)} · ${
          DateUtils.getRelativeTimeSpanString(file.addedAtMs)
        }",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun openFile(context: Context, file: ReceivedFile) {
  val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(file.uri, file.mime)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  runCatching { context.startActivity(intent) }
}

private fun queryReceivedFiles(context: Context): List<ReceivedFile> {
  val projection = arrayOf(
    MediaStore.Downloads._ID,
    MediaStore.Downloads.DISPLAY_NAME,
    MediaStore.Downloads.SIZE,
    MediaStore.Downloads.MIME_TYPE,
    MediaStore.Downloads.DATE_ADDED,
  )
  val results = mutableListOf<ReceivedFile>()
  runCatching {
    context.contentResolver.query(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      projection,
      "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
      arrayOf("Download/AirRelay%"),
      "${MediaStore.Downloads.DATE_ADDED} DESC",
    )?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
      val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
      val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
      val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
      val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
      while (cursor.moveToNext()) {
        results.add(
          ReceivedFile(
            uri = ContentUris.withAppendedId(
              MediaStore.Downloads.EXTERNAL_CONTENT_URI,
              cursor.getLong(idIdx),
            ),
            name = cursor.getString(nameIdx) ?: "file",
            size = cursor.getLong(sizeIdx),
            mime = cursor.getString(mimeIdx) ?: "application/octet-stream",
            addedAtMs = cursor.getLong(dateIdx) * 1000,
          ),
        )
      }
    }
  }
  return results
}
