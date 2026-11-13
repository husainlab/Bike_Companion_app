package com.husainlab.pillion.nativecore

import java.util.concurrent.CopyOnWriteArrayList

data class MirrorNotification(
  val key: String,
  val packageName: String,
  val title: String,
  val text: String,
  val receivedAt: Long = System.currentTimeMillis(),
  val canReply: Boolean = false
)

data class SyncedContact(
  val id: String,
  val name: String,
  val phoneNumber: String,
  val isStarred: Boolean = false
)

data class RecentCallEntry(
  val id: String,
  val name: String,
  val phoneNumber: String,
  val callType: Int,
  val timestampMs: Long
)

sealed class PillionPacket {
  data class Command(val path: String) : PillionPacket()
  data class Title(val value: String) : PillionPacket()
  data class Artist(val value: String) : PillionPacket()
  data class Playing(val isPlaying: Boolean) : PillionPacket()
  data class Progress(val positionMs: Long, val durationMs: Long) : PillionPacket()
  data class Battery(val level: Int, val tempC: Int = -1) : PillionPacket()
  data class Volume(val percent: Int) : PillionPacket()
  data class CallIncoming(val caller: String, val phoneNumber: String = "") : PillionPacket()
  data class CallActive(val caller: String = "", val phoneNumber: String = "") : PillionPacket()
  data class CallMute(val isMuted: Boolean) : PillionPacket()
  data object CallEnd : PillionPacket()
  data class NotificationAdd(val notification: MirrorNotification) : PillionPacket()
  data class NotificationRemove(val key: String) : PillionPacket()
  data object NotificationClearAll : PillionPacket()
  data object ContactsBegin : PillionPacket()
  data class ContactItem(val contact: SyncedContact) : PillionPacket()
  data object ContactsEnd : PillionPacket()
  data object RecentsBegin : PillionPacket()
  data class RecentItem(val recent: RecentCallEntry) : PillionPacket()
  data object RecentsEnd : PillionPacket()
}

object PillionProtocol {
  const val ACTION_SYNC = "com.husainlab.pillion.ACTION_SYNC"
  const val ACTION_SYNC_CONTACTS = "com.husainlab.pillion.ACTION_SYNC_CONTACTS"

  private val KEY_UNESCAPE_REGEX = Regex("%(25|7C|0A|0D)")
  private val CALL_TIMER_REGEX = Regex("\\b\\d{1,2}:\\d{2}\\b")

  fun sanitizeField(raw: String): String =
    raw.replace("\n", " ").replace("\r", " ").replace("|", " ").trim()

  fun escapeKey(raw: String): String =
    raw.replace("%", "%25")
      .replace("|", "%7C")
      .replace("\n", "%0A")
      .replace("\r", "%0D")

  fun unescapeKey(escaped: String): String =
    KEY_UNESCAPE_REGEX.replace(escaped) { match ->
      when (match.groupValues[1]) {
        "25" -> "%"
        "7C" -> "|"
        "0A" -> "\n"
        "0D" -> "\r"
        else -> match.value
      }
    }

  /**
   * Classifies a CATEGORY_CALL notification into CALL_ACTIVE or CALL_IN without
   * flipping an already active call back into ringing state on localized or timer updates.
   */
  fun classifyCallNotification(
    textHint: String?,
    currentCallActive: Boolean,
    currentCallIncoming: Boolean
  ): String {
    val lower = textHint?.lowercase()?.trim().orEmpty()
    val hasActivePhrase = lower.contains("ongoing") ||
      lower.contains("active") ||
      lower.contains("dialing") ||
      lower.contains("calling") ||
      lower.contains("in progress") ||
      lower.contains("connected") ||
      CALL_TIMER_REGEX.containsMatchIn(lower)
    if (hasActivePhrase) {
      return "CALL_ACTIVE"
    }
    if (currentCallActive && !currentCallIncoming) {
      return "CALL_ACTIVE"
    }
    return "CALL_IN"
  }

  fun clampOverlayCoord(value: Int, maxAllowed: Int): Int =
    value.coerceIn(0, maxAllowed.coerceAtLeast(0))

  fun formatCallElapsed(startedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (startedAtMs <= 0L || nowMs <= startedAtMs) return "00:00"
    val totalSec = (nowMs - startedAtMs) / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
      java.lang.String.format(java.util.Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
      java.lang.String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
    }
  }

  fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
      java.lang.String.format(java.util.Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
      java.lang.String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
    }
  }

  fun parse(line: String, isSender: Boolean): PillionPacket? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    if (isSender && trimmed.startsWith("/")) {
      return PillionPacket.Command(trimmed)
    }

    val parts = trimmed.split("|")
    if (parts.isEmpty()) return null

    return when (parts[0]) {
      "CALL_END" -> PillionPacket.CallEnd
      "CALL_MUTE" -> parts.getOrNull(1)?.let { PillionPacket.CallMute(it == "1") }
      "CALL_ACTIVE" -> PillionPacket.CallActive(
        caller = parts.getOrNull(1) ?: "",
        phoneNumber = parts.getOrNull(2) ?: ""
      )
      "TITLE" -> parts.getOrNull(1)?.let { PillionPacket.Title(it) }
      "ARTIST" -> parts.getOrNull(1)?.let { PillionPacket.Artist(it) }
      "PLAYING" -> parts.getOrNull(1)?.let { PillionPacket.Playing(it == "1") }
      "BATTERY" -> {
        parts.getOrNull(1)?.toIntOrNull()?.let { level ->
          val temp = parts.getOrNull(2)?.toIntOrNull() ?: -1
          PillionPacket.Battery(level, temp)
        }
      }
      "VOLUME" -> parts.getOrNull(1)?.toIntOrNull()?.let { PillionPacket.Volume(it) }
      "CALL_IN" -> parts.getOrNull(1)?.let {
        PillionPacket.CallIncoming(
          caller = it,
          phoneNumber = parts.getOrNull(2) ?: ""
        )
      }
      "NOTIF_REMOVE" -> parts.getOrNull(1)?.let { PillionPacket.NotificationRemove(it) }
      "NOTIF_CLEAR_ALL" -> PillionPacket.NotificationClearAll
      "PROGRESS" -> {
        if (parts.size >= 3) {
          PillionPacket.Progress(
            positionMs = parts[1].toLongOrNull() ?: 0L,
            durationMs = parts[2].toLongOrNull() ?: 0L
          )
        } else {
          null
        }
      }
      "NOTIF_ADD" -> {
        if (parts.size >= 5) {
          PillionPacket.NotificationAdd(
            MirrorNotification(
              key = parts[1],
              packageName = parts[2],
              title = parts[3],
              text = parts.drop(4).joinToString("|"),
              canReply = false
            )
          )
        } else {
          null
        }
      }
      "NOTIF_ADD2" -> {
        if (parts.size >= 6) {
          PillionPacket.NotificationAdd(
            MirrorNotification(
              key = parts[1],
              packageName = parts[2],
              canReply = parts[3] == "1",
              title = parts[4],
              text = parts.drop(5).joinToString("|")
            )
          )
        } else {
          null
        }
      }
      "CONTACTS_BEGIN" -> PillionPacket.ContactsBegin
      "CONTACT" -> {
        if (parts.size >= 5) {
          PillionPacket.ContactItem(
            SyncedContact(
              id = parts[1],
              name = parts[2],
              phoneNumber = parts[3],
              isStarred = parts[4] == "1"
            )
          )
        } else {
          null
        }
      }
      "CONTACTS_END" -> PillionPacket.ContactsEnd
      "RECENTS_BEGIN" -> PillionPacket.RecentsBegin
      "RECENT" -> {
        if (parts.size >= 6) {
          PillionPacket.RecentItem(
            RecentCallEntry(
              id = parts[1],
              name = parts[2],
              phoneNumber = parts[3],
              callType = parts[4].toIntOrNull() ?: 1,
              timestampMs = parts[5].toLongOrNull() ?: 0L
            )
          )
        } else {
          null
        }
      }
      "RECENTS_END" -> PillionPacket.RecentsEnd
      else -> null
    }
  }
}

object PillionStateStore {
  @Volatile var connectionStatus: String = "Disconnected"
  @Volatile var isServerRunning: Boolean = false
  @Volatile var isOverlayRunning: Boolean = false
  @Volatile var songTitle: String = "No Media Playing"
  @Volatile var artistName: String = "Unknown Artist"
  @Volatile var isPlaying: Boolean = false
  @Volatile var progressMs: Long = 0L
  @Volatile var durationMs: Long = 0L
  @Volatile var volumePercent: Int = 70
  @Volatile var batteryLevel: Int = -1
  @Volatile var batteryTempC: Int = -1
  @Volatile var isCallActive: Boolean = false
  @Volatile var isCallIncoming: Boolean = false
  @Volatile var isCallMuted: Boolean = false
  @Volatile var callStartedAtMs: Long = 0L
  @Volatile var callerName: String = ""
  @Volatile var callerNumber: String = ""
  @Volatile var lastNotification: MirrorNotification? = null

  private val notificationsList = mutableListOf<MirrorNotification>()
  private val contactsList = mutableListOf<SyncedContact>()
  private val contactsStaging = mutableListOf<SyncedContact>()
  private val recentsList = mutableListOf<RecentCallEntry>()
  private val recentsStaging = mutableListOf<RecentCallEntry>()
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  @Volatile var outgoingSender: ((String) -> Unit)? = null
  @Volatile var commandExecutor: ((String) -> Unit)? = null
  @Volatile var notificationDismissHandler: ((String) -> Boolean)? = null
  @Volatile var notificationReplyHandler: ((String, String) -> Boolean)? = null
  @Volatile var callActionFallbackHandler: ((Boolean) -> Boolean)? = null
  @Volatile var mediaSeekHandler: ((Long) -> Unit)? = null

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  fun notifyChanged() {
    for (listener in listeners) {
      listener()
    }
  }

  fun sendMessage(message: String) {
    outgoingSender?.invoke(message)
  }

