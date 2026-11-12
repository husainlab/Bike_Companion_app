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
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
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

  private var lastTitle = ""
  private var lastArtist = ""
  private var isCallListenerRegistered = false

  private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
    updateControllers(controllers)
  }

  private val syncReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        PillionProtocol.ACTION_SYNC -> {
          val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
          val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
          if (level != -1) {
            PillionStateStore.sendMessage("BATTERY|$level")
          }
          checkAndSendMediaUpdate()
          PillionStateStore.sendMessage("NOTIF_CLEAR_ALL")
          try {
            activeNotifications?.forEach { sbn ->
              onNotificationPosted(sbn)
            }
          } catch (_: Exception) {}
        }
        TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
          val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
          val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
          when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
              if (!number.isNullOrEmpty()) {
                lastCallerNumber = PillionProtocol.sanitizeField(number)
                lastCallerName = PillionProtocol.sanitizeField(getContactName(number))
              }
              val name = lastCallerName.ifEmpty { "Incoming Call" }
              PillionStateStore.sendMessage("CALL_IN|$name|$lastCallerNumber")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
              lastCallerName = ""
              lastCallerNumber = ""
              activeCallAnswerAction = null
              activeCallDeclineAction = null
              PillionStateStore.sendMessage("CALL_END")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
              val name = lastCallerName.ifEmpty { "Active Call" }
              PillionStateStore.sendMessage("CALL_ACTIVE|$name|$lastCallerNumber")
            }
          }
        }
      }
    }
  }

  private val batteryReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
      if (level != -1) {
        PillionStateStore.sendMessage("BATTERY|$level")
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

    val intentFilter = IntentFilter().apply {
      addAction(PillionProtocol.ACTION_SYNC)
      addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
      registerReceiver(syncReceiver, intentFilter, Context.RECEIVER_EXPORTED)
    } else {
      registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      registerReceiver(syncReceiver, intentFilter)
    }

    val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    if (level != -1) {
      PillionStateStore.sendMessage("BATTERY|$level")
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
    PillionStateStore.notificationDismissHandler = null
    PillionStateStore.notificationReplyHandler = null
    PillionStateStore.callActionFallbackHandler = null
    trackedNotifications.clear()
    mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
    try {
      unregisterReceiver(batteryReceiver)
      unregisterReceiver(syncReceiver)
    } catch (_: Exception) {}
    removeAllCallbacks()
  }

  private fun registerHandlers() {
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
          telephonyManager.registerTelephonyCallback(
            mainExecutor,
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
              override fun onCallStateChanged(state: Int) {
                handleCallState(state, null)
              }
            }
          )
          isCallListenerRegistered = true
        } catch (_: SecurityException) {}
      } else {
        telephonyManager.listen(
          object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
              handleCallState(state, phoneNumber)
            }
          },
          PhoneStateListener.LISTEN_CALL_STATE
        )
        isCallListenerRegistered = true
      }
    } catch (_: Exception) {}
  }

  private fun handleCallState(state: Int, phoneNumber: String?) {
    when (state) {
      TelephonyManager.CALL_STATE_RINGING -> {
        if (!phoneNumber.isNullOrEmpty()) {
          lastCallerNumber = PillionProtocol.sanitizeField(phoneNumber)
          lastCallerName = PillionProtocol.sanitizeField(getContactName(phoneNumber))
        }
        val name = lastCallerName.ifEmpty { "Incoming Call" }
        PillionStateStore.sendMessage("CALL_IN|$name|$lastCallerNumber")
      }
      TelephonyManager.CALL_STATE_IDLE -> {
        lastCallerName = ""
        lastCallerNumber = ""
        activeCallAnswerAction = null
        activeCallDeclineAction = null
        PillionStateStore.sendMessage("CALL_END")
      }
      TelephonyManager.CALL_STATE_OFFHOOK -> {
        val name = lastCallerName.ifEmpty { "Active Call" }
        PillionStateStore.sendMessage("CALL_ACTIVE|$name|$lastCallerNumber")
      }
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

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val category = sbn.notification.category
    if (category == Notification.CATEGORY_CALL) {
      captureCallActions(sbn.notification)
      val extras = sbn.notification.extras
      val callerTitle = PillionProtocol.sanitizeField(extras.getString("android.title") ?: "Incoming Call")
      if (callerTitle.isNotEmpty()) {
        lastCallerName = callerTitle
      }
      val textHint = PillionProtocol.sanitizeField(extras.getCharSequence("android.text")?.toString() ?: "")
      if (textHint.lowercase().contains("ongoing") || textHint.lowercase().contains("active") || textHint.lowercase().contains("dialing")) {
        PillionStateStore.sendMessage("CALL_ACTIVE|$callerTitle|$lastCallerNumber")
      } else {
        PillionStateStore.sendMessage("CALL_IN|$callerTitle|$lastCallerNumber")
      }
      return
    }

    if (sbn.isOngoing) return

    val isMedia = category == Notification.CATEGORY_TRANSPORT ||
      sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    if (isMedia) return

    val key = PillionProtocol.escapeKey(sbn.key)
    val pkg = sbn.packageName
    val extras = sbn.notification.extras
    val title = PillionProtocol.sanitizeField(extras.getString("android.title") ?: "New Notification")
    val text = PillionProtocol.sanitizeField(extras.getCharSequence("android.text")?.toString() ?: "")

    val replyPair = findReplyAction(sbn.notification)
    trackedNotifications[key] = StoredNotificationEntry(
      rawKey = sbn.key,
      replyAction = replyPair?.first,
      remoteInputs = replyPair?.second
    )

    if (pkg != packageName) {
      val canReplyFlag = if (replyPair != null) "1" else "0"
      PillionStateStore.sendMessage("NOTIF_ADD2|$key|$pkg|$canReplyFlag|$title|$text")
    }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    val key = PillionProtocol.escapeKey(sbn.key)
    trackedNotifications.remove(key)
    PillionStateStore.sendMessage("NOTIF_REMOVE|$key")
    if (sbn.notification.category == Notification.CATEGORY_CALL) {
      activeCallAnswerAction = null
      activeCallDeclineAction = null
      PillionStateStore.sendMessage("CALL_END")
    }
  }
}
