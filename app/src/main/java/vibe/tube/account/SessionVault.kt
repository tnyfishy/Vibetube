package vibe.tube.account

import kotlinx.serialization.json.Json
import java.io.File

interface SessionVault {
    fun loadAll(): List<AccountSession>
    fun save(session: AccountSession)
    fun delete(accountId: String)
    fun loadActiveId(): String?
    fun saveActiveId(id: String?)
    fun wipe()
}

class FileSessionVault(
    private val dir: File,
    private val cipher: SecretCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SessionVault {

    init {
        dir.mkdirs()
    }

    override fun loadAll(): List<AccountSession> =
        dir.listFiles { f -> f.name.endsWith(SESSION_SUFFIX) }.orEmpty().mapNotNull { file ->
            runCatching {
                json.decodeFromString(AccountSession.serializer(), String(cipher.decrypt(file.readBytes()), Charsets.UTF_8))
            }.getOrElse {
                file.delete()
                null
            }
        }.sortedBy { it.createdAt }

    override fun save(session: AccountSession) {
        val bytes = cipher.encrypt(json.encodeToString(AccountSession.serializer(), session).toByteArray(Charsets.UTF_8))
        writeAtomically(File(dir, session.id + SESSION_SUFFIX), bytes)
    }

    override fun delete(accountId: String) {
        File(dir, accountId + SESSION_SUFFIX).delete()
        File(dir, accountId + SESSION_SUFFIX + TMP_SUFFIX).delete()
    }

    override fun loadActiveId(): String? = runCatching {
        val file = File(dir, ACTIVE_FILE)
        if (file.exists()) String(cipher.decrypt(file.readBytes()), Charsets.UTF_8).ifEmpty { null } else null
    }.getOrNull()

    override fun saveActiveId(id: String?) {
        val file = File(dir, ACTIVE_FILE)
        if (id == null) file.delete() else writeAtomically(file, cipher.encrypt(id.toByteArray(Charsets.UTF_8)))
    }

    override fun wipe() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "rename failed" }
        }
    }

    private companion object {
        const val SESSION_SUFFIX = ".session"
        const val TMP_SUFFIX = ".tmp"
        const val ACTIVE_FILE = "active.bin"
    }
}

class InMemorySessionVault : SessionVault {
    private val sessions = linkedMapOf<String, AccountSession>()
    private var active: String? = null
    override fun loadAll(): List<AccountSession> = sessions.values.toList()
    override fun save(session: AccountSession) { sessions[session.id] = session }
    override fun delete(accountId: String) { sessions.remove(accountId) }
    override fun loadActiveId(): String? = active
    override fun saveActiveId(id: String?) { active = id }
    override fun wipe() { sessions.clear(); active = null }
}
