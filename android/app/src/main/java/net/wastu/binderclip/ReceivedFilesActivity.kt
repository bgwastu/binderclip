package net.wastu.binderclip

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        transfers = FileTransferStore.forContext(this)
        savingId = savedInstanceState?.getString("saving_file")
        setContent {
            BinderClipTheme {
                val runtime by AppRuntime.state.collectAsState()
                LaunchedEffect(runtime.transferRevision) {
                    if (runtime.transferProgress?.let { it.state == TransferProgress.State.RECEIVED && it.kind == "file" } == true) refresh()
                }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.received_files)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.back),
                                    )
                                }
                            },
                        )
                    },
                ) { innerPadding ->
                    if (files.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(32.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Text(
                                    stringResource(R.string.received_files_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .consumeWindowInsets(innerPadding),
                            contentPadding = innerPadding,
                        ) {
                            items(files, key = { it.storageId }) { file ->
                                ReceivedFileItem(
                                    file = file,
                                    busy = busy,
                                    onOpen = { openFile(file) },
                                    onSaveDownloads = { saveToDownloads(file) },
                                    onSaveAs = { chooseDestination(file) },
                                    onDelete = { deleteFile(file) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("saving_file", savingId)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        lifecycleScope.launch {
            files = withContext(Dispatchers.IO) { transfers.receivedFiles() }
        }
    }

    private fun saveToDownloads(file: ReceivedFile) {
        busy = true
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.metadata.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, file.metadata.mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Could not create download entry")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    } ?: error("Could not write download content")
                    true
                }.isSuccess
            }
            busy = false
            Toast.makeText(
                this@ReceivedFilesActivity,
                if (success) R.string.file_saved else R.string.file_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

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

    private fun deleteFile(file: ReceivedFile) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { transfers.deleteReceived(file.storageId) }
            refresh()
        }
    }
}

@Composable
private fun ReceivedFileItem(
    file: ReceivedFile,
    busy: Boolean,
    onOpen: () -> Unit,
    onSaveDownloads: () -> Unit,
    onSaveAs: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val formattedSize = Formatter.formatFileSize(context, file.metadata.size)
    val relativeTime = DateUtils.getRelativeTimeSpanString(
        file.file.lastModified(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )

    ListItem(
        modifier = Modifier.clickable(enabled = !busy, onClick = onOpen),
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        fileTypeIcon(file.metadata.mimeType, file.metadata.name),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        headlineContent = {
            Text(
                file.metadata.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                "$formattedSize, $relativeTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = !busy,
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.file_open),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file_open)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file_save_downloads)) },
                        leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onSaveDownloads()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file_save)) },
                        leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onSaveAs()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file_delete)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}
