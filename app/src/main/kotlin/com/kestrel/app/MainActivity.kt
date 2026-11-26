// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.kestrel.app.navigation.KestrelAppShell
import com.kestrel.app.ui.FirstLaunchDisclaimerDialog
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.data.preferences.AppPreferences
import com.lagradost.cloudstream3.CommonActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonActivity.setActivityInstance(this)
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

    override fun onResume() {
        super.onResume()
        CommonActivity.setActivityInstance(this)
    }

    override fun onDestroy() {
        if (CommonActivity.activity === this) {
            CommonActivity.setActivityInstance(null)
        }
        super.onDestroy()
    }
}
