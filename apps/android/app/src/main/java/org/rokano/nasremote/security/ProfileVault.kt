package org.rokano.nasremote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import org.rokano.nasremote.core.ConnectionProfile
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredProfile(val profile: ConnectionProfile, val key: ByteArray)

/** One encrypted record. No backup, plaintext temporary file, password or transcript storage. */
class ProfileVault(context: Context, record: String = "connection.v1", private val alias: String = "nas-remote-profile-v1", private val maxBytes: Int = 131_072) {
    private val file = AtomicFile(File(context.noBackupFilesDir, record))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun save(profile: ConnectionProfile, privateKey: ByteArray) {
        val data = JSONObject().put("host", profile.host).put("port", profile.port)
            .put("user", profile.user).put("fingerprint", profile.fingerprint)
            .put("key", Base64.getEncoder().encodeToString(privateKey)).toString().toByteArray()
        try { writeRecord(data) } finally { data.fill(0) }
    }
    fun writeRecord(data: ByteArray) {
        require(data.size + 28 <= maxBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(alias.toByteArray())
        val ciphertext = cipher.doFinal(data)
        val out = file.startWrite()
        try {
            out.write(cipher.iv)
            out.write(ciphertext)
            file.finishWrite(out)
        } catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun readRecord(): ByteArray? {
        if (!file.baseFile.exists()) return null
        val data = file.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= maxBytes)
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        require(data.size in 29..maxBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(alias.toByteArray())
        return cipher.doFinal(data, 12, data.size - 12)
    }
    fun load(): StoredProfile? {
        val plain = readRecord() ?: return null
        try {
            val j = JSONObject(plain.toString(Charsets.UTF_8))
            val p = ConnectionProfile(j.getString("host"), j.getInt("port"), j.getString("user"), j.getString("fingerprint"))
            p.validate()
            return StoredProfile(p, Base64.getDecoder().decode(j.getString("key")))
        } finally { plain.fill(0) }
    }
    fun delete() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
