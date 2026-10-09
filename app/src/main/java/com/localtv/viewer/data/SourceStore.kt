package com.localtv.viewer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials stay in app-private storage, encrypted with a non-exportable device key. */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("localtv", Context.MODE_PRIVATE)
    private val keyAlias = "localtv.source-credentials.v1"

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun load(): List<Source> {
        val array = runCatching { JSONArray(prefs.getString("sources", "[]")) }.getOrElse { return emptyList() }
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val o = array.getJSONObject(index)
                Source(o.getString("id"), o.getString("name"), SourceKind.valueOf(o.getString("kind")), o.getString("address"),
                    o.optString("username"), decrypt(o.getString("secret")), o.optString("domain"))
            }.getOrNull()
        }
    }

    fun save(sources: List<Source>) {
        val array = JSONArray()
        sources.filter { it.kind != SourceKind.DEMO }.forEach { source ->
            array.put(JSONObject().put("id", source.id).put("name", source.name).put("kind", source.kind.name)
                .put("address", source.address).put("username", source.username).put("secret", encrypt(source.password)).put("domain", source.domain))
        }
        check(prefs.edit().putString("sources", array.toString()).commit()) { "保存失败，请检查电视存储空间" }
    }

    fun position(key: String): Long = prefs.getLong("position.$key", 0)
    fun savePosition(key: String, position: Long) {
        prefs.edit().putLong("position.$key", position.coerceAtLeast(0)).apply()
        // Keep at most the most recent 200 playback positions.
        val keys = prefs.all.keys.filter { it.startsWith("position.") }
        if (keys.size > 200) prefs.edit().also { editor -> keys.filter { it != "position.$key" }.take(keys.size - 200).forEach(editor::remove) }.apply()
    }
}
