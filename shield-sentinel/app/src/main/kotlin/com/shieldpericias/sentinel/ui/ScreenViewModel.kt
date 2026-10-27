package com.shieldpericias.sentinel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UiState<out T> {
    data object Loading : UiState<Nothing>()
    data class Ready<T>(val data: T) : UiState<T>()
    data class Failed(val message: String) : UiState<Nothing>()
}

/**
 * ViewModel genérico: carrega um snapshot em IO, expõe estado e mensagens, e
 * executa ações seguidas de recarga. Toda chamada ao DevicePolicyManager fica
 * fora da thread principal.
 */
class ScreenViewModel<T>(private val loader: suspend () -> T) : ViewModel() {
    private val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = try {
                UiState.Ready(withContext(Dispatchers.IO) { loader() })
            } catch (e: Exception) {
                UiState.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Executa [block] em IO; a string retornada vira mensagem; depois recarrega. */
    fun act(block: suspend () -> String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }?.let { _messages.emit(it) }
            } catch (e: Exception) {
                _messages.emit("Erro: ${e.javaClass.simpleName}: ${e.message}")
            }
            refresh()
        }
    }
}
