package com.husainlab.pillion.nativecore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Android Auto-style floating AMOLED widget rendered over Google Maps (or any nav app).
 * Communicates strictly over Bluetooth SPP for media, contacts/calls, and alerts while
 * keeping all voice/music audio on the primary pocket phone.
 */
class PillionOverlayService : Service() {
  companion object {
    private const val CHANNEL_ID = "pillion_overlay_channel"
    private const val FOREGROUND_NOTIF_ID = 4109
  }

  private enum class WidgetTab { MEDIA, CALLS, ALERTS }
  private enum class CallsSubTab { FAV, RECENTS, ALL, KEYPAD }

  private data class RenderSignature(
    val isExpanded: Boolean,
    val popupKey: String?,
    val activeTab: WidgetTab,
    val callsSubTab: CallsSubTab,
    val isCallActive: Boolean,
    val isCallIncoming: Boolean,
    val isCallMuted: Boolean,
    val callerName: String,
    val callerNumber: String,
    val keypadText: String,
    val hasDuration: Boolean,
    val missedCallsCount: Int,
    val alertsCount: Int,
    val contactsStamp: Int,
    val recentsStamp: Int,
    val alertsStamp: Int,
    val showCollapsedTitle: Boolean
  )

  private lateinit var windowManager: WindowManager
  private lateinit var params: WindowManager.LayoutParams
  private var rootContainer: LinearLayout? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  private var isExpanded = true
  private var activeTab = WidgetTab.MEDIA
  private var callsSubTab = CallsSubTab.FAV
  private var keypadDigits = StringBuilder()
  private var popupNotification: MirrorNotification? = null
  private var lastSeenNotifKey: String? = null
  private var prevCallActive = false
  private var prevCallIncoming = false
  private var prevVolumePercent = -1
  private var isVolumeHudVisible = false
  private var isUserSeeking = false
  private var lastSignature: RenderSignature? = null

  private var headerLiveBadge: TextView? = null
  private var headerBatteryView: TextView? = null
  private var collapsedStatusView: TextView? = null
  private var collapsedTitleView: TextView? = null
  private var mediaTitleView: TextView? = null
  private var mediaArtistView: TextView? = null
  private var mediaTimeView: TextView? = null
  private var mediaProgressView: SeekBar? = null
  private var mediaPlayPauseButton: TextView? = null
  private var callTimerView: TextView? = null
  private var volumeHudContainer: LinearLayout? = null
  private var volumeHudBar: ProgressBar? = null
  private var volumeHudLabel: TextView? = null

  private val hidePopupRunnable = Runnable {
    popupNotification = null
    render()
  }

  private val hideVolumeRunnable = Runnable {
    isVolumeHudVisible = false
    volumeHudContainer?.visibility = View.GONE
  }

  private fun showVolumeHud(pct: Int, isAuto: Boolean = false) {
    mainHandler.removeCallbacks(hideVolumeRunnable)
    isVolumeHudVisible = true
    val autoTag = if (isAuto) " (AUTO)" else ""
    volumeHudLabel?.text = "VOLUME $pct%$autoTag"
    volumeHudBar?.progress = pct
    volumeHudContainer?.visibility = View.VISIBLE
    mainHandler.postDelayed(hideVolumeRunnable, 2500L)
  }

  private val callTimerRunnable: Runnable = object : Runnable {
    override fun run() {
      syncCallTimer()
    }
  }

  private fun syncCallTimer() {
    mainHandler.removeCallbacks(callTimerRunnable)
    if (isExpanded && PillionStateStore.isCallActive && !PillionStateStore.isCallIncoming && PillionStateStore.callStartedAtMs > 0L) {
      val elapsed = PillionProtocol.formatCallElapsed(PillionStateStore.callStartedAtMs)
      val mutedTag = if (PillionStateStore.isCallMuted) " • MUTED" else ""
      callTimerView?.text = "ACTIVE CALL • $elapsed$mutedTag"
      mainHandler.postDelayed(callTimerRunnable, 1000L)
    }
  }

