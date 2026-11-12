package com.husainlab.pillion.nativecore

import java.util.concurrent.CopyOnWriteArrayList

data class MirrorNotification(
  val key: String,
  val packageName: String,
  val title: String,
  val text: String,
  val receivedAt: Long = System.currentTimeMillis()
)

sealed class PillionPacket {
  data class Command(val path: String) : PillionPacket()
  data class Title(val value: String) : PillionPacket()
  data class Artist(val value: String) : PillionPacket()
  data class Playing(val isPlaying: Boolean) : PillionPacket()
  data class Progress(val positionMs: Long, val durationMs: Long) : PillionPacket()
  data class Battery(val level: Int) : PillionPacket()
  data class CallIncoming(val caller: String) : PillionPacket()
  data object CallActive : PillionPacket()
  data object CallEnd : PillionPacket()
  data class NotificationAdd(val notification: MirrorNotification) : PillionPacket()
  data class NotificationRemove(val key: String) : PillionPacket()
}

object PillionProtocol {
  const val ACTION_SYNC = "com.husainlab.pillion.ACTION_SYNC"

  fun sanitizeField(raw: String): String =
    raw.replace("\n", " ").replace("\r", " ").replace("|", " ").trim()

  fun escapeKey(raw: String): String = raw.replace("|", "^")

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
      "CALL_ACTIVE" -> PillionPacket.CallActive
      "TITLE" -> parts.getOrNull(1)?.let { PillionPacket.Title(it) }
      "ARTIST" -> parts.getOrNull(1)?.let { PillionPacket.Artist(it) }
      "PLAYING" -> parts.getOrNull(1)?.let { PillionPacket.Playing(it == "1") }
      "BATTERY" -> parts.getOrNull(1)?.toIntOrNull()?.let { PillionPacket.Battery(it) }
      "CALL_IN" -> parts.getOrNull(1)?.let { PillionPacket.CallIncoming(it) }
      "NOTIF_REMOVE" -> parts.getOrNull(1)?.let { PillionPacket.NotificationRemove(it) }
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
              text = parts.drop(4).joinToString("|")
            )
          )
        } else {
          null
        }
      }
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
  @Volatile var batteryLevel: Int = -1
  @Volatile var isCallActive: Boolean = false
  @Volatile var isCallIncoming: Boolean = false
  @Volatile var callerName: String = ""
  @Volatile var lastNotification: MirrorNotification? = null

  private val notificationsList = mutableListOf<MirrorNotification>()
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  @Volatile var outgoingSender: ((String) -> Unit)? = null
  @Volatile var commandExecutor: ((String) -> Unit)? = null

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

  fun applyPacket(line: String, isSender: Boolean) {
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
      is PillionPacket.Battery -> batteryLevel = packet.level
      is PillionPacket.CallIncoming -> {
        isCallActive = true
        isCallIncoming = true
        callerName = packet.caller
      }
      PillionPacket.CallActive -> {
        isCallActive = true
        isCallIncoming = false
      }
      PillionPacket.CallEnd -> {
        isCallActive = false
        isCallIncoming = false
        callerName = ""
      }
      is PillionPacket.NotificationAdd -> upsertNotification(packet.notification)
      is PillionPacket.NotificationRemove -> removeNotification(packet.key)
    }
    notifyChanged()
  }
}

