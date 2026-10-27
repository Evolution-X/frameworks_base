/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.appdatabackup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.android.axion.compose.preferences.ExpressiveShapes
import com.android.axion.compose.theme.AxionTheme

class AppDataBackupActivity : ComponentActivity() {
    private val viewModel: BackupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AxionTheme {
                MaterialTheme(shapes = ExpressiveShapes) {
                    BackupScreen(viewModel, onBack = { finish() })
                }
            }
        }
    }
}
