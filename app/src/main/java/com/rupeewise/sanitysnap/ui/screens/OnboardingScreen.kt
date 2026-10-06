package com.rupeewise.sanitysnap.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rupeewise.sanitysnap.ui.FsScaffold
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.SectionCard
import kotlinx.coroutines.launch

/** Fresh install: New user, or Existing user (import backup / sync with my other device). */
@Composable
fun OnboardingScreen(onCreated: () -> Unit, onImportBackup: () -> Unit, onSyncWithDevice: () -> Unit) {
    val repo = LocalContainer.current.repo
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    FsScaffold(title = "Welcome to SanitySnap") {
        Text(
            "Split expenses with friends — no server, no internet. Your data lives on this phone and syncs " +
                "directly with nearby friends.",
            style = MaterialTheme.typography.bodyMedium,
        )
        when (mode) {
            null -> {
                Button(onClick = { mode = "new" }, modifier = Modifier.fillMaxWidth()) { Text("I'm new here") }
                OutlinedButton(onClick = { mode = "existing" }, modifier = Modifier.fillMaxWidth()) { Text("I already use SanitySnap") }
            }
            "new" -> SectionCard("Create your profile") {
                OutlinedTextField(username, { username = it.filter { c -> !c.isWhitespace() } }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("A device keypair is generated in the Android Keystore and signs everything you record.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    if (username.length < 3) {
                        error = "Username must be at least 3 characters"
                    } else scope.launch {
                        repo.createProfile(username, displayName)
                        onCreated()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Create profile") }
                TextButton(onClick = { mode = null }) { Text("Back") }
            }
            else -> SectionCard("Restore your account") {
                Text("Bring your existing data onto this phone. This phone gets its own new device key; your account id stays the same.")
                Button(onClick = onImportBackup, modifier = Modifier.fillMaxWidth()) { Text("Import encrypted backup file") }
                OutlinedButton(onClick = onSyncWithDevice, modifier = Modifier.fillMaxWidth()) { Text("Sync with my other device") }
                TextButton(onClick = { mode = null }) { Text("Back") }
            }
        }
    }
}
