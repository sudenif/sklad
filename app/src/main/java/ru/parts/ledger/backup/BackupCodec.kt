package ru.parts.ledger.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import ru.parts.ledger.domain.*
import java.security.MessageDigest
import java.time.Instant
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.File

@Serializable private data class Envelope(val format:String="parts-ledger",val version:Int=1,val createdAt:String,val revision:Long,val checksum:String,val payload:String)
data class VerifiedBackup(val state:LedgerState,val createdAt:String,internal val archive:File?=null)
object BackupCodec {
    const val MAX_BYTES=20*1024*1024
    private fun checksum(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    fun fingerprint(state:LedgerState)=checksum(LedgerJson.encodeToString(state).toByteArray(Charsets.UTF_8))
    fun encode(state:LedgerState):ByteArray {
        Ledger.validate(state)
        val payload=LedgerJson.encodeToString(state)
        val bytes=LedgerJson.encodeToString(Envelope(createdAt=Instant.now().toString(),revision=state.revision,checksum=checksum(payload.toByteArray(Charsets.UTF_8)),payload=payload)).toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_BYTES) {"Копия превышает 20 МБ"}
        return bytes
    }
    fun decode(bytes:ByteArray):LedgerState=inspect(bytes).state
    fun inspect(bytes:ByteArray):VerifiedBackup = try {
        require(bytes.size<=MAX_BYTES) {"Копия превышает 20 МБ"}
        val e=LedgerJson.decodeFromString<Envelope>(bytes.toString(Charsets.UTF_8))
        require(e.format=="parts-ledger" && e.version==1) {"Этот формат или версия копии не поддерживается"}
        Instant.parse(e.createdAt)
        require(e.checksum==checksum(e.payload.toByteArray(Charsets.UTF_8))) {"Копия повреждена: контрольная сумма не совпала"}
        val state=LedgerJson.decodeFromString<LedgerState>(e.payload).also {
            require(it.revision==e.revision) {"Ревизия копии не совпала"};Ledger.validate(it)
        }
        VerifiedBackup(state,e.createdAt)
    } catch(e:IllegalArgumentException) {throw e}
      catch(e:Exception) {throw IllegalArgumentException("Не удалось прочитать копию: неверный формат",e)}
    fun readBounded(input:InputStream):ByteArray {
        val result=ByteArrayOutputStream();val chunk=ByteArray(8192)
        while(true) {val n=input.read(chunk);if(n<0)break;require(result.size()+n<=MAX_BYTES) {"Файл превышает 20 МБ"};result.write(chunk,0,n)}
        return result.toByteArray()
    }
}
