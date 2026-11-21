package ru.parts.ledger.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.parts.ledger.LedgerApplication

class BackupWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = try {
        val graph=applicationContext as LedgerApplication
        repeat(5) {
            val revision=graph.repository.snapshot().revision
            graph.backup.save(automatic=true)
            if(graph.repository.snapshot().revision==revision) return Result.success()
        }
        Result.retry()
    } catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException)throw e;Result.retry()}
}
