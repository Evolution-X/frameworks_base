/*
 * Copyright (C) 2025-2026 RisingOS (Revived) Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.globalactions.compose

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.PowerManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.statusbar.BlurUtils
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

data class TileConfig(
    val action: GlobalActionsDialogLite.Action,
    val id: String,
    val spanCols: Int = 1,
    val spanRows: Int = 1,
    val category: String = "system",
    val position: Int = 0
) {
    companion object {
        fun fromAction(action: GlobalActionsDialogLite.Action, defaultSpanX: Int = 1, defaultSpanY: Int = 1, position: Int = 0): TileConfig {
            val className = action.javaClass.simpleName
            val id = className.lowercase()
            val category = when {
                className.contains("Emergency") -> "system"
                className.contains("Power") || className.contains("Restart") -> "system"
                className.contains("Screenshot") -> "utilities"
                else -> "other"
            }
            return TileConfig(action, id, defaultSpanX, defaultSpanY, category, position)
        }
    }
}

data class LayoutState(
    val activeTiles: List<TileConfig>,
    val inactiveTiles: List<TileConfig>
)

enum class GlobalActionsView {
    GRID,
    RESTART_CHOICE,
    CONFIRMATION
}

fun isAdvancedRestartPossible(context: Context): Boolean {
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
    return try {
        val advRestart = android.provider.Settings.Secure.getInt(
            context.contentResolver,
            "advanced_reboot", 0
        ) == 1
        
        advRestart && keyguardManager?.isKeyguardLocked != true
    } catch (e: Exception) {
        false
    }
}

fun getActionLabel(action: GlobalActionsDialogLite.Action, context: Context): String {
    val msg = action.getMessage()
    if (!msg.isNullOrEmpty()) {
        val str = msg.toString()
        if (str.all { it.isDigit() }) {
            return try {
                context.getString(str.toInt())
            } catch (e: Exception) {
                str
            }
        }
        return str
    }
    
    val msgResId = action.getMessageResId()
    if (msgResId != 0) {
        return try {
            context.getString(msgResId)
        } catch (e: Exception) {
            cleanClassName(action.javaClass.simpleName)
        }
    }
    
    return cleanClassName(action.javaClass.simpleName)
}

private val CAMEL_CASE_REGEX = Regex("([a-z])([A-Z])")

fun cleanClassName(name: String): String {
    return name
        .replace("GlobalActions", "")
        .replace("Action", "")
        .replace("DialogLite", "")
        .replace("$", "")
        .replace("4", "Device Controls")
        .replace(CAMEL_CASE_REGEX, "$1 $2")
        .trim()
}

class GlobalActionsComposeDialog(
    context: Context,
    private val actions: List<GlobalActionsDialogLite.Action>,
    private val restartActions: List<GlobalActionsDialogLite.Action> = emptyList(),
    private val onActionClick: (GlobalActionsDialogLite.Action) -> Unit,
    private val onActionLongClick: ((GlobalActionsDialogLite.Action) -> Boolean)? = null,
    private val onUserInteraction: () -> Unit,
    private val onDismissed: () -> Unit,
    private val blurUtils: BlurUtils
) : Dialog(context, com.android.systemui.res.R.style.Theme_SystemUI_Dialog_GlobalActionsLite),
    LifecycleOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val prefs: SharedPreferences = context.getSharedPreferences("global_actions_layout", Context.MODE_PRIVATE)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var composeView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            
            if (blurUtils.supportsBlursOnWindows()) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply {
                    blurBehindRadius = context.resources.getDimensionPixelSize(
                        com.android.systemui.res.R.dimen.max_window_blur_radius
                    )
                }
                setDimAmount(0.4f)
            } else {
                setDimAmount(0.7f)
            }
            
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }

        composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@GlobalActionsComposeDialog)
            setViewTreeSavedStateRegistryOwner(this@GlobalActionsComposeDialog)
            
            setContent {
                MaterialExpressiveTheme {
                    GlobalActionsScreen(
                        actions = actions,
                        restartActions = restartActions,
                        prefs = prefs,
                        onActionClick = onActionClick,
                        onActionLongClick = onActionLongClick,
                        realDismiss = {
                            dismiss()
                        }
                    )
                }
            }
        }

        savedStateRegistryController.performRestore(null)
        setContentView(composeView)
        setOnDismissListener { onDismissed() }

        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStart() {
        super.onStart()
        if (lifecycleRegistry.currentState != Lifecycle.State.STARTED) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }
    }

    override fun onStop() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun dismiss() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        super.dismiss()
    }
}

@Composable
private fun MaterialExpressiveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()

    val colorScheme = remember(context, darkTheme) { if (darkTheme) {
        val accentPrimary = Color(context.getColor(android.R.color.system_accent1_200))
        val accentSecondary = Color(context.getColor(android.R.color.system_accent2_200))
        val surfaceContainer = Color(context.getColor(android.R.color.system_neutral1_900))
        val surfaceVariant = Color(context.getColor(android.R.color.system_neutral2_800))
        
        darkColorScheme(
            primary = accentPrimary,
            secondary = accentSecondary,
            surface = surfaceContainer,
            surfaceVariant = surfaceVariant,
            onSurface = Color.White,
            background = Color.Transparent,
            error = Color(0xFFE25C5C),
            onError = Color(0xFF410002)
        )
    } else {
        val accentPrimary = Color(context.getColor(android.R.color.system_accent1_600))
        val accentSecondary = Color(context.getColor(android.R.color.system_accent2_600))
        val surfaceContainer = Color(context.getColor(android.R.color.system_neutral1_50))
        val surfaceVariant = Color(context.getColor(android.R.color.system_neutral2_100))
        
        lightColorScheme(
            primary = accentPrimary,
            secondary = accentSecondary,
            surface = surfaceContainer,
            surfaceVariant = surfaceVariant,
            onSurface = Color.Black,
            background = Color.Transparent,
            onSurfaceVariant = Color.DarkGray,
            error = Color(0xFFB3261E),
            onError = Color.White
        )
    } }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes(
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(32.dp)
        ),
        content = content
    )
}

private fun loadLayout(prefs: SharedPreferences, allTiles: List<TileConfig>, isLandscape: Boolean): LayoutState {
    val key = if (isLandscape) "active_tiles_land" else "active_tiles"
    val activeJson = prefs.getString(key, null)
    
    return if (activeJson != null) {
        try {
            val activeArray = JSONArray(activeJson)
            val activeTiles = mutableListOf<TileConfig>()
            val activeIds = mutableSetOf<String>()
            
            for (i in 0 until activeArray.length()) {
                val obj = activeArray.getJSONObject(i)
                val id = obj.getString("id")
                val spanCols = obj.optInt("spanCols", 1)
                val spanRows = obj.optInt("spanRows", 1)
                val position = obj.getInt("position")
                allTiles.find { it.id == id }?.let {
                    activeTiles.add(it.copy(spanCols = spanCols, spanRows = spanRows, position = position))
                    activeIds.add(id)
                }
            }
            
            val inactiveTiles = allTiles.filter { !activeIds.contains(it.id) }
            LayoutState(activeTiles.sortedBy { it.position }, inactiveTiles)
        } catch (e: Exception) {
            getDefaultLayout(allTiles)
        }
    } else {
        getDefaultLayout(allTiles)
    }
}

private fun getDefaultLayout(allTiles: List<TileConfig>): LayoutState {
    val active = allTiles.take(8).mapIndexed { i, t -> t.copy(position = i) }
    val inactive = allTiles.drop(8)
    return LayoutState(active, inactive)
}

private fun saveLayout(prefs: SharedPreferences, layoutState: LayoutState, isLandscape: Boolean) {
    val activeArray = JSONArray()
    layoutState.activeTiles.forEachIndexed { index, tile ->
        val obj = JSONObject().apply {
            put("id", tile.id)
            put("spanCols", tile.spanCols)
            put("spanRows", tile.spanRows)
            put("position", index)
        }
        activeArray.put(obj)
    }
    val key = if (isLandscape) "active_tiles_land" else "active_tiles"
    prefs.edit().putString(key, activeArray.toString()).apply()
}

@Composable
fun GlobalActionsScreen(
    actions: List<GlobalActionsDialogLite.Action>,
    restartActions: List<GlobalActionsDialogLite.Action> = emptyList(),
    prefs: SharedPreferences,
    onActionClick: (GlobalActionsDialogLite.Action) -> Unit,
    onActionLongClick: ((GlobalActionsDialogLite.Action) -> Boolean)?,
    realDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isEditMode by remember { mutableStateOf(false) }
    var currentView by remember { mutableStateOf(GlobalActionsView.GRID) }
    var pendingConfirmationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmationTitle by remember { mutableStateOf("") }
    var confirmationMessage by remember { mutableStateOf("") }
    var confirmationIconBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var confirmationColor by remember { mutableStateOf(Color.Red) }
    var confirmationReturnsToGrid by remember { mutableStateOf(false) }
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val visibleState = remember { MutableTransitionState(false) }
    
    LaunchedEffect(Unit) {
        visibleState.targetState = true
    }

    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (!visibleState.currentState && !visibleState.targetState) {
            realDismiss()
        }
    }

    val startExit: () -> Unit = {
        visibleState.targetState = false
    }
    
    val allTiles = remember(actions) {
        actions.mapIndexed { index, action ->
            val className = action.javaClass.simpleName
            var cols = 1
            var rows = 1
            
            if (className.contains("Emergency")) { cols = 4; rows = 1 }
            else if (className.contains("Power")) { cols = 2; rows = 1 }
            else if (className.contains("Restart")) { cols = 2; rows = 1 }
            
            TileConfig.fromAction(action, cols, rows, index)
        }
    }

    var layoutState by remember(isLandscape) {
        mutableStateOf<LayoutState>(loadLayout(prefs, allTiles, isLandscape))
    }

    val applyLayout = { newState: LayoutState ->
        layoutState = newState
    }

    val commitLayout = { newState: LayoutState ->
        layoutState = newState
        saveLayout(prefs, newState, isLandscape)
    }

    val persistLayout = {
        saveLayout(prefs, layoutState, isLandscape)
    }

    val errorColor = MaterialTheme.colorScheme.error

    val handleTileClick: (TileConfig) -> Unit = { tile ->
        if (!isEditMode) {
            val className = tile.action.javaClass.simpleName
            
            when {
                className.contains("Power") || className.contains("ShutDown") -> {
                    confirmationTitle = getActionLabel(tile.action, context)
                    confirmationMessage = "Slide to power off"
                    confirmationIconBitmap = tile.action.getIcon(context)?.toBitmap(96, 96)?.asImageBitmap()
                    confirmationColor = errorColor
                    confirmationReturnsToGrid = true
                    pendingConfirmationAction = { onActionClick(tile.action) }
                    currentView = GlobalActionsView.CONFIRMATION
                }
                
                className.contains("Restart") -> {
                    if (isAdvancedRestartPossible(context)) {
                        currentView = GlobalActionsView.RESTART_CHOICE
                    } else {
                        confirmationTitle = getActionLabel(tile.action, context)
                        confirmationMessage = "Slide to restart"
                        confirmationIconBitmap = tile.action.getIcon(context)?.toBitmap(96, 96)?.asImageBitmap()
                        confirmationColor = errorColor
                        confirmationReturnsToGrid = true
                        pendingConfirmationAction = { onActionClick(tile.action) }
                        currentView = GlobalActionsView.CONFIRMATION
                    }
                }
                
                className.contains("Users") -> {
                    onActionClick(tile.action)
                }
                
                else -> {
                    onActionClick(tile.action)
                    startExit()
                }
            }
        }
    }

    val handleRestartOptionClick: (GlobalActionsDialogLite.Action) -> Unit = { action ->
        confirmationTitle = getActionLabel(action, context)
        confirmationMessage = "Slide to restart"
        confirmationIconBitmap = action.getIcon(context)?.toBitmap(96, 96)?.asImageBitmap()
        confirmationColor = errorColor
        confirmationReturnsToGrid = false
        pendingConfirmationAction = { onActionClick(action) }
        currentView = GlobalActionsView.CONFIRMATION
    }

    val handleResize: (String, Int, Int) -> Unit = { tileId, newCols, newRows ->
        val updated = layoutState.activeTiles.map {
            if (it.id == tileId) it.copy(spanCols = newCols, spanRows = newRows) else it
        }
        applyLayout(layoutState.copy(activeTiles = updated))
    }

    val handleAdd: (TileConfig) -> Unit = { tile ->
        val newActive = layoutState.activeTiles + tile.copy(spanCols = 1, spanRows = 1)
        val newInactive = layoutState.inactiveTiles.filter { it.id != tile.id }
        commitLayout(LayoutState(newActive, newInactive))
    }

    val handleRemove: (String) -> Unit = { tileId ->
        val tile = layoutState.activeTiles.find { it.id == tileId }
        if (tile != null) {
            val newActive = layoutState.activeTiles.filter { it.id != tileId }
            val newInactive = layoutState.inactiveTiles + tile.copy(spanCols = 1, spanRows = 1)
            commitLayout(LayoutState(newActive, newInactive))
        }
    }

    val handleMove: (Int, Int) -> Unit = { fromIndex, toIndex ->
        val tiles = layoutState.activeTiles.toMutableList()
        if (fromIndex in tiles.indices && toIndex in tiles.indices && fromIndex != toIndex) {
            val item = tiles.removeAt(fromIndex)
            tiles.add(toIndex, item)
            applyLayout(layoutState.copy(activeTiles = tiles))
        }
    }

    val handleDismiss: () -> Unit = {
        when (currentView) {
            GlobalActionsView.CONFIRMATION -> {
                currentView = when {
                    confirmationReturnsToGrid -> GlobalActionsView.GRID
                    else -> GlobalActionsView.RESTART_CHOICE
                }
            }
            GlobalActionsView.RESTART_CHOICE -> currentView = GlobalActionsView.GRID
            GlobalActionsView.GRID -> {
                if (isEditMode) {
                    persistLayout()
                    isEditMode = false
                } else {
                    startExit()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        handleDismiss()
                    }
                }
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = currentView,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.9f))
                    .togetherWith(fadeOut(animationSpec = tween(200)))
                },
                label = "view_transition"
            ) { state ->
                when (state) {
                    GlobalActionsView.CONFIRMATION -> {
                        ConfirmationSliderView(
                            title = confirmationTitle,
                            message = confirmationMessage,
                            iconBitmap = confirmationIconBitmap,
                            color = confirmationColor,
                            onConfirm = { pendingConfirmationAction?.invoke() },
                            onCancel = { handleDismiss() }
                        )
                    }
                    GlobalActionsView.RESTART_CHOICE -> {
                        RestartChoiceMenu(
                            restartActions = restartActions,
                            onOptionSelected = handleRestartOptionClick,
                            onBack = { currentView = GlobalActionsView.GRID }
                        )
                    }
                    GlobalActionsView.GRID -> {
                        AnimatedVisibility(
                            visibleState = visibleState,
                            enter = fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.95f, animationSpec = tween(300)),
                            exit = fadeOut(animationSpec = tween(250)) + scaleOut(targetScale = 0.95f, animationSpec = tween(250))
                        ) {
                            PowerMenuContainer(
                                layoutState = layoutState,
                                isEditMode = isEditMode,
                                onTileClick = handleTileClick,
                                onResize = handleResize,
                                onAdd = handleAdd,
                                onRemove = handleRemove,
                                onMove = handleMove,
                                onInteractionEnd = persistLayout,
                                onToggleEdit = {
                                    if (isEditMode) persistLayout()
                                    isEditMode = !isEditMode
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestartChoiceMenu(
    restartActions: List<GlobalActionsDialogLite.Action>,
    onOptionSelected: (GlobalActionsDialogLite.Action) -> Unit,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(340.dp)
            .padding(16.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Restart",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            restartActions.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { action ->
                        RestartOptionTile(
                            action = action,
                            onClick = { onOptionSelected(action) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            TextButton(onClick = onBack) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun RestartOptionTile(
    action: GlobalActionsDialogLite.Action,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val label = remember(action) { getActionLabel(action, context) }
    val iconBitmap = remember(action) {
        action.getIcon(context)?.toBitmap(96, 96)?.asImageBitmap()
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.height(100.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (iconBitmap != null) {
                Icon(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
        }
    }
}

@Composable
private fun PowerMenuContainer(
    layoutState: LayoutState,
    isEditMode: Boolean,
    onTileClick: (TileConfig) -> Unit,
    onResize: (String, Int, Int) -> Unit,
    onAdd: (TileConfig) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onInteractionEnd: () -> Unit,
    onToggleEdit: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridColumns = 4

    if (isLandscape) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            val animatedBias by animateFloatAsState(
                targetValue = if (isEditMode) -1f else 0f,
                label = "landscape_position_bias",
                animationSpec = spring(stiffness = Spring.StiffnessLow)
            )
            
            val topPadding = if (isEditMode) 4.dp else 0.dp

            Surface(
                modifier = Modifier
                    .width(340.dp)
                    .align(BiasAlignment(0f, animatedBias))
                    .padding(top = topPadding, start = 16.dp, end = 16.dp)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MenuHeader(isEditMode, onToggleEdit)
                    Spacer(modifier = Modifier.height(16.dp))
                    DraggableGrid(
                        tiles = layoutState.activeTiles,
                        isEditMode = isEditMode,
                        columns = gridColumns,
                        onTileClick = onTileClick,
                        onResize = onResize,
                        onRemove = onRemove,
                        onMove = onMove,
                        onInteractionEnd = onInteractionEnd
                    )
                }
            }

            AnimatedVisibility(
                visible = isEditMode && layoutState.inactiveTiles.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .width(200.dp)
                        .pointerInput(Unit) { detectTapGestures { } },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 120.dp)
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Text(
                                text = "Add Actions",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        layoutState.inactiveTiles.forEach { tile ->
                            InactiveTileChip(
                                tile = tile, 
                                onClick = { onAdd(tile) }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    } else {
        Surface(
            modifier = Modifier
                .width(340.dp)
                .padding(16.dp)
                .pointerInput(Unit) { detectTapGestures { } }
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MenuHeader(isEditMode, onToggleEdit)
                Spacer(modifier = Modifier.height(24.dp))
                DraggableGrid(
                    tiles = layoutState.activeTiles,
                    isEditMode = isEditMode,
                    columns = gridColumns,
                    onTileClick = onTileClick,
                    onResize = onResize,
                    onRemove = onRemove,
                    onMove = onMove,
                    onInteractionEnd = onInteractionEnd
                )

                AnimatedVisibility(visible = isEditMode && layoutState.inactiveTiles.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Add Actions",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            layoutState.inactiveTiles.forEach { tile ->
                                InactiveTileChip(
                                    tile = tile, 
                                    onClick = { onAdd(tile) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(isEditMode: Boolean, onToggleEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isEditMode) "Arrange Tiles" else "Power Menu",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        IconButton(
            onClick = onToggleEdit,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if(isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if(isEditMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                imageVector = if (isEditMode) Icons.Rounded.Check else Icons.Rounded.Edit,
                contentDescription = "Edit",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private val SPACING = 12.dp

@Composable
private fun DraggableGrid(
    tiles: List<TileConfig>,
    isEditMode: Boolean,
    columns: Int,
    onTileClick: (TileConfig) -> Unit,
    onResize: (String, Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onInteractionEnd: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val totalWidth = maxWidth
        val density = LocalDensity.current
        
        val cellSize = ((totalWidth - (SPACING * (columns - 1))) / columns)

        val itemPositions = remember(tiles, totalWidth, columns) {
            calculateGridPositions(tiles, columns)
        }
        
        val totalRows = itemPositions.maxOfOrNull { it.gridY + it.spanY } ?: 0
        val totalHeight = (cellSize * totalRows) + (SPACING * (totalRows - 1).coerceAtLeast(0))

        val draggingIndex = remember { mutableIntStateOf(-1) }
        val dragStartOffset = remember { mutableStateOf(Offset.Zero) }
        val totalDragOffset = remember { mutableStateOf(Offset.Zero) }

        Box(modifier = Modifier.height(totalHeight.coerceAtLeast(cellSize))) {
            if (isEditMode) {
                GridBackground(
                    rows = totalRows,
                    cols = columns,
                    cellSize = cellSize,
                    spacing = SPACING
                )
            }

            tiles.forEachIndexed { index, tile ->
                key(tile.id) {
                    val placement = itemPositions.getOrElse(index) { GridPlacement(0, 0, 1, 1) }
                    val xPosPx = with(density) { ((cellSize + SPACING) * placement.gridX).toPx() }
                    val yPosPx = with(density) { ((cellSize + SPACING) * placement.gridY).toPx() }

                    GridTile(
                        tile = tile,
                        index = index,
                        isEditMode = isEditMode,
                        cellSize = cellSize,
                        gridPositionPx = Offset(xPosPx, yPosPx),
                        draggingIndex = draggingIndex,
                        dragStartOffset = dragStartOffset,
                        totalDragOffset = totalDragOffset,
                        onTileClick = onTileClick,
                        onResize = onResize,
                        onRemove = onRemove,
                        onDragStart = {
                            if (isEditMode) {
                                draggingIndex.intValue = tiles.indexOfFirst { it.id == tile.id }
                                dragStartOffset.value = Offset(xPosPx, yPosPx)
                                totalDragOffset.value = Offset.Zero
                            }
                        },
                        onDragEnd = {
                            draggingIndex.intValue = -1
                            totalDragOffset.value = Offset.Zero
                            onInteractionEnd()
                        },
                        onResizeEnd = onInteractionEnd,
                        onDrag = { change, dragAmount ->
                            if (isEditMode) {
                                change.consume()
                                totalDragOffset.value += dragAmount

                                val tileWidth = (cellSize * tile.spanCols) + (SPACING * (tile.spanCols - 1))
                                val tileHeight = (cellSize * tile.spanRows) + (SPACING * (tile.spanRows - 1))
                                val currentCenterX = dragStartOffset.value.x + totalDragOffset.value.x + with(density) { tileWidth.toPx() / 2 }
                                val currentCenterY = dragStartOffset.value.y + totalDragOffset.value.y + with(density) { tileHeight.toPx() / 2 }

                                val targetIndex = itemPositions.indexOfFirst { p ->
                                    val px = with(density) { ((cellSize + SPACING) * p.gridX).toPx() }
                                    val py = with(density) { ((cellSize + SPACING) * p.gridY).toPx() }
                                    val pw = with(density) { ((cellSize * p.spanX) + (SPACING * (p.spanX - 1))).toPx() }
                                    val ph = with(density) { ((cellSize * p.spanY) + (SPACING * (p.spanY - 1))).toPx() }

                                    currentCenterX in px..(px + pw) && currentCenterY in py..(py + ph)
                                }

                                if (targetIndex != -1 && targetIndex != draggingIndex.intValue) {
                                    onMove(draggingIndex.intValue, targetIndex)
                                    draggingIndex.intValue = targetIndex
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GridTile(
    tile: TileConfig,
    index: Int,
    isEditMode: Boolean,
    cellSize: Dp,
    gridPositionPx: Offset,
    draggingIndex: State<Int>,
    dragStartOffset: State<Offset>,
    totalDragOffset: State<Offset>,
    onTileClick: (TileConfig) -> Unit,
    onResize: (String, Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onResizeEnd: () -> Unit,
    onDrag: (androidx.compose.ui.input.pointer.PointerInputChange, Offset) -> Unit
) {
    val isDragging = index == draggingIndex.value

    val targetOffset = if (isDragging) {
        IntOffset(
            (dragStartOffset.value.x + totalDragOffset.value.x).roundToInt(),
            (dragStartOffset.value.y + totalDragOffset.value.y).roundToInt()
        )
    } else {
        IntOffset(gridPositionPx.x.roundToInt(), gridPositionPx.y.roundToInt())
    }

    val animationSpec = if (isDragging) snap<IntOffset>() else spring<IntOffset>(stiffness = Spring.StiffnessMediumLow)

    val animatedOffset by animateIntOffsetAsState(
        targetValue = targetOffset,
        animationSpec = animationSpec,
        label = "tile_offset"
    )

    val zIndex = if (isDragging) 10f else 0f
    val scale = if (isDragging) 1.05f else 1f
    val alpha = if (isDragging) 0.9f else 1f

    val targetWidth = (cellSize * tile.spanCols) + (SPACING * (tile.spanCols - 1))
    val targetHeight = (cellSize * tile.spanRows) + (SPACING * (tile.spanRows - 1))

    val animatedWidth by animateDpAsState(targetValue = targetWidth, label = "tile_width")
    val animatedHeight by animateDpAsState(targetValue = targetHeight, label = "tile_height")

    Box(
        modifier = Modifier
            .offset { animatedOffset }
            .width(animatedWidth)
            .height(animatedHeight)
            .zIndex(zIndex)
            .scale(scale)
            .alpha(alpha)
    ) {
        TileItem(
            tile = tile,
            isEditMode = isEditMode,
            gridCellSize = cellSize,
            onClick = { onTileClick(tile) },
            onResize = { w, h -> onResize(tile.id, w, h) },
            onRemove = { onRemove(tile.id) },
            onDragStart = onDragStart,
            onDragEnd = onDragEnd,
            onResizeEnd = onResizeEnd,
            onDrag = onDrag
        )
    }
}

data class GridPlacement(val gridX: Int, val gridY: Int, val spanX: Int, val spanY: Int)

private fun calculateGridPositions(tiles: List<TileConfig>, columns: Int): List<GridPlacement> {
    val placements = mutableListOf<GridPlacement>()
    val occupied = mutableSetOf<Pair<Int, Int>>()
    
    var currentRow = 0
    
    tiles.forEach { tile ->
        var placed = false
        var searchRow = 0
        
        while (!placed) {
            for (col in 0 until columns) {
                if (col + tile.spanCols > columns) continue
                
                var fits = true
                for (r in 0 until tile.spanRows) {
                    for (c in 0 until tile.spanCols) {
                        if (occupied.contains((searchRow + r) to (col + c))) {
                            fits = false
                            break
                        }
                    }
                    if (!fits) break
                }
                
                if (fits) {
                    placements.add(GridPlacement(col, searchRow, tile.spanCols, tile.spanRows))
                    for (r in 0 until tile.spanRows) {
                        for (c in 0 until tile.spanCols) {
                            occupied.add((searchRow + r) to (col + c))
                        }
                    }
                    placed = true
                    break
                }
            }
            if (!placed) searchRow++
        }
    }
    return placements
}

@Composable
private fun GridBackground(rows: Int, cols: Int, cellSize: Dp, spacing: Dp) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = 2.dp.toPx()
        val color = Color.Gray.copy(alpha = 0.3f)
        
        for (r in 0 until rows) { 
            for (c in 0 until cols) {
                val x = (cellSize.toPx() + spacing.toPx()) * c + (cellSize.toPx() / 2)
                val y = (cellSize.toPx() + spacing.toPx()) * r + (cellSize.toPx() / 2)
                drawCircle(color, radius, Offset(x, y))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileItem(
    tile: TileConfig,
    isEditMode: Boolean,
    gridCellSize: Dp,
    onClick: () -> Unit,
    onResize: (Int, Int) -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onResizeEnd: () -> Unit,
    onDrag: (androidx.compose.ui.input.pointer.PointerInputChange, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isEmergency = remember(tile.id) { tile.id.contains("emergency") }
    val label = remember(tile.id) { getActionLabel(tile.action, context) }
    val iconBitmap = remember(tile.id) {
        tile.action.getIcon(context)?.toBitmap(96, 96)?.asImageBitmap()
    }

    val backgroundColor = when {
        isEmergency -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isEmergency -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDrag by rememberUpdatedState(onDrag)

    Box(
        modifier = modifier
            .pointerInput(isEditMode) {
                if (isEditMode) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { currentOnDragStart() },
                        onDragEnd = { currentOnDragEnd() },
                        onDragCancel = { currentOnDragEnd() },
                        onDrag = { change, amount -> currentOnDrag(change, amount) }
                    )
                }
            }
    ) {
        Surface(
            onClick = onClick,
            enabled = !isEditMode,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { },
            shape = RoundedCornerShape(24.dp),
            color = backgroundColor,
            border = if(isEditMode) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f)) else null
        ) {
            if (tile.spanCols == 1) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconBitmap != null) {
                        Icon(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = contentColor
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    if (iconBitmap != null) {
                        Icon(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp).align(Alignment.CenterStart),
                            tint = contentColor
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(start = 40.dp)
                            .basicMarquee()
                    )
                }
            }
        }

        if (isEditMode) {
            Surface(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .size(28.dp)
                    .zIndex(2f),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                shadowElevation = 4.dp
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.padding(6.dp),
                    tint = MaterialTheme.colorScheme.onError
                )
            }

            ResizeHandle(
                currentCols = tile.spanCols,
                currentRows = tile.spanRows,
                gridCellSize = gridCellSize,
                onResize = onResize,
                onResizeEnd = onResizeEnd,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
private fun ResizeHandle(
    currentCols: Int,
    currentRows: Int,
    gridCellSize: Dp,
    onResize: (Int, Int) -> Unit,
    onResizeEnd: () -> Unit,
    modifier: Modifier
) {
    val density = LocalDensity.current
    val stepPx = with(density) { (gridCellSize + SPACING).toPx() }

    val currentColsState by rememberUpdatedState(currentCols)
    val currentRowsState by rememberUpdatedState(currentRows)
    val onResizeState by rememberUpdatedState(onResize)
    val onResizeEndState by rememberUpdatedState(onResizeEnd)

    var accumulatedDragX by remember { mutableFloatStateOf(0f) }
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }
    
    var hasMoved by remember { mutableStateOf(false) }
    val touchSlop = 20f

    Box(
        modifier = modifier
            .size(40.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { 
                        accumulatedDragX = 0f
                        accumulatedDragY = 0f
                        hasMoved = false
                    },
                    onDragEnd = {
                        accumulatedDragX = 0f
                        accumulatedDragY = 0f
                        hasMoved = false
                        onResizeEndState()
                    },
                    onDragCancel = {
                        accumulatedDragX = 0f
                        accumulatedDragY = 0f
                        hasMoved = false
                        onResizeEndState()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDragX += dragAmount.x
                        accumulatedDragY += dragAmount.y
                        
                        if (!hasMoved) {
                            val dist = (accumulatedDragX * accumulatedDragX) + (accumulatedDragY * accumulatedDragY)
                            if (dist > touchSlop * touchSlop) {
                                hasMoved = true
                            }
                        }

                        if (hasMoved) {
                            val threshold = stepPx * 0.75f
                            
                            val colChange = if (abs(accumulatedDragX) > threshold) (accumulatedDragX / threshold).toInt() else 0
                            val rowChange = if (abs(accumulatedDragY) > threshold) (accumulatedDragY / threshold).toInt() else 0
                            
                            if (colChange != 0 || rowChange != 0) {
                                val newCols = (currentColsState + colChange).coerceIn(1, 4)
                                val newRows = (currentRowsState + rowChange).coerceIn(1, 3)
                                
                                if (newCols != currentColsState || newRows != currentRowsState) {
                                    onResizeState(newCols, newRows)
                                    if (newCols != currentColsState) accumulatedDragX = 0f
                                    if (newRows != currentRowsState) accumulatedDragY = 0f
                                }
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.BottomEnd
    ) {
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = "Resize",
            modifier = Modifier
                .padding(8.dp)
                .rotate(45f)
                .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                .padding(4.dp)
                .size(16.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun InactiveTileChip(
    tile: TileConfig,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val label = remember(tile.id) { getActionLabel(tile.action, context) }
    val iconBitmap = remember(tile.id) {
        tile.action.getIcon(context)?.toBitmap(64, 64)?.asImageBitmap()
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun rememberHapticClick(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    }
}

@Composable
private fun sliderTrackColor(blurEnabled: Boolean): Color =
    if (blurEnabled) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    }

@Composable
private fun ConfirmationSliderView(
    title: String,
    message: String,
    iconBitmap: ImageBitmap?,
    color: Color,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    blurEnabled: Boolean = false
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var maxOffsetPx by remember { mutableFloatStateOf(0f) }
    val threshold = 0.75f
    var hasFiredThresholdHaptic by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val thumbInset = 10.dp
    val view = LocalView.current
    val cancelHaptic = rememberHapticClick()

    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color.Black

    Column(
        modifier = Modifier
            .width(320.dp)
            .padding(16.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = textColor
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(sliderTrackColor(blurEnabled))
                .border(
                    1.dp,
                    if (blurEnabled) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
                    },
                    RoundedCornerShape(36.dp)
                )
                .padding(thumbInset)
                .onGloballyPositioned { coordinates ->
                    maxOffsetPx = coordinates.size.width - with(density) { 56.dp.toPx() }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    alpha = if (maxOffsetPx > 0f) (1f - (offsetX / maxOffsetPx)).coerceIn(0f, 1f) else 1f
                },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(color)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                if (maxOffsetPx > 0 && offsetX / maxOffsetPx > threshold) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    onConfirm()
                                } else {
                                    offsetX = 0f
                                }
                                hasFiredThresholdHaptic = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxOffsetPx)
                                val pastThreshold = maxOffsetPx > 0 && offsetX / maxOffsetPx > threshold
                                if (pastThreshold && !hasFiredThresholdHaptic) {
                                    hasFiredThresholdHaptic = true
                                    view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                                } else if (!pastThreshold && hasFiredThresholdHaptic) {
                                    hasFiredThresholdHaptic = false
                                    view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    Icon(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        TextButton(
            onClick = {
                cancelHaptic()
                onCancel()
            }
        ) {
            Text(
                text = "Cancel",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}