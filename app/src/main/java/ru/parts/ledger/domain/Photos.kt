package ru.parts.ledger.domain

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class PhotoRef(val id: String, val itemId: String?, val buildId: String?, val ordinal: Int, val size: Int, val checksum: String) {
    val ownerId: String get() = itemId ?: requireNotNull(buildId)
}

object Photos {
    const val MAX_PER_CARD = 8
    const val MAX_COUNT = 2000
    const val MAX_SIZE = 512 * 1024
    private val uuid = Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
    private val sha = Regex("[a-f0-9]{64}")

    fun validate(s: LedgerState) {
        require(s.photos.size <= MAX_COUNT && s.photos.map { it.id }.toSet().size == s.photos.size) { "Повторяющиеся фото или слишком много снимков" }
        val index = Ledger.index(s)
        s.photos.forEach { photo ->
            require(photo.id.matches(uuid) && photo.checksum.matches(sha) && photo.size in 4..MAX_SIZE) { "Повреждены сведения о фото" }
            require((photo.itemId != null) != (photo.buildId != null)) { "У фото должен быть один владелец" }
            require(photo.itemId in index.items || photo.buildId in index.builds) { "Товар или сборка для фото не найдены" }
        }
        s.photos.groupBy { it.ownerId }.values.forEach { refs ->
            require(refs.size <= MAX_PER_CARD && refs.map { it.ordinal } == refs.indices.toList()) { "Неверный порядок фото" }
        }
    }

    fun checksum(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun verify(photo: PhotoRef, bytes: ByteArray): ByteArray {
        require(bytes.size == photo.size && isJpeg(bytes) && checksum(bytes) == photo.checksum) { "Снимок повреждён или отсутствует" }
        return bytes
    }

    fun isJpeg(bytes: ByteArray): Boolean = bytes.size in 4..MAX_SIZE &&
        bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
}
