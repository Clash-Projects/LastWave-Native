package com.lastwave.app.data.music

import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MoodsRepository @Inject constructor(
    private val api: InnerTubeMusicApi,
    private val ytAuth: YtMusicAuthManager,
) {
    suspend fun fetchGroups(): List<MoodGroup> = withContext(Dispatchers.IO) {
        parseMoodGroups(browse(MOODS_BROWSE_ID))
    }

    suspend fun fetchCategory(browseId: String, params: String): MoodCategory = withContext(Dispatchers.IO) {
        require(browseId == MOOD_CATEGORY_BROWSE_ID && params.isNotBlank())
        parseMoodCategory(browse(browseId, params), api)
    }

    private suspend fun browse(browseId: String, params: String? = null): JsonObject {
        val authenticated = ytAuth.awaitLoadedConnection().isConnected
        val root = try {
            api.browseRoot(browseId, authenticated = authenticated, params = params)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!authenticated) throw error
            currentCoroutineContext().ensureActive()
            api.browseRoot(browseId, authenticated = false, params = params)
        }
        currentCoroutineContext().ensureActive()
        return root
    }
}
