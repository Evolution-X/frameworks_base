/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.volume.dialog.percentage.ui.binder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.View
import android.widget.TextView
import com.android.app.tracing.coroutines.launchInTraced
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.percentage.ui.viewmodel.VolumeDialogVolumePercentageViewModel
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Job

@VolumeDialogScope
class VolumeDialogVolumePercentageViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogVolumePercentageViewModel,
) : ViewBinder {
    override fun CoroutineScope.bind(view: View) {
        val percentageText = view.requireViewById<TextView>(R.id.volume_percentage_text)

        viewModel.isVisible
            .onEach { isVisible ->
                percentageText.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
            .launchInTraced("VDVPVB#isVisible", this)

        val intentFilter = IntentFilter(AudioManager.VOLUME_CHANGED_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    percentageText.text = if (max > 0) "${current * 100 / max}%" else "0%"
                }
            }
        }
        view.context.registerReceiver(receiver, intentFilter)

        val audioManager = view.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        percentageText.text = if (max > 0) "${current * 100 / max}%" else "0%"

        coroutineContext[Job]?.invokeOnCompletion {
            view.context.unregisterReceiver(receiver)
        }
    }
}
