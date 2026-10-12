package net.wastu.binderclip

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DebugClipboardPanel() {
    val context = LocalContext.current
    val clipboard = remember { AppClipboard(context) }
    val content by DebugClipboard.content.collectAsState()
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Private Debug Clipboard")
        Text("Test content stays in the debug group. The system clipboard is untouched.")
        OutlinedTextField(value = text, onValueChange = { text = it },
            label = { Text("Text to send") }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Debug test", text)) }, enabled = text.isNotBlank()) {
                Text("Send Text")
            }
            FilledTonalButton(onClick = {
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.rgb(175, 40, 210))
                val bytes = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
                }
                bitmap.recycle()
                ImageClipboard.write(context, clipboard, ImagePayload(mimeType = "image/png", data = bytes))
            }) { Text("Send Test PNG") }
        }
        val item = content?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        Text(item?.text?.toString()?.take(200)?.let { "Private content: $it" }
            ?: if (item?.uri != null) "Private image received/ready" else "Private clipboard is empty")
    }
}
