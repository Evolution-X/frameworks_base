package com.android.systemui.biometrics.ui.binder

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

private const val TAG = "UdfpsPromptAnimationBinder"
private const val UDFPS_ANIMATIONS_PACKAGE = "org.evolution.udfps.animations"

/** Lets the caller forward raw touch events to drive animation start/stop immediately. */
interface UdfpsPromptAnimationController {
    fun onTouchEvent(event: MotionEvent)
}

object UdfpsPromptAnimationBinder {

    @JvmStatic
    fun bind(animView: ImageView, viewModel: PromptViewModel): UdfpsPromptAnimationController? {
        val context = animView.context
        val styleIdx = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.UDFPS_ANIM_STYLE,
            0,
            UserHandle.USER_CURRENT,
        )
        val bgDrawable = getBgDrawable(context, styleIdx) ?: return null
        animView.setImageDrawable(bgDrawable)
        animView.visibility = View.INVISIBLE
        val anim = bgDrawable as? AnimationDrawable ?: return null

        var isAuthenticatedOrError = false

        fun startAnim() {
            animView.visibility = View.VISIBLE
            anim.stop()
            anim.selectDrawable(0)
            anim.start()
        }

        fun stopAnim() {
            anim.stop()
            anim.selectDrawable(0)
            animView.visibility = View.INVISIBLE
        }

        animView.repeatWhenAttached {
            lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        stopAnim()
                    }
                }
            )

            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val shouldStop =
                    combine(viewModel.showingError, viewModel.isAuthenticated) { showingError, authState ->
                            showingError || authState.isAuthenticated
                        }
                        .distinctUntilChanged()

                launch {
                    shouldStop.collect { stop ->
                        isAuthenticatedOrError = stop
                        if (stop) stopAnim()
                    }
                }
            }

            stopAnim()
        }

        return object : UdfpsPromptAnimationController {
            override fun onTouchEvent(event: MotionEvent) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!isAuthenticatedOrError) startAnim()
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> stopAnim()
                }
            }
        }
    }

    private fun getBgDrawable(context: Context, styleIdx: Int): Drawable? {
        val apkResources: Resources
        try {
            apkResources = context.packageManager
                .getResourcesForApplication(UDFPS_ANIMATIONS_PACKAGE)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to load package resources", e)
            return null
        }
        val res = apkResources.getIdentifier(
            "udfps_animation_styles", "array", UDFPS_ANIMATIONS_PACKAGE
        )
        if (res == 0) return null
        val styleNames = apkResources.getStringArray(res)
        if (styleIdx >= styleNames.size) return null
        val drawableName = styleNames[styleIdx]
        return try {
            val resId = apkResources.getIdentifier(
                drawableName, "drawable", UDFPS_ANIMATIONS_PACKAGE
            )
            if (resId == 0) null else apkResources.getDrawable(resId)
        } catch (e: Resources.NotFoundException) {
            Log.w(TAG, "Drawable resource not found: $drawableName", e)
            null
        }
    }
}
