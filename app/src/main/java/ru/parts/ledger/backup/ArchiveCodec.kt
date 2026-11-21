package ru.parts.ledger.backup

import ru.parts.ledger.domain.LedgerState
import ru.parts.ledger.domain.PhotoRef
import ru.parts.ledger.domain.Photos
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.concurrent.CancellationException

object ArchiveCodec {
    const val MAX_BYTES = BackupCodec.MAX_BYTES.toLong() + Photos.MAX_COUNT.toLong() * (Photos.MAX_SIZE + 4096)
    private const val LEDGER = "ledger.json"
    private fun path(photo: PhotoRef) = "photos/${photo.id}.jpg"

    fun write(output: OutputStream, state: LedgerState, load: (PhotoRef) -> ByteArray, checkpoint: () -> Unit = {}) {
        val metadata = BackupCodec.encode(state)
        val stream = object : FilterOutputStream(BufferedOutputStream(output)) {
            override fun close() = flush()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        }
        ZipOutputStream(stream).use { zip ->
            // JPEG is already compressed. Avoid repeating the expensive compression for every backup.
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry(LEDGER))
            zip.write(metadata)
            zip.closeEntry()
            for (photo in state.photos) {
                checkpoint()
                zip.putNextEntry(ZipEntry(path(photo)))
                zip.write(Photos.verify(photo, load(photo)))
                zip.closeEntry()
            }
        }
    }

    fun inspect(file: File, verifyPhotos: Boolean = true, checkpoint: () -> Unit = {}): VerifiedBackup = try {
        require(file.length() in 1..MAX_BYTES) { "Файл копии пуст или слишком велик" }
        val isZip = file.inputStream().buffered().use { it.read() == 0x50 && it.read() == 0x4b }
        if (isZip) {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(LEDGER) ?: throw IllegalArgumentException("В архиве нет учёта")
                val backup = zip.getInputStream(entry).use { BackupCodec.inspect(BackupCodec.readBounded(it)) }
                val names = backup.state.photos.map(::path).toSet() + LEDGER
                require(zip.size() == names.size && zip.entries().asSequence().map { it.name }.toSet() == names) {
                    "В архиве отсутствуют или повторяются файлы"
                }
                if (verifyPhotos) backup.state.photos.forEach { checkpoint(); loadPhoto(zip, it) }
                backup.copy(archive = file)
            }
        } else {
            file.inputStream().use { BackupCodec.inspect(BackupCodec.readBounded(it)) }.also {
                require(it.state.photos.isEmpty()) { "В файле отсутствуют снимки" }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("Не удалось прочитать архив копии", e)
    }

    private fun loadPhoto(zip: ZipFile, photo: PhotoRef): ByteArray {
        val entry = zip.getEntry(path(photo)) ?: throw IllegalArgumentException("В архиве нет снимка")
        require(entry.size == photo.size.toLong()) { "Неверный размер снимка в копии" }
        val bytes = ByteArray(photo.size)
        DataInputStream(zip.getInputStream(entry)).use { input ->
            input.readFully(bytes)
            require(input.read() == -1) { "Неверный размер снимка в копии" }
        }
        return Photos.verify(photo, bytes)
    }

    suspend fun <T> withPhotos(backup: VerifiedBackup, action: suspend ((PhotoRef) -> ByteArray) -> T): T {
        val archive = backup.archive ?: return action { error("В копии отсутствует снимок") }
        return ZipFile(archive).use { zip -> action { loadPhoto(zip, it) } }
    }
}
