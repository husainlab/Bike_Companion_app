package com.husainlab.pillion.nativecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PillionProtocolTest {
  @Test
  fun parsesMediaAndBatteryPackets() {
    assertEquals(PillionPacket.Title("Nightcall"), PillionProtocol.parse("TITLE|Nightcall", false))
    assertEquals(PillionPacket.Artist("Kavinsky"), PillionProtocol.parse("ARTIST|Kavinsky", false))
    assertEquals(PillionPacket.Playing(true), PillionProtocol.parse("PLAYING|1", false))
    assertEquals(PillionPacket.Battery(84), PillionProtocol.parse("BATTERY|84", false))
    assertEquals(PillionPacket.Progress(12000L, 258000L), PillionProtocol.parse("PROGRESS|12000|258000", false))
  }

  @Test
  fun parsesCommandsOnlyInSenderMode() {
    assertEquals(PillionPacket.Command("/media/playpause"), PillionProtocol.parse("/media/playpause", true))
    assertEquals(PillionPacket.Command("/call/dial|+15550199"), PillionProtocol.parse("/call/dial|+15550199", true))
    assertNull(PillionProtocol.parse("/media/playpause", false))
  }

  @Test
  fun parsesNotificationsLegacyAndReplyAware() {
    val legacy = PillionProtocol.parse("NOTIF_ADD|k1|com.chat|Alex|Meet at 7 | Gate B", false)
    assertTrue(legacy is PillionPacket.NotificationAdd)
    val n1 = (legacy as PillionPacket.NotificationAdd).notification
    assertEquals("k1", n1.key)
    assertEquals("com.chat", n1.packageName)
    assertEquals("Alex", n1.title)
    assertEquals("Meet at 7 | Gate B", n1.text)
    assertFalse(n1.canReply)

    val replyable = PillionProtocol.parse("NOTIF_ADD2|k2|com.whatsapp|1|Sam|See you | soon", false)
    assertTrue(replyable is PillionPacket.NotificationAdd)
    val n2 = (replyable as PillionPacket.NotificationAdd).notification
    assertEquals("k2", n2.key)
    assertTrue(n2.canReply)
    assertEquals("See you | soon", n2.text)
  }

  @Test
  fun parsesCallContactsAndRecents() {
    val incoming = PillionProtocol.parse("CALL_IN|Maya|+15551234", false) as PillionPacket.CallIncoming
    assertEquals("Maya", incoming.caller)
    assertEquals("+15551234", incoming.phoneNumber)

    val contact = PillionProtocol.parse("CONTACT|42|Maya|+15551234|1", false) as PillionPacket.ContactItem
    assertEquals("42", contact.contact.id)
    assertEquals("Maya", contact.contact.name)
    assertEquals("+15551234", contact.contact.phoneNumber)
    assertTrue(contact.contact.isStarred)

    val recent = PillionProtocol.parse("RECENT|r1|Maya|+15551234|3|1700000000000", false) as PillionPacket.RecentItem
    assertEquals(3, recent.recent.callType)
    assertEquals(1700000000000L, recent.recent.timestampMs)
  }

  @Test
  fun roundTripsEscapedKeysWithSpecialCharacters() {
    val raw = "0|com.whatsapp|1|null|1000|tag^with%and|pipe"
    val escaped = PillionProtocol.escapeKey(raw)
    assertFalse(escaped.contains("|"))
    assertFalse(escaped.contains("\n"))
    assertEquals(raw, PillionProtocol.unescapeKey(escaped))
  }

  @Test
  fun classifiesCallNotificationCategoriesWithoutStaleFlips() {
    assertEquals(
      "CALL_ACTIVE",
      PillionProtocol.classifyCallNotification("Ongoing call • 01:23", currentCallActive = false, currentCallIncoming = false)
    )
    assertEquals(
      "CALL_ACTIVE",
      PillionProtocol.classifyCallNotification("00:42", currentCallActive = false, currentCallIncoming = false)
    )
    assertEquals(
      "CALL_ACTIVE",
      PillionProtocol.classifyCallNotification("Call with Alex", currentCallActive = true, currentCallIncoming = false)
    )
    assertEquals(
      "CALL_IN",
      PillionProtocol.classifyCallNotification("Incoming call", currentCallActive = true, currentCallIncoming = true)
    )
    assertEquals(
      "CALL_IN",
      PillionProtocol.classifyCallNotification(null, currentCallActive = false, currentCallIncoming = false)
    )
  }

  @Test
  fun clampsOverlayCoordinatesCleanly() {
    assertEquals(0, PillionProtocol.clampOverlayCoord(-20, 1000))
    assertEquals(500, PillionProtocol.clampOverlayCoord(500, 1000))
    assertEquals(1000, PillionProtocol.clampOverlayCoord(1400, 1000))
    assertEquals(0, PillionProtocol.clampOverlayCoord(50, -10))
  }

  @Test
  fun resetTransientStateClearsCallMediaAndNotifications() {
    PillionStateStore.applyPacket("TITLE|Track A", false)
    PillionStateStore.applyPacket("ARTIST|Artist A", false)
    PillionStateStore.applyPacket("PLAYING|1", false)
    PillionStateStore.applyPacket("BATTERY|65", false)
    PillionStateStore.applyPacket("CALL_IN|Maya|+15550000", false)
    PillionStateStore.applyPacket("NOTIF_ADD2|k1|com.msg|1|Sam|Hey", false)

    assertTrue(PillionStateStore.isCallActive)
    assertTrue(PillionStateStore.isCallIncoming)
    assertEquals(1, PillionStateStore.getNotifications().size)

    PillionStateStore.resetTransientState()

    assertFalse(PillionStateStore.isCallActive)
    assertFalse(PillionStateStore.isCallIncoming)
    assertFalse(PillionStateStore.isPlaying)
    assertEquals(-1, PillionStateStore.batteryLevel)
    assertEquals("No Media Playing", PillionStateStore.songTitle)
    assertTrue(PillionStateStore.getNotifications().isEmpty())
    assertNull(PillionStateStore.lastNotification)
  }

  @Test
  fun commitsContactsBatchAtomicallyAndCapsNotificationsAt40() {
    PillionStateStore.resetTransientState()
    PillionStateStore.applyPacket("CONTACTS_BEGIN", false)
    PillionStateStore.applyPacket("CONTACT|1|Alice|+15550001|1", false)
    PillionStateStore.applyPacket("CONTACT|2|Bob|+15550002|0", false)
    PillionStateStore.applyPacket("CONTACTS_END", false)
    assertEquals(2, PillionStateStore.getContacts().size)

    for (i in 1..45) {
      PillionStateStore.applyPacket("NOTIF_ADD2|k$i|com.app|0|Title $i|Body $i", false)
    }
    assertEquals(40, PillionStateStore.getNotifications().size)
    PillionStateStore.resetTransientState()
  }
}
