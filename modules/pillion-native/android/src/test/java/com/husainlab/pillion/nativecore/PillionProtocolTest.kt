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
}
