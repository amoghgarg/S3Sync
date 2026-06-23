package com.example.s3sync.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.s3sync.data.local.S3Config

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val config by viewModel.s3Config.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    
    var bucketName by remember(config) { mutableStateOf(config.bucketName) }
    var prefix by remember(config) { mutableStateOf(config.prefix) }
    var region by remember(config) { mutableStateOf(config.region) }
    var accessKey by remember(config) { mutableStateOf(config.accessKey) }
    var secretKey by remember(config) { mutableStateOf(config.secretKey) }
    var storageClass by remember(config) { mutableStateOf(config.storageClass) }
    var accessKeyVisible by remember { mutableStateOf(false) }
    var secretKeyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("AWS S3 Configuration", style = MaterialTheme.typography.headlineSmall)
        
        OutlinedTextField(
            value = bucketName,
            onValueChange = { bucketName = it },
            label = { Text("Bucket Name") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = prefix,
            onValueChange = { prefix = it },
            label = { Text("Subfolder (Prefix)") },
            placeholder = { Text("e.g. backups/phone1") },
            modifier = Modifier.fillMaxWidth()
        )
        
        OutlinedTextField(
            value = region,
            onValueChange = { region = it },
            label = { Text("Region") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = accessKey,
            onValueChange = { accessKey = it },
            label = { Text("Access Key") },
            visualTransformation = if (accessKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                val image = if (accessKeyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                IconButton(onClick = { accessKeyVisible = !accessKeyVisible }) {
                    Icon(imageVector = image, contentDescription = "Toggle access key visibility")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = secretKey,
            onValueChange = { secretKey = it },
            label = { Text("Secret Key") },
            visualTransformation = if (secretKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                val image = if (secretKeyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                IconButton(onClick = { secretKeyVisible = !secretKeyVisible }) {
                    Icon(imageVector = image, contentDescription = "Toggle secret key visibility")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Text("Storage Class: $storageClass")
        
        if (testResult != null) {
            Text(
                text = testResult ?: "",
                color = if (testResult?.startsWith("Success") == true) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.testConnection(S3Config(bucketName, prefix, region, accessKey, secretKey, storageClass, 24))
                },
                enabled = !isTesting
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Test Connection")
            }

            Button(
                onClick = {
                    viewModel.saveConfig(S3Config(bucketName, prefix, region, accessKey, secretKey, storageClass, 24))
                },
                enabled = !isTesting
            ) {
                Text("Save Settings")
            }
        }
    }
}