  private val stateListener: () -> Unit = {
    mainHandler.post {
      val latest = PillionStateStore.lastNotification
      if (latest != null && latest.key != lastSeenNotifKey) {
        lastSeenNotifKey = latest.key
        if (!isExpanded) {
          popupNotification = latest
          mainHandler.removeCallbacks(hidePopupRunnable)
          mainHandler.postDelayed(hidePopupRunnable, 5000L)
        }
      }
      val nowCall = PillionStateStore.isCallActive
      val nowIncoming = PillionStateStore.isCallIncoming
      if ((nowCall && !prevCallActive) || (nowIncoming && !prevCallIncoming)) {
        isExpanded = true
      }
      prevCallActive = nowCall
      prevCallIncoming = nowIncoming

      val currentVol = PillionStateStore.volumePercent
      if (prevVolumePercent != -1 && currentVol != prevVolumePercent) {
        showVolumeHud(currentVol, false)
      }
      prevVolumePercent = currentVol

      render()
    }
  }

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      resources.displayMetrics
    ).roundToInt()

  private fun roundedBox(fillColor: Int, strokeColor: Int, strokeDp: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply {
      setColor(fillColor)
      setStroke(dp(strokeDp), strokeColor)
      cornerRadius = dp(radiusDp).toFloat()
    }

  override fun onCreate() {
    super.onCreate()
    startOverlayForeground()
    if (!Settings.canDrawOverlays(this)) {
      PillionStateStore.isOverlayRunning = false
      PillionStateStore.notifyChanged()
      stopSelf()
      return
    }
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    lastSeenNotifKey = PillionStateStore.lastNotification?.key
    prevCallActive = PillionStateStore.isCallActive
    prevCallIncoming = PillionStateStore.isCallIncoming
    if (createOverlayWindow()) {
      PillionStateStore.isOverlayRunning = true
      PillionStateStore.addListener(stateListener)
      PillionStateStore.notifyChanged()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startOverlayForeground()
    if (!Settings.canDrawOverlays(this)) {
      PillionStateStore.isOverlayRunning = false
      PillionStateStore.notifyChanged()
      stopSelf()
      return START_NOT_STICKY
    }
    return START_NOT_STICKY
  }

  private fun startOverlayForeground() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val existing = nm.getNotificationChannel(CHANNEL_ID)
      if (existing == null) {
        nm.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Pillion Cockpit Overlay", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps floating handlebar controls alive over Google Maps"
            setShowBadge(false)
          }
        )
      }
    }

    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent = launchIntent?.let {
      val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }
      PendingIntent.getActivity(this, 0, it, flags)
    }

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    val notification = builder
      .setContentTitle("Pillion Handlebar Widget")
      .setContentText("Floating media, call & alert controls active")
      .setSmallIcon(applicationInfo.icon)
      .setOngoing(true)
      .setContentIntent(contentIntent)
      .build()

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(FOREGROUND_NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(FOREGROUND_NOTIF_ID, notification)
      }
    } catch (_: Exception) {}
  }

  private fun createOverlayWindow(): Boolean {
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
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = dp(12)
      y = dp(84)
    }

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      background = roundedBox(Color.BLACK, Color.parseColor("#242824"), 2, 12)
    }

    return try {
      windowManager.addView(container, params)
      rootContainer = container
      render()
      true
    } catch (_: Exception) {
      rootContainer = null
      PillionStateStore.isOverlayRunning = false
      PillionStateStore.notifyChanged()
      stopSelf()
      false
    }
  }

  private fun attachDragOrClick(view: View, onClick: (() -> Unit)? = null) {
    var startX = 0
    var startY = 0
    var touchX = 0f
    var touchY = 0f
    var moved = false
    val slop = dp(8)

    view.setOnTouchListener { v, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          startX = params.x
          startY = params.y
          touchX = event.rawX
          touchY = event.rawY
          moved = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - touchX).roundToInt()
          val dy = (event.rawY - touchY).roundToInt()
          if (abs(dx) > slop || abs(dy) > slop) {
            moved = true
          }
          val metrics = resources.displayMetrics
          val viewWidth = (rootContainer?.width ?: dp(80)).coerceAtLeast(dp(48))
          val viewHeight = (rootContainer?.height ?: dp(48)).coerceAtLeast(dp(36))
          params.x = PillionProtocol.clampOverlayCoord(startX + dx, metrics.widthPixels - viewWidth)
          params.y = PillionProtocol.clampOverlayCoord(startY + dy, metrics.heightPixels - viewHeight)
          rootContainer?.let {
            try {
              windowManager.updateViewLayout(it, params)
            } catch (_: Exception) {}
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          if (!moved) {
            v.performClick()
            onClick?.invoke()
          }
          true
        }
        else -> false
      }
    }
  }

  private fun makeButton(
    label: String,
    bgColor: Int,
    fgColor: Int,
    borderColor: Int,
    textSp: Float = 13f,
    padH: Int = 10,
    padV: Int = 9,
    maxLines: Int = 1,
    onClick: () -> Unit
  ): TextView = TextView(this).apply {
    text = label
    setTextColor(fgColor)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    background = roundedBox(bgColor, borderColor, 1, 7)
    this.maxLines = maxLines
    if (maxLines == 1) {
      ellipsize = TextUtils.TruncateAt.END
    }
    setOnClickListener { onClick() }
  }

  private fun openLocalMaps() {
    val mapsPkg = "com.google.android.apps.maps"
    val intent = packageManager.getLaunchIntentForPackage(mapsPkg)
      ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      startActivity(intent)
    } catch (_: Exception) {}
  }

  private fun dialOverBluetooth(rawNumber: String) {
    val clean = rawNumber.trim()
    if (clean.isEmpty()) return
    PillionStateStore.sendMessage("/call/dial|$clean")
  }

  private fun buildRenderSignature(): RenderSignature {
    val contacts = PillionStateStore.getContacts()
    val recents = PillionStateStore.getRecentCalls()
    val alerts = PillionStateStore.getNotifications()
    val missedCalls = recents.count { it.callType == 3 }
    val showCollapsedTitle = PillionStateStore.songTitle != "No Media Playing" && !PillionStateStore.isCallActive
    return RenderSignature(
      isExpanded = isExpanded,
      popupKey = if (!isExpanded) popupNotification?.key else null,
      activeTab = activeTab,
      callsSubTab = callsSubTab,
      isCallActive = PillionStateStore.isCallActive,
      isCallIncoming = PillionStateStore.isCallIncoming,
      isCallMuted = PillionStateStore.isCallMuted,
      callerName = PillionStateStore.callerName,
      callerNumber = PillionStateStore.callerNumber,
      keypadText = keypadDigits.toString(),
      hasDuration = PillionStateStore.durationMs > 0L,
      missedCallsCount = missedCalls,
      alertsCount = alerts.size,
      contactsStamp = contacts.hashCode(),
      recentsStamp = recents.hashCode(),
      alertsStamp = alerts.hashCode(),
      showCollapsedTitle = showCollapsedTitle
    )
  }

  private fun clearViewRefs() {
    headerLiveBadge = null
    headerBatteryView = null
    collapsedStatusView = null
    collapsedTitleView = null
    mediaTitleView = null
    mediaArtistView = null
    mediaTimeView = null
    mediaProgressView = null
    mediaPlayPauseButton = null
    callTimerView = null
    volumeHudContainer = null
    volumeHudBar = null
    volumeHudLabel = null
  }

  private fun patchDynamicViews() {
    val orange = Color.parseColor("#FF5A1F")
    val emerald = Color.parseColor("#00E096")
    val crimson = Color.parseColor("#FF453A")
    val cardBg = Color.parseColor("#0B0B0C")
    val border = Color.parseColor("#242824")
    val muted = Color.parseColor("#B5BAB3")
    val connected = PillionStateStore.connectionStatus == "Connected"
    val level = PillionStateStore.batteryLevel
    val tempC = PillionStateStore.batteryTempC

    collapsedStatusView?.let { tv ->
      tv.text = when {
        PillionStateStore.isCallActive -> "CALL"
        tempC >= 45 -> "HOT!"
        level in 0..15 -> "LOW!"
        level >= 0 -> "$level%"
        connected -> "LIVE"
        else -> "OFF"
      }
      tv.setTextColor(
        when {
          PillionStateStore.isCallActive || tempC >= 45 || level in 0..15 -> crimson
          tempC >= 40 -> orange
          connected -> emerald
          else -> muted
        }
      )
    }
    collapsedTitleView?.text = "  ${PillionStateStore.songTitle}"

    headerLiveBadge?.let { tv ->
      tv.text = if (connected) "LIVE" else "OFF"
      tv.setTextColor(if (connected) emerald else muted)
      tv.background = roundedBox(cardBg, if (connected) emerald else border, 1, 5)
    }
    headerBatteryView?.let { tv ->
      val batteryLabel = if (level >= 0) "$level%" else "--%"
      val tempLabel = if (tempC >= 40) " • ${tempC}°C" else ""
      tv.text = "  $batteryLabel$tempLabel"
      tv.setTextColor(if (tempC >= 45 || level in 0..15) crimson else if (tempC >= 40) orange else Color.WHITE)
    }
    mediaTitleView?.text = PillionStateStore.songTitle
    mediaArtistView?.text = PillionStateStore.artistName
    if (!isUserSeeking && PillionStateStore.durationMs > 0L) {
      mediaProgressView?.progress = ((PillionStateStore.progressMs.toDouble() / PillionStateStore.durationMs.toDouble()) * 1000.0)
        .roundToInt()
        .coerceIn(0, 1000)
      val cur = PillionProtocol.formatDurationMs(PillionStateStore.progressMs)
      val dur = PillionProtocol.formatDurationMs(PillionStateStore.durationMs)
      mediaTimeView?.text = "$cur / $dur"
    } else if (PillionStateStore.durationMs <= 0L) {
      mediaTimeView?.text = ""
    }
    mediaPlayPauseButton?.text = if (PillionStateStore.isPlaying) "PAUSE" else "PLAY"
    volumeHudBar?.progress = PillionStateStore.volumePercent
  }

  private fun render() {
    val root = rootContainer ?: return
    val signature = buildRenderSignature()
    if (signature == lastSignature && root.childCount > 0) {
      patchDynamicViews()
      syncCallTimer()
      return
    }
    lastSignature = signature
    clearViewRefs()
    root.removeAllViews()

    val orange = Color.parseColor("#FF5A1F")
    val emerald = Color.parseColor("#00E096")
    val crimson = Color.parseColor("#FF453A")
    val cardBg = Color.parseColor("#0B0B0C")
    val surfaceMuted = Color.parseColor("#141516")
    val border = Color.parseColor("#242824")
    val muted = Color.parseColor("#B5BAB3")

    val popup = popupNotification
    if (!isExpanded && popup != null) {
      root.setPadding(dp(14), dp(12), dp(14), dp(12))
      val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.WRAP_CONTENT)
      }
      val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
      top.addView(TextView(this).apply {
        text = popup.title
        setTextColor(orange)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      })
      top.addView(makeButton("OPEN", cardBg, emerald, emerald, textSp = 11f, padH = 8, padV = 4) {
        popupNotification = null
        activeTab = WidgetTab.ALERTS
        isExpanded = true
        render()
      })
      row.addView(top)
      if (popup.text.isNotEmpty()) {
        row.addView(TextView(this).apply {
          text = popup.text
          setTextColor(Color.WHITE)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          maxLines = 3
          ellipsize = TextUtils.TruncateAt.END
          setPadding(0, dp(4), 0, 0)
        })
      }
      attachDragOrClick(row) {
        popupNotification = null
        activeTab = WidgetTab.ALERTS
        isExpanded = true
        render()
      }
      root.addView(row)
      syncCallTimer()
      return
    }

    if (!isExpanded) {
      root.setPadding(dp(12), dp(9), dp(12), dp(9))
      val pillRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
      val level = PillionStateStore.batteryLevel
      val connected = PillionStateStore.connectionStatus == "Connected"
      val statusLabel = when {
        PillionStateStore.isCallActive -> "CALL"
        level >= 0 -> "$level%"
        connected -> "LIVE"
        else -> "OFF"
      }
      val statusView = TextView(this).apply {
        text = statusLabel
        setTextColor(
          when {
            PillionStateStore.isCallActive -> crimson
            level in 0..20 -> crimson
            connected -> emerald
            else -> muted
          }
        )
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.MONOSPACE
      }
      collapsedStatusView = statusView
      pillRow.addView(statusView)
      if (PillionStateStore.songTitle != "No Media Playing" && !PillionStateStore.isCallActive) {
        val titleView = TextView(this).apply {
          text = "  ${PillionStateStore.songTitle}"
          setTextColor(Color.WHITE)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          maxLines = 1
          ellipsize = TextUtils.TruncateAt.END
          maxWidth = dp(130)
        }
        collapsedTitleView = titleView
        pillRow.addView(titleView)
      }
      attachDragOrClick(pillRow) {
        isExpanded = true
        render()
      }
      root.addView(pillRow)
      syncCallTimer()
      return
    }

    // Expanded Android Auto-style Floating Widget
    root.setPadding(dp(12), dp(12), dp(12), dp(12))
    val metrics = resources.displayMetrics
    val screenWidthDp = (metrics.widthPixels / metrics.density).roundToInt()
    val panelWidthDp = (screenWidthDp - 24).coerceIn(280, 340)

    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(dp(panelWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // Draggable Top Header
    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, 0, 0, dp(10))
    }
    attachDragOrClick(header)

    val connected = PillionStateStore.connectionStatus == "Connected"
    val liveBadge = TextView(this).apply {
      text = if (connected) "LIVE" else "OFF"
      setTextColor(if (connected) emerald else muted)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      typeface = Typeface.MONOSPACE
      setPadding(dp(8), dp(4), dp(8), dp(4))
      background = roundedBox(cardBg, if (connected) emerald else border, 1, 5)
    }
    headerLiveBadge = liveBadge
    header.addView(liveBadge)

    val level = PillionStateStore.batteryLevel
    val batView = TextView(this).apply {
      text = if (level >= 0) "  $level%" else "  --%"
      setTextColor(if (level in 0..20) crimson else Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.MONOSPACE
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerBatteryView = batView
    header.addView(batView)

    header.addView(makeButton("MAPS", cardBg, orange, orange, textSp = 11f, padH = 9, padV = 5) {
      openLocalMaps()
    })

    header.addView(makeButton("-", cardBg, muted, border, textSp = 13f, padH = 10, padV = 5) {
      isExpanded = false
      render()
    }.apply {
      val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
      lp.marginStart = dp(6)
      layoutParams = lp
    })
    panel.addView(header)

    // Pinned Call HUD (takes priority over body when ringing or on active call)
    if (PillionStateStore.isCallActive) {
      val isIncoming = PillionStateStore.isCallIncoming
      val isMuted = PillionStateStore.isCallMuted
      val callBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = roundedBox(cardBg, if (isIncoming) crimson else if (isMuted) orange else emerald, 1, 9)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        layoutParams = lp
      }
      val statusTv = TextView(this).apply {
        val elapsed = if (!isIncoming && PillionStateStore.callStartedAtMs > 0L) {
          " • " + PillionProtocol.formatCallElapsed(PillionStateStore.callStartedAtMs)
        } else ""
        val mutedTag = if (isMuted) " • MUTED" else ""
        text = if (isIncoming) "INCOMING CALL" else "ACTIVE CALL$elapsed$mutedTag"
        setTextColor(if (isIncoming) crimson else if (isMuted) orange else emerald)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
      }
      callTimerView = statusTv
      callBox.addView(statusTv)

      callBox.addView(TextView(this).apply {
        text = "AUDIO ON POCKET PHONE / INTERCOM"
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(1), 0, dp(4))
      })

      callBox.addView(TextView(this).apply {
        text = PillionStateStore.callerName.ifEmpty { "Unknown Caller" }
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(2), 0, dp(2))
      })
      if (PillionStateStore.callerNumber.isNotEmpty() && PillionStateStore.callerNumber != PillionStateStore.callerName) {
        callBox.addView(TextView(this).apply {
          text = PillionStateStore.callerNumber
          setTextColor(muted)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          typeface = Typeface.MONOSPACE
        })
      }

      val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        layoutParams = lp
      }
      actions.addView(makeButton(
        if (isIncoming) "DECLINE" else "END CALL",
        crimson,
        Color.WHITE,
        crimson,
        textSp = 13f
      ) {
        PillionStateStore.sendMessage("/call/decline")
      }.apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      })

      if (isIncoming) {
        actions.addView(makeButton("ANSWER", emerald, Color.BLACK, emerald, textSp = 13f) {
          PillionStateStore.sendMessage("/call/answer")
        }.apply {
          val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          lp.marginStart = dp(8)
          layoutParams = lp
        })
      } else {
        actions.addView(makeButton(
          if (isMuted) "MUTED" else "MUTE MIC",
          if (isMuted) orange else surfaceMuted,
          if (isMuted) Color.BLACK else Color.WHITE,
          if (isMuted) orange else border,
          textSp = 13f
        ) {
          PillionStateStore.sendMessage("/call/mute")
        }.apply {
          val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          lp.marginStart = dp(8)
          layoutParams = lp
        })
      }
      callBox.addView(actions)
      panel.addView(callBox)
    }

    // Main Widget Tab Content
    when (activeTab) {
      WidgetTab.MEDIA -> {
        val mediaCard = LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(12), dp(10), dp(12), dp(10))
          background = roundedBox(cardBg, border, 1, 9)
        }
        val topMeta = LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
          lp.bottomMargin = dp(4)
          layoutParams = lp
        }
        topMeta.addView(TextView(this).apply {
          text = "NOW PLAYING"
          setTextColor(muted)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
          typeface = Typeface.MONOSPACE
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val timeTv = TextView(this).apply {
          val cur = PillionProtocol.formatDurationMs(PillionStateStore.progressMs)
          val dur = PillionProtocol.formatDurationMs(PillionStateStore.durationMs)
          text = if (PillionStateStore.durationMs > 0L) "$cur / $dur" else ""
          setTextColor(orange)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
          typeface = Typeface.MONOSPACE
        }
        mediaTimeView = timeTv
        topMeta.addView(timeTv)
        mediaCard.addView(topMeta)

        val titleTv = TextView(this).apply {
          text = PillionStateStore.songTitle
          setTextColor(Color.WHITE)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
          typeface = Typeface.DEFAULT_BOLD
          maxLines = 2
          ellipsize = TextUtils.TruncateAt.END
        }
        mediaTitleView = titleTv
        mediaCard.addView(titleTv)

        val artistTv = TextView(this).apply {
          text = PillionStateStore.artistName
          setTextColor(muted)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
          maxLines = 2
          ellipsize = TextUtils.TruncateAt.END
          setPadding(0, dp(2), 0, 0)
        }
        mediaArtistView = artistTv
        mediaCard.addView(artistTv)

        if (PillionStateStore.durationMs > 0L) {
          val bar = SeekBar(this).apply {
            max = 1000
            progress = ((PillionStateStore.progressMs.toDouble() / PillionStateStore.durationMs.toDouble()) * 1000.0)
              .roundToInt()
              .coerceIn(0, 1000)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26))
            lp.topMargin = dp(6)
            layoutParams = lp
            setPadding(dp(4), 0, dp(4), 0)
          }
          bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, prog: Int, fromUser: Boolean) {
              if (fromUser && PillionStateStore.durationMs > 0L) {
                val targetMs = ((prog.toDouble() / 1000.0) * PillionStateStore.durationMs.toDouble()).toLong()
                val cur = PillionProtocol.formatDurationMs(targetMs)
                val dur = PillionProtocol.formatDurationMs(PillionStateStore.durationMs)
                mediaTimeView?.text = "$cur / $dur"
              }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
              isUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
              isUserSeeking = false
              seekBar?.let { sb ->
                if (PillionStateStore.durationMs > 0L) {
                  val targetMs = ((sb.progress.toDouble() / 1000.0) * PillionStateStore.durationMs.toDouble()).toLong()
                  PillionStateStore.progressMs = targetMs
                  PillionStateStore.sendMessage("/media/seek|$targetMs")
                }
              }
            }
          })
          mediaProgressView = bar
          mediaCard.addView(bar)
        }
        panel.addView(mediaCard)

        val controls = LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
          lp.topMargin = dp(8)
          layoutParams = lp
        }
        controls.addView(makeButton("PREV", cardBg, Color.WHITE, border) {
          PillionStateStore.sendMessage("/media/prev")
        }.apply {
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val playBtn = makeButton(
          if (PillionStateStore.isPlaying) "PAUSE" else "PLAY",
          orange,
          Color.BLACK,
          orange
        ) {
          PillionStateStore.sendMessage("/media/playpause")
        }.apply {
          val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
          lp.marginStart = dp(6)
          lp.marginEnd = dp(6)
          layoutParams = lp
        }
        mediaPlayPauseButton = playBtn
        controls.addView(playBtn)
        controls.addView(makeButton("NEXT", cardBg, Color.WHITE, border) {
          PillionStateStore.sendMessage("/media/next")
        }.apply {
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        panel.addView(controls)

        val volHud = LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(10), dp(6), dp(10), dp(6))
          background = roundedBox(cardBg, orange, 1, 7)
          visibility = if (isVolumeHudVisible) View.VISIBLE else View.GONE
          val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
          lp.topMargin = dp(6)
          layoutParams = lp
        }
        volumeHudContainer = volHud

        val vhLabel = TextView(this).apply {
          text = "VOLUME ${PillionStateStore.volumePercent}%"
          setTextColor(orange)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
          typeface = Typeface.MONOSPACE
        }
        volumeHudLabel = vhLabel
        volHud.addView(vhLabel)

        val vhBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
          max = 100
          progress = PillionStateStore.volumePercent
          val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
          lp.topMargin = dp(4)
          layoutParams = lp
        }
        volumeHudBar = vhBar
        volHud.addView(vhBar)
        panel.addView(volHud)

        val volRow = LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
          lp.topMargin = dp(6)
          layoutParams = lp
        }
        volRow.addView(makeButton("VOL -", surfaceMuted, muted, border, textSp = 12f, padV = 8) {
          val next = (PillionStateStore.volumePercent - 7).coerceIn(0, 100)
          showVolumeHud(next, false)
          PillionStateStore.sendMessage("/volume/down")
        }.apply {
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        volRow.addView(makeButton("VOL +", surfaceMuted, muted, border, textSp = 12f, padV = 8) {
          val next = (PillionStateStore.volumePercent + 7).coerceIn(0, 100)
          showVolumeHud(next, false)
          PillionStateStore.sendMessage("/volume/up")
        }.apply {
          val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          lp.marginStart = dp(6)
          layoutParams = lp
        })
        panel.addView(volRow)
      }

      WidgetTab.CALLS -> {
        val subBar = LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
          lp.bottomMargin = dp(8)
          layoutParams = lp
        }
        fun addSubBtn(tab: CallsSubTab, label: String, margin: Boolean = true) {
          val selected = callsSubTab == tab
          subBar.addView(makeButton(
            label,
            if (selected) surfaceMuted else cardBg,
            if (selected) orange else muted,
            if (selected) orange else border,
            textSp = 11f,
            padH = 6,
            padV = 6
          ) {
            callsSubTab = tab
            render()
          }.apply {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (margin) lp.marginEnd = dp(4)
            layoutParams = lp
          })
        }
        addSubBtn(CallsSubTab.FAV, "FAV")
        addSubBtn(CallsSubTab.RECENTS, "RECENTS")
        addSubBtn(CallsSubTab.ALL, "ALL")
        addSubBtn(CallsSubTab.KEYPAD, "PAD", margin = false)
        panel.addView(subBar)

        if (callsSubTab == CallsSubTab.KEYPAD) {
          val readoutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBox(cardBg, border, 1, 7)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            layoutParams = lp
          }
          readoutRow.addView(TextView(this).apply {
            text = if (keypadDigits.isEmpty()) "Enter number" else keypadDigits.toString()
            setTextColor(if (keypadDigits.isEmpty()) muted else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          })
          readoutRow.addView(makeButton("DEL", surfaceMuted, muted, border, textSp = 11f, padH = 8, padV = 5) {
            if (keypadDigits.isNotEmpty()) {
              keypadDigits.deleteCharAt(keypadDigits.length - 1)
              render()
            }
          })
          panel.addView(readoutRow)

          val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
          )
          for (keys in rows) {
            val kr = LinearLayout(this).apply {
              orientation = LinearLayout.HORIZONTAL
              val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
              lp.bottomMargin = dp(4)
              layoutParams = lp
            }
            keys.forEachIndexed { idx, key ->
              kr.addView(makeButton(key, cardBg, Color.WHITE, border, textSp = 15f, padV = 7) {
                keypadDigits.append(key)
                render()
              }.apply {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (idx < 2) lp.marginEnd = dp(4)
                layoutParams = lp
              })
            }
            panel.addView(kr)
          }

          panel.addView(makeButton("CALL VIA POCKET PHONE", emerald, Color.BLACK, emerald, textSp = 13f, padV = 10) {
            if (keypadDigits.isNotEmpty()) {
              dialOverBluetooth(keypadDigits.toString())
            }
          }.apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(2)
            layoutParams = lp
          })
        } else {
          val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(196))
          }
          val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

          if (callsSubTab == CallsSubTab.RECENTS) {
            val recents = PillionStateStore.getRecentCalls()
            if (recents.isEmpty()) {
              list.addView(makeEmptyRow("No recent calls synced yet.", muted, surfaceMuted, border) {
                PillionStateStore.sendMessage("/sync/contacts")
              })
            } else {
              recents.take(15).forEach { entry ->
                val badge = when (entry.callType) {
                  2 -> "OUT"
                  3 -> "MISSED"
                  else -> "IN"
                }
                list.addView(makeContactRow(
                  title = entry.name,
                  subtitle = "$badge • ${entry.phoneNumber}",
                  isAlert = entry.callType == 3,
                  cardBg = cardBg,
                  border = border,
                  muted = muted,
                  orange = orange,
                  crimson = crimson
                ) {
                  dialOverBluetooth(entry.phoneNumber)
                })
              }
            }
          } else {
            val allContacts = PillionStateStore.getContacts()
            val items = if (callsSubTab == CallsSubTab.FAV) {
              val starred = allContacts.filter { it.isStarred }
              if (starred.isNotEmpty()) starred else allContacts.take(12)
            } else {
              allContacts
            }
            if (items.isEmpty()) {
              list.addView(makeEmptyRow("No contacts synced yet.", muted, surfaceMuted, border) {
                PillionStateStore.sendMessage("/sync/contacts")
              })
            } else {
              items.take(30).forEach { c ->
                list.addView(makeContactRow(
                  title = c.name,
                  subtitle = if (c.isStarred) "★ ${c.phoneNumber}" else c.phoneNumber,
                  isAlert = false,
                  cardBg = cardBg,
                  border = border,
                  muted = muted,
                  orange = orange,
                  crimson = crimson
                ) {
                  dialOverBluetooth(c.phoneNumber)
                })
              }
            }
          }
          scroll.addView(list)
          panel.addView(scroll)
        }
      }

      WidgetTab.ALERTS -> {
        val scroll = ScrollView(this).apply {
          layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(204))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = PillionStateStore.getNotifications()
        if (items.isEmpty()) {
          list.addView(TextView(this).apply {
            text = "No active notifications mirrored"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(24), dp(8), dp(24))
          })
        } else {
          items.take(10).forEach { notif ->
            val itemCard = LinearLayout(this).apply {
              orientation = LinearLayout.VERTICAL
              setPadding(dp(10), dp(8), dp(10), dp(8))
              background = roundedBox(cardBg, border, 1, 7)
              val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
              lp.bottomMargin = dp(6)
              layoutParams = lp
            }
            val top = LinearLayout(this).apply {
              orientation = LinearLayout.HORIZONTAL
              gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
              text = notif.title
              setTextColor(Color.WHITE)
              setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
              typeface = Typeface.DEFAULT_BOLD
              maxLines = 2
              ellipsize = TextUtils.TruncateAt.END
              layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(makeButton("X", surfaceMuted, muted, border, textSp = 11f, padH = 8, padV = 3) {
              PillionStateStore.sendMessage("/notif/dismiss|${notif.key}")
            })
            itemCard.addView(top)
            if (notif.text.isNotEmpty()) {
              itemCard.addView(TextView(this).apply {
                text = notif.text
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
              })
            }
            if (notif.canReply) {
              val repliesRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
              }
              val presets = listOf("Riding, call soon", "On my way")
              presets.forEachIndexed { idx, reply ->
                repliesRow.addView(makeButton(reply, surfaceMuted, emerald, emerald, textSp = 11f, padH = 6, padV = 4) {
                  PillionStateStore.sendMessage("/notif/reply|${notif.key}|$reply")
                }.apply {
                  val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                  if (idx == 0) lp.marginEnd = dp(6)
                  layoutParams = lp
                })
              }
              itemCard.addView(repliesRow)
            }
            list.addView(itemCard)
          }
        }
        scroll.addView(list)
        panel.addView(scroll)
      }
    }

    // Android Auto-Style Bottom Navigation Dock Rail
    val dock = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
      lp.topMargin = dp(10)
      layoutParams = lp
    }
    fun addDockTab(tab: WidgetTab, label: String, marginEnd: Boolean = true) {
      val selected = activeTab == tab
      dock.addView(makeButton(
        label,
        if (selected) orange else cardBg,
        if (selected) Color.BLACK else Color.WHITE,
        if (selected) orange else border,
        textSp = 11f,
        padH = 4,
        padV = 8,
        maxLines = 1
      ) {
        activeTab = tab
        render()
      }.apply {
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (marginEnd) lp.marginEnd = dp(6)
        layoutParams = lp
      })
    }
    val missedCalls = PillionStateStore.getRecentCalls().count { it.callType == 3 }
    val alertsCount = PillionStateStore.getNotifications().size
    addDockTab(WidgetTab.MEDIA, "MEDIA")
    addDockTab(WidgetTab.CALLS, if (missedCalls > 0) "CALLS ($missedCalls)" else "CALLS")
    addDockTab(WidgetTab.ALERTS, if (alertsCount > 0) "ALERTS ($alertsCount)" else "ALERTS", marginEnd = false)
    panel.addView(dock)

    root.addView(panel)
    syncCallTimer()
  }

  private fun makeEmptyRow(
    message: String,
    muted: Int,
    surfaceMuted: Int,
    border: Int,
    onSync: () -> Unit
  ): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(8), dp(14), dp(8), dp(14))
    addView(TextView(this@PillionOverlayService).apply {
      text = message
      setTextColor(muted)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      setPadding(0, 0, 0, dp(8))
    })
    addView(makeButton("SYNC CONTACTS NOW", surfaceMuted, Color.WHITE, border, textSp = 12f) {
      onSync()
    })
  }

  private fun makeContactRow(
    title: String,
    subtitle: String,
    isAlert: Boolean,
    cardBg: Int,
    border: Int,
    muted: Int,
    orange: Int,
    crimson: Int,
    onDial: () -> Unit
  ): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(10), dp(8), dp(10), dp(8))
    background = roundedBox(cardBg, border, 1, 7)
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.bottomMargin = dp(5)
    layoutParams = lp
    setOnClickListener { onDial() }

    val info = LinearLayout(this@PillionOverlayService).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    info.addView(TextView(this@PillionOverlayService).apply {
      text = title
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      typeface = Typeface.DEFAULT_BOLD
      maxLines = 2
      ellipsize = TextUtils.TruncateAt.END
    })
    info.addView(TextView(this@PillionOverlayService).apply {
      text = subtitle
      setTextColor(if (isAlert) crimson else muted)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      typeface = Typeface.MONOSPACE
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.END
    })
    addView(info)
    addView(makeButton("CALL", orange, Color.BLACK, orange, textSp = 11f, padH = 10, padV = 6) {
      onDial()
    })
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    PillionStateStore.isOverlayRunning = false
    PillionStateStore.removeListener(stateListener)
    PillionStateStore.notifyChanged()
    mainHandler.removeCallbacks(hidePopupRunnable)
    mainHandler.removeCallbacks(hideVolumeRunnable)
    mainHandler.removeCallbacks(callTimerRunnable)
    rootContainer?.let {
      try {
        windowManager.removeView(it)
      } catch (_: Exception) {}
    }
    rootContainer = null
  }
}
