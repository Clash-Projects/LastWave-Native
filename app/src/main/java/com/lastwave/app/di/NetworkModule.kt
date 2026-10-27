package com.lastwave.app.di

import android.content.Context
import com.lastwave.app.BuildConfig
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmRateGuard
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import android.util.Log
import android.os.SystemClock
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // Metadata/JSON responses are small; a large HTTP cache only duplicates
    // data already held by Room and the dedicated artwork/media caches.
    private const val HTTP_CACHE_SIZE = 8L * 1024 * 1024
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 LastWave/1.0"

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        rateGuard: LastFmRateGuard,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // Release logging still formats every request and writes logcat
            // on the networking threads. Keep it for diagnostics only.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        // Keep enough parallelism for Home's batched metadata calls without
        // allowing a slow network to retain dozens of response buffers and
        // coroutines on low-memory devices. Artwork uses its own dispatcher.
        val dispatcher = Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 8
        }

        val cacheDir = File(context.cacheDir, "lfm_http_cache")
        val cache = Cache(cacheDir, HTTP_CACHE_SIZE)

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cache(cache)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(com.lastwave.app.data.network.Ipv4FirstDnsResolver())
            .retryOnConnectionFailure(true)
            .addInterceptor(TrafficPriorityInterceptor())
            .addInterceptor { chain ->
                val original = chain.request()
                val isLastFm = original.url.host.endsWith("audioscrobbler.com", ignoreCase = true)
                val request = if (original.header("User-Agent") != null) {
                    original
                } else {
                    original.newBuilder()
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                }

                if (!isLastFm) {
                    chain.proceed(request)
                } else {
                    val response = chain.proceed(request)
                    if (response.code == 429 || response.code == 503) {
                        response.closeQuietly()
                        rateGuard.onRequestLimited()
                        runCatching { Thread.sleep(1500L) }
                        chain.proceed(request)
                    } else {
                        if (response.isSuccessful) rateGuard.onRequestSucceeded()
                        response
                    }
                }
            }
            .addInterceptor(logging)
            .build()
    }

    private fun Response.closeQuietly() {
        runCatching { close() }
    }

    /**
     * One line per call so background metadata is visible next to playback.
     * Priority is inferred from the host. Playback requests are not delayed.
     */
    private class TrafficPriorityInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val started = SystemClock.elapsedRealtime()
            try {
                val response = chain.proceed(request)
                log(request, SystemClock.elapsedRealtime() - started, response.code)
                return response
            } catch (error: Exception) {
                log(request, SystemClock.elapsedRealtime() - started, 0)
                throw error
            }
        }

        private fun log(request: Request, elapsedMs: Long, status: Int) {
            val host = request.url.host
            val priority = when {
                host.contains("googlevideo") || host.contains("youtubei") || host.contains("youtube.com") -> "playback"
                host.contains("ytimg") || host.contains("googleusercontent") -> "artwork"
                host.contains("apple") || host.contains("itunes") || host.contains("mzstatic") -> "apple"
                host.contains("tidal") -> "tidal"
                host.contains("lrclib") || host.contains("lyrics") || host.contains("kugou") ||
                    host.contains("boidu") || host.contains("simpmusic") || host.contains("musixmatch") ||
                    host.contains("lrc.red") -> "lyrics"
                else -> "other"
            }
            val videoId = request.url.queryParameter("v")
                ?: request.url.queryParameter("id")
                ?: request.url.pathSegments.lastOrNull()?.takeIf { it.length == 11 }
                ?: ""
            Log.i(
                TAG,
                "source=$host videoId=$videoId priority=$priority elapsedMs=$elapsedMs status=$status",
            )
        }

        private companion object {
            const val TAG = "LastWaveTraffic"
        }
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(LastFmApiService.BASE_URL)
            .client(client)
            .build()

    @Provides
    @Singleton
    fun provideLastFmApiService(retrofit: Retrofit): LastFmApiService =
        retrofit.create(LastFmApiService::class.java)
}
