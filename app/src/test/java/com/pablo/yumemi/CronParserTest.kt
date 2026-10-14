package com.pablo.yumemi

import com.pablo.yumemi.data.cron.CronParser
import com.pablo.yumemi.data.cron.CronStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronParserTest {

    @Test
    fun fullJob() {
        val body = """
            {"jobs":[{
              "id":"abc123","name":"Resumen diario","prompt":"Resume las noticias",
              "schedule_display":"0 9 * * *","schedule":{"kind":"cron","expr":"0 9 * * *"},
              "enabled":true,"state":"scheduled","paused_reason":null,
              "created_at":"2026-10-01T08:00:00Z","next_run_at":"2026-10-15T09:00:00+00:00",
              "last_run_at":1760432400,"last_status":"error","last_error":"boom",
              "failure_streak":2,"repeat":{"times":null,"completed":5},"deliver":"origin",
              "latest_execution":{"status":"failed","started_at":"2026-10-14T09:00:00Z","error":"boom"}
            }]}
        """.trimIndent()
        val jobs = CronParser.parseList(body)
        assertEquals(1, jobs.size)
        val j = jobs[0]
        assertEquals("abc123", j.id)
        assertEquals("Resumen diario", j.displayName)
        assertEquals("0 9 * * *", j.scheduleDisplay)
        assertTrue(j.nextRunAt > 0)
        assertEquals(1760432400_000L, j.lastRunAt)
        assertEquals(2, j.failureStreak)
        assertNull(j.repeatTimes)
        assertEquals(5, j.repeatCompleted)
        assertEquals("boom", j.errorText)
        assertEquals(CronStatus.FAILED, j.status)
    }

    @Test
    fun nullNameFallsBackToPrompt() {
        val body = """{"job":{"id":"x1","name":null,"prompt":"Revisa el correo y avisame si hay algo urgente de verdad","enabled":false,"state":"paused"}}"""
        val j = CronParser.parseOne(body)!!
        assertEquals(
            "Revisa el correo y avisame si hay algo urgente de verdad".take(40),
            j.displayName,
        )
        assertEquals(CronStatus.PAUSED, j.status)
        assertEquals("", j.scheduleDisplay)
    }

    @Test
    fun errorBodyDoesNotCrash() {
        val body = """{"error":"Cron module not available"}"""
        assertTrue(CronParser.parseList(body).isEmpty())
        assertNull(CronParser.parseOne(body))
        assertTrue(CronParser.parseList("").isEmpty())
        assertTrue(CronParser.parseList("not json").isEmpty())
    }
}
