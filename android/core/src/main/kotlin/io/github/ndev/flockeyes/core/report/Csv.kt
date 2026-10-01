package io.github.ndev.flockeyes.core.report

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A saved count, for the lists and exports. */
class CountInfo(
    val id: Long,
    /** "field" or "gate". */
    val kind: String,
    val name: String,
    val started: Long,
    val ended: Long,
    /** "live" (the camera) or "video". */
    val source: String,
    /** Field: cows counted. Gate: crossings. */
    val count: Int,
    /** Field: the most in view at once. */
    val peak: Int,
    val unknown: Int,
    /** Cows learnt during the count, and the size of the herd when it ended. */
    val fresh: Int,
    val herd: Int,
    /** Gate: what the two directions are called, and how many went each way. */
    val dir1: String = "",
    val dir2: String = "",
    val n1: Int = 0,
    val n2: Int = 0,
    val note: String = "",
)

/** One cow counted, for the lists and exports. `cow` is its name or tag, empty if it wasn't recognised. */
class SightingInfo(val t: Long, val cowId: Int?, val cow: String, val tag: String, val dir: Int, val score: Double, val fresh: Boolean)

/** A cow, for the herd export. */
class CowInfo(val id: Int, val name: String, val tag: String, val note: String, val created: Long, val lastSeen: Long, val seen: Int, val views: Int)

/** The exports: plain CSV that opens in a spreadsheet. */
object Csv {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun time(t: Long, zone: ZoneId = ZoneId.systemDefault()): String = if (t <= 0) "" else DATE.format(Instant.ofEpochMilli(t).atZone(zone))

    fun cell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun rows(header: List<String>, rows: List<List<Any?>>): String =
        (listOf(header) + rows).joinToString("\r\n") { r -> r.joinToString(",") { cell(it) } } + "\r\n"

    /** Every count, one row each. */
    fun counts(list: List<CountInfo>, zone: ZoneId = ZoneId.systemDefault()): String = rows(
        listOf("count", "type", "name", "started", "ended", "from", "cows", "most_in_view", "unrecognised", "new_cows", "herd_size", "direction_1", "went_1", "direction_2", "went_2", "note"),
        list.map {
            listOf(
                it.id, it.kind, it.name, time(it.started, zone), time(it.ended, zone), it.source, it.count, if (it.kind == "field") it.peak else "",
                it.unknown, it.fresh, it.herd, it.dir1, if (it.kind == "gate") it.n1 else "", it.dir2, if (it.kind == "gate") it.n2 else "", it.note,
            )
        },
    )

    /** One count's cows, in the order they were counted. */
    fun sightings(c: CountInfo, list: List<SightingInfo>, zone: ZoneId = ZoneId.systemDefault()): String = rows(
        listOf("time", "cow", "tag", "cow_number", "direction", "match", "new"),
        list.map {
            listOf(
                time(it.t, zone), if (it.cowId == null) "Unrecognised" else it.cow, it.tag, it.cowId ?: "",
                when (it.dir) {
                    1 -> c.dir1
                    2 -> c.dir2
                    else -> ""
                },
                if (it.cowId == null) "" else "%.2f".format(java.util.Locale.ROOT, it.score), if (it.fresh) "yes" else "",
            )
        },
    )

    /** The herd, one row a cow. */
    fun herd(list: List<CowInfo>, zone: ZoneId = ZoneId.systemDefault()): String = rows(
        listOf("cow_number", "name", "tag", "note", "first_seen", "last_seen", "counts_seen_in", "looks_kept"),
        list.map { listOf(it.id, it.name, it.tag, it.note, time(it.created, zone), time(it.lastSeen, zone), it.seen, it.views) },
    )
}
