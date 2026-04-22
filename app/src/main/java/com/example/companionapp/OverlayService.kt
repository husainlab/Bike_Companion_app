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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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

        Surface(
            modifier = Modifier
                .padding(4.dp)
                .wrapContentSize(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1E1E1E).copy(alpha = 0.98f),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Column(
                modifier = Modifier
                    .padding(if (isExpanded.value) 12.dp else 8.dp)
                    .width(if (isExpanded.value) 640.dp else 160.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isExpanded.value) {
                    Box(
                        modifier = Modifier
                            .clickable { isExpanded.value = true }
                            .padding(4.dp)
                            .defaultMinSize(minWidth = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCallActive.value) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Call, "Call", tint = Color.Green, modifier = Modifier.size(48.dp))
                                Text("CALLING", color = Color.Green, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    if (connectionStatus.value == "Connected") Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled, 
                                    "BT", 
                                    tint = if(connectionStatus.value == "Connected") Color.Cyan else Color.Red, 
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                val level = batteryLevel.intValue
                                if (level != -1) {
                                    Text("$level%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                                } else if (connectionStatus.value == "Connected") {
                                    Text("SYNCING", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                } else {
                    // Expanded View Header - DRAGGABLE AREA
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
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                if (connectionStatus.value == "Connected") Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled, 
                                null, 
                                tint = if(connectionStatus.value == "Connected") Color.Cyan else Color.Red, 
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if(connectionStatus.value == "Connected") "Connected" else "Offline", 
                                color = Color.White.copy(alpha = 0.6f), 
                                fontSize = 10.sp
                            )
                            
                            Spacer(Modifier.weight(1f))

                            val level = batteryLevel.intValue
                            if (level != -1) {
                                Icon(
                                    when {
                                        level > 80 -> Icons.Default.BatteryFull
                                        level > 20 -> Icons.Default.Battery5Bar
                                        else -> Icons.Default.BatteryAlert
                                    }, 
                                    null, 
                                    tint = if (level <= 15) Color.Red else Color.Cyan, 
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("$level%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        IconButton(onClick = { isExpanded.value = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    if (isCallActive.value) {
                        // CALL VIEW
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isCallIn.value) Color(0xFF2E7D32) else Color(0xFF1565C0), RoundedCornerShape(16.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(if (isCallIn.value) "Incoming Call" else "Active Call", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Text(callerName.value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, maxLines = 1)
                            
                            Spacer(Modifier.height(16.dp))
                            
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Button(
                                    onClick = { MainActivity.sendMessage("/call/decline") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                    shape = CircleShape,
                                    modifier = Modifier.size(50.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.CallEnd, "End", tint = Color.White, modifier = Modifier.size(24.dp))
                                }
                                
                                if (isCallIn.value) {
                                    Button(
                                        onClick = { MainActivity.sendMessage("/call/answer") },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Green),
                                        shape = CircleShape,
                                        modifier = Modifier.size(50.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Icon(Icons.Default.Call, "Answer", tint = Color.White, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        // MEDIA VIEW
                        Column(
                            modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, null, tint = Color.Cyan, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(songTitle.value, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                                    Text(artistName.value, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                                }
                            }
                            
                            if (duration.longValue > 0) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { progress.longValue.toFloat() / duration.longValue.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(3.dp),
                                    color = Color.Cyan,
                                    trackColor = Color.White.copy(alpha = 0.1f),
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { MainActivity.sendMessage("/media/prev") }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            IconButton(
                                onClick = { MainActivity.sendMessage("/media/playpause") },
                                modifier = Modifier.size(48.dp).background(Color.Cyan, CircleShape)
                            ) {
                                Icon(if (isPlaying.value) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(32.dp))
                            }
                            IconButton(onClick = { MainActivity.sendMessage("/media/next") }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }

                    if (notifications.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(6.dp))
                        Text("NOTIFICATIONS", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())
                        ) {
                            notifications.forEach { notif ->
                                Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(modifier = Modifier.size(32.dp), shape = CircleShape, color = Color.Cyan.copy(alpha = 0.2f)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(notif.title.take(1).uppercase(), color = Color.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(notif.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(notif.text, color = Color.Gray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
