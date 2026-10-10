package net.wastu.binderclip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReceivedFilesActivity : ComponentActivity() {
    private lateinit var transfers: FileTransferStore
    private var files by mutableStateOf<List<ReceivedFile>>(emptyList())
    private var busy by mutableStateOf(false)
    private var savingId by mutableStateOf<String?>(null)
    private val saveDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = savingId
        savingId = null
        if (result.resultCode == RESULT_OK && id != null && result.data?.data != null) {
            saveFile(id, result.data!!.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transfers = FileTransferStore.forContext(this)
        savingId = savedInstanceState?.getString("saving_file")
        setContent {
            BinderClipTheme {
                val runtime by AppRuntime.state.collectAsState()
                LaunchedEffect(runtime.transferStatus) {
                    if (runtime.transferStatus == getString(R.string.transfer_received, getString(R.string.transfer_kind_file))) refresh()
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
                        Text(stringResource(R.string.received_files), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.received_files_help), modifier = Modifier.padding(vertical = 12.dp))
                        if (files.isEmpty()) Text(stringResource(R.string.received_files_empty))
                        LazyColumn {
                            items(files, key = { it.storageId }) { file ->
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    Text(file.metadata.name, style = MaterialTheme.typography.titleMedium)
                                    Text(android.text.format.Formatter.formatFileSize(this@ReceivedFilesActivity, file.metadata.size))
                                    Row {
                                        Button(enabled = !busy && savingId == null, onClick = { chooseDestination(file) }) { Text(stringResource(R.string.file_save)) }
                                        TextButton(enabled = !busy, onClick = { openFile(file) }) { Text(stringResource(R.string.file_open)) }
                                        TextButton(enabled = !busy, onClick = {
                                            lifecycleScope.launch { withContext(Dispatchers.IO) { transfers.deleteReceived(file.storageId) }; refresh() }
                                        }) { Text(stringResource(R.string.file_delete)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("saving_file", savingId); super.onSaveInstanceState(outState) }
    private fun refresh() { lifecycleScope.launch { files = withContext(Dispatchers.IO) { transfers.receivedFiles() } } }

    private fun chooseDestination(file: ReceivedFile) {
        savingId = file.storageId
        saveDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = file.metadata.mimeType
            putExtra(Intent.EXTRA_TITLE, file.metadata.name)
        })
    }
    private fun saveFile(id: String, uri: Uri) {
        busy = true
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val saved = runCatching {
                    val file = transfers.receivedFiles().first { it.storageId == id }
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("No output")
                    output.use { destination -> file.file.inputStream().use { it.copyTo(destination, 64 * 1024) } }
                }.isSuccess
                if (!saved) runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
                saved
            }
            busy = false
            Toast.makeText(this@ReceivedFilesActivity, if (success) R.string.file_saved else R.string.file_save_failed, Toast.LENGTH_LONG).show()
        }
    }
    private fun openFile(file: ReceivedFile) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.images", file.file, file.metadata.name)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.metadata.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, R.string.file_open_failed, Toast.LENGTH_LONG).show() }
    }
}
