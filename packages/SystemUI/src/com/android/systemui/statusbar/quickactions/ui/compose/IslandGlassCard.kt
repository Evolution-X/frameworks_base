/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.quickactions.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.axion.blur.AxBlurSurface
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSTileRim
import com.android.systemui.statusbar.quickactions.popups.shared.model.PopupActionModel

/** True when the island draws its ColorOS style glass cards. */
@Composable fun islandGlassCards(): Boolean = rememberDynamicIslandColorOSStyle()

private val GlassCardShape = RoundedCornerShape(28.dp)
private val GlassTint = Color.White.copy(alpha = 0.10f)
private val GlassButtonFill = Color.White.copy(alpha = 0.16f)
private val GlassPrimaryFill = Color(0xFF2F7BFF)

/** Dark fill for devices without a blur region, so the white text keeps its contrast. */
private val GlassNoBlurFill = Color(0xE62C2C2E)

/**
 * The ColorOS style live card used by the island features with the ColorOS style on: a glyph, a
 * large primary line over a small label, and up to two round buttons, on frosted glass.
 */
@Composable
fun IslandGlassCard(
    primary: String,
    secondary: String?,
    glyph: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    primaryAction: PopupActionModel? = null,
    secondaryAction: PopupActionModel? = null,
    primaryActionColor: Color = GlassPrimaryFill,
    below: (@Composable () -> Unit)? = null,
) {
    AxBlurSurface(
        modifier =
            modifier
                .widthIn(min = 300.dp, max = 400.dp)
                .pressScale(enabled = onClick != null, pressedScale = 0.97f) { onClick?.invoke() },
        shape = GlassCardShape,
        cornerRadius = 28.dp,
        surfaceColor = GlassNoBlurFill,
        tintColor = GlassTint,
        highlight = ColorOSTileRim,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        // Keep the content off the camera lens; the glass runs up around it.
                        .padding(top = LocalPopupCameraClearance.current)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        glyph()
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = primary,
                            fontSize = 26.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        secondary
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                Text(
                                    text = it,
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                    }
                    if (primaryAction != null || secondaryAction != null) {
                        Spacer(Modifier.width(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            primaryAction?.let {
                                GlassRoundButton(it, fill = primaryActionColor, size = 52)
                            }
                            secondaryAction?.let {
                                GlassRoundButton(it, fill = GlassButtonFill, size = 48)
                            }
                        }
                    }
                }
                below?.invoke()
            }
        }
    }
}

@Composable
private fun GlassRoundButton(action: PopupActionModel, fill: Color, size: Int) {
    Box(
        modifier =
            Modifier.size(size.dp)
                .pressScale(onClick = action.onClick)
                .background(fill, CircleShape)
                .semantics { contentDescription = action.label },
        contentAlignment = Alignment.Center,
    ) {
        val icon = action.icon
        val vector = action.vector
        when {
            vector != null ->
                MaterialIcon(
                    imageVector = vector,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = Color.White,
                )
            icon != null -> Icon(icon = icon, modifier = Modifier.size(24.dp), tint = Color.White)
            else ->
                Text(
                    text = action.label.take(2),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
        }
    }
}

/** A thin glass progress track for live cards. */
@Composable
fun IslandGlassProgress(progress: Float, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape)
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(accent, CircleShape)
        )
    }
}
