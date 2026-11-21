package ru.parts.ledger.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Enqueue under the gate synchronously; IO dispatch must not reorder user actions. */
class DraftCoordinator(private val scope:CoroutineScope,private val io:CoroutineDispatcher=Dispatchers.IO,private val write:suspend(String?)->Unit) {
    private val gate=Mutex()
    fun enqueue(value:String?):Job=scope.launch(start=CoroutineStart.UNDISPATCHED) {
        gate.withLock {withContext(io) {write(value)}}
    }
    suspend fun <T> exclusive(action:suspend()->T):T=gate.withLock {withContext(io) {action()}}
}
