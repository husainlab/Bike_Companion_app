package com.husainlab.pillion.nativecore

import android.Manifest
import android.app.Notification
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import java.util.concurrent.ConcurrentHashMap

class PillionNotificationListener : NotificationListenerService() {
  private data class StoredNotificationEntry(
    val rawKey: String,
    val replyAction: Notification.Action?,
    val remoteInputs: Array<RemoteInput>?
  )

  private var mediaSessionManager: MediaSessionManager? = null
  private val activeControllers = mutableMapOf<MediaController, MediaController.Callback>()
  private val trackedNotifications = ConcurrentHashMap<String, StoredNotificationEntry>()

  @Volatile private var activeCallAnswerAction: Notification.Action? = null
  @Volatile private var activeCallDeclineAction: Notification.Action? = null
  @Volatile private var lastCallerName: String = ""
  @Volatile private var lastCallerNumber: String = ""
  @Volatile private var wasRinging = false

  private var lastTitle = ""
  private var lastArtist = ""
  private var isCallListenerRegistered = false
  private var telephonyCallbackRef: TelephonyCallback? = null
  @Suppress("DEPRECATION")
  private var phoneStateListenerRef: PhoneStateListener? = null
  @Volatile private var lastSentCallKey: String = ""

  private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
    updateControllers(controllers)
  }

  private val internalSyncReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != PillionProtocol.ACTION_SYNC) return
      setupCallListener()
      registerHandlers()
      val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
      val tempRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
      val tempC = if (tempRaw > 0) tempRaw / 10 else -1
      if (level != -1) {
        PillionStateStore.batteryLevel = level
        PillionStateStore.batteryTempC = tempC
        PillionStateStore.sendMessage("BATTERY|$level|$tempC")
      }

      try {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
          val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
          val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          val pct = if (max > 0) (cur * 100) / max else 70
          PillionStateStore.volumePercent = pct
          PillionStateStore.sendMessage("VOLUME|$pct")
        }
      } catch (_: Exception) {}

      if (PillionStateStore.isCallActive) {
        val packetType = if (PillionStateStore.isCallIncoming) "CALL_IN" else "CALL_ACTIVE"
        val name = PillionStateStore.callerName.ifEmpty { "Active Call" }
        val num = PillionStateStore.callerNumber
        PillionStateStore.sendMessage("$packetType|$name|$num")
        PillionStateStore.sendMessage("CALL_MUTE|${if (PillionStateStore.isCallMuted) "1" else "0"}")
      } else {
        PillionStateStore.sendMessage("CALL_END")
      }

      lastTitle = ""
      lastArtist = ""
      checkAndSendMediaUpdate()
      PillionStateStore.sendMessage("NOTIF_CLEAR_ALL")
      try {
        activeNotifications?.forEach { sbn ->
          onNotificationPosted(sbn)
        }
      } catch (_: Exception) {}
    }
  }

  private val volumeReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return
      val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
      if (streamType == AudioManager.STREAM_MUSIC || streamType == -1) {
        try {
          val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
          val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
          val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          val pct = if (max > 0) (cur * 100) / max else 70
          if (pct != PillionStateStore.volumePercent) {
            PillionStateStore.volumePercent = pct
            PillionStateStore.sendMessage("VOLUME|$pct")
            PillionStateStore.notifyChanged()
          }
        } catch (_: Exception) {}
      }
    }
  }

  private val systemPhoneStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
      val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
      @Suppress("DEPRECATION")
      val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
      when (state) {
        TelephonyManager.EXTRA_STATE_RINGING -> emitCallRinging(number)
        TelephonyManager.EXTRA_STATE_IDLE -> emitCallEnded()
        TelephonyManager.EXTRA_STATE_OFFHOOK -> emitCallOffhook()
      }
    }
  }

  private val batteryReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
      val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
      val tempC = if (tempRaw > 0) tempRaw / 10 else -1
      if (level != -1) {
        PillionStateStore.batteryLevel = level
        PillionStateStore.batteryTempC = tempC
        PillionStateStore.sendMessage("BATTERY|$level|$tempC")
      }
    }
  }

  private fun getContactName(phoneNumber: String): String {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      return phoneNumber
    }
    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
    val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
    return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else phoneNumber
    } ?: phoneNumber
  }

  override fun onCreate() {
    super.onCreate()
    mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
  }

  override fun onListenerConnected() {
    super.onListenerConnected()
    setupCallListener()
    registerHandlers()

    val internalFilter = IntentFilter(PillionProtocol.ACTION_SYNC)
    val phoneFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
    val volumeFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
      registerReceiver(systemPhoneStateReceiver, phoneFilter, Context.RECEIVER_EXPORTED)
      registerReceiver(volumeReceiver, volumeFilter, Context.RECEIVER_EXPORTED)
      registerReceiver(internalSyncReceiver, internalFilter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      registerReceiver(systemPhoneStateReceiver, phoneFilter)
      registerReceiver(volumeReceiver, volumeFilter)
      registerReceiver(internalSyncReceiver, internalFilter)
    }

    val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val tempRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val tempC = if (tempRaw > 0) tempRaw / 10 else -1
    if (level != -1) {
      PillionStateStore.batteryLevel = level
      PillionStateStore.batteryTempC = tempC
      PillionStateStore.sendMessage("BATTERY|$level|$tempC")
    }

    val componentName = ComponentName(this, this::class.java)
    try {
      mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
      updateControllers(mediaSessionManager?.getActiveSessions(componentName))
    } catch (_: SecurityException) {}

    try {
      for (sbn in activeNotifications) {
        onNotificationPosted(sbn)
      }
    } catch (_: Exception) {}
  }

  override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    teardownCallListener()
    PillionStateStore.notificationDismissHandler = null
    PillionStateStore.notificationReplyHandler = null
    PillionStateStore.callActionFallbackHandler = null
    trackedNotifications.clear()
    mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
    try {
      unregisterReceiver(batteryReceiver)
    } catch (_: Exception) {}
    try {
      unregisterReceiver(systemPhoneStateReceiver)
    } catch (_: Exception) {}
    try {
      unregisterReceiver(internalSyncReceiver)
    } catch (_: Exception) {}
    try {
      unregisterReceiver(volumeReceiver)
    } catch (_: Exception) {}
    removeAllCallbacks()
  }

  override fun onDestroy() {
    teardownCallListener()
    PillionStateStore.mediaSeekHandler = null
    super.onDestroy()
  }

  private fun registerHandlers() {
    PillionStateStore.mediaSeekHandler = { targetMs ->
      try {
        val componentName = ComponentName(this, this::class.java)
        val controllers = mediaSessionManager?.getActiveSessions(componentName)
        val bestController = controllers?.find { it.playbackState?.state == PlaybackState.STATE_PLAYING }
          ?: controllers?.find { it.metadata != null }
          ?: controllers?.firstOrNull()
        bestController?.transportControls?.seekTo(targetMs)
      } catch (_: Exception) {}
    }

    PillionStateStore.notificationDismissHandler = { escapedKey ->
      val rawKey = trackedNotifications[escapedKey]?.rawKey ?: PillionProtocol.unescapeKey(escapedKey)
      try {
        cancelNotification(rawKey)
        trackedNotifications.remove(escapedKey)
        PillionStateStore.sendMessage("NOTIF_REMOVE|$escapedKey")
        true
      } catch (_: Exception) {
        false
      }
    }

    PillionStateStore.notificationReplyHandler = { escapedKey, replyText ->
      val entry = trackedNotifications[escapedKey]
      val action = entry?.replyAction
      val inputs = entry?.remoteInputs
      if (action?.actionIntent != null && !inputs.isNullOrEmpty() && replyText.isNotBlank()) {
        try {
          val replyIntent = Intent()
          val bundle = Bundle()
          for (input in inputs) {
            bundle.putCharSequence(input.resultKey, replyText)
          }
          RemoteInput.addResultsToIntent(inputs, replyIntent, bundle)
          action.actionIntent.send(this, 0, replyIntent)
          true
        } catch (_: Exception) {
          false
        }
      } else {
        false
      }
    }

    PillionStateStore.callActionFallbackHandler = { isAnswer ->
      val action = if (isAnswer) activeCallAnswerAction else activeCallDeclineAction
      if (action?.actionIntent != null) {
        try {
          action.actionIntent.send()
          true
        } catch (_: Exception) {
          false
        }
      } else {
        false
      }
    }
  }

  private fun updateControllers(controllers: List<MediaController>?) {
    removeAllCallbacks()
    controllers?.forEach { controller ->
      val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
          checkAndSendMediaUpdate()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
          checkAndSendMediaUpdate()
        }
      }
      controller.registerCallback(callback)
      activeControllers[controller] = callback
    }
    checkAndSendMediaUpdate()
  }

  private fun checkAndSendMediaUpdate() {
    val componentName = ComponentName(this, this::class.java)
    val controllers = try {
      mediaSessionManager?.getActiveSessions(componentName)
    } catch (_: SecurityException) {
      null
    }

    val bestController = controllers?.find { it.playbackState?.state == PlaybackState.STATE_PLAYING }
      ?: controllers?.find { it.metadata != null }
      ?: controllers?.firstOrNull()

    bestController?.let { sendMediaUpdate(it) }
  }

  private fun removeAllCallbacks() {
    activeControllers.forEach { (controller, callback) ->
      try {
        controller.unregisterCallback(callback)
      } catch (_: Exception) {}
    }
    activeControllers.clear()
  }

  private fun sendMediaUpdate(controller: MediaController) {
    val metadata = controller.metadata
    val pbState = controller.playbackState
    val title = PillionProtocol.sanitizeField(
      metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "No Media Playing"
    )
    val artist = PillionProtocol.sanitizeField(
      metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
    )

    val state = pbState?.state ?: PlaybackState.STATE_NONE
    val isPlaying = state == PlaybackState.STATE_PLAYING ||
      state == PlaybackState.STATE_BUFFERING ||
      state == PlaybackState.STATE_FAST_FORWARDING

    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    val position = pbState?.position ?: 0L

    if (title != lastTitle || artist != lastArtist) {
      PillionStateStore.sendMessage("TITLE|$title")
      PillionStateStore.sendMessage("ARTIST|$artist")
      lastTitle = title
      lastArtist = artist
    }

    PillionStateStore.sendMessage("PLAYING|${if (isPlaying) "1" else "0"}")
    PillionStateStore.sendMessage("PROGRESS|$position|$duration")
  }

  @Suppress("DEPRECATION")
  private fun setupCallListener() {
    if (isCallListenerRegistered) return
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
      return
    }

    try {
      val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
          val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
              handleCallState(state, null)
            }
          }
          telephonyManager.registerTelephonyCallback(mainExecutor, cb)
          telephonyCallbackRef = cb
          isCallListenerRegistered = true
        } catch (_: SecurityException) {}
      } else {
        val listener = object : PhoneStateListener() {
          override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallState(state, phoneNumber)
          }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        phoneStateListenerRef = listener
        isCallListenerRegistered = true
      }
    } catch (_: Exception) {}
  }

  @Suppress("DEPRECATION")
  private fun teardownCallListener() {
    if (!isCallListenerRegistered) return
    try {
      val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        telephonyCallbackRef?.let { telephonyManager.unregisterTelephonyCallback(it) }
      } else {
        phoneStateListenerRef?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
      }
    } catch (_: Exception) {}
    telephonyCallbackRef = null
    phoneStateListenerRef = null
    isCallListenerRegistered = false
  }

  private fun emitCallPacket(packet: String) {
    if (packet == lastSentCallKey) return
    lastSentCallKey = packet
    PillionStateStore.sendMessage(packet)
  }

  private fun emitCallRinging(phoneNumber: String?) {
    wasRinging = true
    if (!phoneNumber.isNullOrEmpty()) {
      lastCallerNumber = PillionProtocol.sanitizeField(phoneNumber)
      lastCallerName = PillionProtocol.sanitizeField(getContactName(phoneNumber))
    }
    val name = lastCallerName.ifEmpty { "Incoming Call" }
    PillionStateStore.isCallActive = true
    PillionStateStore.isCallIncoming = true
    PillionStateStore.callerName = name
    PillionStateStore.callerNumber = lastCallerNumber
    emitCallPacket("CALL_IN|$name|$lastCallerNumber")
    PillionStateStore.notifyChanged()
  }

  private fun emitCallEnded() {
    if (wasRinging) {
      PillionStateStore.sendMessage("CALL_MISSED")
      wasRinging = false
    }
    lastCallerName = ""
    lastCallerNumber = ""
    lastSentCallKey = "CALL_END"
    activeCallAnswerAction = null
    activeCallDeclineAction = null
    try {
      (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMicrophoneMute = false
    } catch (_: Exception) {}
    PillionStateStore.isCallMuted = false
    PillionStateStore.isCallActive = false
    PillionStateStore.isCallIncoming = false
    PillionStateStore.callStartedAtMs = 0L
    PillionStateStore.sendMessage("CALL_MUTE|0")
    PillionStateStore.sendMessage("CALL_END")
    PillionStateStore.notifyChanged()
  }

  private fun emitCallOffhook() {
    wasRinging = false
    val name = lastCallerName.ifEmpty { "Active Call" }
    PillionStateStore.isCallActive = true
    PillionStateStore.isCallIncoming = false
    if (PillionStateStore.callStartedAtMs <= 0L) {
      PillionStateStore.callStartedAtMs = System.currentTimeMillis()
    }
    PillionStateStore.callerName = name
    PillionStateStore.callerNumber = lastCallerNumber
    emitCallPacket("CALL_ACTIVE|$name|$lastCallerNumber")
    try {
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      val isMuted = audioManager?.isMicrophoneMute == true
      PillionStateStore.isCallMuted = isMuted
      PillionStateStore.sendMessage("CALL_MUTE|${if (isMuted) "1" else "0"}")
    } catch (_: Exception) {}
    PillionStateStore.notifyChanged()
  }

  private fun handleCallState(state: Int, phoneNumber: String?) {
    when (state) {
      TelephonyManager.CALL_STATE_RINGING -> emitCallRinging(phoneNumber)
      TelephonyManager.CALL_STATE_IDLE -> emitCallEnded()
      TelephonyManager.CALL_STATE_OFFHOOK -> emitCallOffhook()
    }
  }

  private fun captureCallActions(notification: Notification) {
    val actions = notification.actions ?: return
    for (action in actions) {
      val titleLower = action.title?.toString()?.lowercase() ?: ""
      val semantic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) action.semanticAction else 0
      val isAnswer = titleLower.contains("answer") || titleLower.contains("accept")
      val isDecline = titleLower.contains("decline") || titleLower.contains("reject") ||
        titleLower.contains("hang") || titleLower.contains("end") || titleLower.contains("dismiss")
      if (semantic == Notification.Action.SEMANTIC_ACTION_DELETE || isDecline) {
        activeCallDeclineAction = action
      } else if (isAnswer) {
        activeCallAnswerAction = action
      }
    }
  }

  private fun findReplyAction(notification: Notification): Pair<Notification.Action, Array<RemoteInput>>? {
    val actions = notification.actions ?: return null
    for (action in actions) {
      val inputs = action.remoteInputs
      if (!inputs.isNullOrEmpty() && action.actionIntent != null) {
        return action to inputs
      }
    }
    return null
  }

  private fun extractNotificationMessages(extras: Bundle?): List<String> {
    if (extras == null) return emptyList()
    val extracted = mutableListOf<String>()

    // 1. Android MessagingStyle EXTRA_MESSAGES (used by WhatsApp, Google Messages, Signal, Telegram, etc.)
    try {
      val parcelables = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
      } else {
        @Suppress("DEPRECATION")
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)
      }
      if (!parcelables.isNullOrEmpty()) {
        for (item in parcelables) {
          if (item is Bundle) {
            val text = item.getCharSequence("text")?.toString()?.trim()
            val sender = item.getCharSequence("sender")?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
              val line = if (!sender.isNullOrEmpty()) "$sender: $text" else text
              extracted.add(PillionProtocol.sanitizeField(line))
            }
          }
        }
      }
    } catch (_: Exception) {}

    // 2. EXTRA_TEXT_LINES (used by InboxStyle notifications: SMS, Gmail, etc.)
    if (extracted.isEmpty()) {
      try {
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
          for (line in lines) {
            val str = line?.toString()?.trim()
            if (!str.isNullOrEmpty()) {
              extracted.add(PillionProtocol.sanitizeField(str))
            }
          }
        }
      } catch (_: Exception) {}
    }

    // 3. EXTRA_BIG_TEXT (used by BigTextStyle notifications)
    if (extracted.isEmpty()) {
      val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
      if (!bigText.isNullOrEmpty()) {
        val splitLines = bigText.split("\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }
        if (splitLines.size > 1) {
          for (l in splitLines) {
            extracted.add(PillionProtocol.sanitizeField(l))
          }
        } else {
          extracted.add(PillionProtocol.sanitizeField(bigText))
        }
      }
    }

    // 4. Fallback to EXTRA_TEXT
    if (extracted.isEmpty()) {
      val singleText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
      if (!singleText.isNullOrEmpty()) {
        val splitLines = singleText.split("\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }
        if (splitLines.size > 1) {
          for (l in splitLines) {
            extracted.add(PillionProtocol.sanitizeField(l))
          }
        } else {
          extracted.add(PillionProtocol.sanitizeField(singleText))
        }
      }
    }

    return extracted
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val pkg = sbn.packageName
    if (pkg == packageName) return

    val category = sbn.notification.category
    val extras = sbn.notification.extras
    val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    if (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_MISSED_CALL) {
      setupCallListener()
      if (category == Notification.CATEGORY_MISSED_CALL) {
        emitCallEnded()
        return
      }
      captureCallActions(sbn.notification)
      val callerTitle = PillionProtocol.sanitizeField(rawTitle ?: "Incoming Call")
      if (callerTitle.isNotEmpty()) {
        lastCallerName = callerTitle
      }
      val textHint = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
      val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
      val combinedText = if (subText.isNullOrEmpty()) textHint else "$textHint $subText"
      val packetType = PillionProtocol.classifyCallNotification(
        textHint = combinedText,
        currentCallActive = lastSentCallKey.startsWith("CALL_ACTIVE|"),
        currentCallIncoming = lastSentCallKey.startsWith("CALL_IN|"),
        titleHint = rawTitle
      )
      if (packetType == "CALL_END") {
        emitCallEnded()
        return
      }
      if (packetType == "CALL_IN") {
        wasRinging = true
        PillionStateStore.isCallActive = true
        PillionStateStore.isCallIncoming = true
      } else if (packetType == "CALL_ACTIVE") {
        wasRinging = false
        PillionStateStore.isCallActive = true
        PillionStateStore.isCallIncoming = false
        if (PillionStateStore.callStartedAtMs <= 0L) {
          PillionStateStore.callStartedAtMs = System.currentTimeMillis()
        }
      }
      PillionStateStore.callerName = callerTitle
      PillionStateStore.callerNumber = lastCallerNumber
      emitCallPacket("$packetType|$callerTitle|$lastCallerNumber")
      PillionStateStore.notifyChanged()
      return
    }

    if (sbn.isOngoing) return

    val isMedia = category == Notification.CATEGORY_TRANSPORT ||
      extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    if (isMedia) return

    val key = PillionProtocol.escapeKey(sbn.key)
    val title = PillionProtocol.sanitizeField(rawTitle ?: "New Notification")
    val messageList = extractNotificationMessages(extras)
    val fullText = if (messageList.isNotEmpty()) {
      messageList.joinToString("\n")
    } else {
      extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    }
    val escapedText = PillionProtocol.escapeKey(fullText)

    val replyPair = findReplyAction(sbn.notification)
    trackedNotifications[key] = StoredNotificationEntry(
      rawKey = sbn.key,
      replyAction = replyPair?.first,
      remoteInputs = replyPair?.second
    )

    val canReplyFlag = if (replyPair != null) "1" else "0"
    PillionStateStore.applyPacket("NOTIF_ADD2|$key|$pkg|$canReplyFlag|$title|$escapedText", false)
    PillionStateStore.sendMessage("NOTIF_ADD2|$key|$pkg|$canReplyFlag|$title|$escapedText")
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    if (sbn.packageName == packageName) return
    val key = PillionProtocol.escapeKey(sbn.key)
    trackedNotifications.remove(key)
    PillionStateStore.sendMessage("NOTIF_REMOVE|$key")
    if (sbn.notification.category == Notification.CATEGORY_CALL ||
      sbn.notification.category == Notification.CATEGORY_MISSED_CALL ||
      sbn.notification.extras.containsKey(Notification.EXTRA_CALL_PERSON)
    ) {
      activeCallAnswerAction = null
      activeCallDeclineAction = null
      emitCallEnded()
    }
  }
}
