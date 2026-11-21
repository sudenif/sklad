package ru.parts.ledger.backup

import ru.parts.ledger.domain.LedgerState
import ru.parts.ledger.domain.PhotoRef
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class SnapshotInfo(val id:String,val createdAt:String,val isProtection:Boolean,val revision:Long,val purchases:Int,val sales:Int)
class LocalSnapshots(private val directory:File) {
    @Synchronized fun save(state:LedgerState,load:(PhotoRef)->ByteArray={error("Нет снимка")},checkpoint:()->Unit={}):File=write(state,load,"ledger",checkpoint)
    @Synchronized fun protect(state:LedgerState,load:(PhotoRef)->ByteArray={error("Нет снимка")}):File=write(state,load,"protected")
    @Synchronized fun beforeUpgrade(state:LedgerState):File=write(state,{error("Нет снимка")},"upgrade")
    private fun write(state:LedgerState,load:(PhotoRef)->ByteArray,prefix:String,checkpoint:()->Unit={}) :File {
        require(directory.isDirectory || directory.mkdirs()) {"Не удалось создать папку копий"}
        val file=File(directory,"$prefix-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip")
        val temporary=File(directory,file.name+".tmp")
        try {
            FileOutputStream(temporary).use {ArchiveCodec.write(it,state,load,checkpoint);it.fd.sync()}
            require(ArchiveCodec.inspect(temporary,checkpoint=checkpoint).state==state) {"Не удалось проверить внутреннюю копию"}
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE)
            if(prefix=="ledger")prune(file)
            return file
        } finally {temporary.delete()}
    }
    private fun prune(latest:File) {
        var bytes=latest.length()
        copies().filter {it!=latest && it.name.startsWith("ledger-")}.forEachIndexed {i,old->
            bytes+=old.length()
            if(i>=9 || bytes>384L*1024*1024)old.delete()
        }
    }
    @Synchronized fun file(id:String):File {
        require(id.matches(Regex("(ledger|protected|upgrade)-[0-9]+-[a-f0-9-]+\\.(json|zip)"))) {"Некорректный файл внутренней копии"}
        val file=File(directory,id).canonicalFile
        require(file.parentFile==directory.canonicalFile && file.isFile) {"Внутренняя копия недоступна"}
        return file
    }
    @Synchronized fun read(id:String):VerifiedBackup=ArchiveCodec.inspect(file(id))
    private fun copies():List<File> = directory.listFiles()?.filter {it.isFile && it.extension in setOf("json","zip")}
        ?.sortedByDescending {it.lastModified()}.orEmpty()
    @Synchronized fun list():List<SnapshotInfo> = copies().mapNotNull {file->
        runCatching {val backup=ArchiveCodec.inspect(file,verifyPhotos=false);SnapshotInfo(file.name,backup.createdAt,!file.name.startsWith("ledger-"),backup.state.revision,backup.state.purchases.size,backup.state.sales.size)}.getOrNull()
    }
}
