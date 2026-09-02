package net.wastu.binderclip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

sealed interface SharedPayload {
    data class Image(val value: ImagePayload) : SharedPayload
    data class Text(val value: String) : SharedPayload
}

object SharedPayloadCache {
    @Volatile
    var value: SharedPayload? = null
}

/** Native Android share-sheet endpoint with interactive target device selection. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.initialize(this)

        val payload = when (intent.action) {
            Intent.ACTION_SEND -> {
                val extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                val extraStream = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                val intentType = intent.type?.lowercase()

                when {
                    // 1. Explicit text or URL share: If EXTRA_TEXT is present and the intent is text/plain or has no image stream,
                    // prioritize the text/URL over any auxiliary thumbnail/favicon attached in clipData.
                    extraText != null && (intentType == null || intentType == "text/plain" || !intentType.startsWith("image/") || extraStream == null) -> {
                        SharedPayload.Text(extraText)
                    }

                    // 2. Explicit image share with EXTRA_STREAM (e.g. from Photos or Gallery)
                    extraStream != null && (intentType == null || intentType.startsWith("image/") || intentType == "*/*") -> {
                        ImageClipboard.readUri(this, extraStream, intentType)?.let(SharedPayload::Image)
                            ?: extraText?.let(SharedPayload::Text)
                    }

                    // 3. Fallback to clipData items (checking text first, then URI)
                    intent.clipData != null && intent.clipData!!.itemCount > 0 -> {
                        val item = intent.clipData!!.getItemAt(0)
                        val clipText = item.text?.toString()?.takeIf { it.isNotBlank() }
                        val clipUri = item.uri
                        when {
                            clipText != null -> SharedPayload.Text(clipText)
                            clipUri != null -> ImageClipboard.readUri(this, clipUri, intentType)?.let(SharedPayload::Image)
                            else -> null
                        }
                    }

                    // 4. Any remaining EXTRA_TEXT
                    extraText != null -> SharedPayload.Text(extraText)

                    else -> null
                }
            }

            else -> null
        }

        if (payload == null) {
            Log.w("BinderClip", "Share sheet did not provide supported content")
            DiagnosticLog.error("Could not read shared content")
            Toast.makeText(this, getString(R.string.share_error_unsupported), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val store = DeviceStore(this)
        val live = AppRuntime.state.value
        val candidateDevices = buildList {
            (live.peer ?: store.peer)?.let(::add)
            addAll(if (live.members.isNotEmpty()) live.members else store.members)
        }.distinctBy { it.deviceId }.filter { it.deviceId != store.deviceId }

        val isUrl = payload is SharedPayload.Text && (
                payload.value.trim().lowercase().startsWith("http://") ||
                payload.value.trim().lowercase().startsWith("https://") ||
                android.util.Patterns.WEB_URL.matcher(payload.value.trim()).matches()
                )

        // If no paired remote peers or only 1 remote peer and not a URL, we can send immediately.
        // If it is a URL or has candidate devices, show the device picker so the user can choose which device to open/send to.
        if (candidateDevices.isEmpty()) {
            sendPayload(payload, targetDeviceId = null)
            return
        }

        setContent {
            BinderClipTheme {
                ShareDevicePickerScreen(
                    payload = payload,
                    isUrl = isUrl,
                    devices = candidateDevices,
                    onSelectDevice = { targetId ->
                        sendPayload(payload, targetDeviceId = targetId)
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun sendPayload(payload: SharedPayload, targetDeviceId: String?) {
        Log.i(
            "BinderClip",
            "Accepted shared ${if (payload is SharedPayload.Image) "image" else "text"} for target: $targetDeviceId"
        )
        SharedPayloadCache.value = payload
        val serviceIntent = Intent(this, BinderClipService::class.java).apply {
            action = BinderClipService.ACTION_SEND_SHARED
            if (!targetDeviceId.isNullOrBlank()) putExtra(BinderClipService.EXTRA_TARGET_DEVICE_ID, targetDeviceId)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(
            this,
            if (payload is SharedPayload.Text && (payload.value.startsWith("http://") || payload.value.startsWith("https://"))) getString(R.string.share_sending_link) else getString(R.string.share_sending),
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}

@Composable
private fun ShareDevicePickerScreen(
    payload: SharedPayload,
    isUrl: Boolean,
    devices: List<RememberedPeer>,
    onSelectDevice: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isUrl) Icons.Outlined.OpenInBrowser else Icons.Outlined.DeviceHub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isUrl) stringResource(R.string.share_open_link_title) else stringResource(R.string.share_to_device_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val subtitle = when (payload) {
                                is SharedPayload.Text -> payload.value.trim().lines().firstOrNull()?.take(40)
                                    ?: stringResource(R.string.share_text_content)

                                is SharedPayload.Image -> stringResource(R.string.share_image_label, payload.value.mimeType)
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    HorizontalDivider()

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            ListItem(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectDevice(null) },
                                headlineContent = { Text(stringResource(R.string.share_all_devices), fontWeight = FontWeight.Medium) },
                                supportingContent = { Text(if (isUrl) stringResource(R.string.share_all_devices_url_sub) else stringResource(R.string.share_all_devices_sub)) },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Outlined.DeviceHub,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.5f
                                    )
                                )
                            )
                        }

                        items(devices, key = { it.deviceId }) { device ->
                            ListItem(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectDevice(device.deviceId) },
                                headlineContent = { Text(device.name, fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    Text(
                                        if (isUrl) stringResource(R.string.share_opens_browser_on, device.name)
                                        else if (device.connected) stringResource(R.string.status_connected) else stringResource(R.string.status_reconnecting)
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = if (device.platform.contains(
                                                "mac",
                                                ignoreCase = true
                                            )
                                        ) Icons.Outlined.LaptopMac else Icons.Outlined.Smartphone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.3f
                                    )
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}

