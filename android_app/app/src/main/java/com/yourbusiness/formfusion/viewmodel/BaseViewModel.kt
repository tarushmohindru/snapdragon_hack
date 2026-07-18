package com.yourbusiness.formfusion.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Lightweight ViewModel base for this project's manual Screen-enum navigation
 * (see Screen.kt / MainActivity.kt — there is no Navigation-Compose backstack).
 * Using androidx.lifecycle's `viewModel()` here would scope instances to the whole
 * Activity instead of a single screen visit, so screens instead create these via
 * `remember { ... }` and must call [dispose] from a `DisposableEffect(Unit) { onDispose { } }`
 * to cancel [viewModelScope] and release resources when leaving the screen.
 */
abstract class BaseViewModel {
    protected val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    open fun dispose() {
        viewModelScope.cancel()
    }
}
