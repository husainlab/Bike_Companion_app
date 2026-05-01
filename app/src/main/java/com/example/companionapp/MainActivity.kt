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

    private fun handleIncomingCommand(command: String) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val telecomManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        } else null

        when (command) {
            "/media/playpause" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "/media/next" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "/media/prev" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "/volume/up" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "/volume/down" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "/call/answer" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        telecomManager?.acceptRingingCall()
                    }
                }
            }
            "/call/decline" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        telecomManager?.endCall()
                    }
                }
            }
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun checkNotificationPermission(onGranted: (() -> Unit)? = null) {
        val nls = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (nls == null || !nls.contains(packageName)) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            Toast.makeText(this, "Please enable notification access for this app", Toast.LENGTH_LONG).show()
        } else {
            onGranted?.invoke()
        }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream? = mmSocket.inputStream
        private val mmOutStream: OutputStream? = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024)

        override fun run() {
            var numBytes: Int
            while (true) {
                try {
                    numBytes = mmInStream?.read(mmBuffer) ?: -1
                    if (numBytes == -1) break
                    val message = String(mmBuffer, 0, numBytes)
                    handleIncomingMessage(message)
                } catch (e: IOException) {
                    updateStatus("Disconnected")
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                mmOutStream?.write(bytes)
            } catch (e: IOException) {
                updateStatus("Send failed")
            }
        }

        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {}
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SENDER MODE", style = MaterialTheme.typography.labelLarge, color = Color(0xFF006B5B), fontWeight = FontWeight.Black)
            Button(onClick = onSwitchMode, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCCE8E3), contentColor = Color(0xFF00201A))) {
                Text("Switch to Receiver")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFDBE5E0))
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (isServerRunning) Icons.Default.BluetoothAudio else Icons.Default.BluetoothDisabled,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = if (isServerRunning) Color(0xFF006B5B) else Color.Gray
                )
                Spacer(Modifier.height(16.dp))
                Text(status, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Bluetooth Server", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }

        Button(
            onClick = if (isServerRunning) onStopServer else onStartServer,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = if (isServerRunning) Color(0xFFBA1A1A) else Color(0xFF006B5B))
        ) {
            Text(if (isServerRunning) "Stop Server" else "Start Server")
        }

        OutlinedButton(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CircleShape
        ) {
            Text("Repair Notification Access")
        }

        Spacer(Modifier.weight(1f))

        Text("NOW STREAMING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Gray)
        Text(songTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1)
        Text(artistName, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
    }
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RECEIVER MODE", style = MaterialTheme.typography.labelLarge, color = Color(0xFF006B5B), fontWeight = FontWeight.Black)
            Button(onClick = onSwitchMode, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCCE8E3), contentColor = Color(0xFF00201A))) {
                Text("Switch to Sender")
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFDBE5E0))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Icon(Icons.Default.Link, null, tint = Color(0xFF006B5B))
                    Text(status, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("Connection", fontSize = 12.sp, color = Color.Gray)
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFDBE5E0))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Icon(Icons.Default.BatteryChargingFull, null, tint = Color(0xFF006B5B))
                    Text(if (batteryLevel == -1) "--%" else "$batteryLevel%", fontWeight = FontWeight.Bold)
                    Text("Phone Battery", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onConnectClick,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006B5B))
        ) {
            Icon(Icons.Default.Bluetooth, null)
            Spacer(Modifier.width(8.dp))
            Text("Connect to Sender")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartOverlay,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4B635C))
            ) {
                Text("Start Overlay")
            }
            OutlinedButton(
                onClick = onStopOverlay,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = CircleShape
            ) {
                Text("Stop Overlay")
            }
        }

        Spacer(Modifier.weight(1f))
        
        Surface(
            color = Color(0xFFCCE8E3).copy(alpha = 0.5f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Color(0xFF006B5B))
                Spacer(Modifier.width(12.dp))
                Text("Ensure the sender phone is paired in system settings first.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun DevicePickerDialog(
    devices: List<BluetoothDevice>,
    onDismiss: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Sender Device") },
        text = {
            LazyColumn {
                items(devices) { device ->
                    ListItem(
                        headlineContent = { 
                            if (ActivityCompat.checkSelfPermission(LocalContext.current, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                Text(device.name ?: "Unknown Device")
                            } else {
                                Text("Unknown Device")
                            }
                        },
                        supportingContent = { Text(device.address) },
                        modifier = Modifier.clickable { onDeviceSelected(device) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
