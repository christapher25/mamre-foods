package com.mamre.billing.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/**
 * Ends the view models a test made. A view model's scope may still be resuming on a Room thread, and a resume goes through
 * Dispatchers.Main; Dispatchers.resetMain() while that happens throws IllegalStateException from TestMainDispatcher (seen on CI,
 * LockNowAndSecureWindowTest, run 41535982796), and a scope that is never ended keeps reading a database the test then closes.
 * Call [endAll] first in tearDown: it cancels every tracked scope and WAITS for it, so nothing uses Dispatchers.Main afterwards.
 */
class ViewModelScopes {
    private val made = mutableListOf<ViewModel>()

    fun <T : ViewModel> track(viewModel: T): T = viewModel.also { made += it }

    fun endAll() {
        runBlocking { made.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        made.clear()
    }
}
