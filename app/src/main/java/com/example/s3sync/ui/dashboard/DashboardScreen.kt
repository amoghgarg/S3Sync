package com.example.s3sync.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val monthStates by viewModel.monthStates.collectAsState()
    val config by viewModel.s3Config.collectAsState()

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("S3 Sync Dashboard") },
                actions = {
                    IconButton(onClick = { viewModel.onSuspendAllClicked() }) {
                        Icon(
                            Icons.Default.StopCircle,
                            contentDescription = "Suspend All",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh All")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Configuration",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (config.bucketName.isEmpty()) "Bucket Not Configured" else "Bucket: ${config.bucketName}/${config.prefix}/",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Text(
                text = "Last 6 Months",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(monthStates.values.toList().sortedByDescending { it.monthKey }) { state ->
                    MonthCard(
                        state = state,
                        onSyncClick = { viewModel.onSyncNowClicked(state.monthKey) },
                        onVerifyClick = { viewModel.onVerifyClicked(state.monthKey) }
                    )
                }
            }
        }
    }
}

@Composable
fun MonthCard(
    state: MonthState,
    onSyncClick: () -> Unit,
    onVerifyClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.monthKey,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (state.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("Local Files: ${state.localCount}")
            Text("DB Uploaded: ${state.dbCount}")
            Text("S3 File Count: ${state.s3Count}")
            
            if (state.currentFileAction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.currentFileAction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            
            if (state.lastUpdated > 0) {
                Text(
                    text = "Last S3 Check: ${dateFormat.format(Date(state.lastUpdated))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Error: ${state.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onVerifyClick,
                    enabled = !state.isSyncing
                ) {
                    Text("Verify")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onSyncClick,
                    enabled = !state.isSyncing
                ) {
                    Text("Sync Now")
                }
            }
        }
    }
}
