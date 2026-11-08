package com.example

import com.example.data.model.XtreamAccount
import com.example.data.repository.XtreamRepository
import com.example.ui.components.AdMobConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleRobolectricTest {

    @Test
    fun `xtream library stream candidates follow mp4 then mkv then m3u8 order`() {
        val repo = XtreamRepository()
        val account = XtreamAccount(
            id = "1",
            name = "Test",
            host = "http://grace4k.com/",
            username = "user1",
            password = "pass1"
        )
        val candidates = repo.buildLibraryStreamCandidates(
            account = account,
            streamId = "1501766",
            isSeries = false
        )
        assertEquals(
            listOf(
                "http://grace4k.com/movie/user1/pass1/1501766.mp4",
                "http://grace4k.com/movie/user1/pass1/1501766.mkv",
                "http://grace4k.com/movie/user1/pass1/1501766.m3u8",
                "http://grace4k.com/movie/user1/pass1/1501766.ts"
            ),
            candidates
        )
    }

    @Test
    fun `admob config resolves official production and test ad unit ids`() {
        assertEquals("ca-app-pub-8077917786836804~5000681161", AdMobConfig.APP_ID)
        assertEquals(
            "ca-app-pub-8077917786836804/6112076148",
            AdMobConfig.resolveBannerAdUnitId(useProductionAdUnit = true)
        )
        assertEquals(
            "ca-app-pub-3940256099942544/9214589741",
            AdMobConfig.resolveBannerAdUnitId(useProductionAdUnit = false)
        )
        assertEquals(
            "ca-app-pub-8077917786836804/1849397509",
            AdMobConfig.resolveNativeAdUnitId(useProductionAdUnit = true)
        )
        assertEquals(
            "ca-app-pub-3940256099942544/2247696110",
            AdMobConfig.resolveNativeAdUnitId(useProductionAdUnit = false)
        )
    }
}
