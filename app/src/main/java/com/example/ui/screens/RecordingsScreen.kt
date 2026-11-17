package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.recordings.RecordedVideoItem
import com.example.ui.theme.LocalStudioColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
  recordings: List<RecordedVideoItem>,
  isLoading: Boolean,
  onBack: () -> Unit,
  onDelete: (RecordedVideoItem) -> Unit,
) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current

  var playingItem by remember { mutableStateOf<RecordedVideoItem?>(null) }
  var pendingDelete by remember { mutableStateOf<RecordedVideoItem?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.recordings_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("recordings_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back_button),
            )
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      when {
        isLoading && recordings.isEmpty() -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
        recordings.isEmpty() -> {
          EmptyRecordingsState(modifier = Modifier.fillMaxSize().padding(32.dp))
        }
        else -> {
          LazyColumn(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(recordings, key = { it.id.toString() + it.displayName }) { item ->
              RecordingItemCard(
                item = item,
                onPlay = { playingItem = item },
                onShare = { shareVideo(context, item) },
                onRequestDelete = { pendingDelete = item },
              )
            }
          }
        }
      }
    }
  }

  playingItem?.let { item ->
    VideoPlayerDialog(item = item, onDismiss = { playingItem = null })
  }

  pendingDelete?.let { item ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Delete recording?") },
      text = { Text("Remove ${item.displayName} permanently from device storage?") },
      confirmButton = {
        Button(
          onClick = {
            onDelete(item)
            pendingDelete = null
          },
        ) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDelete = null }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}

@Composable
private fun RecordingItemCard(
  item: RecordedVideoItem,
  onPlay: () -> Unit,
  onShare: () -> Unit,
  onRequestDelete: () -> Unit,
) {
  val studioColors = LocalStudioColors.current
  Card(
    onClick = onPlay,
    modifier = Modifier.fillMaxWidth().testTag("recording_card_${item.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(52.dp),
        shape = RoundedCornerShape(12.dp),
        color = studioColors.recordContainer,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.Movie,
            contentDescription = null,
            tint = studioColors.recordAccent,
          )
        }
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = item.displayName,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val details = buildList {
          if (item.width > 0 && item.height > 0) add("${item.width}×${item.height}")
          if (item.durationMs > 0) add(formatDuration(item.durationMs))
          add(formatFileSize(item.sizeBytes))
          if (item.dateAddedSec > 0) add(formatDate(item.dateAddedSec))
        }.joinToString(" • ")

        Text(
          text = details,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      IconButton(onClick = onPlay) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = stringResource(R.string.action_play),
        )
      }
      IconButton(onClick = onShare) {
        Icon(
          imageVector = Icons.Default.Share,
          contentDescription = stringResource(R.string.action_share),
        )
      }
      IconButton(onClick = onRequestDelete) {
        Icon(
          imageVector = Icons.Default.DeleteOutline,
          contentDescription = stringResource(R.string.action_delete),
        )
      }
    }
  }
}

@Composable
private fun EmptyRecordingsState(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Surface(
      modifier = Modifier.size(72.dp),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Default.VideoLibrary,
          contentDescription = null,
          modifier = Modifier.size(34.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
      text = stringResource(R.string.recordings_empty_title),
      style = MaterialTheme.typography.titleMedium,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = stringResource(R.string.recordings_empty_subtitle),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun VideoPlayerDialog(item: RecordedVideoItem, onDismiss: () -> Unit) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = item.displayName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = onDismiss) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.action_close),
            )
          }
        }
        Box(
          modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp),
          contentAlignment = Alignment.Center,
        ) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
              VideoView(ctx).apply {
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(item.uri)
                setOnPreparedListener { mp ->
                  mp.isLooping = false
                  start()
                }
              }
            },
          )
        }
      }
    }
  }
}

private fun shareVideo(context: Context, item: RecordedVideoItem) {
  val shareIntent =
    Intent(Intent.ACTION_SEND).apply {
      type = "video/mp4"
      putExtra(Intent.EXTRA_STREAM, item.uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  context.startActivity(Intent.createChooser(shareIntent, item.displayName))
}

private fun formatDuration(ms: Long): String {
  val totalSec = (ms / 1000L).coerceAtLeast(0L)
  val mins = totalSec / 60L
  val secs = totalSec % 60L
  return String.format(Locale.US, "%d:%02d", mins, secs)
}

private fun formatFileSize(bytes: Long): String {
  if (bytes < 1024L) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
  val mb = kb / 1024.0
  if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
  val gb = mb / 1024.0
  return String.format(Locale.US, "%.2f GB", gb)
}

private fun formatDate(epochSec: Long): String {
  val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.US)
  return sdf.format(Date(epochSec * 1000L))
}
