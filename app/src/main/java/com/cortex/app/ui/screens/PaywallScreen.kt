package com.cortex.app.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cortex.app.monetization.RevenueCatManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val isPro by RevenueCatManager.isProUser.collectAsState()
    var isProcessingPurchase by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var promoCodeInput by remember { mutableStateOf("") }
    var promoMessage by remember { mutableStateOf<String?>(null) }
    var showPromoDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            isRestoring = true
                            RevenueCatManager.restorePurchases { restored ->
                                isRestoring = false
                                if (restored) onDismiss()
                            }
                        },
                        enabled = !isRestoring && !isProcessingPurchase
                    ) {
                        if (isRestoring) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Restore", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Glow Badge
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFFA855F7)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (isPro) "Cortex Pro Scholar (Active)" else "Cortex Pro Scholar",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (isPro) "You have full access to all Pro AI study capabilities" else "Supercharge your study workflow with grounded AI",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Feature Highlights
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureRow(text = "Unlimited Course Notebooks & Heavy PDF Uploads")
                    FeatureRow(text = "2-Host Audio Briefing generation (Alex & Sam)")
                    FeatureRow(text = "Interactive Exam Quizzing with instant rationale")
                    FeatureRow(text = "Automated Spaced Repetition Study Reminders")
                    FeatureRow(text = "S-Pen formula recognition & LaTeX export")
                }
            }

            // Pricing Card & Trial CTA
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isPro) "Active Subscription" else "7-Day Free Trial",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (isPro) "Managed via Samsung Galaxy Store" else "Then $4.99/month. Cancel anytime in Galaxy Store.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (isPro) {
                            onDismiss()
                        } else {
                            isProcessingPurchase = true
                            val activity = context as? Activity
                            RevenueCatManager.purchaseProPlan(activity) { completed ->
                                isProcessingPurchase = false
                                if (completed) onDismiss()
                            }
                        }
                    },
                    enabled = !isProcessingPurchase,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isProcessingPurchase) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isPro) "Done • Continue Studying" else "Start 7-Day Free Trial",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Judge & Reviewer Promo Code link
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { showPromoDialog = true }
                ) {
                    Text(
                        text = "Devpost Judge / Promo Code (Redeem)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Text(
                    text = "Auto-renewable subscription. Payment processed via Samsung Galaxy Store. Terms & Privacy apply.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    if (showPromoDialog) {
        AlertDialog(
            onDismissRequest = { showPromoDialog = false },
            title = { Text("Redeem Judge / Promo Code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter code 'SHIPATON2026' to unlock Pro features for judging & testing:",
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = promoCodeInput,
                        onValueChange = { promoCodeInput = it },
                        placeholder = { Text("SHIPATON2026") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    promoMessage?.let {
                        Text(
                            text = it,
                            fontSize = 12.sp,
                            color = if (it.contains("applied", ignoreCase = true)) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val code = promoCodeInput.ifBlank { "SHIPATON2026" }
                        RevenueCatManager.redeemPromoCode(code) { success, msg ->
                            promoMessage = msg
                            if (success) {
                                showPromoDialog = false
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Text("Redeem")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPromoDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FeatureRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0xFF10B981).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF10B981),
                modifier = Modifier.size(14.dp)
            )
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Paywall Screen Preview", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PaywallScreenPreview() {
    MaterialTheme {
        PaywallScreen(onDismiss = {})
    }
}
