package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.database.ProofReceiptEntity
import com.example.core.database.SnapshotEntity
import com.example.core.model.VaultItem
import com.example.core.runtime.CapabilitySubsystem
import com.example.core.runtime.CapabilityTruthState
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.PurpBorder
import com.example.ui.theme.PurpDeep
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpPrimary
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.PurpVoid
import com.example.ui.theme.RoseOffline
import com.example.ui.theme.TextHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AuditProofScreen(
  subsystems: List<CapabilitySubsystem> = emptyList(),
  receipts: List<ProofReceiptEntity>,
  snapshots: List<SnapshotEntity>,
  vaultItems: List<VaultItem>,
  onCreateSnapshot: (String) -> Unit,
  onAddVaultSecret: (String, String, String) -> Unit,
  onReverifySubsystem: (String) -> Unit = {},
  modifier: Modifier = Modifier
) {
  var selectedTab by remember { mutableStateOf("TRUTH") } // TRUTH, RECEIPTS, SNAPSHOTS, VAULT, PURP_PKG
  var showSnapshotDialog by remember { mutableStateOf(false) }
  var showVaultDialog by remember { mutableStateOf(false) }

  var snapshotReason by remember { mutableStateOf("") }
  var secretKey by remember { mutableStateOf("") }
  var secretVal by remember { mutableStateOf("") }
  var secretCat by remember { mutableStateOf("API Keys") }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(PurpVoid)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
      Text(
        text = "CAPABILITY TRUTH & AUDIT",
        fontSize = 15.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
        color = TextPrimary
      )
      Text(
        text = "Hardware Keystore receipts · Subsystem truth registry · Zero unverified claims",
        fontSize = 10.5.sp,
        color = TextSecondary
      )

      Spacer(modifier = Modifier.height(10.dp))

      // Tab selector — horizontal scroll, never wraps letters vertically
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        listOf(
          Pair("TRUTH", "Truth Registry"),
          Pair("RECEIPTS", "Receipts"),
          Pair("SNAPSHOTS", "Snapshots"),
          Pair("VAULT", "Vault"),
          Pair("PURP_PKG", ".purp Pkg")
        ).forEach { (key, label) ->
          val active = selectedTab == key
          Surface(
            modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .clickable { selectedTab = key }
              .testTag("audit_tab_${key.lowercase()}"),
            color = if (active) PurpPrimary else PurpSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (active) PurpNeon else PurpBorder)
          ) {
            Text(
              text = label,
              fontSize = 9.5.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              softWrap = false,
              color = if (active) Color.White else TextSecondary,
              modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
              textAlign = TextAlign.Center
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      when (selectedTab) {
        "TRUTH" -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "SUBSYSTEM CAPABILITY TRUTH (${subsystems.size})",
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = CyanNeon
            )
            Text(
              text = "${subsystems.count { it.state == CapabilityTruthState.LIVE_VERIFIED }} LIVE / ${subsystems.size}",
              fontFamily = FontFamily.Monospace,
              fontSize = 9.sp,
              color = EmeraldOnline
            )
          }
          Spacer(modifier = Modifier.height(6.dp))

          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            items(subsystems, key = { it.id }) { sub ->
              SubsystemTruthCard(subsystem = sub, onReverify = { onReverifySubsystem(sub.id) })
            }
            item { Spacer(modifier = Modifier.height(60.dp)) }
          }
        }

        "RECEIPTS" -> {
          Text(
            text = "OMNI CRYPTOGRAPHIC PROOF RECEIPTS (${receipts.size})",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = CyanAccent
          )
          Spacer(modifier = Modifier.height(6.dp))

          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            if (receipts.isEmpty()) {
              item {
                Text(
                  text = "No tool execution receipts recorded in this session yet. Execute armed tools in EXECUTE or SWARM mode to generate Keystore signed SHA-256 evidence proofs.",
                  fontSize = 11.sp,
                  color = TextMuted
                )
              }
            } else {
              items(receipts, key = { it.receiptId }) { receipt ->
                ReceiptCard(receipt = receipt)
              }
            }
            item { Spacer(modifier = Modifier.height(60.dp)) }
          }
        }

        "SNAPSHOTS" -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "ROLLBACK CHECKPOINTS (${snapshots.size})",
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = PurpNeon
            )
            OutlinedButton(
              onClick = { showSnapshotDialog = true },
              shape = RoundedCornerShape(6.dp),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp), tint = PurpNeon)
              Spacer(modifier = Modifier.width(4.dp))
              Text("Snapshot", fontSize = 10.sp, color = PurpNeon)
            }
          }
          Spacer(modifier = Modifier.height(6.dp))

          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            items(snapshots, key = { it.id }) { snap ->
              SnapshotCard(
                snapshot = snap,
                onRollback = { onAddVaultSecret("ROLLBACK", snap.id, "Snapshot restore requested") }
              )
            }
            item { Spacer(modifier = Modifier.height(60.dp)) }
          }
        }

        "VAULT" -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "ANDROID KEYSTORE SECURE VAULT",
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = EmeraldOnline
            )
            OutlinedButton(
              onClick = { showVaultDialog = true },
              shape = RoundedCornerShape(6.dp),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp), tint = EmeraldOnline)
              Spacer(modifier = Modifier.width(4.dp))
              Text("Add Secret", fontSize = 10.sp, color = EmeraldOnline)
            }
          }
          Spacer(modifier = Modifier.height(6.dp))

          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            items(vaultItems, key = { it.id }) { item ->
              VaultItemCard(item = item)
            }
            item { Spacer(modifier = Modifier.height(60.dp)) }
          }
        }

        "PURP_PKG" -> {
          PurpPackageInstallerSection(onIngest = { onAddVaultSecret("PURP_PKG", "@purp/vision-pro-pack.purp", "Ecosystem Package") })
        }
      }
    }

    // Create Snapshot Dialog
    if (showSnapshotDialog) {
      AlertDialog(
        onDismissRequest = { showSnapshotDialog = false },
        title = { Text("Create State Rollback Snapshot", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
          Column {
            OutlinedTextField(
              value = snapshotReason,
              onValueChange = { snapshotReason = it },
              label = { Text("Snapshot Label / Reason") },
              modifier = Modifier.fillMaxWidth()
            )
          }
        },
        confirmButton = {
          ElevatedButton(
            onClick = {
              if (snapshotReason.isNotBlank()) {
                onCreateSnapshot(snapshotReason)
                snapshotReason = ""
                showSnapshotDialog = false
              }
            },
            colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpNeon, contentColor = Color.White)
          ) {
            Text("Commit Snapshot")
          }
        },
        dismissButton = {
          OutlinedButton(onClick = { showSnapshotDialog = false }) {
            Text("Cancel", color = TextMuted)
          }
        },
        containerColor = PurpSurfaceCard
      )
    }

    // Add Vault Secret Dialog
    if (showVaultDialog) {
      AlertDialog(
        onDismissRequest = { showVaultDialog = false },
        title = { Text("Add Encrypted Vault Secret", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = secretKey,
              onValueChange = { secretKey = it },
              label = { Text("Secret Key Name (e.g. OPENROUTER_API_KEY)") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
              value = secretVal,
              onValueChange = { secretVal = it },
              label = { Text("Secret Value") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
            )
          }
        },
        confirmButton = {
          ElevatedButton(
            onClick = {
              if (secretKey.isNotBlank() && secretVal.isNotBlank()) {
                onAddVaultSecret(secretKey, secretVal, secretCat)
                secretKey = ""
                secretVal = ""
                showVaultDialog = false
              }
            },
            colors = ButtonDefaults.elevatedButtonColors(containerColor = EmeraldOnline, contentColor = Color.White)
          ) {
            Text("Store in Keystore")
          }
        },
        dismissButton = {
          OutlinedButton(onClick = { showVaultDialog = false }) {
            Text("Cancel", color = TextMuted)
          }
        },
        containerColor = PurpSurfaceCard
      )
    }
  }
}

