package com.soundmesh.product

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.soundmesh.probe.R

/**
 * The product's home, and the only screen a user who never attaches a cable ever sees.
 *
 * Separate from [com.soundmesh.probe.MainActivity] rather than grown out of it. That screen is the
 * harness's front door - ADB starts it by name and reads what it prints - and every alignment
 * measurement on record was taken through it. This one owes nothing to that contract, and the two
 * can change without either one having to think about the other.
 */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Meant to be put down on a table and looked at, like every other screen in this app.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Text(getString(R.string.home_title))
            }
        }
    }
}
