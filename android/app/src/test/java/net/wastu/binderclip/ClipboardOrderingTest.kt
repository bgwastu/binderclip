package net.wastu.binderclip

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClipboardOrderingTest {
    private val hashA = SyncProtocol.sha256Hex("a")
    private val hashB = SyncProtocol.sha256Hex("b")
    private fun stamp(time: Long, origin: String = "mac", counter: Long = 0, event: String = "00000000-0000-0000-0000-000000000001", hash: String = hashA, kind: String = "text") =
        ClipboardStamp(time, counter, origin, event, kind, hash)

    @Test fun deterministicConflictsConvergeInEitherArrivalOrder() {
        val a = stamp(100, "phone-a")
        val b = stamp(100, "phone-b", event = "00000000-0000-0000-0000-000000000002", hash = hashB)
        val one = ClipboardOrdering("phone", uptime = { 10 }, bootId = "boot")
        val two = ClipboardOrdering("phone", uptime = { 10 }, bootId = "boot")
        assertTrue(one.receive(a)); assertTrue(one.receive(b))
        assertTrue(two.receive(b)); assertFalse(two.receive(a))
        assertEquals(b, one.head); assertEquals(one.head, two.head)
    }

    @Test fun clockSkewAndWallClockChangesDoNotMakeAnOlderOfflineCopyWin() {
        var elapsed = 100L
        var wall = 9_000_000L
        val phone = ClipboardOrdering("phone", wall = { wall }, uptime = { elapsed }, bootId = "boot")
        phone.observe("text", hashA)
        elapsed = 300
        phone.calibrate(ClipboardClockSample(1_300, 300))
        assertEquals(1_100L, phone.head!!.time)
        assertTrue(phone.receive(stamp(1_200, hash = hashB)))
        // Once calibrated, future copies use monotonic time, even if the user changes the clock.
        wall = 1; elapsed = 500
        assertEquals(1_500L, phone.observe("text", hashA)!!.time)
    }

    @Test fun newerOfflineCopyBeatsHubAndUnchangedClipboardKeepsItsOldVersion() {
        var elapsed = 100L
        val phone = ClipboardOrdering("phone", wall = { 100 }, uptime = { elapsed }, bootId = "boot")
        phone.calibrate(ClipboardClockSample(1_000, 100))
        val hub = stamp(1_000)
        phone.receive(hub)
        elapsed = 200
        val local = phone.observe("text", hashB)!!
        elapsed = 500
        phone.calibrate(ClipboardClockSample(1_400, 500))
        assertEquals(1_100L, phone.head!!.time)
        assertFalse(phone.receive(hub))
        assertNull(phone.observe("text", hashB))
        assertEquals(local, phone.head)
    }

    @Test fun publishedImageVersionStaysImmutableWhenAConnectionResumes() {
        var elapsed = 10L
        val phone = ClipboardOrdering("phone", wall = { 100 }, uptime = { elapsed }, bootId = "boot")
        phone.calibrate(ClipboardClockSample(100, 10))
        val image = phone.observe("image", hashA)!!
        phone.markPublished(image.event)
        elapsed = 50
        phone.calibrate(ClipboardClockSample(200, 50))
        assertEquals(image, phone.head)
        val restored = ClipboardOrdering("phone", wall = { 400 }, uptime = { elapsed }, bootId = "boot", checkpoint = phone.checkpoint())
        restored.calibrate(ClipboardClockSample(500, elapsed))
        assertEquals(image, restored.head)
    }

    @Test fun aNewCopyCannotRebaseBehindAnAlreadyPublishedImage() {
        var elapsed = 10L
        val phone = ClipboardOrdering("phone", wall = { 100 }, uptime = { elapsed }, bootId = "boot")
        phone.calibrate(ClipboardClockSample(1_000, elapsed))
        val image = phone.observe("image", hashA)!!
        phone.markPublished(image.event)
        elapsed = 20
        phone.observe("text", hashB)
        phone.calibrate(ClipboardClockSample(900, elapsed))
        assertTrue(phone.head!! > image)
        assertFalse(phone.receive(image))
    }

    @Test fun checkpointPreservesPendingCopyAgeAcrossProcessRestart() {
        var elapsed = 10L
        val original = ClipboardOrdering("phone", wall = { 9_000 }, uptime = { elapsed }, bootId = "boot")
        val local = original.observe("text", hashA)!!
        elapsed = 100
        val restored = ClipboardOrdering("phone", wall = { 9_090 }, uptime = { elapsed }, bootId = "boot", checkpoint = original.checkpoint())
        restored.calibrate(ClipboardClockSample(2_000, elapsed))
        assertEquals(local.event, restored.head!!.event)
        assertEquals(1_910L, restored.head!!.time)
        assertTrue(restored.pending)
        assertFalse(restored.receive(restored.head!!))
        assertFalse(restored.pending)
    }

    @Test fun repeatedUserCopyAndTextAfterImageGetFreshVersions() {
        val phone = ClipboardOrdering("phone", wall = { 100 }, uptime = { 10 }, bootId = "boot")
        val text = phone.observe("text", hashA)!!
        assertNull(phone.observe("text", hashA))
        val recopied = phone.observe("text", hashA, force = true)!!
        assertTrue(recopied > text)
        val image = phone.observe("image", hashA)!!
        val afterImage = phone.observe("text", hashA)!!
        assertTrue(afterImage > image)
        assertFalse(phone.receive(text))
        assertEquals(afterImage, phone.head)
    }

    @Test fun lateImageCompletionCannotOverwriteNewerTextAndReplayCannotResurrect() {
        val phone = ClipboardOrdering("phone", uptime = { 10 }, bootId = "boot")
        val image = stamp(100, kind = "image")
        val text = stamp(200, event = "00000000-0000-0000-0000-000000000002", hash = hashB)
        assertTrue(phone.receive(image)); assertTrue(phone.receive(text))
        assertFalse(phone.receive(image)); assertFalse(phone.receive(image.copy(time = 300)))
        assertEquals(text, phone.head)
    }

    @Test fun unknownStartupClipboardIsNotTimestampedAsANewCopy() {
        val phone = ClipboardOrdering("phone", wall = { 9_000 }, uptime = { 10 }, bootId = "boot")
        assertNull(phone.observe("empty", hashA, bootstrap = true))
        assertEquals(0L, phone.observe("text", hashA, bootstrap = true)!!.time)
        assertTrue(phone.receive(stamp(100, hash = hashB)))
        val restored = ClipboardOrdering("phone", wall = { 90_000 }, uptime = { 20 }, bootId = "boot", checkpoint = phone.checkpoint())
        assertEquals(100L, restored.observe("text", hashA, bootstrap = true)!!.time)
        assertTrue(restored.receive(stamp(200, event = "00000000-0000-0000-0000-000000000002", hash = hashB)))
    }

    @Test fun wireMetadataRejectsPartialBooleanFloatingPointAndBadIdentifiers() {
        val valid = stamp(100)
        assertEquals(valid, ClipboardStamp.parse(valid.putInto(JSONObject())))
        for ((key, value) in listOf("clipTime" to true, "clipCounter" to 1.5, "clipOrigin" to "bad origin", "clipEvent" to "../file", "clipHash" to "wrong", "clipKind" to "file")) {
            assertTrue(runCatching { ClipboardStamp.parse(valid.putInto(JSONObject()).put(key, value)) }.isFailure)
        }
        assertTrue(runCatching { ClipboardStamp.parse(JSONObject().put("clipTime", 100)) }.isFailure)
        assertNull(ClipboardStamp.parse(JSONObject()))
        assertEquals(ClipboardClockSample(1_110, 40), clipboardClockSample(JSONObject().put("clockProbe", 10).put("clockReceive", 1_090).put("clockNow", 1_100), 40))
    }
}
