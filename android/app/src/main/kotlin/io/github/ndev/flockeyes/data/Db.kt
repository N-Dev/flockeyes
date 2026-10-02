package io.github.ndev.flockeyes.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.ndev.flockeyes.core.count.Sighting
import io.github.ndev.flockeyes.core.report.CountInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A cow as saved (its picture is read separately, when it's shown). */
class CowRow(val id: Int, val name: String, val tag: String, val note: String, val created: Long, val lastSeen: Long, val seen: Int)

/** A saved look of a cow: what the recognition model made of it, and the picture it was made from. */
class ViewRow(val id: Long, val cow: Int, val emb: FloatArray?, val crop: ByteArray?, val aspect: Double, val quality: Double, val added: Long, val model: String)

/** One cow counted, as saved. `cow` is null for one that wasn't recognised. */
class SightingRow(val t: Long, val cow: Int?, val dir: Int, val score: Double, val fresh: Boolean)

/**
 * Everything the app keeps, on the phone only: the herd (each cow with a few small pictures of it), and
 * the counts with who was counted. No video.
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, 1) {
    companion object {
        const val NAME = "flockeyes.db"

        fun floats(b: ByteArray): FloatArray = FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }

        fun bytes(f: FloatArray): ByteArray = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(f) }.array()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cows (id INTEGER PRIMARY KEY, name TEXT, tag TEXT, note TEXT, created INTEGER, last_seen INTEGER, seen INTEGER, thumb BLOB)")
        db.execSQL("CREATE TABLE views (id INTEGER PRIMARY KEY AUTOINCREMENT, cow INTEGER, emb BLOB, crop BLOB, aspect REAL, quality REAL, added INTEGER, model TEXT)")
        db.execSQL("CREATE INDEX views_cow ON views(cow)")
        db.execSQL(
            "CREATE TABLE counts (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, name TEXT, started INTEGER, ended INTEGER, source TEXT, " +
                "cows INTEGER, peak INTEGER, unknown INTEGER, fresh INTEGER, herd INTEGER, dir1 TEXT, dir2 TEXT, n1 INTEGER, n2 INTEGER, note TEXT, model TEXT)",
        )
        db.execSQL("CREATE TABLE sightings (id INTEGER PRIMARY KEY AUTOINCREMENT, session INTEGER, cow INTEGER, t INTEGER, dir INTEGER, score REAL, fresh INTEGER)")
        db.execSQL("CREATE INDEX sightings_session ON sightings(session)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------------------------------------------------------------- meta
    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setMeta(key: String, value: String) {
        val v = ContentValues()
        v.put("key", key)
        v.put("value", value)
        writableDatabase.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------------------------------------------------------- the herd
    fun cows(): List<CowRow> =
        readableDatabase.rawQuery("SELECT id, name, tag, note, created, last_seen, seen FROM cows ORDER BY id", null).use { c ->
            val out = ArrayList<CowRow>()
            while (c.moveToNext()) {
                out.add(CowRow(c.getInt(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4), c.getLong(5), c.getInt(6)))
            }
            out
        }

    @Synchronized
    fun saveCow(id: Int, name: String, tag: String, note: String, created: Long, lastSeen: Long, seen: Int) {
        val v = ContentValues()
        v.put("name", name)
        v.put("tag", tag)
        v.put("note", note)
        v.put("last_seen", lastSeen)
        v.put("seen", seen)
        val db = writableDatabase
        if (db.update("cows", v, "id = ?", arrayOf(id.toString())) == 0) {
            v.put("id", id)
            v.put("created", created)
            db.insert("cows", null, v)
        }
    }

    fun setThumb(id: Int, jpeg: ByteArray?) {
        val v = ContentValues()
        if (jpeg != null) v.put("thumb", jpeg) else v.putNull("thumb")
        writableDatabase.update("cows", v, "id = ?", arrayOf(id.toString()))
    }

    fun thumb(id: Int): ByteArray? =
        readableDatabase.rawQuery("SELECT thumb FROM cows WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

    fun hasThumb(id: Int): Boolean =
        readableDatabase.rawQuery("SELECT thumb IS NOT NULL FROM cows WHERE id = ?", arrayOf(id.toString())).use { c -> c.moveToFirst() && c.getInt(0) == 1 }

    @Synchronized
    fun deleteCow(id: Int) {
        val db = writableDatabase
        db.delete("views", "cow = ?", arrayOf(id.toString()))
        db.delete("cows", "id = ?", arrayOf(id.toString()))
    }

    /** Saves a look and returns its id. */
    @Synchronized
    fun addView(cow: Int, emb: FloatArray, crop: ByteArray?, aspect: Double, quality: Double, added: Long, model: String): Long {
        val v = ContentValues()
        v.put("cow", cow)
        v.put("emb", bytes(emb))
        if (crop != null) v.put("crop", crop)
        v.put("aspect", aspect)
        v.put("quality", quality)
        v.put("added", added)
        v.put("model", model)
        return writableDatabase.insert("views", null, v)
    }

    fun deleteView(id: Long) {
        writableDatabase.delete("views", "id = ?", arrayOf(id.toString()))
    }

    /** A look's description worked out again with another recognition model. */
    fun updateView(id: Long, emb: FloatArray, model: String) {
        val v = ContentValues()
        v.put("emb", bytes(emb))
        v.put("model", model)
        writableDatabase.update("views", v, "id = ?", arrayOf(id.toString()))
    }

    /** Every saved look, without its picture: for putting the herd together at start-up. */
    fun views(): List<ViewRow> =
        readableDatabase.rawQuery("SELECT id, cow, emb, aspect, quality, added, model FROM views ORDER BY id", null).use { c ->
            val out = ArrayList<ViewRow>()
            while (c.moveToNext()) {
                out.add(ViewRow(c.getLong(0), c.getInt(1), c.getBlob(2)?.let { floats(it) }, null, c.getDouble(3), c.getDouble(4), c.getLong(5), c.getString(6) ?: ""))
            }
            out
        }

    /** One cow's looks with their pictures, best first. */
    fun viewsOf(cow: Int): List<ViewRow> =
        readableDatabase.rawQuery("SELECT id, cow, crop, aspect, quality, added, model FROM views WHERE cow = ? ORDER BY quality DESC, id", arrayOf(cow.toString())).use { c ->
            val out = ArrayList<ViewRow>()
            while (c.moveToNext()) {
                out.add(ViewRow(c.getLong(0), c.getInt(1), null, c.getBlob(2), c.getDouble(3), c.getDouble(4), c.getLong(5), c.getString(6) ?: ""))
            }
            out
        }

    fun crop(view: Long): ByteArray? =
        readableDatabase.rawQuery("SELECT crop FROM views WHERE id = ?", arrayOf(view.toString())).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

    /** A merge: every look and sighting of cow `from` becomes cow `into`'s, and `from` goes. */
    @Synchronized
    fun mergeCows(into: Int, from: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE views SET cow = ? WHERE cow = ?", arrayOf<Any>(into, from))
            db.execSQL("UPDATE sightings SET cow = ? WHERE cow = ?", arrayOf<Any>(into, from))
            db.delete("cows", "id = ?", arrayOf(from.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun clearHerd() {
        val db = writableDatabase
        db.delete("views", null, null)
        db.delete("cows", null, null)
    }

    // ---------------------------------------------------------------- counts
    fun newCount(kind: String, name: String, started: Long, source: String, dir1: String, dir2: String, model: String): Long {
        val v = ContentValues()
        v.put("kind", kind)
        v.put("name", name)
        v.put("started", started)
        v.put("ended", started)
        v.put("source", source)
        v.put("cows", 0)
        v.put("peak", 0)
        v.put("unknown", 0)
        v.put("fresh", 0)
        v.put("herd", 0)
        v.put("dir1", dir1)
        v.put("dir2", dir2)
        v.put("n1", 0)
        v.put("n2", 0)
        v.put("note", "")
        v.put("model", model)
        return writableDatabase.insert("counts", null, v)
    }

    /** The count's figures so far (saved now and then while it runs, and when it ends). */
    fun updateCount(id: Long, ended: Long, cows: Int, peak: Int, unknown: Int, fresh: Int, herd: Int, n1: Int, n2: Int) {
        val v = ContentValues()
        v.put("ended", ended)
        v.put("cows", cows)
        v.put("peak", peak)
        v.put("unknown", unknown)
        v.put("fresh", fresh)
        v.put("herd", herd)
        v.put("n1", n1)
        v.put("n2", n2)
        writableDatabase.update("counts", v, "id = ?", arrayOf(id.toString()))
    }

    fun renameCount(id: Long, name: String, note: String) {
        val v = ContentValues()
        v.put("name", name)
        v.put("note", note)
        writableDatabase.update("counts", v, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun addSightings(count: Long, list: List<Sighting>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in list) {
                val v = ContentValues()
                v.put("session", count)
                if (s.cowId != null) v.put("cow", s.cowId) else v.putNull("cow")
                v.put("t", s.t)
                v.put("dir", s.dir)
                v.put("score", s.score)
                v.put("fresh", if (s.fresh) 1 else 0)
                db.insert("sightings", null, v)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun counts(): List<CountInfo> =
        readableDatabase.rawQuery(
            "SELECT id, kind, name, started, ended, source, cows, peak, unknown, fresh, herd, dir1, dir2, n1, n2, note FROM counts ORDER BY started DESC",
            null,
        ).use { c ->
            val out = ArrayList<CountInfo>()
            while (c.moveToNext()) {
                out.add(
                    CountInfo(
                        c.getLong(0), c.getString(1) ?: "field", c.getString(2) ?: "", c.getLong(3), c.getLong(4), c.getString(5) ?: "live",
                        c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9), c.getInt(10), c.getString(11) ?: "", c.getString(12) ?: "",
                        c.getInt(13), c.getInt(14), c.getString(15) ?: "",
                    ),
                )
            }
            out
        }

    fun count(id: Long): CountInfo? = counts().firstOrNull { it.id == id }

    fun sightings(count: Long): List<SightingRow> =
        readableDatabase.rawQuery("SELECT t, cow, dir, score, fresh FROM sightings WHERE session = ? ORDER BY t, id", arrayOf(count.toString())).use { c ->
            val out = ArrayList<SightingRow>()
            while (c.moveToNext()) out.add(SightingRow(c.getLong(0), if (c.isNull(1)) null else c.getInt(1), c.getInt(2), c.getDouble(3), c.getInt(4) == 1))
            out
        }

    /** The counts a cow was counted in, most recent first: (count id, time, direction). */
    fun sightingsOf(cow: Int, limit: Int = 30): List<Triple<Long, Long, Int>> =
        readableDatabase.rawQuery("SELECT session, t, dir FROM sightings WHERE cow = ? ORDER BY t DESC LIMIT $limit", arrayOf(cow.toString())).use { c ->
            val out = ArrayList<Triple<Long, Long, Int>>()
            while (c.moveToNext()) out.add(Triple(c.getLong(0), c.getLong(1), c.getInt(2)))
            out
        }

    @Synchronized
    fun deleteCount(id: Long) {
        val db = writableDatabase
        db.delete("sightings", "session = ?", arrayOf(id.toString()))
        db.delete("counts", "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clearCounts() {
        writableDatabase.delete("sightings", null, null)
        writableDatabase.delete("counts", null, null)
    }

    /** Makes sure everything written is in the main database file (for a backup). */
    @Synchronized
    fun checkpoint() {
        runCatching { writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } }
    }
}
