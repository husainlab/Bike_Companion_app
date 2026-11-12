package com.husainlab.pillion.nativecore

import org.junit.Assert.assertEquals
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
    assertNull(PillionProtocol.parse("/media/playpause", false))
  }

  @Test
  fun parsesNotificationWithPipesInBody() {
    val pkt = PillionProtocol.parse("NOTIF_ADD|k1|com.chat|Alex|Meet at 7 | Gate B", false)
    assertTrue(pkt is PillionPacket.NotificationAdd)
    val notif = (pkt as PillionPacket.NotificationAdd).notification
    assertEquals("k1", notif.key)
    assertEquals("com.chat", notif.packageName)
    assertEquals("Alex", notif.title)
    assertEquals("Meet at 7 | Gate B", notif.text)
  }
}

