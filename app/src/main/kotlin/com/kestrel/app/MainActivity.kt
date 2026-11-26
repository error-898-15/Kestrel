// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.kestrel.app.navigation.KestrelAppShell
import com.kestrel.app.ui.FirstLaunchDisclaimerDialog
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.data.preferences.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            KestrelTheme {
                val disclaimerAccepted by appPreferences.disclaimerAccepted.collectAsState(initial = true)
                val scope = rememberCoroutineScope()

                KestrelAppShell()

                if (!disclaimerAccepted) {
                    FirstLaunchDisclaimerDialog(
                        onAccept = {
                            scope.launch { appPreferences.acceptDisclaimer() }
                        }
                    )
                }
            }
        }
    }
}