@Composable
fun SubsystemTruthCard(subsystem: CapabilitySubsystem, onReverify: () -> Unit) {
  val stateColor = Color(subsystem.state.colorCode)
  val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
    shape = RoundedCornerShape(8.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, stateColor.copy(alpha = 0.6f))
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(text = subsystem.state.badge, fontSize = 12.sp)
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = subsystem.name,
            fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp,
            color = TextPrimary
          )
        }
        Surface(
          color = stateColor.copy(alpha = 0.15f),
          shape = RoundedCornerShape(4.dp),
          border = androidx.compose.foundation.BorderStroke(0.5.dp, stateColor)
        ) {
          Text(
            text = subsystem.state.label,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = stateColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Owner: ${subsystem.owner} · Impl: ${subsystem.implClass.split(".").last()}",
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        color = CyanAccent
      )
      Text(
        text = subsystem.health,
        fontSize = 11.sp,
        color = TextSecondary
      )

      if (subsystem.dependencies.isNotEmpty()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "Deps: ${subsystem.dependencies.joinToString(", ")}",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.5.sp,
          color = TextMuted
        )
      }

      if (subsystem.permissions.isNotEmpty()) {
        Text(
          text = "Perms: ${subsystem.permissions.joinToString(", ")}",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.5.sp,
          color = TextMuted
        )
      }

      if (subsystem.lastError != null) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "Note: ${subsystem.lastError}",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.5.sp,
          color = Color(0xFFFBBF24)
        )
      }

      if (subsystem.signedReceiptDigest != null) {
        Spacer(modifier = Modifier.height(3.dp))
        Text(
          text = subsystem.signedReceiptDigest,
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          color = EmeraldOnline,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
        )
      }

      Spacer(modifier = Modifier.height(4.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Verified: ${timeFormat.format(Date(subsystem.lastVerifiedAt))}",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.sp,
          color = TextMuted
        )
        IconButton(
          onClick = onReverify,
          modifier = Modifier.size(20.dp)
        ) {
          Icon(Icons.Default.Refresh, contentDescription = "Reverify", tint = TextMuted, modifier = Modifier.size(12.dp))
        }
      }
    }
  }
}

