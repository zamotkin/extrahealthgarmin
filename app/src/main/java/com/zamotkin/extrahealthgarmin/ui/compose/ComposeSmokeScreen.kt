package com.zamotkin.extrahealthgarmin.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.tooling.preview.Preview

/**
 * Compose toolchain smoke screen (task tefal2garmin-on1).
 *
 * Compile-only proof that the Compose compiler plugin
 * (org.jetbrains.kotlin.plugin.compose 2.2.10, matching the KGP behind
 * AGP 9's built-in Kotlin) turns @Composable functions into compilable
 * output on this toolchain. Nothing references this file yet — the UI
 * epic (mtf) builds on top of the configuration it validates. No R.string
 * usage on purpose: this file must not touch res/values.
 */
@Composable
fun ComposeSmokeScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold { innerPadding ->
                Text(
                    text = "Compose OK",
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

/**
 * Unregistered host activity proving the ComposeView interop path compiles;
 * deliberately absent from the AndroidManifest (never launched).
 */
class ComposeSmokeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ComposeView(this).apply {
            setContent { ComposeSmokeScreen() }
        }.also { content ->
            setContentView(content)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ComposeSmokeScreenPreview() {
    ComposeSmokeScreen()
}
