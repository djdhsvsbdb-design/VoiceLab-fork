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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val audioFiles = remember { mutableStateListOf<Pair<Uri, String>>() }
                val results = remember { mutableStateMapOf<String, String>() }
                val errors = remember { mutableStateMapOf<String, String>() }
                val analyzing = remember { mutableStateOf<Set<String>>(emptySet()) }
                val scope = rememberCoroutineScope()

                val picker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    uris.forEach { uri ->
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
                                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                            }
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                            ?: "Audio file"

                        if (audioFiles.none { it.first == uri }) audioFiles.add(uri to name)
                    }
                }

                fun analyze(uri: Uri) {
                    val key = uri.toString()
                    if (key in analyzing.value) return
                    analyzing.value = analyzing.value + key
                    errors.remove(key)
                    scope.launch {
                        try {
                            val metrics = withContext(Dispatchers.IO) {
                                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: error("Cannot open this audio file.")
                                val audio = WavPcmDecoder.decode(bytes)
                                VoiceAnalyzer.analyze(audio)
                            }
                            results[key] = buildString {
                                appendLine("Duration: %.3f s".format(metrics.durationSeconds))
                                appendLine("Sample rate: ${metrics.sampleRateHz} Hz")
                                appendLine("Channels: ${metrics.channels}")
                                appendLine("RMS: %.5f".format(metrics.rms))
                                appendLine("Intensity: %.2f dBFS".format(metrics.intensityDbfs))
                                appendLine("Zero-crossing rate: %.5f".format(metrics.zeroCrossingRate))
                                appendLine("Mean pitch: ${metrics.pitchMeanHz?.let { "%.1f Hz".format(it) } ?: "not detected"}")
                                appendLine("Pitch range: ${metrics.pitchMinHz?.let { "%.1f".format(it) } ?: "—"}–${metrics.pitchMaxHz?.let { "%.1f Hz".format(it) } ?: "—"}")
                                append("Pitch SD: ${metrics.pitchStdHz?.let { "%.1f Hz".format(it) } ?: "—"}")
                            }
                        } catch (error: Exception) {
                            errors[key] = error.message ?: "Analysis failed."
                        } finally {
                            analyzing.value = analyzing.value - key
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("VoiceLab", style = MaterialTheme.typography.headlineLarge)
                        Text("Android · audio analysis", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
                            Text("Import audio")
                        }
                        Text("Loaded files: ${audioFiles.size}")

                        if (audioFiles.isEmpty()) {
                            Text("No audio files imported yet.")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(audioFiles, key = { it.first.toString() }) { item ->
                                    val key = item.first.toString()
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(item.second, style = MaterialTheme.typography.titleMedium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = { analyze(item.first) },
                                                    enabled = key !in analyzing.value
                                                ) {
                                                    Text("Analyze")
                                                }
                                                Button(onClick = {
                                                    audioFiles.remove(item)
                                                    results.remove(key)
                                                    errors.remove(key)
                                                }) {
                                                    Text("Remove")
                                                }
                                                if (key in analyzing.value) CircularProgressIndicator()
                                            }
                                            results[key]?.let {
                                                Text(it, style = MaterialTheme.typography.bodyMedium)
                                            }
                                            errors[key]?.let {
                                                Text("Analysis error: $it", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "Current engine: uncompressed PCM WAV · duration, RMS intensity, " +
                                "zero-crossing rate and basic autocorrelation pitch. Other formats " +
                                "and the remaining VoiceLab measurements are not ported yet.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
