package com.pablo.yumemi

import com.pablo.yumemi.data.feeds.FeedBlock
import com.pablo.yumemi.data.feeds.FeedParser
import com.pablo.yumemi.data.feeds.FeedStatus
import com.pablo.yumemi.data.settings.HostRules
import com.pablo.yumemi.ui.feeds.formatDayTime
import com.pablo.yumemi.ui.feeds.groupBlocks
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {

    @Test
    fun indexSkipsUnsafeTags() {
        val feeds = FeedParser.parseIndex(FeedSamples.INDEX)
        assertEquals(listOf("vuelos", "luces"), feeds.map { it.tag })
        assertEquals("dbca38163700", feeds[0].jobId)
        assertEquals("✈️", feeds[0].emoji)
        assertNull(feeds[1].jobId)
    }

    @Test
    fun realVuelosEnvelope() {
        val env = FeedParser.parseEnvelope(FeedSamples.VUELOS)!!
        assertEquals("vuelos", env.tag)
        assertEquals(FeedStatus.OK, env.status)
        assertEquals(ZonedDateTime.parse("2026-10-18T21:25:01+02:00").toInstant().toEpochMilli(), env.runAt)
        // The unknown "hologram" block (no text) is dropped; the other 8 are kept in order.
        assertEquals(8, env.view.size)
        val stat = env.view[0] as FeedBlock.Stat
        assertEquals("112 €", stat.value)
        assertEquals("-18 €", stat.delta)
        assertEquals("SVQ→LON", (env.view[2] as FeedBlock.KeyValue).items[0].second)
        val table = env.view[3] as FeedBlock.Table
        assertEquals(5, table.columns.size)
        assertEquals("easyJet", table.rows[2][4])
        val series = env.view[4] as FeedBlock.Series
        assertEquals(9, series.points.size)
        assertEquals(112.0, series.points.last().second, 0.0)
        assertTrue(env.view[7] is FeedBlock.Link)
    }

    @Test
    fun errorEnvelopeAndOddBlocks() {
        val env = FeedParser.parseEnvelope(
            """{"tag":"dam","status":"error","error":"Apify 503","view":[
               {"type":"series","points":[3,{"x":"b","y":4},["c","x"]]},
               {"type":"link","url":"javascript:alert(1)"},
               {"type":"note","text":"degrada a texto"}]}""",
        )!!
        assertEquals(FeedStatus.ERROR, env.status)
        assertEquals("Apify 503", env.error)
        val series = env.view[0] as FeedBlock.Series
        assertEquals(listOf("1" to 3.0, "b" to 4.0), series.points)
        assertEquals(FeedBlock.Text("degrada a texto"), env.view[1])
        assertEquals(2, env.view.size)
    }

    @Test
    fun historyNewestFirst() {
        val lines = listOf(
            """{"tag":"vuelos","run_at":"2026-10-16T09:07:00+02:00","summary":"a"}""",
            "no es json",
            """{"tag":"vuelos","run_at":"2026-10-18T09:07:00+02:00","summary":"c"}""",
            """{"tag":"vuelos","run_at":"2026-10-17T09:07:00+02:00","summary":"b"}""",
        ).joinToString("\n")
        assertEquals(listOf("c", "b", "a"), FeedParser.parseHistory(lines).map { it.summary })
    }

    @Test
    fun statsPairUp() {
        val groups = groupBlocks(FeedParser.parseEnvelope(FeedSamples.VUELOS)!!.view)
        assertEquals(2, groups[0].size)
        assertEquals(7, groups.size)
    }

    @Test
    fun defaultFeedsUrl() {
        assertEquals(
            "https://instance-20260806-122821.tailf21419.ts.net:8443/feeds",
            HostRules.defaultFeedsUrl("https://instance-20260806-122821.tailf21419.ts.net:8443/"),
        )
        assertEquals("http://100.64.1.2:8642/feeds", HostRules.defaultFeedsUrl("100.64.1.2:8642"))
        assertEquals("", HostRules.defaultFeedsUrl(""))
    }

    @Test
    fun dayTimeAcrossNewYear() {
        val zone = ZoneId.of("Europe/Madrid")
        fun at(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()
        val now = at("2026-12-31T21:30:00+01:00")
        assertEquals("hoy 09:07", formatDayTime(at("2026-12-31T09:07:00+01:00"), now, zone))
        assertEquals("mañana 09:00", formatDayTime(at("2027-01-01T09:00:00+01:00"), now, zone))
        assertEquals("ayer 21:25", formatDayTime(at("2026-12-30T21:25:00+01:00"), now, zone))
    }
}
