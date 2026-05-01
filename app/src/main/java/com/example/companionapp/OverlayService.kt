package com.example.companionapp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.companionapp.ui.theme.CompanionAppTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    companion object {
        var songTitle = mutableStateOf("No Media Playing")
        var artistName = mutableStateOf("Unknown Artist")
        val notifications = mutableStateListOf<NotificationData>()
        var isCallActive = mutableStateOf(false)
        var isCallIn = mutableStateOf(false)
        var callerName = mutableStateOf("")
        var batteryLevel = mutableIntStateOf(-1)
        var connectionStatus = mutableStateOf("Disconnected")
        var isExpanded = mutableStateOf(true)
        var showNotificationPanel = mutableStateOf(false)
        var lastNotification = mutableStateOf<NotificationData?>(null)
        
        var isPlaying = mutableStateOf(false)
        var progress = mutableLongStateOf(0L)
        var duration = mutableLongStateOf(0L)

        fun updateMedia(title: String, artist: String) {
            songTitle.value = title
            artistName.value = artist
        }

        fun updatePlaybackState(playing: Boolean) {
            isPlaying.value = playing
        }

        fun updateProgress(pos: Long, dur: Long) {
            progress.longValue = pos
            duration.longValue = dur
        }

        fun addNotification(notif: NotificationData) {
            val index = notifications.indexOfFirst { it.key == notif.key }
            if (index != -1) {
                notifications[index] = notif
            } else {
                notifications.add(0, notif)
            }
            lastNotification.value = notif
        }

        fun removeNotification(key: String) {
            notifications.removeAll { it.key == key }
        }

        fun updateCall(active: Boolean, name: String) {
            isCallActive.value = active
            callerName.value = name
        }

        fun updateBattery(level: Int) {
            batteryLevel.intValue = level
        }
        
        fun updateConnectionStatus(status: String) {
            connectionStatus.value = status
        }

        fun handleMessage(message: String, isSender: Boolean, commandHandler: (String) -> Unit) {
            if (isSender && message.startsWith("/")) {
                commandHandler(message)
                return
            }
            
            val parts = message.split("|")
            if (parts.isEmpty()) return
            val type = parts[0]
            
            when (type) {
                "CALL_END" -> {
                    updateCall(false, "")
                    isCallIn.value = false
                }
                "CALL_ACTIVE" -> {
                    isCallActive.value = true
                    isCallIn.value = false
                }
                else -> {
                    if (parts.size >= 2) {
                        val value = parts[1]
                        when (type) {
                            "TITLE" -> updateMedia(value, artistName.value)
                            "ARTIST" -> updateMedia(songTitle.value, value)
                            "NOTIF_ADD" -> {
                                if (parts.size >= 5) {
                                    addNotification(NotificationData(parts[1], parts[2], parts[3], parts[4]))
                                }
                            }
                            "NOTIF_REMOVE" -> removeNotification(value)
                            "CALL_IN" -> {
                                updateCall(true, value)
                                isCallIn.value = true
                                isExpanded.value = true
                            }
                            "BATTERY" -> updateBattery(value.toIntOrNull() ?: -1)
                            "PLAYING" -> updatePlaybackState(value == "1")
                            "PROGRESS" -> {
                                if (parts.size >= 3) {
                                    updateProgress(parts[1].toLongOrNull() ?: 0L, parts[2].toLongOrNull() ?: 0L)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    private fun showOverlay() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)

        composeView.setContent {
            CompanionAppTheme {
                OverlayContent()
            }
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager.addView(composeView, params)
        overlayView = composeView
    }

    @Composable
    fun OverlayContent() {
        val dragPosition = remember { object { var x = params.x.toFloat(); var y = params.y.toFloat() } }
        var popupNotification by remember { mutableStateOf<NotificationData?>(null) }

        // Design System Constants (M3 Android 16 - Verdant Horizon)
        val M3Primary = Color(0xFF006B5B)
        val M3Surface = Color(0xFFFBFDF9)
        val M3OnSurface = Color(0xFF191C1B)
        val M3SecondaryContainer = Color(0xFFCCE8E3)
        val M3OnSecondaryContainer = Color(0xFF05201C)
        val M3Error = Color(0xFFBA1A1A)
        val M3SurfaceVariant = Color(0xFFDBE5E0)

        // Performance Optimization: Explicit Animated Width
        val targetWidth by remember {
            derivedStateOf {
                when {
                    isExpanded.value -> 320.dp
                    popupNotification != null -> 280.dp
                    else -> 64.dp
                }
            }
        }
        val animatedWidth by animateDpAsState(
            targetValue = targetWidth,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "OverlayWidth"
        )

        LaunchedEffect(lastNotification.value) {
            lastNotification.value?.let {
                if (!showNotificationPanel.value) {
                    popupNotification = it
                    delay(4000)
                    popupNotification = null
                }
            }
        }

        Surface(
            modifier = Modifier
                .padding(8.dp)
                .width(animatedWidth)
                .wrapContentHeight(),
            shape = RoundedCornerShape(32.dp),
            color = M3Surface,
            tonalElevation = 2.dp, // Lowered for performance
            shadowElevation = 4.dp, // Lowered for performance
            border = BorderStroke(1.dp, M3SurfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .padding(if (isExpanded.value) 20.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (popupNotification != null && !isExpanded.value) {
                    // Minimized Notification Popup
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notifications, null, tint = M3Primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(popupNotification!!.title, color = M3OnSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                            Text(popupNotification!!.text, color = M3OnSurface.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else if (!isExpanded.value) {
                    // Small Status Pill
                    Row(
                        modifier = Modifier
                            .clickable { isExpanded.value = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .defaultMinSize(minWidth = 64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isCallActive.value) {
                            Icon(Icons.Default.Call, "Call", tint = M3Error, modifier = Modifier.size(24.dp))
                        } else {
                            val level = batteryLevel.intValue
                            Icon(
                                imageVector = when {
                                    level == -1 -> Icons.Default.QuestionMark
                                    level <= 20 -> Icons.Default.BatteryAlert
                                    else -> Icons.Default.BatteryFull
                                },
                                contentDescription = "Battery",
                                tint = if (level != -1 && level <= 20) M3Error else M3Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            if (level != -1) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "$level%",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (level <= 20) M3Error else M3OnSurface
                                )
                            }
                        }
                    }
                } else {
                    // Fully Expanded View
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Header - Draggable
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        dragPosition.x += dragAmount.x
                                        dragPosition.y += dragAmount.y
                                        params.x = dragPosition.x.roundToInt()
                                        params.y = dragPosition.y.roundToInt()
                                        windowManager.updateViewLayout(overlayView, params)
                                    }
                                }
                                .padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = M3SecondaryContainer, shape = CircleShape) {
                                    Text(
                                        if(connectionStatus.value == "Connected") "ACTIVE" else "OFFLINE", 
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Black,
                                        color = M3Primary
                                    )
                                }
                                
                                val level = batteryLevel.intValue
                                if (level != -1) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        imageVector = if (level <= 20) Icons.Default.BatteryAlert else Icons.Default.BatteryFull,
                                        contentDescription = null,
                                        tint = if (level <= 20) M3Error else M3Primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "$level%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (level <= 20) M3Error else M3OnSurface
                                    )
                                }
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { showNotificationPanel.value = !showNotificationPanel.value },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showNotificationPanel.value) Icons.Default.Close else Icons.Default.Notifications,
                                        contentDescription = null,
                                        tint = M3OnSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = { isExpanded.value = false }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, null, tint = M3OnSurface.copy(alpha = 0.6f))
                                }
                            }
                        }

                        if (isCallActive.value) {
                            CallSection(M3Primary, M3OnSurface, M3Error, M3SecondaryContainer)
                        } else if (showNotificationPanel.value) {
                            NotificationSection(M3OnSurface, M3SurfaceVariant)
                        } else {
                            MediaSection(M3Primary, M3OnSurface, M3OnSecondaryContainer, M3SecondaryContainer)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CallSection(primary: Color, onSurface: Color, error: Color, container: Color) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = container)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VOICE LINK", color = primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                Text(callerName.value, color = onSurface, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { MainActivity.sendMessage("/call/decline") }, modifier = Modifier.background(error, CircleShape)) {
                        Icon(Icons.Default.CallEnd, null, tint = Color.White)
                    }
                    if (isCallIn.value) {
                        IconButton(onClick = { MainActivity.sendMessage("/call/answer") }, modifier = Modifier.background(primary, CircleShape)) {
                            Icon(Icons.Default.Call, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NotificationSection(onSurface: Color, variant: Color) {
        Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            if (notifications.isEmpty()) {
                Text("No pending data", color = onSurface.copy(alpha = 0.4f), modifier = Modifier.padding(16.dp))
            } else {
                notifications.forEach { notif ->
                    Surface(
                        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = variant.copy(alpha = 0.3f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(notif.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(notif.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MediaSection(primary: Color, onSurface: Color, onContainer: Color, container: Color) {
        // Read states at the highest level needed to avoid unnecessary recomposition of children
        val currentTitle by songTitle
        val currentArtist by artistName
        val currentIsPlaying by isPlaying
        val currentProgress by progress
        val currentDuration by duration

        Column {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = container)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(currentTitle, color = onContainer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(currentArtist, color = onContainer.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    
                    if (currentDuration > 0) {
                        Spacer(Modifier.height(12.dp))
                        // Optimization: LinearProgressIndicator is efficient, but we ensure progress is calculated once
                        val progressFraction = remember(currentProgress, currentDuration) {
                            currentProgress.toFloat() / currentDuration.toFloat()
                        }
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = primary,
                            trackColor = primary.copy(alpha = 0.1f),
                            strokeCap = StrokeCap.Round
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalIconButton(onClick = { MainActivity.sendMessage("/media/prev") }) { Icon(Icons.Default.SkipPrevious, null) }
                Button(
                    onClick = { MainActivity.sendMessage("/media/playpause") },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = primary)
                ) {
                    Icon(if (currentIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                }
                FilledTonalIconButton(onClick = { MainActivity.sendMessage("/media/next") }) { Icon(Icons.Default.SkipNext, null) }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        overlayView?.let { windowManager.removeView(it) }
    }
}
