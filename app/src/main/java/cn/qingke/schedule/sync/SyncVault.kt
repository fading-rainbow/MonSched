package cn.qingke.schedule.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.qingke.schedule.importer.SchoolConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SyncProfile(
    val id: String = UUID.randomUUID().toString(), val loginUrl: String, val pageUrl: String,
    val system: String, val allowHttp: Boolean, val username: String, val password: String,
    val controls: Map<String, String> = emptyMap(), val cookies: Map<String, String> = emptyMap(),
    val year: String = "", val term: String = "", val pending: Boolean = true, val timetableId: Long = 0,
    val schoolName: String = ""
) {
    // Do not use generated data-class toString(): credentials must never appear in diagnostics.
    override fun toString() = "SyncProfile(id=$id, pending=$pending)"
    fun valid(): Boolean = SchoolConnection.valid(loginUrl, loginUrl, allowHttp) &&
        SyncSafety.sameOrigin(loginUrl, pageUrl) && username.isNotBlank() && password.isNotEmpty() &&
        username.length <= 256 && password.length <= 1024 && controls.size <= 20 &&
        cookies.keys.all { SyncSafety.sameOrigin(it, loginUrl) }
}

/** AES-GCM ciphertext only. The non-exportable key belongs to this app in Android Keystore. */
class SyncVault(context: Context) {
    companion object { private val keyLock = Any() }
    private val directory = File(context.noBackupFilesDir, "school-sync").apply { mkdirs() }
    private fun file(id: String): File { require(id.matches(Regex("[a-fA-F0-9-]{36}"))); return File(directory, "$id.enc") }
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("monsched.school-sync.v1", null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("monsched.school-sync.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun save(profile: SyncProfile) {
        require(profile.valid()) { "同步入口与登录信息无效，请重新配置" }
        val json = JSONObject().put("id", profile.id).put("loginUrl", profile.loginUrl).put("pageUrl", profile.pageUrl)
            .put("system", profile.system).put("allowHttp", profile.allowHttp).put("username", profile.username)
            .put("password", profile.password).put("year", profile.year).put("term", profile.term).put("pending", profile.pending)
            .put("controls", JSONObject(profile.controls)).put("cookies", JSONObject(profile.cookies)).put("timetableId", profile.timetableId).put("schoolName", profile.schoolName)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(profile.id.toByteArray()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val target = file(profile.id)
        val atomic = android.util.AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(byteArrayOf(1)); stream.write(cipher.iv); stream.write(encrypted); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    @Synchronized fun load(id: String): SyncProfile? {
        val target = file(id); if (!target.exists() && !File(target.path + ".bak").exists()) return null
        val bytes = android.util.AtomicFile(target).openRead().use { it.readBytes() }; require(bytes.size in 30..(128 * 1024) && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13))); updateAAD(id.toByteArray())
        }
        val obj = JSONObject(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8))
        fun map(name: String): Map<String, String> = obj.optJSONObject(name)?.let { value -> value.keys().asSequence().associateWith { value.getString(it) } }.orEmpty()
        return SyncProfile(id, obj.getString("loginUrl"), obj.getString("pageUrl"), obj.getString("system"),
            obj.getBoolean("allowHttp"), obj.getString("username"), obj.getString("password"), map("controls"), map("cookies"),
            obj.optString("year"), obj.optString("term"), obj.optBoolean("pending"), obj.optLong("timetableId"), obj.optString("schoolName")).also { require(it.valid()) }
    }
    @Synchronized fun delete(id: String) { android.util.AtomicFile(file(id)).delete() }
    fun cleanOrphans(retained: Set<String>) {
        directory.listFiles()?.filter { it.extension == "enc" && it.nameWithoutExtension !in retained &&
            System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
    }
}
