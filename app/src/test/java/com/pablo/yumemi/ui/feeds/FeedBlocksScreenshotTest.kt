package com.pablo.yumemi.ui.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.pablo.yumemi.FeedSamples
import com.pablo.yumemi.data.feeds.FeedInfo
import com.pablo.yumemi.data.feeds.FeedParser
import com.pablo.yumemi.ui.theme.Yu
import com.pablo.yumemi.ui.theme.YumemiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xxhdpi")
class FeedBlocksScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun vuelos() = capture(FeedSamples.VUELOS, 0, "feed_vuelos")

    @Test
    fun luces() = capture(FeedSamples.LUCES, 1, "feed_luces")

    @Test
    fun lista() {
        val feeds = FeedParser.parseIndex(FeedSamples.INDEX)
        val failed = FeedParser.parseEnvelope(
            """{"tag":"dam","run_at":"2026-10-19T08:00:00+02:00","status":"error","error":"Apify devolvió 503"}""",
        )
        val cards = listOf(
            FeedCardState(feeds[0], FeedParser.parseEnvelope(FeedSamples.VUELOS), job = null),
            FeedCardState(feeds[1], FeedParser.parseEnvelope(FeedSamples.LUCES), job = null),
            FeedCardState(FeedInfo("dam", "DAM", "🎓", null), failed, job = null),
            FeedCardState(FeedInfo("gym", "Gimnasio", "", null), null, job = null),
        )
        composeTestRule.setContent {
            YumemiTheme {
                Column(
                    Modifier.fillMaxWidth().background(Yu.BackgroundBrush).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    cards.forEach { FeedCard(it, onClick = {}) }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/feed_lista.png")
    }

    private fun capture(json: String, feedIndex: Int, name: String) {
        val env = FeedParser.parseEnvelope(json)!!
        val feed = FeedParser.parseIndex(FeedSamples.INDEX)[feedIndex]
        composeTestRule.setContent {
            YumemiTheme {
                Column(
                    Modifier.fillMaxWidth().background(Yu.BackgroundBrush).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FeedHeader(feed, env, job = null)
                    groupBlocks(env.view).forEach { BlockRow(it) }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
