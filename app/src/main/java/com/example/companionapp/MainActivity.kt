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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    if (isSenderMode) {
                        SenderScreen(
                            status = connectionStatus,
                            isServerRunning = isServerRunning,
                            onSwitchMode = { isSenderMode = false },
                            onRequestPermissions = { checkNotificationPermission() },
                            onStartServer = { 
                                requestRequiredPermissions {
                                    checkNotificationPermission {
                                        startServer()
                                    }
                                }
                            },
                            onStopServer = { stopServer() },
                            onSimulateCall = {
                                if (connectionStatus == "Connected") {
                                    sendMessage("CALL_IN|Test Caller")
                                } else {
                                    Toast.makeText(this@MainActivity, "Connect first", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onEndSimulatedCall = {
                                sendMessage("CALL_END")
                            }
                        )
                    } else {
                        ReceiverScreen(
                            status = connectionStatus,
                            onSwitchMode = { isSenderMode = true },
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Primary Phone") },
        text = {
            LazyColumn {
                items(devices) { device ->
                    @OptIn(ExperimentalMaterial3Api::class)
                    ListItem(
                        headlineContent = { 
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(LocalContext.current, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                Text(device.name ?: device.address)
                            } else {
                                Text(device.address)
                            }
                        },
                        modifier = Modifier.clickable { onDeviceSelected(device) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ReceiverScreen(
    status: String,
    onSwitchMode: () -> Unit,
    onConnectClick: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Receiver Mode", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Status: $status", color = Color.Cyan, fontWeight = FontWeight.Medium)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (status != "Connected") {
            Button(onClick = onConnectClick, modifier = Modifier.fillMaxWidth()) {
                Text("Connect to Sender")
            }
        } else {
            Button(onClick = onStartOverlay, modifier = Modifier.fillMaxWidth()) {
                Text("Start Overlay Widget")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onStopOverlay, modifier = Modifier.fillMaxWidth()) {
                Text("Stop Overlay Widget", color = Color.White)
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        OutlinedButton(onClick = onSwitchMode, modifier = Modifier.fillMaxWidth()) {
            Text("Switch to Sender Mode", color = Color.White)
        }
    }
}

@Composable
fun SenderScreen(
    status: String,
    isServerRunning: Boolean,
    onSwitchMode: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSimulateCall: () -> Unit = {},
    onEndSimulatedCall: () -> Unit = {}
) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.SettingsRemote, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Sender Mode Active", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Status: $status", color = Color.Cyan, fontWeight = FontWeight.Medium)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!isServerRunning) {
            Button(onClick = onStartServer, modifier = Modifier.fillMaxWidth()) {
                Text("Start Server (Wait for Receiver)")
            }
        } else {
            Button(onClick = onStopServer, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                Text("Stop Server")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("Enable Notification Access")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedButton(onClick = onSwitchMode, modifier = Modifier.fillMaxWidth()) {
            Text("Switch to Receiver Mode (UI)", color = Color.White)
        }
    }
}
