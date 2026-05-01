package com.example.companionapp

import android.Manifest
import android.bluetooth.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.companionapp.ui.theme.CompanionAppTheme
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

data class NotificationData(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String
)

class MainActivity : ComponentActivity() {

    companion object {
        private val APP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private var connectedThread: ConnectedThread? = null

        fun sendMessage(message: String) {
            connectedThread?.write(message.toByteArray())
        }
    }

    private var isSenderMode by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("Disconnected")
    private var showDevicePicker by mutableStateOf(false)
    private var isServerRunning by mutableStateOf(false)
    
    private var lastConnectedDeviceAddress: String? = null
    private var reconnectJob: Job? = null
    private var pendingBluetoothAction: (() -> Unit)? = null
    
    private lateinit var prefs: android.content.SharedPreferences

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            pendingBluetoothAction?.invoke()
        } else {
            Toast.makeText(this, "Permissions are required for this app to work", Toast.LENGTH_SHORT).show()
        }
        pendingBluetoothAction = null
    }
    
    // Receiver States (shared with OverlayService)
    private var songTitle by mutableStateOf("No Media Playing")
    private var artistName by mutableStateOf("Unknown Artist")
    private val notifications = mutableStateListOf<NotificationData>()
    private var isCallActive by mutableStateOf(false)
    private var callerName by mutableStateOf("")
    private var batteryLevel by mutableIntStateOf(-1)

    private var bluetoothAdapter: BluetoothAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        prefs = getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
        isSenderMode = prefs.getBoolean("is_sender_mode", false)
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        startReconnectLoop()

        setContent {
            LaunchedEffect(isSenderMode) {
                if (!isSenderMode) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            CompanionAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFBFDF9)) {
                    if (isSenderMode) {
                        SenderScreen(
                            status = connectionStatus,
                            isServerRunning = isServerRunning,
                            songTitle = songTitle,
                            artistName = artistName,
                            onSwitchMode = { 
                                isSenderMode = false 
                                prefs.edit().putBoolean("is_sender_mode", false).apply()
                            },
                            onRequestPermissions = { checkNotificationPermission() },
                            onStartServer = { 
                                requestRequiredPermissions {
                                    checkNotificationPermission {
                                        startServer()
                                    }
                                }
                            },
                            onStopServer = { stopServer() }
                        )
                    } else {
                        ReceiverScreen(
                            status = connectionStatus,
                            batteryLevel = batteryLevel,
                            songTitle = songTitle,
                            artistName = artistName,
                            onSwitchMode = { 
                                isSenderMode = true 
                                prefs.edit().putBoolean("is_sender_mode", true).apply()
                            },
                            onConnectClick = { 
                                if (hasRequiredPermissions()) {
                                    showDevicePicker = true 
                                } else {
                                    requestRequiredPermissions { showDevicePicker = true }
                                }
                            },
                            onStartOverlay = {
                                if (Settings.canDrawOverlays(this@MainActivity)) {
                                    startService(Intent(this@MainActivity, OverlayService::class.java))
                                } else {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:$packageName")
                                    )
                                    startActivity(intent)
                                }
                            },
                            onStopOverlay = {
                                stopService(Intent(this@MainActivity, OverlayService::class.java))
                            }
                        )
                    }
                }

                if (showDevicePicker) {
                    DevicePickerDialog(
                        devices = getPairedDevices(),
                        onDismiss = { showDevicePicker = false },
                        onDeviceSelected = { device ->
                            connectToDevice(device)
                            showDevicePicker = false
                        }
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isSenderMode && connectionStatus == "Connected") {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    sendMessage("/volume/up")
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    sendMessage("/volume/down")
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        permissions.add(Manifest.permission.READ_PHONE_STATE)
        permissions.add(Manifest.permission.READ_CALL_LOG)
        permissions.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        return permissions.all { 
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED 
        }
    }

    private fun requestRequiredPermissions(onGranted: (() -> Unit)? = null) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        permissions.add(Manifest.permission.READ_PHONE_STATE)
        permissions.add(Manifest.permission.READ_CALL_LOG)
        permissions.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val ungranted = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isEmpty()) {
            onGranted?.invoke()
            return
        }

        pendingBluetoothAction = onGranted
        requestPermissionLauncher.launch(ungranted.toTypedArray())
    }

    private fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasRequiredPermissions()) {
            return emptyList()
        }
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun startServer() {
        if (isServerRunning) return
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions { startServer() }
            return
        }
        isServerRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            var serverSocket: BluetoothServerSocket? = null
            try {
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("CompanionApp", APP_UUID)
                while (isServerRunning) {
                    updateStatus("Waiting for connection...")
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: IOException) {
                        if (isServerRunning) {
                            updateStatus("Server Error")
                            delay(2000)
                            // Re-open server socket if it was closed unexpectedly
                            try { serverSocket?.close() } catch (ex: Exception) {}
                            serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("CompanionApp", APP_UUID)
                            continue
                        } else {
                            break
                        }
                    }
                    socket?.let { 
                        manageConnectedSocket(it)
                        // Wait for the connection thread to finish before accepting again
                        // Or let it run in parallel if you want multiple connections,
                        // but here we want the server to return to accept() AFTER disconnect.
                        while (connectedThread?.isAlive == true && isServerRunning) {
                            delay(1000)
                        }
                    }
                }
            } catch (e: Exception) {
                updateStatus("Server failed")
                isServerRunning = false
            } finally {
                try { serverSocket?.close() } catch (e: IOException) {}
            }
        }
    }

    private fun stopServer() {
        isServerRunning = false
        connectedThread?.cancel()
        connectedThread = null
        updateStatus("Server Stopped")
    }

    private fun connectToDevice(device: BluetoothDevice) {
        lastConnectedDeviceAddress = device.address
        getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
            .edit().putString("last_device", device.address).apply()
        
        startReconnectLoop() // Start loop if not already running

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (connectionStatus == "Connected") return@launch
                updateStatus("Connecting...")
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    updateStatus("Permission denied")
                    return@launch
                }
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                manageConnectedSocket(socket)
            } catch (e: IOException) {
                updateStatus("Disconnected")
            } catch (e: SecurityException) {
                updateStatus("Permission denied")
            }
        }
    }

    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (!isSenderMode && connectionStatus != "Connected") {
                    val address = lastConnectedDeviceAddress ?: getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
                        .getString("last_device", null)
                    
                    if (address != null && bluetoothAdapter != null) {
                        try {
                            val device = bluetoothAdapter!!.getRemoteDevice(address)
                            updateStatus("Auto-reconnecting...")
                            val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                            socket.connect()
                            manageConnectedSocket(socket)
                        } catch (e: Exception) {
                            updateStatus("Disconnected")
                        }
                    }
                }
                delay(5000)
            }
        }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        updateStatus("Connected")
        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
        
        // Trigger a sync when connected
        sendBroadcast(Intent("com.example.companionapp.ACTION_SYNC"))
    }

    private fun updateStatus(status: String) {
        runOnUiThread { 
            connectionStatus = status 
            OverlayService.updateConnectionStatus(status)
        }
    }

    private fun handleIncomingMessage(message: String) {
        runOnUiThread {
            OverlayService.handleMessage(message, isSenderMode) { command ->
                handleIncomingCommand(command)
            }
            
            // Also update local UI state for redundancy
            val parts = message.split("|")
            if (parts.size >= 2) {
                val type = parts[0]
                when (type) {
                    "TITLE" -> songTitle = parts[1]
                    "ARTIST" -> artistName = parts[1]
                    "CALL_IN" -> {
                        isCallActive = true
                        callerName = parts[1]
                    }
                    "CALL_ACTIVE" -> {
                        isCallActive = true
                    }
                    "CALL_END" -> {
                        isCallActive = false
                        callerName = ""
                    }
                    "BATTERY" -> batteryLevel = parts[1].toIntOrNull() ?: -1
                }
            }
        }
    }

    private fun handleIncomingCommand(path: String) {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            when (path) {
                "/call/answer" -> {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        telecomManager.acceptRingingCall()
                    }
                }
                "/call/decline" -> {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        telecomManager.endCall()
                    }
                }
                "/media/playpause" -> {
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                }
                "/media/next" -> {
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                }
                "/media/prev" -> {
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                }
                "/volume/up" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                "/volume/down" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
            }
        } catch (e: Exception) {
            // Command execution failed
        }
    }

    private fun checkNotificationPermission(onGranted: (() -> Unit)? = null) {
        val cn = ComponentName(this, CompanionNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val enabled = flat != null && flat.contains(cn.flattenToString())
        if (!enabled) {
            Toast.makeText(this, "Please enable notification access for this app", Toast.LENGTH_LONG).show()
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } else {
            onGranted?.invoke()
        }
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = socket.inputStream
        private val mmOutStream: OutputStream = socket.outputStream

        override fun run() {
            val reader = mmInStream.bufferedReader()
            while (true) {
                try {
                    val line = reader.readLine() ?: ""
                    if (line.isNotEmpty()) {
                        handleIncomingMessage(line)
                    }
                } catch (e: IOException) {
                    updateStatus("Disconnected")
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
                mmOutStream.write("\n".toByteArray())
                mmOutStream.flush()
            } catch (e: IOException) {
                // Error writing
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {
                // Error closing socket
            }
        }
    }
}

@Composable
fun DevicePickerDialog(devices: List<BluetoothDevice>, onDismiss: () -> Unit, onDeviceSelected: (BluetoothDevice) -> Unit) {
    val M3Primary = Color(0xFF006B5B)
    val M3Surface = Color(0xFFFBFDF9)
    val M3OnSurface = Color(0xFF191C1B)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        containerColor = M3Surface,
        title = {
            Text(
                "Select Device",
                color = M3OnSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium
            )
        },
        text = {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn {
                    items(devices) { device ->
                        @OptIn(ExperimentalMaterial3Api::class)
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = M3OnSurface,
                                supportingColor = Color(0xFF4B635C)
                            ),
                            headlineContent = {
                                val name = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(LocalContext.current, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                    device.name ?: device.address
                                } else {
                                    device.address
                                }
                                Text(name, fontWeight = FontWeight.Bold)
                            },
                            supportingContent = {
                                Text(device.address, fontSize = 12.sp)
                            },
                            modifier = Modifier
                                .clickable { onDeviceSelected(device) }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = M3Primary)
            ) {
                Text("Dismiss", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ReceiverScreen(
    status: String,
    batteryLevel: Int,
    songTitle: String,
    artistName: String,
    onSwitchMode: () -> Unit,
    onConnectClick: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(64.dp))

        Text(
            "Companion Receiver",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if(status == "Connected") Icons.Default.CheckCircle else Icons.Default.Info,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        status,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (status == "Connected") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Battery: ${if(batteryLevel != -1) "$batteryLevel%" else "Unknown"}", color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(songTitle, color = MaterialTheme.colorScheme.primary, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            if (status != "Connected") {
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Bluetooth, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connect Device", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStartOverlay,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Show Controls Overlay", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(
                    onClick = onStopOverlay,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape
                ) {
                    Text("Stop Service", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(80.dp))

            OutlinedButton(
                onClick = onSwitchMode,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape
            ) {
                Text("Switch to Sender Mode", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun SenderScreen(
    status: String,
    isServerRunning: Boolean,
    songTitle: String,
    artistName: String,
    onSwitchMode: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(64.dp))

        Text(
            "Signal Sender",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = if(isServerRunning) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).background(if(isServerRunning) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        if(isServerRunning) "Broadcasting Signal" else "Server Offline",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isServerRunning && status == "Connected") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Device Linked: $status", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            if (!isServerRunning) {
                Button(
                    onClick = onStartServer,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Podcasts, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Server", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStopServer,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Broadcasting", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            FilledTonalButton(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape
            ) {
                Text("Manage Permissions", color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(80.dp))

            OutlinedButton(
                onClick = onSwitchMode,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape
            ) {
                Text("Switch to Receiver Mode", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
