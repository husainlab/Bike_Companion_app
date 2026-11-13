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
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PillionNativeModule : Module() {
  companion object {
    private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val PREFS_NAME = "pillion_native_prefs"
    private const val PREF_LAST_DEVICE = "last_device"
    private const val PREF_AUTO_START_OVERLAY = "auto_start_overlay"
    private const val MAX_SYNC_CONTACTS = 250
    private const val MAX_SYNC_RECENTS = 25
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var connectedThread: ConnectedThread? = null
  private var reconnectJob: Job? = null
  @Volatile private var autoReconnectEnabled = false
  @Volatile private var isSenderMode = false
  @Volatile private var currentGpsSpeedKmh: Double = 0.0
  private var locationListener: LocationListener? = null
  private val isConnecting = AtomicBoolean(false)
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
      reconnectJob?.cancel()
      reconnectJob = null
      startServerInternal()
    }

    AsyncFunction("stopServer") {
      stopServerInternal()
    }

    AsyncFunction("setRelayRole") { mode: String ->
      val nextSender = mode == "sender"
      val roleChanged = isSenderMode != nextSender
      isSenderMode = nextSender
      if (nextSender) {
        stopGpsSpeedTracking()
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        if (roleChanged) {
          closeCurrentConnection("Disconnected")
          context.stopService(Intent(context, PillionOverlayService::class.java))
        }
      } else {
        startGpsSpeedTracking()
        if (PillionStateStore.isServerRunning) {
          stopServerInternal()
        } else if (roleChanged) {
          closeCurrentConnection("Disconnected")
        }
      }
      PillionStateStore.notifyChanged()
    }

    AsyncFunction("connectToDevice") { address: String ->
      isSenderMode = false
      if (PillionStateStore.isServerRunning) {
        stopServerInternal()
      }
      autoReconnectEnabled = true
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_LAST_DEVICE, address)
        .apply()
      closeCurrentConnection("Connecting...")
      startReconnectLoop(initialDelayMs = 5000L)
      connectByAddress(address)
    }

    AsyncFunction("disconnect") {
      autoReconnectEnabled = false
      closeCurrentConnection("Disconnected")
    }

    AsyncFunction("setAutoReconnect") { enabled: Boolean ->
      autoReconnectEnabled = enabled
      if (enabled && !isSenderMode) {
        startReconnectLoop(initialDelayMs = 0L)
      } else if (!enabled) {
        reconnectJob?.cancel()
        reconnectJob = null
      }
    }

    AsyncFunction("startOverlay") {
      if (!Settings.canDrawOverlays(context)) {
        return@AsyncFunction false
      }
      startOverlayForegroundService()
      true
    }

    AsyncFunction("stopOverlay") {
      context.stopService(Intent(context, PillionOverlayService::class.java))
    }

    AsyncFunction("setAutoStartOverlay") { enabled: Boolean ->
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_AUTO_START_OVERLAY, enabled)
        .apply()
    }

    AsyncFunction("sendCommand") { path: String ->
      PillionStateStore.sendMessage(path)
    }

    AsyncFunction("syncContacts") {
      if (isSenderMode) {
        scope.launch { syncContactsAndRecents() }
      } else {
        PillionStateStore.sendMessage("/sync/contacts")
      }
    }

    AsyncFunction("launchGoogleMaps") { splitScreen: Boolean ->
      launchGoogleMapsInternal(splitScreen)
    }
  }

  private fun startGpsSpeedTracking() {
    if (locationListener != null) return
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
      ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val listener = LocationListener { location ->
      val speedKmh = if (location.hasSpeed()) (location.speed * 3.6).toDouble() else 0.0
      currentGpsSpeedKmh = speedKmh
      PillionStateStore.notifyChanged()
    }
    locationListener = listener
    try {
      if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
      }
    } catch (_: Exception) {}
  }

  private fun stopGpsSpeedTracking() {
    val listener = locationListener ?: return
    locationListener = null
    try {
      val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
      lm?.removeUpdates(listener)
    } catch (_: Exception) {}
    currentGpsSpeedKmh = 0.0
  }

  private fun startOverlayForegroundService() {
    val intent = Intent(context, PillionOverlayService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent)
    } else {
      context.startService(intent)
    }
  }

  private fun launchGoogleMapsInternal(splitScreen: Boolean): Boolean {
    if (Settings.canDrawOverlays(context) && !PillionStateStore.isOverlayRunning) {
      try {
        startOverlayForegroundService()
      } catch (_: Exception) {}
    }

    val mapsPkg = "com.google.android.apps.maps"
    val launchIntent = context.packageManager.getLaunchIntentForPackage(mapsPkg)
      ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))

    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (splitScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      launchIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
    }

    return try {
      context.startActivity(launchIntent)
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun hasRequiredPermissions(): Boolean {
    val permissions = mutableListOf<String>()
    if (isSenderMode) {
      permissions.add(Manifest.permission.READ_PHONE_STATE)
      permissions.add(Manifest.permission.READ_CALL_LOG)
      permissions.add(Manifest.permission.READ_CONTACTS)
      permissions.add(Manifest.permission.CALL_PHONE)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
      permissions.add(Manifest.permission.BLUETOOTH_SCAN)
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

  private fun closeCurrentConnection(nextStatus: String) {
    val previous = connectedThread
    connectedThread = null
    previous?.cancel()
    PillionStateStore.resetTransientState()
    updateStatus(nextStatus)
  }

  private fun startServerInternal() {
    if (PillionStateStore.isServerRunning) return
    val adapter = bluetoothAdapter
    if (adapter == null) {
      updateStatus("Bluetooth unavailable")
      return
    }
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
        while (PillionStateStore.isServerRunning && isActive) {
          val serverSocket = activeServerSocket ?: try {
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord("Pillion", APP_UUID)?.also {
              activeServerSocket = it
            }
          } catch (_: Exception) {
            null
          }

          if (serverSocket == null) {
            updateStatus("Server Error")
            delay(2000)
            continue
          }

          updateStatus("Waiting for connection...")
          val socket = try {
            serverSocket.accept()
          } catch (_: IOException) {
            if (PillionStateStore.isServerRunning) {
              updateStatus("Server Error")
              try {
                activeServerSocket?.close()
              } catch (_: Exception) {}
              activeServerSocket = null
              delay(2000)
              continue
            } else {
              break
            }
          }
          socket?.let { accepted ->
            manageConnectedSocket(accepted)
            while (connectedThread?.isAlive == true && PillionStateStore.isServerRunning && isActive) {
              delay(1000)
            }
          }
        }
      } catch (_: Exception) {
        PillionStateStore.resetTransientState()
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
    closeCurrentConnection("Server Stopped")
  }

  private fun attemptConnectSocket(address: String, connectingStatus: String) {
    val adapter = bluetoothAdapter ?: run {
      updateStatus("Bluetooth unavailable")
      return
    }
    if (!isConnecting.compareAndSet(false, true)) return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
      ) {
        updateStatus("Permission denied")
        return
      }
      updateStatus(connectingStatus)
      val device: BluetoothDevice = adapter.getRemoteDevice(address)
      val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
      socket.connect()
      manageConnectedSocket(socket)
    } catch (_: IOException) {
      updateStatus("Disconnected")
    } catch (_: SecurityException) {
      updateStatus("Permission denied")
    } catch (_: IllegalArgumentException) {
      updateStatus("Disconnected")
    } finally {
      isConnecting.set(false)
    }
  }

  private fun connectByAddress(address: String) {
    scope.launch {
      if (PillionStateStore.connectionStatus == "Connected") return@launch
      attemptConnectSocket(address, "Connecting...")
    }
  }

  private fun startReconnectLoop(initialDelayMs: Long = 0L) {
    if (reconnectJob?.isActive == true) return
    reconnectJob = scope.launch {
      if (initialDelayMs > 0L) {
        delay(initialDelayMs)
      }
      while (isActive) {
        if (autoReconnectEnabled && !isSenderMode && connectedThread == null && !isConnecting.get()) {
          val address = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LAST_DEVICE, null)
          if (address != null && bluetoothAdapter != null) {
            attemptConnectSocket(address, "Auto-reconnecting...")
          }
        }
        delay(5000)
      }
    }
  }

  private fun manageConnectedSocket(socket: BluetoothSocket) {
    val previous = connectedThread
    val nextThread = ConnectedThread(socket)
    connectedThread = nextThread
    previous?.cancel()
    nextThread.start()
    updateStatus("Connected")
    context.sendBroadcast(Intent(PillionProtocol.ACTION_SYNC))
    if (isSenderMode) {
      scope.launch {
        delay(350)
        syncContactsAndRecents()
      }
    } else {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val autoStartOverlay = prefs.getBoolean(PREF_AUTO_START_OVERLAY, false)
      if (autoStartOverlay && Settings.canDrawOverlays(context) && !PillionStateStore.isOverlayRunning) {
        try {
          startOverlayForegroundService()
        } catch (_: Exception) {}
      }
      scope.launch {
        delay(450)
        PillionStateStore.sendMessage("/sync/contacts")
      }
    }
  }

  private fun normalizePhoneKey(raw: String): String =
    raw.filter { it.isDigit() || it == '+' }.takeLast(10)

  private fun syncContactsAndRecents() {
    val contacts = readDeviceContacts()
    val recents = readRecentCalls()

    PillionStateStore.setContacts(contacts)
    PillionStateStore.setRecentCalls(recents)
    PillionStateStore.notifyChanged()

    if (connectedThread == null) return

    PillionStateStore.sendMessage("CONTACTS_BEGIN")
    for (c in contacts) {
      val starredFlag = if (c.isStarred) "1" else "0"
      PillionStateStore.sendMessage(
        "CONTACT|${PillionProtocol.sanitizeField(c.id)}|${PillionProtocol.sanitizeField(c.name)}|${PillionProtocol.sanitizeField(c.phoneNumber)}|$starredFlag"
      )
    }
    PillionStateStore.sendMessage("CONTACTS_END")

    PillionStateStore.sendMessage("RECENTS_BEGIN")
    for (r in recents) {
      PillionStateStore.sendMessage(
        "RECENT|${PillionProtocol.sanitizeField(r.id)}|${PillionProtocol.sanitizeField(r.name)}|${PillionProtocol.sanitizeField(r.phoneNumber)}|${r.callType}|${r.timestampMs}"
      )
    }
    PillionStateStore.sendMessage("RECENTS_END")
  }

  private fun readDeviceContacts(): List<SyncedContact> {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      return emptyList()
    }
    val results = mutableListOf<SyncedContact>()
    val seenKeys = HashSet<String>()
    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Phone._ID,
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER,
      ContactsContract.CommonDataKinds.Phone.STARRED
    )
    val sortOrder = "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, ${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC, ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

    try {
      context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection,
        null,
        null,
        sortOrder
      )?.use { cursor ->
        val idxId = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
        val idxName = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val idxNum = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val idxStar = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)

        while (cursor.moveToNext() && results.size < MAX_SYNC_CONTACTS) {
          val rawNum = cursor.getString(idxNum)?.trim() ?: continue
          val key = normalizePhoneKey(rawNum)
          if (key.length < 3 || !seenKeys.add(key)) continue
          val id = if (idxId >= 0) cursor.getString(idxId) ?: "$key-${results.size}" else "$key-${results.size}"
          val name = (if (idxName >= 0) cursor.getString(idxName) else null)?.trim().orEmpty().ifEmpty { rawNum }
          val starred = idxStar >= 0 && cursor.getInt(idxStar) == 1
          results.add(SyncedContact(id = id, name = name, phoneNumber = rawNum, isStarred = starred))
        }
      }
    } catch (_: Exception) {}
    return results
  }

  private fun readRecentCalls(): List<RecentCallEntry> {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
      return emptyList()
    }
    val results = mutableListOf<RecentCallEntry>()
    val projection = arrayOf(
      CallLog.Calls._ID,
      CallLog.Calls.CACHED_NAME,
      CallLog.Calls.NUMBER,
      CallLog.Calls.TYPE,
      CallLog.Calls.DATE
    )
    try {
      context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        projection,
        null,
        null,
        "${CallLog.Calls.DATE} DESC"
      )?.use { cursor ->
        val idxId = cursor.getColumnIndex(CallLog.Calls._ID)
        val idxName = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
        val idxNum = cursor.getColumnIndex(CallLog.Calls.NUMBER)
        val idxType = cursor.getColumnIndex(CallLog.Calls.TYPE)
        val idxDate = cursor.getColumnIndex(CallLog.Calls.DATE)

        while (cursor.moveToNext() && results.size < MAX_SYNC_RECENTS) {
          val num = cursor.getString(idxNum)?.trim().orEmpty()
          if (num.isEmpty()) continue
          val id = if (idxId >= 0) cursor.getString(idxId) ?: "${results.size}" else "${results.size}"
          val name = (if (idxName >= 0) cursor.getString(idxName) else null)?.trim().orEmpty().ifEmpty { num }
          val callType = if (idxType >= 0) cursor.getInt(idxType) else CallLog.Calls.INCOMING_TYPE
          val dateMs = if (idxDate >= 0) cursor.getLong(idxDate) else 0L
          results.add(
            RecentCallEntry(
              id = id,
              name = name,
              phoneNumber = num,
              callType = callType,
              timestampMs = dateMs
            )
          )
        }
      }
    } catch (_: Exception) {}
    return results
  }

  private fun lookupDisplayName(number: String): String {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      return number
    }
    return try {
      val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
      context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) ?: number else number
      } ?: number
    } catch (_: Exception) {
      number
    }
  }

  private fun placeOutgoingCall(rawNumber: String) {
    val clean = rawNumber.trim()
    if (clean.isEmpty()) return
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
      return
    }
    val uri = Uri.fromParts("tel", clean, null)
    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val display = PillionProtocol.sanitizeField(lookupDisplayName(clean))
    PillionStateStore.sendMessage("CALL_ACTIVE|$display|${PillionProtocol.sanitizeField(clean)}")

    try {
      telecomManager.placeCall(uri, Bundle())
    } catch (_: Exception) {
      try {
        val intent = Intent(Intent.ACTION_CALL, uri).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
      } catch (_: Exception) {}
    }
  }

  private fun updateStatus(status: String) {
    PillionStateStore.connectionStatus = status
    PillionStateStore.notifyChanged()
  }

  private fun handleIncomingCommand(path: String) {
    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    try {
      when {
        path == "/call/answer" -> {
          val answeredByNotif = PillionStateStore.callActionFallbackHandler?.invoke(true) == true
          if (!answeredByNotif &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
          ) {
            @Suppress("DEPRECATION")
            telecomManager.acceptRingingCall()
          }
        }
        path == "/call/decline" -> {
          var ended = false
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
          ) {
            @Suppress("DEPRECATION")
            ended = telecomManager.endCall()
          }
          if (!ended) {
            PillionStateStore.callActionFallbackHandler?.invoke(false)
          }
          try {
            audioManager.isMicrophoneMute = false
          } catch (_: Exception) {}
          PillionStateStore.isCallMuted = false
          PillionStateStore.sendMessage("CALL_MUTE|0")
        }
        path == "/call/mute" -> {
          val nextMuted = !audioManager.isMicrophoneMute
          try {
            audioManager.isMicrophoneMute = nextMuted
          } catch (_: Exception) {}
          PillionStateStore.isCallMuted = nextMuted
          PillionStateStore.notifyChanged()
          PillionStateStore.sendMessage("CALL_MUTE|${if (nextMuted) "1" else "0"}")
        }
        path.startsWith("/call/mute|") -> {
          val nextMuted = path.removePrefix("/call/mute|") == "1"
          try {
            audioManager.isMicrophoneMute = nextMuted
          } catch (_: Exception) {}
          PillionStateStore.isCallMuted = nextMuted
          PillionStateStore.notifyChanged()
          PillionStateStore.sendMessage("CALL_MUTE|${if (nextMuted) "1" else "0"}")
        }
        path.startsWith("/call/dial|") -> {
          val number = path.removePrefix("/call/dial|")
          placeOutgoingCall(number)
        }
        path == "/sync/contacts" -> {
          scope.launch { syncContactsAndRecents() }
        }
        path.startsWith("/notif/dismiss|") -> {
          val key = path.removePrefix("/notif/dismiss|")
          PillionStateStore.notificationDismissHandler?.invoke(key)
        }
        path.startsWith("/notif/reply|") -> {
          val payload = path.removePrefix("/notif/reply|")
          val sep = payload.indexOf('|')
          if (sep > 0) {
            val key = payload.substring(0, sep)
            val replyText = payload.substring(sep + 1)
            PillionStateStore.notificationReplyHandler?.invoke(key, replyText)
          }
        }
        path == "/media/playpause" -> {
          audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
          audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }
        path == "/media/next" -> {
          audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
          audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
        }
        path == "/media/prev" -> {
          audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
          audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        }
        path.startsWith("/media/seek|") -> {
          val targetMs = path.removePrefix("/media/seek|").toLongOrNull() ?: 0L
          PillionStateStore.progressMs = targetMs
          PillionStateStore.mediaSeekHandler?.invoke(targetMs)
          PillionStateStore.notifyChanged()
        }
        path == "/volume/up" -> {
          audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
          val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
          val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          val pct = if (max > 0) ((cur.toDouble() / max.toDouble()) * 100.0).roundToInt() else 0
          PillionStateStore.volumePercent = pct
          PillionStateStore.sendMessage("VOLUME|$pct")
          PillionStateStore.notifyChanged()
        }
        path == "/volume/down" -> {
          audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
          val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
          val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          val pct = if (max > 0) ((cur.toDouble() / max.toDouble()) * 100.0).roundToInt() else 0
          PillionStateStore.volumePercent = pct
          PillionStateStore.sendMessage("VOLUME|$pct")
          PillionStateStore.notifyChanged()
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
    "volumePercent" to PillionStateStore.volumePercent,
    "batteryLevel" to PillionStateStore.batteryLevel,
    "batteryTempC" to PillionStateStore.batteryTempC,
    "currentSpeedKmh" to currentGpsSpeedKmh,
    "isCallActive" to PillionStateStore.isCallActive,
    "isCallIncoming" to PillionStateStore.isCallIncoming,
    "isCallMuted" to PillionStateStore.isCallMuted,
    "callStartedAtMs" to PillionStateStore.callStartedAtMs.toDouble(),
    "callerName" to PillionStateStore.callerName,
    "callerNumber" to PillionStateStore.callerNumber,
    "notifications" to PillionStateStore.getNotifications().map {
      mapOf(
        "key" to it.key,
        "packageName" to it.packageName,
        "title" to it.title,
        "text" to it.text,
        "receivedAt" to it.receivedAt.toDouble(),
        "canReply" to it.canReply
      )
    },
    "contacts" to PillionStateStore.getContacts().map {
      mapOf(
        "id" to it.id,
        "name" to it.name,
        "phoneNumber" to it.phoneNumber,
        "isStarred" to it.isStarred
      )
    },
    "recentCalls" to PillionStateStore.getRecentCalls().map {
      mapOf(
        "id" to it.id,
        "name" to it.name,
        "phoneNumber" to it.phoneNumber,
        "callType" to it.callType,
        "timestampMs" to it.timestampMs.toDouble()
      )
    },
    "hasNotificationAccess" to hasNotificationAccess(),
    "hasOverlayPermission" to Settings.canDrawOverlays(context),
    "hasRuntimePermissions" to hasRequiredPermissions()
  )

  private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
    private val mmInStream: InputStream = socket.inputStream
    private val mmOutStream: OutputStream = socket.outputStream
    private val writeQueue = LinkedBlockingQueue<ByteArray>(512)
    private val closed = AtomicBoolean(false)
    private val writerThread = Thread {
      while (!closed.get()) {
        try {
          val frame = writeQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
          mmOutStream.write(frame)
          mmOutStream.write('\n'.code)
          mmOutStream.flush()
        } catch (_: InterruptedException) {
          break
        } catch (_: IOException) {
          break
        }
      }
    }.apply {
      name = "PillionSppWriter"
      isDaemon = true
    }

    override fun run() {
      writerThread.start()
      val reader = mmInStream.bufferedReader()
      while (!closed.get()) {
        try {
          val line = reader.readLine() ?: break
          if (line.isNotEmpty()) {
            PillionStateStore.applyPacket(line, isSenderMode)
          }
        } catch (_: IOException) {
          break
        }
      }
      closed.set(true)
      writerThread.interrupt()
      if (connectedThread === this) {
        connectedThread = null
        PillionStateStore.resetTransientState()
        updateStatus("Disconnected")
      }
    }

    fun write(bytes: ByteArray) {
      if (closed.get()) return
      writeQueue.offer(bytes.copyOf())
    }

    fun cancel() {
      if (!closed.compareAndSet(false, true)) return
      writerThread.interrupt()
      try {
        socket.close()
      } catch (_: IOException) {}
    }
  }
}
