package com.husainlab.pillion.nativecore

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Floating AMOLED cockpit overlay service rendered with native Android views so it
 * runs with zero Compose runtime overhead and stays readable in direct sunlight.
 */
class PillionOverlayService : Service() {
  private lateinit var windowManager: WindowManager
  private lateinit var params: WindowManager.LayoutParams
  private var rootContainer: LinearLayout? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  private var isExpanded = true
  private var showNotifications = false
  private var popupNotification: MirrorNotification? = null
  private var lastSeenNotifKey: String? = null

  private val hidePopupRunnable = Runnable {
    popupNotification = null
    render()
  }

  private val stateListener: () -> Unit = {
    mainHandler.post {
      val latest = PillionStateStore.lastNotification
      if (latest != null && latest.key != lastSeenNotifKey && !showNotifications && !isExpanded) {
        lastSeenNotifKey = latest.key
        popupNotification = latest
        mainHandler.removeCallbacks(hidePopupRunnable)
        mainHandler.postDelayed(hidePopupRunnable, 4000L)
      }
      if (PillionStateStore.isCallActive) {
        isExpanded = true
      }
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
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    PillionStateStore.isOverlayRunning = true
    PillionStateStore.addListener(stateListener)
    PillionStateStore.notifyChanged()
    createOverlayWindow()
  }

  private fun createOverlayWindow() {
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
      x = dp(16)
      y = dp(96)
    }

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      background = roundedBox(Color.BLACK, Color.parseColor("#242824"), 2, 10)
    }

