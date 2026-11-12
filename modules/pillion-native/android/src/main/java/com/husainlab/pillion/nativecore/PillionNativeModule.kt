package com.husainlab.pillion.nativecore

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class PillionNativeModule : Module() {
  companion object {
    private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val PREFS_NAME = "pillion_native_prefs"
    private const val PREF_LAST_DEVICE = "last_device"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var connectedThread: ConnectedThread? = null
  private var reconnectJob: Job? = null
  @Volatile private var autoReconnectEnabled = false
  @Volatile private var isSenderMode = false
  @Volatile private var serverAcceptJob: Job? = null
  @Volatile private var activeServerSocket: BluetoothServerSocket? = null

  private val context: Context
    get() = appContext.reactContext ?: throw IllegalStateException("React context unavailable")

  private val bluetoothAdapter: BluetoothAdapter?
    get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private val stateListener: () -> Unit = {
    try {
      sendEvent("onStateChange", buildSnapshotMap())
    } catch (_: Throwable) {}
  }

  override fun definition() = ModuleDefinition {
    Name("PillionNative")

    Events("onStateChange")

    OnCreate {
      PillionStateStore.outgoingSender = { msg -> connectedThread?.write(msg.toByteArray()) }
      PillionStateStore.commandExecutor = { cmd -> handleIncomingCommand(cmd) }
      PillionStateStore.addListener(stateListener)
    }

    OnDestroy {
      PillionStateStore.removeListener(stateListener)
      stopServerInternal()
      reconnectJob?.cancel()
      scope.cancel()
    }

    AsyncFunction("getSnapshot") {
      buildSnapshotMap()
    }

    AsyncFunction("getPairedDevices") {
      getPairedDevicesList()
    }

    AsyncFunction("requestRuntimePermissions") {
      hasRequiredPermissions()
    }

    AsyncFunction("openNotificationListenerSettings") {
      val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }

    AsyncFunction("openOverlaySettings") {
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
      ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }

    AsyncFunction("startServer") {
      isSenderMode = true
      autoReconnectEnabled = false
      startServerInternal()
    }

    AsyncFunction("stopServer") {
      stopServerInternal()
    }

    AsyncFunction("connectToDevice") { address: String ->
      isSenderMode = false
      autoReconnectEnabled = true
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_LAST_DEVICE, address)
        .apply()
      startReconnectLoop()
      connectByAddress(address)
    }

    AsyncFunction("disconnect") {
      autoReconnectEnabled = false
      connectedThread?.cancel()
      connectedThread = null
      updateStatus("Disconnected")
    }

    AsyncFunction("setAutoReconnect") { enabled: Boolean ->
      autoReconnectEnabled = enabled
      if (enabled) {
        isSenderMode = false
        startReconnectLoop()
      }
    }

    AsyncFunction("startOverlay") {
      if (!Settings.canDrawOverlays(context)) {
        return@AsyncFunction false
      }
      context.startService(Intent(context, PillionOverlayService::class.java))
      true
    }

    AsyncFunction("stopOverlay") {
      context.stopService(Intent(context, PillionOverlayService::class.java))
    }

    AsyncFunction("sendCommand") { path: String ->
      PillionStateStore.sendMessage(path)
    }
  }

  private fun hasRequiredPermissions(): Boolean {
    val permissions = mutableListOf(
      Manifest.permission.READ_PHONE_STATE,
      Manifest.permission.READ_CALL_LOG,
      Manifest.permission.READ_CONTACTS
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
      permissions.add(Manifest.permission.BLUETOOTH_SCAN)
      permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return permissions.all {
      ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
  }

  private fun hasNotificationAccess(): Boolean {
    val cn = ComponentName(context, PillionNotificationListener::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(cn.flattenToString())
  }

  private fun getPairedDevicesList(): List<Map<String, String>> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
      ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
      return emptyList()
    }
    return try {
      bluetoothAdapter?.bondedDevices?.map { device ->
        mapOf(
          "name" to (device.name ?: device.address),
          "address" to device.address
        )
      } ?: emptyList()
    } catch (_: SecurityException) {
      emptyList()
    }
  }

  private fun startServerInternal() {
    if (PillionStateStore.isServerRunning) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
      ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
      updateStatus("Permission denied")
      return
    }

    PillionStateStore.isServerRunning = true
    PillionStateStore.notifyChanged()

    serverAcceptJob = scope.launch {
      try {
        activeServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("Pillion", APP_UUID)
        while (PillionStateStore.isServerRunning && isActive) {
          updateStatus("Waiting for connection...")
          val socket = try {
            activeServerSocket?.accept()
          } catch (_: IOException) {
            if (PillionStateStore.isServerRunning) {
              updateStatus("Server Error")
              delay(2000)
              try {
                activeServerSocket?.close()
              } catch (_: Exception) {}
              activeServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("Pillion", APP_UUID)
              continue
            } else {
              break
            }
          }
          socket?.let { accepted ->
            manageConnectedSocket(accepted)
            while (connectedThread?.isAlive == true && PillionStateStore.isServerRunning) {
              delay(1000)
            }
          }
        }
      } catch (_: Exception) {
        updateStatus("Server failed")
        PillionStateStore.isServerRunning = false
        PillionStateStore.notifyChanged()
      } finally {
        try {
          activeServerSocket?.close()
        } catch (_: IOException) {}
        activeServerSocket = null
      }
    }
  }

  private fun stopServerInternal() {
    PillionStateStore.isServerRunning = false
    try {
      activeServerSocket?.close()
    } catch (_: IOException) {}
    activeServerSocket = null
    serverAcceptJob?.cancel()
    serverAcceptJob = null
    connectedThread?.cancel()
    connectedThread = null
    updateStatus("Server Stopped")
  }

  private fun connectByAddress(address: String) {
    val adapter = bluetoothAdapter ?: return
    scope.launch {
      try {
        if (PillionStateStore.connectionStatus == "Connected") return@launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
          updateStatus("Permission denied")
          return@launch
        }
        updateStatus("Connecting...")
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
        socket.connect()
        manageConnectedSocket(socket)
      } catch (_: IOException) {
        updateStatus("Disconnected")
      } catch (_: SecurityException) {
        updateStatus("Permission denied")
      }
    }
  }

  private fun startReconnectLoop() {
    if (reconnectJob?.isActive == true) return
    reconnectJob = scope.launch {
      while (isActive) {
        if (autoReconnectEnabled && !isSenderMode && PillionStateStore.connectionStatus != "Connected") {
          val address = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LAST_DEVICE, null)
          if (address != null && bluetoothAdapter != null) {
            try {
              if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
              ) {
                val device = bluetoothAdapter!!.getRemoteDevice(address)
                updateStatus("Auto-reconnecting...")
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                manageConnectedSocket(socket)
              }
            } catch (_: Exception) {
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
    connectedThread = ConnectedThread(socket).also { it.start() }
    context.sendBroadcast(Intent(PillionProtocol.ACTION_SYNC))
  }

  private fun updateStatus(status: String) {
    PillionStateStore.connectionStatus = status
    PillionStateStore.notifyChanged()
  }

  private fun handleIncomingCommand(path: String) {
    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    try {
      when (path) {
        "/call/answer" -> {
          if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            @Suppress("DEPRECATION")
            telecomManager.acceptRingingCall()
          }
        }
        "/call/decline" -> {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
          ) {
            @Suppress("DEPRECATION")
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
    } catch (_: Exception) {}
  }

  private fun buildSnapshotMap(): Map<String, Any?> = mapOf(
    "connectionStatus" to PillionStateStore.connectionStatus,
    "isServerRunning" to PillionStateStore.isServerRunning,
    "isOverlayRunning" to PillionStateStore.isOverlayRunning,
    "songTitle" to PillionStateStore.songTitle,
    "artistName" to PillionStateStore.artistName,
    "isPlaying" to PillionStateStore.isPlaying,
    "progressMs" to PillionStateStore.progressMs.toDouble(),
    "durationMs" to PillionStateStore.durationMs.toDouble(),
    "batteryLevel" to PillionStateStore.batteryLevel,
    "isCallActive" to PillionStateStore.isCallActive,
    "isCallIncoming" to PillionStateStore.isCallIncoming,
    "callerName" to PillionStateStore.callerName,
    "notifications" to PillionStateStore.getNotifications().map {
      mapOf(
        "key" to it.key,
        "packageName" to it.packageName,
        "title" to it.title,
        "text" to it.text,
        "receivedAt" to it.receivedAt.toDouble()
      )
    },
    "hasNotificationAccess" to hasNotificationAccess(),
    "hasOverlayPermission" to Settings.canDrawOverlays(context),
    "hasRuntimePermissions" to hasRequiredPermissions()
  )

  private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
    private val mmInStream: InputStream = socket.inputStream
    private val mmOutStream: OutputStream = socket.outputStream

    override fun run() {
      val reader = mmInStream.bufferedReader()
      while (true) {
        try {
          val line = reader.readLine() ?: break
          if (line.isNotEmpty()) {
            PillionStateStore.applyPacket(line, isSenderMode)
          }
        } catch (_: IOException) {
          updateStatus("Disconnected")
          break
        }
      }
      updateStatus("Disconnected")
    }

    fun write(bytes: ByteArray) {
      try {
        mmOutStream.write(bytes)
        mmOutStream.write("\n".toByteArray())
        mmOutStream.flush()
      } catch (_: IOException) {}
    }

    fun cancel() {
      try {
        socket.close()
      } catch (_: IOException) {}
    }
  }
}

