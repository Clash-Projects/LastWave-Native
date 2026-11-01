package com.lastwave.app.ui.moods

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.MoodCategory
import com.lastwave.app.data.music.MoodsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class MoodCategoryUiState(
    val category: MoodCategory? = null,
    val isLoading: Boolean = true,
    val failed: Boolean = false,
)

@HiltViewModel
internal class MoodsCategoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MoodsRepository,
) : ViewModel() {
    private val browseId = savedStateHandle.get<String>("browseId").orEmpty()
    private val params = savedStateHandle.get<String>("params").orEmpty()
    val fallbackTitle = savedStateHandle.get<String>("title").orEmpty()
    private val mutableState = MutableStateFlow(MoodCategoryUiState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.value = MoodCategoryUiState()
            try {
                mutableState.value = MoodCategoryUiState(
                    category = repository.fetchCategory(browseId, params),
                    isLoading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = MoodCategoryUiState(isLoading = false, failed = true)
            }
        }
    }
}
