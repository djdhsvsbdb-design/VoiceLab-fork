package org.voicelab.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val audioFiles = remember { mutableStateListOf<Pair<Uri, String>>() }

                val picker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    uris.forEach { uri ->
                        // Some document providers do not offer persistable grants.
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }

                        val name = runCatching {
                            context.contentResolver.query(
                                uri,
                                arrayOf(OpenableColumns.DISPLAY_NAME),
                                null,
                                null,
                                null
                            )?.use { cursor ->
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (cursor.moveToFirst() && nameIndex >= 0) {
                                    cursor.getString(nameIndex)
                                } else {
                                    null
                                }
                            }
                        }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: uri.lastPathSegment?.substringAfterLast('/')
                                ?.takeIf { it.isNotBlank() }
                            ?: "Audio file"

                        if (audioFiles.none { it.first == uri }) {
                            audioFiles.add(uri to name)
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("VoiceLab", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "Android port · audio workspace",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
                            Text("Import audio")
                        }
                        Text("Loaded files: ${audioFiles.size}")

                        if (audioFiles.isEmpty()) {
                            Text(
                                "No audio files imported yet.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = audioFiles,
                                    key = { it.first.toString() }
                                ) { item ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = item.second,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(onClick = { audioFiles.remove(item) }) {
                                                Text("Remove")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            "Analysis engine is not connected yet. This app currently " +
                                "imports and lists audio files only.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
