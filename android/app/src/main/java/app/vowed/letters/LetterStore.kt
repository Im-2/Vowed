package app.vowed.letters

import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * All letters in one encrypted file on this phone. Only the cipher text is ever written; the text of a letter is never sent anywhere.
 * A file that cannot be decrypted (the key is gone, or the file was changed) reads as "no letters" instead of crashing the app.
 */
class LetterStore(private val file: File, private val cipher: LetterCipher) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Letter.serializer())

    @Synchronized
    fun all(): List<Letter> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(serializer, cipher.decrypt(file.readBytes()).decodeToString()) }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(letters: List<Letter>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(json.encodeToString(serializer, letters).encodeToByteArray()))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun add(letter: Letter) = save(all() + letter)
    fun delete(id: String) = save(all().filterNot { it.id == id })

    /** Marks the letters delivered and returns them. A letter is delivered once. */
    @Synchronized
    fun deliver(ids: Set<String>, now: Long, reason: (Letter) -> String): List<Letter> {
        val updated = all().map { if (it.id in ids && it.deliveredAt == null) it.copy(deliveredAt = now, deliveredBecause = reason(it)) else it }
        save(updated)
        return updated.filter { it.id in ids }
    }

    /** The person has read a delivered letter: it is erased if they asked for that. */
    fun finishedReading(id: String) {
        val l = all().firstOrNull { it.id == id } ?: return
        if (l.deleteAfterReading) delete(id)
    }
}
