package com.lastwave.app.ui.moods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.MoodGroup
import com.lastwave.app.data.music.MoodTile
import com.lastwave.app.data.music.MoodsRepository
import com.lastwave.app.ui.navigation.AppRouteNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class MoodsUiState(
    val groups: List<MoodGroup> = emptyList(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
)

@HiltViewModel
internal class MoodsViewModel @Inject constructor(
    private val repository: MoodsRepository,
    private val navigator: AppRouteNavigator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MoodsUiState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.value = MoodsUiState()
            try {
                mutableState.value = MoodsUiState(groups = repository.fetchGroups(), isLoading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = MoodsUiState(isLoading = false, failed = true)
            }
        }
    }

    fun openAll() = navigator.navigateTo(MoodsRoutes.GROUPS)

    fun openTile(tile: MoodTile) = navigator.navigateTo(MoodsRoutes.category(tile))
}
