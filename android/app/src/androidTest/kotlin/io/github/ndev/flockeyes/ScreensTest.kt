package io.github.ndev.flockeyes

import android.graphics.Bitmap
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.ndev.flockeyes.core.count.Gate
import io.github.ndev.flockeyes.core.count.Sighting
import io.github.ndev.flockeyes.data.Backup
import io.github.ndev.flockeyes.debug.DebugLog
import io.github.ndev.flockeyes.scan.Scanner
import io.github.ndev.flockeyes.service.BackgroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.Callable

/** The app's screens on a device: each tab, a field count and a gate count through to the herd and the saved counts. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    /** Before the app opens: no speed test offer or update check in the way, debug mode off, nothing left over from another test. */
    private val quiet = object : ExternalResource() {
        override fun before() {
            val app = App.instance
            val p = app.prefs
            p.speedTestOffered = true
            p.introSeen = true
            p.updateCheck = false
            p.debug = false
            p.learn = true
            p.far = false
            p.finderModel = "tiny"
            p.strictness = "normal"
            p.gapOverride = 0.0
            p.backgroundCounting = false
            p.gateLine = Gate.default()
            p.gateOrientation = ""
            p.orientation = "auto"
            clean(app)
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
        .around(quiet).around(compose)

    private val app: App get() = App.instance

    private fun clean(app: App) {
        if (app.field.ui.value.running) app.field.discard()
        if (app.gate.ui.value.running) app.gate.discard()
        app.field.scan.reset()
        app.gate.scan.reset()
        app.db.clearCounts()
        synchronized(app.herd) { app.db.clearHerd() }
        app.store.load()
    }

    private fun waitForText(text: String, timeoutMs: Long = 60_000, substring: Boolean = false) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun settle(ms: Long = 1500) {
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    /** Frames go straight to a counter (the emulator's camera has no cows), every look answered before the next. */
    private fun feed(sc: Scanner, frames: Int, t0: Double, stepMs: Double = 200.0, name: String? = null, frame: (Int) -> Bitmap): Double {
        var t = t0
        for (i in 0 until frames) {
            val bmp = frame(i)
            if (name != null && i == frames / 2) Shots.save("$name.png", Shots.png(bmp))
            sc.onFrame(bmp, t)
            sc.awaitLooks()
            t += stepMs
        }
        return t
    }

    @Test
    fun everyTab() {
        // Field: the live camera (the emulator's is a test pattern).
        waitForText("Start counting")
        settle(6000)
        Shots.take("01-field")
        compose.onNodeWithTag("tab-gate").performClick()
        waitForText("Set up")
        settle(5000)
        Shots.take("02-gate")
        compose.onNodeWithText("Set up").performClick()
        waitForText("Set up the counting line")
        settle()
        Shots.take("03-gate-setup")
        compose.onNodeWithText("Done").performClick()
        waitForText("Start counting")
        compose.onNodeWithTag("tab-herd").performClick()
        waitForText("No cows learnt yet")
        settle()
        Shots.take("04-herd-empty")
        compose.onNodeWithTag("tab-history").performClick()
        waitForText("Nothing counted yet")
        settle()
        Shots.take("05-counts-empty")
        compose.onNodeWithTag("tab-settings").performClick()
        waitForText("RECOGNISING COWS")
        settle()
        Shots.take("06-settings")
    }

    /**
     * A field count from the frames of a real video (cows at a heap of silage): the two standing clear are
     * learnt and counted, with the ones behind counted too; counted again they're known, not learnt
     * twice. Then the Herd and Counts tabs show them.
     */
    @Test
    fun fieldCountLearnsCowsAndKnowsThemAgain() {
        // The camera mustn't feed the counter too: watch from another tab.
        compose.onNodeWithTag("tab-settings").performClick()
        waitForText("RECOGNISING COWS")
        settle(2000)
        val clip = Shots.clip("heap")
        val sc = app.field
        sc.reconfigure()
        // 2.5 frames a second, as the clip was sampled.
        var t = feed(sc, 2, 1000.0, 400.0) { Shots.frame(clip[it]) }
        sc.start()
        t = feed(sc, clip.size, t, 400.0, name = "video-field") { Shots.frame(clip[it]) }
        val first = sc.ui.value
        Shots.log("field count 1: ${first.count} counted, ${first.named} named, ${first.fresh} new, most in view ${first.peak}; finder ${first.msFind.toInt()} ms, look ${first.msLook.toInt()} ms")
        val id1 = sc.stop()
        assertNotNull(id1)
        sc.rename(id1!!, "Top field")
        assertEquals("the two cows standing clear are learnt", 2, app.herd.size)
        assertEquals(2, first.fresh)
        assertTrue("counted with the ones behind them (${first.count})", first.count in 3..4)
        val c1 = app.db.count(id1)!!
        assertEquals(first.count, c1.count)
        assertEquals("Top field", c1.name)
        assertEquals(2, app.db.sightings(id1).size)
        assertTrue("each is known from several looks", synchronized(app.herd) { app.herd.cows.all { it.views.size >= 3 } })

        // Another time (the tracks start afresh after a gap).
        t = feed(sc, 2, t + 60_000, 400.0) { Shots.frame(clip[it]) }
        sc.start()
        feed(sc, clip.size, t, 400.0) { Shots.frame(clip[it]) }
        val second = sc.ui.value
        Shots.log("field count 2: ${second.count} counted, ${second.named} named, ${second.fresh} new; boxes ${second.boxes.map { "${it.label} %.2f".format(it.score) }}")
        val id2 = sc.stop()!!
        sc.rename(id2, "Top field")
        assertEquals(first.count, second.count)
        assertEquals("known again, not learnt twice", 0, second.fresh)
        assertEquals(2, app.herd.size)
        assertEquals(app.db.sightings(id1).mapNotNull { it.cow }.toSet(), app.db.sightings(id2).mapNotNull { it.cow }.toSet())
        assertTrue("each was seen in two counts", synchronized(app.herd) { app.herd.cows.all { it.seen == 2 && it.views.isNotEmpty() } })

        // The herd, a cow's page, and the count.
        compose.onNodeWithTag("tab-herd").performClick()
        waitForText("2 cows known")
        settle(2500)
        Shots.take("10-herd")
        val firstCow = synchronized(app.herd) { app.herd.cows.first() }
        compose.onNodeWithTag("cow-${firstCow.id}").performClick()
        waitForText("LOOKS KEPT", substring = true)
        settle(2500)
        Shots.take("11-cow")
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("2 cows known")
        compose.onNodeWithTag("tab-history").performClick()
        waitForText("2 saved")
        settle()
        Shots.take("12-counts")
        compose.onNodeWithTag("count-$id2").performClick()
        waitForText("cows counted")
        settle(2000)
        Shots.take("13-count")
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("2 saved")
    }

    /**
     * Counting the cows in a video file, read with the device's own decoder: the same cows at the heap of
     * silage, as an .mp4. The two standing clear are learnt, and the count is saved as from a video.
     */
    @Test
    fun aVideoFileIsCounted() {
        waitForText("Start counting")
        val file = java.io.File(app.cacheDir, "heap.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("clips/heap.mp4").use { src -> file.outputStream().use { src.copyTo(it) } }
        try {
            app.openVideo.value = android.net.Uri.fromFile(file)
            waitForText("Count the cows")
            settle(2000)
            Shots.take("30-video")
            // Debug mode shows what the AI sees as it goes: each cow's box and scores, and the places counted.
            app.prefs.debug = true
            compose.onNodeWithText("Count the cows").performClick()
            waitForText("so far", substring = true, timeoutMs = 60_000)
            Shots.take("31-video-counting")
            waitForText("Done:", substring = true, timeoutMs = 600_000)
            app.prefs.debug = false
            settle(1500)
            Shots.take("32-video-done")
            val c = app.db.counts().first()
            Shots.log("video count: ${c.count} counted, ${c.fresh} new, most in view ${c.peak}; herd ${app.herd.size}; ${DebugLog.entries().lastOrNull { it.cat == "video" }?.msg}")
            assertEquals("video", c.source)
            assertEquals("field", c.kind)
            assertTrue("counted with the ones behind (${c.count})", c.count in 3..4)
            assertEquals("the two cows standing clear are learnt", 2, app.herd.size)
            assertEquals(2, app.db.sightings(c.id).size)
            compose.onNodeWithText("See it").performClick()
            waitForText("counted from a video", substring = true)
            settle(2000)
            Shots.take("33-video-count")
            compose.onNodeWithContentDescription("Back").performClick()
        } finally {
            app.prefs.debug = false
            app.openVideo.value = null
            file.delete()
        }
    }

    /**
     * A gate count from pretend frames (a picture of a real cow moved across a plain background): one cow
     * out, another the other way, then the first one back in a picture taken half a minute later; who went
     * where is saved.
     */
    @Test
    fun gateCountsEachWayAndNamesTheCows() {
        compose.onNodeWithTag("tab-settings").performClick()
        waitForText("RECOGNISING COWS")
        settle(2000)
        val p = app.prefs
        p.gateLine = Gate.default()
        p.dir1 = "Out to the field"
        p.dir2 = "Back in"
        p.gateName = "Test gate"
        val sc = app.gate
        sc.reconfigure()
        sc.start("Test gate")
        fun walk(cow: String, from: Int, to: Int, t0: Double, name: String? = null): Double {
            val t = feed(sc, 23, t0, name = name) { i -> Shots.field(1280, 720, listOf(Placed(Shots.single(cow), from + (to - from) * i / 22, 250, 400))) }
            // Out of view until its track ends.
            return feed(sc, 10, t) { Shots.field(1280, 720, emptyList()) }
        }
        var t = walk("cow_a1", 20, 860, 1000.0, name = "pretend-gate")
        var ui = sc.ui.value
        Shots.log("gate after one cow: ${ui.n1} out, ${ui.n2} in; herd ${app.herd.size}")
        assertEquals(1, ui.n1)
        assertEquals(0, ui.n2)
        assertEquals("learnt as it went through", 1, app.herd.size)
        t = walk("cow_c1", 860, 20, t)
        t = walk("cow_a2", 860, 20, t)
        ui = sc.ui.value
        val id = sc.stop()!!
        val rows = app.db.sightings(id)
        Shots.log("gate: ${ui.n1} out, ${ui.n2} in; ${rows.map { "cow ${it.cow} dir ${it.dir}${if (it.fresh) " new" else " %.2f".format(it.score)}" }}")
        assertEquals(1, ui.n1)
        assertEquals(2, ui.n2)
        assertEquals("the first cow was known on its way back", 2, app.herd.size)
        assertEquals(3, rows.size)
        assertEquals(rows[0].cow, rows[2].cow)
        assertEquals(listOf(1, 2, 2), rows.map { it.dir })
        val c = app.db.count(id)!!
        assertEquals("gate", c.kind)
        assertEquals(3, c.count)
        assertEquals(1 to 2, c.n1 to c.n2)
        assertEquals("Out to the field", c.dir1)

        app.openCount.value = id
        waitForText("WENT THROUGH")
        settle(2000)
        Shots.take("14-gate-count")
        compose.onNodeWithContentDescription("Back").performClick()
    }

    /** Starting and stopping a gate count from its screen: the tabs hide, the screen dims, it carries on in the background. */
    @Test
    fun gateCountFromTheScreenAndInTheBackground() {
        val p = app.prefs
        p.backgroundCounting = true
        p.dimAfter = 3
        try {
            compose.onNodeWithTag("tab-gate").performClick()
            waitForText("Start counting")
            compose.waitUntil(90_000) { app.gate.ui.value.ready }
            compose.onNodeWithText("Start counting").performClick()
            waitForText("Stop")
            compose.waitUntil(10_000) { BackgroundService.running.value }
            val nm = app.getSystemService(NotificationManager::class.java)
            compose.waitUntil(10_000) { nm.activeNotifications.any { it.id == BackgroundService.NOTE_ID } }
            settle(1000)
            Shots.take("15-gate-counting")
            assertTrue("the tabs are hidden while counting", compose.onAllNodesWithTag("tab-field").fetchSemanticsNodes().isEmpty())
            // Dims after 3 s without a touch (1 min normally); a tap wakes it.
            waitForText("tap to wake", timeoutMs = 15_000, substring = true)
            Shots.take("16-gate-dimmed")
            compose.onNodeWithText("tap to wake", substring = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("tap to wake", substring = true).fetchSemanticsNodes().isEmpty() }
            // Leave the app: the camera and the counting go on.
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Thread.sleep(3000)
            val before = app.gate.ui.value.frames
            Thread.sleep(6000)
            val after = app.gate.ui.value.frames
            Shots.log("background: ${after - before} frames analysed in 6 s with the app in the background")
            assertTrue("frames analysed in the background ($before to $after)", after > before)
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitForText("Stop")
            settle()
            compose.onNodeWithText("Stop").performClick()
            waitForText("See it")
            compose.waitUntil(10_000) { !BackgroundService.running.value }
            Shots.take("17-gate-stopped")
        } finally {
            if (app.gate.ui.value.running) app.gate.stop()
            p.backgroundCounting = false
            p.dimAfter = 60
        }
    }

    /** Debug mode: the finder's boxes, each cow's matches and the timings over the picture, and the log. */
    @Test
    fun debugModeShowsWhatTheAiSees() {
        val p = app.prefs
        p.debug = true
        try {
            waitForText("analysed", substring = true)
            settle(3000)
            Shots.take("20-debug-field")
            compose.onNodeWithTag("tab-gate").performClick()
            waitForText("Set up")
            compose.waitUntil(90_000) { app.gate.ui.value.ready }
            waitForText("same cow ≥", substring = true)
            settle(3000)
            Shots.take("21-debug-gate")
            compose.onNodeWithTag("tab-settings").performClick()
            waitForText("DEVELOPER")
            compose.onNodeWithText("Debug log").performScrollTo()
            settle()
            Shots.take("22-debug-settings")
            compose.onNodeWithText("Debug log").performClick()
            waitForText("Problems")
            settle()
            Shots.take("23-debug-log")
            assertTrue("models loaded are logged", DebugLog.entries().any { it.cat == "engine" && it.msg.startsWith("Loaded") })
            assertTrue("the herd being read is logged", DebugLog.entries().any { it.cat == "herd" })
            compose.onNodeWithContentDescription("Back").performClick()
            waitForText("DEVELOPER")
        } finally {
            p.debug = false
        }
    }

    /** A backup holds the herd, counts and settings, and restoring it puts them back. */
    @Test
    fun backupAndRestore() {
        compose.onNodeWithTag("tab-settings").performClick()
        waitForText("RECOGNISING COWS")
        val p = app.prefs
        val herd = app.herd
        val dim = app.engine.reid.dim
        val size = app.engine.reid.size
        val cow = synchronized(herd) {
            val c = herd.create(1_700_000_000_000)
            herd.addView(c, io.github.ndev.flockeyes.core.reid.normalize(FloatArray(dim) { (it % 7 - 3).toFloat() }), 0.9, 1_700_000_000_000, IntArray(size * size) { 0xff808080.toInt() }, 0.6)
            herd.rename(c, "Backup cow", "B 1")
            c
        }
        val count = app.db.newCount("field", "Backup field", 1_700_000_000_000, "live", "", "", app.engine.reid.key)
        app.db.addSightings(count, listOf(Sighting(cow.id, 1_700_000_001_000, 0.9, true)))
        app.db.updateCount(count, 1_700_000_060_000, 1, 1, 0, 1, 1, 0, 0)
        p.gateName = "Before the backup"
        val bytes = app.io.submit(Callable { java.io.ByteArrayOutputStream().also { Backup.write(app, it) }.toByteArray() }).get()
        Shots.log("backup: ${bytes.size} bytes")
        // Change everything, then restore.
        synchronized(herd) { herd.remove(cow.id) }
        app.db.deleteCount(count)
        p.gateName = "After the backup"
        val checked = app.io.submit(Callable { Backup.check(app, java.io.ByteArrayInputStream(bytes)) }).get()
        Shots.log("backup holds: ${checked.summary.text()}")
        assertEquals(1, checked.summary.cows)
        assertEquals(1, checked.summary.counts)
        app.io.submit(Callable { Backup.restore(app, checked) }).get()
        assertEquals("Before the backup", p.gateName)
        val back = herd[cow.id]
        assertNotNull(back)
        assertEquals("B 1", back!!.label)
        assertEquals(1, back.views.size)
        assertEquals(1, app.db.sightings(count).size)
        // Anything else is refused.
        assertTrue(runCatching { Backup.check(app, java.io.ByteArrayInputStream("not a backup".toByteArray())) }.isFailure)
        compose.onNodeWithText("Back up").performScrollTo()
        settle()
        Shots.take("24-settings-backup")
    }

    /** At first launch the speed test is offered once. */
    @Test
    fun speedTestOfferedOnce() {
        val p = app.prefs
        p.speedTestOffered = false
        try {
            waitForText("Make Flock Eyes as quick as it can be?", timeoutMs = 10_000)
            settle()
            Shots.take("25-speed-offer")
            compose.onNodeWithText("Not now").performClick()
            compose.waitUntil(5_000) { p.speedTestOffered }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Make Flock Eyes as quick as it can be?").fetchSemanticsNodes().isEmpty() }
        } finally {
            p.speedTestOffered = true
        }
    }
}
