package com.openyt.core.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val update: UpdateInfo) : UpdateState
    data object UpToDate : UpdateState
    data object Failed : UpdateState
}

/**
 * Shared update state. [checkIfNeeded] runs once per app process when the user
 * enters the app; [check] re-runs on demand from Settings.
 */
class UpdateController(
    private val checker: UpdateChecker,
    private val currentVersion: String,
    private val scope: CoroutineScope,
    private val hasPlatformAsset: (UpdateInfo) -> Boolean = { true },
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var autoChecked = false
    private var checking = false

    fun checkIfNeeded() {
        if (autoChecked) return
        autoChecked = true
        check()
    }

    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            _state.value = UpdateState.Checking
            val result = runCatching { checker.latest(currentVersion) }
            _state.value = result.fold(
                onSuccess = { update ->
                    if (update != null && hasPlatformAsset(update)) {
                        UpdateState.Available(update)
                    } else {
                        UpdateState.UpToDate
                    }
                },
                onFailure = { UpdateState.Failed },
            )
            checking = false
        }
    }
}
