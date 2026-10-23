package com.pablo.yumemi

import com.pablo.yumemi.data.update.AppUpdater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun comparesNumerically() {
        assertTrue(AppUpdater.isNewer("0.10.0", "0.9.2"))
        assertTrue(AppUpdater.isNewer("1.0", "0.9.9"))
        assertTrue(AppUpdater.isNewer("0.2.1", "0.2"))
    }

    @Test
    fun equalOrOlderIsNotNewer() {
        assertFalse(AppUpdater.isNewer("0.2.0", "0.2.0"))
        assertFalse(AppUpdater.isNewer("0.1.9", "0.2.0"))
        assertFalse(AppUpdater.isNewer("0.2", "0.2.0"))
    }
}
