package com.lastwave.app.data.lossless

import com.lastwave.app.data.addon.HybridMediaServer
import com.lastwave.app.data.addon.LoopbackHttpServer
import com.lastwave.app.data.local.HttpAddonConfig
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.plugin.ModuleManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchWarmCancellationTest {
    @Test fun cancelledWarmupCanRetryWithoutRecordingBackendFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val server = LoopbackHttpServer { socket ->
            if (requests.incrementAndGet() == 1) {
                started.complete(Unit)
                Thread.sleep(600)
            }
            val body = "{\"tracks\":[]}".toByteArray()
            LoopbackHttpServer.writeHeaders(socket, body.size)
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
        }
        val http = OkHttpClient()
        try {
            val settings = mockk<SettingsPreferences>()
            every { settings.httpAddons } returns MutableStateFlow(listOf(HttpAddonConfig(
                url = "${server.url}/a/test-token", enabled = true,
            )))
            val native = mockk<NativeSecrets>()
            every { native.signAddonRequest(any(), any()) } returns null
            val api = LosslessMusicApi(http, mockk<ModuleManager>(), native, settings, mockk<HybridMediaServer>(relaxUnitFun = true))
            val warming = launch { api.warmSearch("Test song", "Test artist", null, LosslessMusicApi.QUALITY_CD_LOSSLESS) }
            withTimeout(2_000) { started.await() }
            withTimeout(400) { warming.cancelAndJoin() }
            assertFalse(api.isCoolingDown)
            withTimeout(2_000) { api.warmSearch("Test song", "Test artist", null, LosslessMusicApi.QUALITY_CD_LOSSLESS) }
            assertEquals("Canceled warmup must not suppress its retry", 2, requests.get())
            api.warmSearch("Test song", "Test artist", null, LosslessMusicApi.QUALITY_CD_LOSSLESS)
            assertEquals("Completed warmup should still deduplicate", 2, requests.get())
            assertFalse(api.isCoolingDown)
        } finally {
            server.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
