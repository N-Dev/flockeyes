package io.github.ndev.flockeyes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ndev.flockeyes.core.count.ScanOptions
import io.github.ndev.flockeyes.core.Json
import io.github.ndev.flockeyes.core.reid.normalize
import io.github.ndev.flockeyes.data.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/** Small pieces of the app that need Android: update checks, settings in a backup, the herd in the database. */
@RunWith(AndroidJUnit4::class)
class AppLogicTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()

    @Test
    fun releasesFromGitHub() {
        assertEquals(15, Updates.buildOf("v1.0.15"))
        assertEquals(7, Updates.buildOf("1.0.7"))
        assertNull(Updates.buildOf("latest"))
        val r = Updates.parse(
            """{"tag_name":"v1.0.15","html_url":"https://github.com/N-Dev/flockeyes/releases/tag/v1.0.15","body":"Fixes ’n’ things\r\n",""" +
                """"assets":[{"name":"FlockEyes.apk","browser_download_url":"https://github.com/N-Dev/flockeyes/releases/download/v1.0.15/FlockEyes.apk"}]}""",
        )!!
        assertEquals(15, r.build)
        assertEquals("1.0.15", r.version)
        assertTrue(r.apk.endsWith("/v1.0.15/FlockEyes.apk"))
        assertNull(Updates.parse("""{"message":"Not Found"}"""))
        // The "models" release (the recognition model's files) isn't an app release.
        assertNull(Updates.parse("""{"tag_name":"models","assets":[]}"""))
    }

    @Test
    fun settingsGoIntoABackupAndComeBack() {
        val p = app.prefs
        val keep = p.gateName to p.dimAfter
        try {
            p.gateName = "Backup gate"
            p.dimAfter = 300
            val saved = Json.write(p.export())
            p.gateName = "Somewhere else"
            p.dimAfter = 0
            p.import(Json.obj(saved))
            assertEquals("Backup gate", p.gateName)
            assertEquals(300, p.dimAfter)
            assertTrue("phone-specific settings aren't in a backup", "tuned" !in p.export())
        } finally {
            p.gateName = keep.first
            p.dimAfter = keep.second
        }
    }

    @Test
    fun howSureMovesTheGapACowMustStandOutBy() {
        val p = app.prefs
        val e = app.engine
        val keep = p.strictness to p.gapOverride
        try {
            p.gapOverride = 0.0
            p.strictness = "normal"
            val opt = ScanOptions().also { e.configure(it) }
            assertEquals(e.reid.match, opt.match, 1e-9)
            assertEquals(e.reid.fresh, opt.fresh, 1e-9)
            assertEquals(e.reid.margin, opt.margin, 1e-9)
            p.strictness = "strict"
            e.configure(opt)
            assertEquals(e.reid.margin * 1.4, opt.margin, 1e-9)
            assertEquals(e.reid.match, opt.match, 1e-9)
            assertEquals(e.reid.margin * 1.4, e.bars().margin, 1e-9)
            p.strictness = "loose"
            e.configure(opt)
            assertEquals(e.reid.margin * 0.7, opt.margin, 1e-9)
            p.gapOverride = 0.2
            e.configure(opt)
            assertEquals(0.2, opt.margin, 1e-9)
        } finally {
            p.strictness = keep.first
            p.gapOverride = keep.second
        }
    }

    /** Everything the camera or a screen does to the herd is in the database, and comes back after a restart. */
    @Test
    fun theHerdIsSavedAsItChangesAndReadBack() {
        val herd = app.herd
        val dim = app.engine.reid.dim
        val size = app.engine.reid.size
        val r = Random(5)
        fun look() = normalize(FloatArray(dim) { r.nextGaussian().toFloat() })
        fun picture(shade: Int) = IntArray(size * size) { (0xff shl 24) or (shade shl 16) or (shade shl 8) or shade }
        val ids = ArrayList<Int>()
        try {
            val (a, b) = synchronized(herd) {
                val a = herd.create(1_700_000_000_000)
                herd.addView(a, look(), 0.9, 1_700_000_000_000, picture(200), 0.6)
                herd.addView(a, look(), 0.7, 1_700_000_001_000, picture(120), 0.7)
                val b = herd.create(1_700_000_002_000)
                herd.addView(b, look(), 0.8, 1_700_000_002_000, picture(40), 0.5)
                herd.rename(b, "Daisy", "IE 1234", "test cow")
                herd.sawNow(b, 1_700_000_003_000)
                a to b
            }
            ids.add(a.id)
            ids.add(b.id)
            assertTrue("views were given ids", a.views.all { it.ref > 0 } && b.views.all { it.ref > 0 })
            assertNotNull("the first look became the cow's picture", app.store.cowPicture(a.id))
            val pic = app.store.viewPicture(b.views[0].ref, 0.5)!!
            assertEquals("pulled back to its shape", 224 to 112, pic.width to pic.height)

            // As after a restart.
            app.store.load()
            val a2 = herd[a.id]!!
            val b2 = herd[b.id]!!
            assertEquals(2, a2.views.size)
            assertEquals("Daisy", b2.name)
            assertEquals("IE 1234", b2.label)
            assertEquals("test cow", b2.note)
            assertEquals(1, b2.seen)
            assertEquals(1_700_000_003_000, b2.lastSeen)
            assertTrue("looks come back as they were", a.views[0].emb.contentEquals(a2.views.first { it.ref == a.views[0].ref }.emb))
            assertTrue("new cows get new numbers", herd.nextId > b.id)

            // A merge moves the looks over; a sighting of the merged cow becomes the kept cow's.
            val count = app.db.newCount("field", "Test field", 1_700_000_000_000, "live", "", "", app.engine.reid.key)
            app.db.addSightings(count, listOf(io.github.ndev.flockeyes.core.count.Sighting(b.id, 1_700_000_003_000, 0.8, false)))
            synchronized(herd) { herd.merge(a2, b2) }
            app.store.load()
            assertNull(herd[b.id])
            assertEquals(3, herd[a.id]!!.views.size)
            assertEquals("Daisy", herd[a.id]!!.name)
            assertEquals(a.id, app.db.sightings(count).single().cow)
            assertEquals(1, app.db.sightingsOf(a.id).size)
            app.db.deleteCount(count)
        } finally {
            synchronized(herd) { for (id in ids) herd.remove(id) }
        }
        assertTrue(ids.none { id -> app.db.cows().any { it.id == id } })
    }
}
