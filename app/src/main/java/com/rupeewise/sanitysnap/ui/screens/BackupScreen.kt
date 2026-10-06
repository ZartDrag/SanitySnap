package com.rupeewise.sanitysnap.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Manual encrypted backup file export/import via the system file picker (SAF). Drive: later. */
@Composable
fun BackupScreen(onboarding: Boolean, onBack: () -> Unit, onRestored: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val profile by container.repo.profile.collectAsStateWithLifecycle(null)
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val msg = runCatching {
                val bytes = withContext(Dispatchers.Default) { container.backup.export(password.toCharArray()) }
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                "Encrypted backup saved"
            }.getOrElse { "Export failed: ${it.message}" }
            busy = false
            snackbar.showSnackbar(msg)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    ?: error("Cannot read file")
                withContext(Dispatchers.Default) { container.backup.import(bytes, password.toCharArray()) }
            }
            busy = false
            result.fold(
                onSuccess = { r ->
                    snackbar.showSnackbar("Imported ${r.inserted} new event(s)" + (r.adoptedUsername?.let { ", restored @$it" } ?: "") + if (r.rejected > 0) ", ${r.rejected} rejected" else "")
                    if (onboarding && r.adoptedUsername != null) onRestored()
                },
                onFailure = { snackbar.showSnackbar("Import failed: ${it.message}") },
            )
        }
    }

    FsScaffold(title = if (onboarding) "Restore from backup" else "Backup", onBack = onBack, snackbar = snackbar) {
        SectionCard("Password") {
            OutlinedTextField(
                password, { password = it }, label = { Text("Backup password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Text("AES-256-GCM with a PBKDF2 key. Without the password the file cannot be restored.", style = MaterialTheme.typography.bodySmall)
        }
        if (!onboarding && profile != null) {
            Button(enabled = password.length >= 6 && !busy, onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                exportLauncher.launch("fairshare-${profile?.username}-$stamp.fsbackup.json")
            }, modifier = Modifier.fillMaxWidth()) { Text("Export encrypted backup") }
            if (password.isNotEmpty() && password.length < 6) Text("Use at least 6 characters", color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(enabled = password.isNotEmpty() && !busy, onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Import backup file (merges, never overwrites)")
        }
        Text(
            "The backup holds your full signed event log. Importing merges it into this phone's log, so it is safe to import " +
                "an older backup. Google Drive backup is planned for later.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
