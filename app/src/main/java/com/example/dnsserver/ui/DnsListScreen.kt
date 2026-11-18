package com.example.dnsserver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dnsserver.R
import com.example.dnsserver.model.DNS_PROVIDERS
import com.example.dnsserver.model.DnsProvider
import com.example.dnsserver.util.DnsManager
import com.example.dnsserver.ui.theme.DnsCard
import com.example.dnsserver.ui.theme.SuccessGreen
import com.example.dnsserver.ui.theme.TextMuted
import com.example.dnsserver.ui.theme.TextPrimary
import com.example.dnsserver.ui.theme.TextSecondary
import com.example.dnsserver.ui.theme.WarningOrange
import kotlinx.coroutines.launch

@Composable
fun DnsListScreen(
    modifier: Modifier = Modifier,
    snackbarHostState: androidx.compose.material3.SnackbarHostState
) {
    var selectedProvider by remember { mutableStateOf<DnsProvider?>(null) }
    var isApplying by remember { mutableStateOf(false) }
    var dnsStatus by remember { mutableStateOf("Not configured") }

    val context = LocalContext.current

    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(bottom = 16.dp)
                .clickable {}
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material3.Icons.Default.Wifi,
                    contentDescription = "DNS",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.Text(
                    text = "DNS Server",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                androidx.compose.material3.Text(
                    text = "Choose your DNS provider",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        androidx.compose.material3.Text(
            text = "Available Providers",
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            items(
                items = DNS_PROVIDERS,
                key = { it.id }
            ) { provider ->
                val isSelected = selectedProvider?.id == provider.id
                DnsCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    provider = provider,
                    isSelected = isSelected,
                    onClick = {
                        selectedProvider = provider
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = androidx.compose.ui.graphics.Color(0xFF1E293B)
            ),
            shape = androidx.compose.foundation.shape.RoundedShape(12.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (selectedProvider != null && !isApplying) {
                        androidx.compose.material3.Icons.Default.CheckCircle
                    } else {
                        androidx.compose.material3.Icons.Default.Info
                    },
                    contentDescription = "Status",
                    tint = if (selectedProvider != null && !isApplying) SuccessGreen else WarningOrange,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    androidx.compose.material3.Text(
                        text = if (selectedProvider != null && !isApplying) {
                            "Active: ${selectedProvider.name}"
                        } else if (isApplying) {
                            "Applying DNS settings..."
                        } else {
                            "No DNS provider selected"
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                    androidx.compose.material3.Text(
                        text = if (selectedProvider != null && !isApplying) {
                            "${selectedProvider.hostname} • ${selectedProvider.primaryIp}"
                        } else {
                            dnsStatus
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            onClick = {
                if (selectedProvider == null) {
                    snackbarHostState.showSnackbar("Please select a DNS provider first")
                    return@Button
                }
                isApplying = true
                val success = DnsManager().applyDns(context, selectedProvider)
                isApplying = false
                if (success) {
                    dnsStatus = "DNS applied: ${selectedProvider.hostname}"
                    snackbarHostState.showSnackbar("DNS set to ${selectedProvider.name}")
                } else {
                    dnsStatus = "Failed to apply DNS"
                    snackbarHostState.showSnackbar("Failed to apply DNS settings")
                }
            },
            enabled = selectedProvider != null && !isApplying,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedProvider != null) {
                    androidx.compose.ui.graphics.Color(0xFF6366F1)
                } else {
                    Color(0xFF334155)
                }
            )
        ) {
            if (isApplying) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                androidx.compose.material3.Text(
                    text = "Apply DNS",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        androidx.compose.material3.TextButton(
            onClick = {
                isApplying = true
                val success = DnsManager().clearDns(context)
                isApplying = false
                selectedProvider = null
                if (success) {
                    dnsStatus = "DNS cleared"
                    snackbarHostState.showSnackbar("DNS settings cleared")
                } else {
                    snackbarHostState.showSnackbar("Failed to clear DNS")
                }
            }
        ) {
            androidx.compose.material3.Text(text = "Clear DNS")
        }

        Spacer(modifier = Modifier.height(24.dp))

        androidx.compose.material3.Text(
            text = "Requires 'Modify system settings' permission on Android 6+",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = androidx.compose.ui.text.TextAlign.Center
        )
    }
}
