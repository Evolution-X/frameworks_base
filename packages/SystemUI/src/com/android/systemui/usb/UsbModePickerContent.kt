/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.usb

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R

data class UsbModeItem(
    val function: Long,
    @StringRes val labelResId: Int,
    val icon: ImageVector,
    val selected: Boolean,
)

@Composable
fun UsbModePickerContent(
    modes: List<UsbModeItem>,
    statusText: String,
    onModeSelected: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 4.dp, top = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.usb_mode_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val listState = rememberLazyListState()
        val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = MAX_LIST_HEIGHT)
                .drawScrollbar(listState, scrollbarColor, modes.size),
        ) {
            items(modes, key = { it.function }) { mode ->
                UsbModeRow(mode, onModeSelected)
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Bolt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun UsbModeRow(mode: UsbModeItem, onModeSelected: (Long) -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = if (mode.selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iconBg",
    )
    val iconTint by animateColorAsState(
        targetValue = if (mode.selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iconTint",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onModeSelected(mode.function) }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = iconTint,
            )
        }
        Text(
            text = stringResource(mode.labelResId),
            style = MaterialTheme.typography.bodyLarge,
            color = if (mode.selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private fun Modifier.drawScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    color: androidx.compose.ui.graphics.Color,
    totalItems: Int,
): Modifier = drawWithContent {
    drawContent()
    if (totalItems <= MAX_VISIBLE_ITEMS) return@drawWithContent

    val visibleFraction = listState.layoutInfo.visibleItemsInfo.size.toFloat() / totalItems
    val thumbHeight = (size.height * visibleFraction).coerceAtLeast(SCROLLBAR_MIN_HEIGHT_PX)
    val scrollRange = size.height - thumbHeight
    val firstVisible = listState.firstVisibleItemIndex
    val scrollOffset = listState.firstVisibleItemScrollOffset
    val maxScroll = totalItems - listState.layoutInfo.visibleItemsInfo.size
    val scrollFraction = if (maxScroll > 0) {
        (firstVisible.toFloat() + scrollOffset.toFloat() /
            (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1)) / maxScroll
    } else 0f
    val thumbOffset = scrollRange * scrollFraction.coerceIn(0f, 1f)

    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - SCROLLBAR_WIDTH_PX, thumbOffset),
        size = Size(SCROLLBAR_WIDTH_PX, thumbHeight),
        cornerRadius = CornerRadius(SCROLLBAR_WIDTH_PX / 2f),
    )
}

private const val MAX_VISIBLE_ITEMS = 4
private val MAX_LIST_HEIGHT = 240.dp
private const val SCROLLBAR_WIDTH_PX = 8f
private const val SCROLLBAR_MIN_HEIGHT_PX = 40f