@Composable
fun ReceiptCard(receipt: ProofReceiptEntity) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
    shape = RoundedCornerShape(8.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldOnline.copy(alpha = 0.5f))
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = EmeraldOnline, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "${receipt.receiptId} · ${receipt.verificationStatus}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = EmeraldOnline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
        }
        Text(
          text = "Actor: ${receipt.actor}",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.5.sp,
          color = TextMuted
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Tool: ${receipt.toolName} · Model: ${receipt.modelUsed}",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = TextPrimary
      )
      Text(
        text = receipt.evidenceSummary,
        fontSize = 10.5.sp,
        color = TextSecondary
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "SHA-256 Digest: ${receipt.proofHash}",
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        color = CyanNeon,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (receipt.signature != null) {
        Text(
          text = "Keystore Sig [${receipt.signingKeyId}]: ${receipt.signature.take(32)}...",
          fontFamily = FontFamily.Monospace,
          fontSize = 7.5.sp,
          color = EmeraldOnline,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
fun SnapshotCard(snapshot: SnapshotEntity, onRollback: () -> Unit = {}) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
    shape = RoundedCornerShape(8.dp),
    border = androidx.compose.foundation.BorderStroke(0.5.dp, PurpBorder)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.History, contentDescription = null, tint = PurpNeon, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = snapshot.label,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = TextPrimary
          )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "State Digest: ${snapshot.afterStateHash}",
          fontFamily = FontFamily.Monospace,
          fontSize = 8.5.sp,
          color = TextMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      OutlinedButton(
        onClick = onRollback,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(28.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
      ) {
        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(12.dp))
        Spacer(modifier = Modifier.width(2.dp))
        Text("Rollback", fontSize = 9.sp)
      }
    }
  }
}

@Composable
fun VaultItemCard(item: VaultItem) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
    shape = RoundedCornerShape(8.dp),
    border = androidx.compose.foundation.BorderStroke(0.5.dp, PurpBorder)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Lock, contentDescription = null, tint = EmeraldOnline, modifier = Modifier.size(13.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = item.keyName,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            color = TextPrimary
          )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "Value: ${item.maskedValue} · Category: ${item.category}",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          color = TextSecondary
        )
      }
      Text(
        text = "HARDWARE SECURED",
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        color = EmeraldOnline
      )
    }
  }
}

@Composable
fun PurpPackageInstallerSection(onIngest: () -> Unit = {}) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
    shape = RoundedCornerShape(10.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder)
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = ".purp Ecosystem Package Unpacker",
          fontWeight = FontWeight.Bold,
          fontSize = 13.5.sp,
          color = TextPrimary
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "PurpClaw supports portable .purp capsules containing Souls, Skills, Tools, and Pre-trained LoRAs. Packages are unpacked into the scoped runtime sandbox with cryptographic signature verification.",
        fontSize = 11.sp,
        color = TextSecondary
      )
      Spacer(modifier = Modifier.height(10.dp))
      ElevatedButton(
        onClick = onIngest,
        colors = ButtonDefaults.elevatedButtonColors(containerColor = CyanAccent, contentColor = Color(0xFF00363F)),
        shape = RoundedCornerShape(6.dp)
      ) {
        Text("Install Bundle: @purp/vision-pro-pack.purp", fontSize = 10.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}
