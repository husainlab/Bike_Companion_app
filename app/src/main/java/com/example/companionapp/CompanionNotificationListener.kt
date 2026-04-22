package com.example.companionapp

import android.Manifest
import android.app.Notification
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
import android.os.BatteryManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat

class CompanionNotificationListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private val activeControllers = mutableMapOf<MediaController, MediaController.Callback>()
    private var lastTitle = ""
    private var lastArtist = ""

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateControllers(controllers)
    }

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.companionapp.ACTION_SYNC" -> {
                    // Send battery
                    val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    if (level != -1) {
                        MainActivity.sendMessage("BATTERY|$level")
                    }
                    
                    // Send media
                    checkAndSendMediaUpdate()
                    
                    // Resend all current notifications
                    try {
                        activeNotifications?.forEach { sbn ->
                            onNotificationPosted(sbn)
                        }
                    } catch (e: Exception) {
                        // Error resending notifications
                    }
                }
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    
                    when (state) {
                        TelephonyManager.EXTRA_STATE_RINGING -> {
                            val caller = if (number.isNullOrEmpty()) "Incoming Call" else getContactName(number)
                            MainActivity.sendMessage("CALL_IN|$caller")
                        }
                        TelephonyManager.EXTRA_STATE_IDLE -> MainActivity.sendMessage("CALL_END")
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                            // Call was answered, keep it active on UI but update text if needed
                            MainActivity.sendMessage("CALL_ACTIVE")
                        }
                    }
                }
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

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            if (level != -1) {
                MainActivity.sendMessage("BATTERY|$level")
            }
        }
    }

    private var isCallListenerRegistered = false

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        // setupCallListener() - Call this in onListenerConnected instead to ensure permissions are likely granted
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        setupCallListener()
        
        val intentFilter = IntentFilter().apply {
            addAction("com.example.companionapp.ACTION_SYNC")
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
            registerReceiver(syncReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registerReceiver(syncReceiver, intentFilter)
        }
        
        // Initial battery check and sync
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        if (level != -1) {
            MainActivity.sendMessage("BATTERY|$level")
        }
        
        val componentName = ComponentName(this, this::class.java)
        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            updateControllers(mediaSessionManager?.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            // Permission denied
        }
        
        // Sync existing notifications to receiver
        for (sbn in activeNotifications) {
            onNotificationPosted(sbn)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(syncReceiver)
        } catch (e: Exception) {
            // ignore
        }
        removeAllCallbacks()
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
        val controllers = mediaSessionManager?.getActiveSessions(componentName)
        
        // Priority: 1. Currently playing, 2. Has metadata, 3. First available
        val bestController = controllers?.find { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.find { it.metadata != null }
            ?: controllers?.firstOrNull()
            
        bestController?.let { sendMediaUpdate(it) }
    }

    private fun removeAllCallbacks() {
        activeControllers.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        activeControllers.clear()
    }

    private fun sendMediaUpdate(controller: MediaController) {
        val metadata = controller.metadata
        val pbState = controller.playbackState
        val title = (metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "No Media Playing")
            .replace("\n", " ").replace("|", " ")
        val artist = (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist")
            .replace("\n", " ").replace("|", " ")
        
        // Consider playing, buffering, or fast-forwarding as "playing"
        val state = pbState?.state ?: PlaybackState.STATE_NONE
        val isPlaying = state == PlaybackState.STATE_PLAYING || 
                        state == PlaybackState.STATE_BUFFERING ||
                        state == PlaybackState.STATE_FAST_FORWARDING
        
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = pbState?.position ?: 0L

        // Optimize: Only send title/artist if changed
        if (title != lastTitle || artist != lastArtist) {
            MainActivity.sendMessage("TITLE|$title")
            MainActivity.sendMessage("ARTIST|$artist")
            lastTitle = title
            lastArtist = artist
        }
        
        MainActivity.sendMessage("PLAYING|${if (isPlaying) "1" else "0"}")
        MainActivity.sendMessage("PROGRESS|$position|$duration")
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
                // Wrap in additional try-catch for API 31+ as it's more strict
                try {
                    telephonyManager.registerTelephonyCallback(mainExecutor, object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handleCallState(state, null)
                        }
                    })
                    isCallListenerRegistered = true
                } catch (e: SecurityException) {
                    // API 31+ SecurityException
                }
            } else {
                telephonyManager.listen(object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }, PhoneStateListener.LISTEN_CALL_STATE)
                isCallListenerRegistered = true
            }
        } catch (e: Exception) {
            // General exception while registering call listener
        }
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val caller = if (phoneNumber.isNullOrEmpty()) "Incoming Call" else getContactName(phoneNumber)
                MainActivity.sendMessage("CALL_IN|$caller")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                MainActivity.sendMessage("CALL_END")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call answered - keep UI active
                MainActivity.sendMessage("CALL_ACTIVE")
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return // Skip persistent notifications

        val category = sbn.notification.category
        
        // Fallback call detection via notification category
        if (category == Notification.CATEGORY_CALL) {
            val extras = sbn.notification.extras
            val title = (extras.getString("android.title") ?: "Incoming Call")
            MainActivity.sendMessage("CALL_IN|$title")
        }

        // Skip media notifications as they are handled by MediaSessionManager
        val isMedia = category == Notification.CATEGORY_TRANSPORT || 
                     sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        if (isMedia) return

        val key = sbn.key.replace("|", "^") // Escape pipe in key
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = (extras.getString("android.title") ?: "New Notification")
            .replace("\n", " ").replace("|", " ")
        val text = (extras.getCharSequence("android.text")?.toString() ?: "")
            .replace("\n", " ").replace("|", " ")

        if (packageName != "com.example.companionapp") {
            MainActivity.sendMessage("NOTIF_ADD|$key|$packageName|$title|$text")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key.replace("|", "^")
        MainActivity.sendMessage("NOTIF_REMOVE|$key")
        
        // Robustness: If a call notification is removed, it definitely ended
        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            MainActivity.sendMessage("CALL_END")
        }
    }
}