  @Synchronized
  fun getNotifications(): List<MirrorNotification> = notificationsList.toList()

  @Synchronized
  fun getContacts(): List<SyncedContact> = contactsList.toList()

  @Synchronized
  fun setContacts(items: List<SyncedContact>) {
    contactsList.clear()
    contactsList.addAll(items)
  }

  @Synchronized
  fun getRecentCalls(): List<RecentCallEntry> = recentsList.toList()

  @Synchronized
  fun setRecentCalls(items: List<RecentCallEntry>) {
    recentsList.clear()
    recentsList.addAll(items)
  }

  @Synchronized
  private fun upsertNotification(notif: MirrorNotification) {
    val index = notificationsList.indexOfFirst { it.key == notif.key }
    if (index != -1) {
      notificationsList[index] = notif
    } else {
      notificationsList.add(0, notif)
      if (notificationsList.size > 40) {
        notificationsList.removeAt(notificationsList.lastIndex)
      }
    }
    lastNotification = notif
  }

  @Synchronized
  private fun removeNotification(key: String) {
    notificationsList.removeAll { it.key == key }
  }

  @Synchronized
  private fun clearNotifications() {
    notificationsList.clear()
  }

  @Synchronized
  private fun stageContact(contact: SyncedContact) {
    contactsStaging.add(contact)
  }

  @Synchronized
  private fun beginContacts() {
    contactsStaging.clear()
  }

  @Synchronized
  private fun commitContacts() {
    contactsList.clear()
    contactsList.addAll(contactsStaging)
    contactsStaging.clear()
  }

  @Synchronized
  private fun beginRecents() {
    recentsStaging.clear()
  }

  @Synchronized
  private fun stageRecent(recent: RecentCallEntry) {
    recentsStaging.add(recent)
  }

  @Synchronized
  private fun commitRecents() {
    recentsList.clear()
    recentsList.addAll(recentsStaging)
    recentsStaging.clear()
  }

  @Synchronized
  private fun clearTransientCollections() {
    notificationsList.clear()
    contactsStaging.clear()
    recentsStaging.clear()
    contactsList.clear()
    recentsList.clear()
  }

  fun resetTransientState(notify: Boolean = false) {
    songTitle = "No Media Playing"
    artistName = "Unknown Artist"
    isPlaying = false
    progressMs = 0L
    durationMs = 0L
    volumePercent = 70
    batteryLevel = -1
    batteryTempC = -1
    isCallActive = false
    isCallIncoming = false
    isCallMuted = false
    callStartedAtMs = 0L
    callerName = ""
    callerNumber = ""
    lastNotification = null
    clearTransientCollections()
    if (notify) {
      notifyChanged()
    }
  }

  fun applyPacket(line: String, isSender: Boolean, nowMs: Long = System.currentTimeMillis()) {
    val packet = PillionProtocol.parse(line, isSender) ?: return
    when (packet) {
      is PillionPacket.Command -> {
        commandExecutor?.invoke(packet.path)
        return
      }
      is PillionPacket.Title -> songTitle = packet.value
      is PillionPacket.Artist -> artistName = packet.value
      is PillionPacket.Playing -> isPlaying = packet.isPlaying
      is PillionPacket.Progress -> {
        progressMs = packet.positionMs
        durationMs = packet.durationMs
      }
      is PillionPacket.Battery -> {
        batteryLevel = packet.level
        batteryTempC = packet.tempC
      }
      is PillionPacket.Volume -> {
        volumePercent = packet.percent.coerceIn(0, 100)
      }
      is PillionPacket.CallIncoming -> {
        isCallActive = true
        isCallIncoming = true
        isCallMuted = false
        callStartedAtMs = 0L
        callerName = packet.caller
        callerNumber = packet.phoneNumber
      }
      is PillionPacket.CallActive -> {
        val enteringActive = !isCallActive || isCallIncoming || callStartedAtMs <= 0L
        isCallActive = true
        isCallIncoming = false
        if (enteringActive) {
          callStartedAtMs = nowMs
        }
        if (packet.caller.isNotEmpty()) callerName = packet.caller
        if (packet.phoneNumber.isNotEmpty()) callerNumber = packet.phoneNumber
      }
      is PillionPacket.CallMute -> {
        isCallMuted = packet.isMuted
      }
      PillionPacket.CallEnd -> {
        isCallActive = false
        isCallIncoming = false
        isCallMuted = false
        callStartedAtMs = 0L
        callerName = ""
        callerNumber = ""
      }
      is PillionPacket.NotificationAdd -> upsertNotification(packet.notification)
      is PillionPacket.NotificationRemove -> removeNotification(packet.key)
      PillionPacket.NotificationClearAll -> clearNotifications()
      PillionPacket.ContactsBegin -> beginContacts()
      is PillionPacket.ContactItem -> stageContact(packet.contact)
      PillionPacket.ContactsEnd -> commitContacts()
      PillionPacket.RecentsBegin -> beginRecents()
      is PillionPacket.RecentItem -> stageRecent(packet.recent)
      PillionPacket.RecentsEnd -> commitRecents()
    }
    notifyChanged()
  }
}