    rootContainer = container
    windowManager.addView(container, params)
    render()
  }

  private fun attachDragBehavior(view: View) {
    var startX = 0
    var startY = 0
    var touchX = 0f
    var touchY = 0f
    view.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          startX = params.x
          startY = params.y
          touchX = event.rawX
          touchY = event.rawY
          true
        }
        MotionEvent.ACTION_MOVE -> {
          params.x = startX + (event.rawX - touchX).roundToInt()
          params.y = startY + (event.rawY - touchY).roundToInt()
          rootContainer?.let { windowManager.updateViewLayout(it, params) }
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
    onClick: () -> Unit
  ): TextView = TextView(this).apply {
    text = label
    setTextColor(fgColor)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setPadding(dp(12), dp(10), dp(12), dp(10))
    background = roundedBox(bgColor, borderColor, 1, 6)
    setOnClickListener { onClick() }
  }

  private fun render() {
    val root = rootContainer ?: return
    root.removeAllViews()

    val orange = Color.parseColor("#FF5A1F")
    val emerald = Color.parseColor("#00E096")
    val crimson = Color.parseColor("#FF453A")
    val cardBg = Color.parseColor("#0A0B0A")
    val border = Color.parseColor("#242824")
    val muted = Color.parseColor("#B5BAB3")

    val popup = popupNotification
    if (!isExpanded && popup != null) {
      root.setPadding(dp(14), dp(12), dp(14), dp(12))
      val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnClickListener {
          popupNotification = null
          isExpanded = true
          render()
        }
      }
      row.addView(TextView(this).apply {
        text = popup.title
        setTextColor(orange)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
      })
      row.addView(TextView(this).apply {
        text = popup.text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
      })
      root.addView(row)
      return
    }

    if (!isExpanded) {
      root.setPadding(dp(12), dp(10), dp(12), dp(10))
      val pill = TextView(this).apply {
        val level = PillionStateStore.batteryLevel
        text = when {
          PillionStateStore.isCallActive -> "CALL"
          level >= 0 -> "$level%"
          PillionStateStore.connectionStatus == "Connected" -> "LINK"
          else -> "OFF"
        }
        setTextColor(
          when {
            PillionStateStore.isCallActive -> crimson
            level in 0..20 -> crimson
            PillionStateStore.connectionStatus == "Connected" -> emerald
            else -> Color.WHITE
          }
        )
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.MONOSPACE
        setOnClickListener {
          isExpanded = true
          render()
        }
      }
      attachDragBehavior(pill)
      root.addView(pill)
      return
    }

    root.setPadding(dp(14), dp(14), dp(14), dp(14))
    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // Header row (draggable)
    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, 0, 0, dp(10))
    }
    attachDragBehavior(header)

    val statusBadge = TextView(this).apply {
      val connected = PillionStateStore.connectionStatus == "Connected"
      text = if (connected) "ACTIVE" else "OFFLINE"
      setTextColor(if (connected) emerald else muted)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      typeface = Typeface.MONOSPACE
      setPadding(dp(8), dp(4), dp(8), dp(4))
      background = roundedBox(cardBg, if (connected) emerald else border, 1, 4)
    }
    header.addView(statusBadge)

    val level = PillionStateStore.batteryLevel
    val batteryText = TextView(this).apply {
      text = if (level >= 0) "  $level%" else "  --%"
      setTextColor(if (level in 0..20) crimson else Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      typeface = Typeface.MONOSPACE
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    header.addView(batteryText)

    val notifToggle = makeButton(
      if (showNotifications) "MEDIA" else "ALERTS (${PillionStateStore.getNotifications().size})",
      cardBg,
      Color.WHITE,
      border
    ) {
      showNotifications = !showNotifications
      render()
    }
    header.addView(notifToggle)

    val collapseBtn = makeButton("-", cardBg, muted, border) {
      isExpanded = false
      render()
    }.apply {
      val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
      lp.marginStart = dp(6)
      layoutParams = lp
    }
    header.addView(collapseBtn)
    panel.addView(header)

    if (PillionStateStore.isCallActive) {
      val callBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = roundedBox(cardBg, crimson, 1, 8)
      }
      callBox.addView(TextView(this).apply {
        text = if (PillionStateStore.isCallIncoming) "INCOMING CALL" else "ACTIVE CALL"
        setTextColor(crimson)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
      })
      callBox.addView(TextView(this).apply {
        text = PillionStateStore.callerName.ifEmpty { "Unknown Caller" }
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(10))
      })

      val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
      val declineBtn = makeButton("DECLINE", crimson, Color.WHITE, crimson) {
        PillionStateStore.sendMessage("/call/decline")
      }.apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
      actions.addView(declineBtn)

      if (PillionStateStore.isCallIncoming) {
        val answerBtn = makeButton("ANSWER", emerald, Color.BLACK, emerald) {
          PillionStateStore.sendMessage("/call/answer")
        }.apply {
          val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          lp.marginStart = dp(8)
          layoutParams = lp
        }
        actions.addView(answerBtn)
      }
      callBox.addView(actions)
      panel.addView(callBox)
    } else if (showNotifications) {
      val scroll = ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
      }
      val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
      val items = PillionStateStore.getNotifications()
      if (items.isEmpty()) {
        list.addView(TextView(this).apply {
          text = "No queued alerts"
          setTextColor(muted)
          setPadding(dp(8), dp(16), dp(8), dp(16))
        })
      } else {
        items.take(8).forEach { notif ->
          val itemCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBox(cardBg, border, 1, 6)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            layoutParams = lp
          }
          itemCard.addView(TextView(this).apply {
            text = notif.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
          })
          if (notif.text.isNotEmpty()) {
            itemCard.addView(TextView(this).apply {
              text = notif.text
              setTextColor(muted)
              setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
              maxLines = 2
              ellipsize = TextUtils.TruncateAt.END
            })
          }
          list.addView(itemCard)
        }
      }
      scroll.addView(list)
      panel.addView(scroll)
    } else {
      val mediaCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBox(cardBg, border, 1, 8)
      }
      mediaCard.addView(TextView(this).apply {
        text = PillionStateStore.songTitle
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
      })
      mediaCard.addView(TextView(this).apply {
        text = PillionStateStore.artistName
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
      })
      if (PillionStateStore.durationMs > 0L) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
          max = 1000
          progress = ((PillionStateStore.progressMs.toDouble() / PillionStateStore.durationMs.toDouble()) * 1000.0)
            .roundToInt()
            .coerceIn(0, 1000)
          val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
          lp.topMargin = dp(8)
          layoutParams = lp
        }
        mediaCard.addView(bar)
      }
      panel.addView(mediaCard)

      val controls = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        layoutParams = lp
      }
      val prevBtn = makeButton("PREV", cardBg, Color.WHITE, border) {
        PillionStateStore.sendMessage("/media/prev")
      }.apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
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
      val nextBtn = makeButton("NEXT", cardBg, Color.WHITE, border) {
        PillionStateStore.sendMessage("/media/next")
      }.apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
      controls.addView(prevBtn)
      controls.addView(playBtn)
      controls.addView(nextBtn)
      panel.addView(controls)
    }

    root.addView(panel)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    PillionStateStore.isOverlayRunning = false
    PillionStateStore.removeListener(stateListener)
    PillionStateStore.notifyChanged()
    mainHandler.removeCallbacks(hidePopupRunnable)
    rootContainer?.let {
      try {
        windowManager.removeView(it)
      } catch (_: Exception) {}
    }
    rootContainer = null
  }
}

