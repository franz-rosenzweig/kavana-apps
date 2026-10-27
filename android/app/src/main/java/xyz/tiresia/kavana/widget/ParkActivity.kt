package xyz.tiresia.kavana.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.op
import xyz.tiresia.kavana.ui.KavanaTheme

/**
 * "Park a thought" without opening the app: a small dialog over whatever is on the screen (a widget can't
 * take typed text). The thought goes on the Later list, and the block, if there is one, keeps going.
 */
class ParkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KavanaTheme {
                var text by remember { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                fun park() {
                    if (text.isBlank()) return
                    KavanaApp.repo.launch(op("park", "text" to text.trim()))
                    finish()
                }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Park a thought") },
                    text = {
                        Column {
                            Text("Your block keeps going.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                text, { text = it.take(200) }, placeholder = { Text("A thought to park") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { park() }),
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus),
                            )
                        }
                    },
                    confirmButton = { TextButton(onClick = { park() }, enabled = text.isNotBlank()) { Text("Park") } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }
}
