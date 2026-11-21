package ru.parts.ledger.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.parts.ledger.data.LedgerRepository
import ru.parts.ledger.domain.LedgerState
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.backupSettings by preferencesDataStore("backup")
data class BackupStatus(val folder:String="",val lastAt:String="",val fingerprint:String="",val error:String="",val internalAt:String="",val automatic:Boolean=true)
class BackupManager(private val context:Context,private val repo:LedgerRepository) {
    private val settings=context.backupSettings
    private val lock=Mutex()
    private val background=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val local=LocalSnapshots(File(context.noBackupFilesDir,"snapshots"))
    private val transfers=File(context.cacheDir,"ledger-transfers")
    private val folderKey=stringPreferencesKey("folder")
    private val timeKey=stringPreferencesKey("time")
    private val hashKey=stringPreferencesKey("hash")
    private val errorKey=stringPreferencesKey("error")
    private val internalKey=stringPreferencesKey("internal")
    private val internalHashKey=stringPreferencesKey("internalHash")
    private val internalFileKey=stringPreferencesKey("internalFile")
    private val automaticKey=booleanPreferencesKey("automatic")
    val status=settings.data.map {BackupStatus(it[folderKey]?:"",it[timeKey]?:"",it[hashKey]?:"",it[errorKey]?:"",it[internalKey]?:"",it[automaticKey]?:true)}
    suspend fun setAutomatic(enabled:Boolean) {settings.edit {it[automaticKey]=enabled};if(enabled)afterMutation()}
    fun afterMutation() {
        try {
            val request=OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(4,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("ledger-backup",ExistingWorkPolicy.REPLACE,request)
        } catch(e:Exception) {
            background.launch {runCatching {settings.edit {it[errorKey]="Не удалось запустить автоматическую копию: ${e.message.orEmpty()}"}}}
        }
    }
    suspend fun selectFolder(uri:Uri):Unit=lock.withLock {
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(DocumentFile.fromTreeUri(context,uri)?.canWrite()==true) {"Папка недоступна для записи"}
        settings.edit {it[folderKey]=uri.toString();it[errorKey]="";it[hashKey]=""}
        afterMutation()
    }
    suspend fun save(automatic:Boolean=false):Unit=lock.withLock {
        try {
            val coroutine=currentCoroutineContext()
            val checkpoint={coroutine.ensureActive()}
            val current=settings.data.first()
            val folderUri=current[folderKey].orEmpty()
            val wantsExternal=folderUri.isNotBlank() && (!automatic || current[automaticKey]!=false)
            val (archive,fingerprint)=repo.readArchive {state,load->
                val hash=BackupCodec.fingerprint(state)
                val internalIsCurrent=automatic && current[internalHashKey]==hash &&
                    runCatching {local.file(current[internalFileKey].orEmpty())}.isSuccess
                val externalNeedsCopy=wantsExternal && (!automatic || current[hashKey]!=hash)
                val file=if(internalIsCurrent && !externalNeedsCopy)null else local.save(state,load,checkpoint)
                file to hash
            }
            if(archive!=null)settings.edit {it[internalKey]=Instant.now().toString();it[internalHashKey]=fingerprint;it[internalFileKey]=archive.name}
            if(archive!=null && wantsExternal && (!automatic || current[hashKey]!=fingerprint)) {
                val folder=DocumentFile.fromTreeUri(context,Uri.parse(folderUri)) ?: throw IllegalArgumentException("Выберите папку копий повторно")
                val filename="ledger-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip"
                val file=folder.createFile("application/zip",filename) ?: throw IllegalArgumentException("Не удалось создать копию в выбранной папке")
                try {
                    copyVerified(archive,file.uri,checkpoint)
                    settings.edit {it[timeKey]=Instant.now().toString();it[hashKey]=fingerprint;it[errorKey]=""}
                    // External files may predate a reinstall. Leave their retention to the owner.
                } catch(e:Exception) { file.delete();throw e }
            } else settings.edit {it[errorKey]=""}
        } catch(e:Exception) {
            if(e is CancellationException) throw e
            settings.edit {it[errorKey]=e.message?:"Не удалось сохранить резервную копию"}
            throw e
        }
    }
    suspend fun export(uri:Uri) = lock.withLock {
        val file=temporary()
        try {
            val state=repo.readArchive {s,load->file.outputStream().use {ArchiveCodec.write(it,s,load)};s}
            require(ArchiveCodec.inspect(file).state==state) {"Не удалось проверить копию"}
            copyVerified(file,uri)
        } finally {file.delete()}
    }
    suspend fun inspect(uri:Uri):VerifiedBackup=lock.withLock {
        context.contentResolver.openInputStream(uri)?.use(::stage) ?: throw IllegalArgumentException("Файл недоступен")
    }
    fun internalCopies():List<SnapshotInfo> =local.list()
    suspend fun internalCopy(id:String):VerifiedBackup=lock.withLock {local.file(id).inputStream().use(::stage)}
    suspend fun exportInternal(id:String,uri:Uri)=lock.withLock {
        val backup=local.read(id)
        val archive=backup.archive
        if(archive!=null)copyVerified(archive,uri) else {
            val file=temporary()
            try {
                file.outputStream().use {ArchiveCodec.write(it,backup.state,{error("Нет снимка")})}
                copyVerified(file,uri)
            } finally {file.delete()}
        }
    }
    suspend fun restore(backup:VerifiedBackup):Boolean=lock.withLock {
        ArchiveCodec.withPhotos(backup) {load->
            repo.restore(backup.state,load) {before,read->local.protect(before,read)}
        }
        try {
            settings.edit {it[hashKey]="";it[errorKey]=""}
            true
        } catch(e:Exception) {
            if(e is CancellationException)throw e
            false
        } finally {afterMutation()}
    }

    fun discard(backup:VerifiedBackup?) {
        backup?.archive?.takeIf {it.parentFile==transfers}?.delete()
    }

    private fun temporary():File {
        require(transfers.isDirectory || transfers.mkdirs()) {"Папка для проверки копий недоступна"}
        transfers.listFiles()?.filter {it.name.startsWith("ledger-") && System.currentTimeMillis()-it.lastModified()>86_400_000}?.forEach {it.delete()}
        return File.createTempFile("ledger-",".tmp",transfers)
    }

    private fun stage(input:InputStream):VerifiedBackup {
        val file=temporary()
        var keep=false
        try {
            file.outputStream().buffered().use {output->
                val buffer=ByteArray(64*1024)
                var size=0L
                while(true) {
                    val read=input.read(buffer)
                    if(read<0)break
                    size+=read
                    require(size<=ArchiveCodec.MAX_BYTES) {"Файл копии слишком велик"}
                    output.write(buffer,0,read)
                }
            }
            return ArchiveCodec.inspect(file).also {keep=it.archive!=null}
        } finally {if(!keep)file.delete()}
    }

    private fun copyVerified(file:File,uri:Uri,checkpoint:()->Unit={}) {
        val outgoing=MessageDigest.getInstance("SHA-256")
        context.contentResolver.openOutputStream(uri,"w")?.use {output->
            DigestInputStream(file.inputStream(),outgoing).use {input->
                val buffer=ByteArray(64*1024)
                while(true) {
                    checkpoint()
                    val read=input.read(buffer)
                    if(read<0)break
                    output.write(buffer,0,read)
                }
            }
            output.flush()
        } ?: throw IllegalArgumentException("Не удалось записать файл копии")
        val incoming=MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use {input->
            val buffer=ByteArray(64*1024)
            var size=0L
            while(true) {
                checkpoint()
                val read=input.read(buffer)
                if(read<0)break
                size+=read
                require(size<=file.length()) {"Проверка записанного файла не пройдена"}
                incoming.update(buffer,0,read)
            }
            require(size==file.length() && outgoing.digest().contentEquals(incoming.digest())) {"Проверка записанного файла не пройдена"}
        } ?: throw IllegalArgumentException("Не удалось проверить записанный файл")
    }
    companion object {
        fun readBounded(input:InputStream):ByteArray {
            return BackupCodec.readBounded(input)
        }
    }
}
