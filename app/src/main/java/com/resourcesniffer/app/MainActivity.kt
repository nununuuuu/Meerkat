package com.resourcesniffer.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ResourceSnifferApp(viewModel) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourceSnifferApp(viewModel: MainViewModel) {
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    var targetPackage by remember { mutableStateOf("") }
    var captureActive by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startCapture(targetPackage.ifBlank { null })
            captureActive = true
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Resource Sniffer") }) }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (captureActive) "Capture core active" else "Idle") }
                )

                OutlinedTextField(
                    value = targetPackage,
                    onValueChange = { targetPackage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Target package (optional)") },
                    placeholder = { Text("com.example.app") },
                    singleLine = true
                )

                Text(
                    "v0.2 establishes a local VPN/TUN and records packet metadata. TLS decryption and TCP/UDP forwarding are not implemented yet.",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        val prepare = VpnService.prepare(context)
                        if (prepare != null) vpnLauncher.launch(prepare)
                        else {
                            viewModel.startCapture(targetPackage.ifBlank { null })
                            captureActive = true
                        }
                    }) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Start")
                    }
                    OutlinedButton(onClick = {
                        viewModel.stopCapture()
                        captureActive = false
                    }) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                    OutlinedButton(onClick = viewModel::clear) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                }

                Button(onClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    } else {
                        viewModel.startOverlay()
                    }
                }) { Text("Enable floating bubble") }

                HorizontalDivider()
                Text("Observed endpoints (${resources.size})", fontWeight = FontWeight.SemiBold)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(resources, key = { it.id }) { ResourceRow(it) }
                }
            }
        }
    }
}

@Composable
private fun ResourceRow(resource: Resource) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(resource.host, fontWeight = FontWeight.SemiBold)
            Text(resource.url ?: "No URL available", style = MaterialTheme.typography.bodySmall)
            Text(resource.type.name, style = MaterialTheme.typography.labelSmall)
        }
    }
}
