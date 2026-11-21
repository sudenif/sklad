package ru.parts.ledger

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import ru.parts.ledger.data.*
import ru.parts.ledger.backup.BackupManager
import ru.parts.ledger.backup.LocalSnapshots
import java.io.File

class LedgerApplication:Application(),Configuration.Provider {
    override val workManagerConfiguration:Configuration get()=Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
    val database by lazy {
        val snapshots=LocalSnapshots(File(noBackupFilesDir,"snapshots"))
        Room.databaseBuilder(this,LedgerDatabase::class.java,"parts-ledger.db")
            .addMigrations(migration1To2 {snapshots.beforeUpgrade(it)}).build()
    }
    val repository by lazy {LedgerRepository(database)}
    val backup by lazy {BackupManager(this,repository)}
}
