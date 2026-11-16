package app.family.igguard

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound authenticated storage. Never substitutes plaintext or resets unreadable data. */
object LocalVault {
    private const val PREFIX = "igv1:"
    private const val VERSION = "_local_vault_version"
    private const val AES_ALIAS = "ig_guard.local_storage.aes.v1"
    private const val HMAC_ALIAS = "ig_guard.local_storage.index.v1"
    private val lock = Any()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    private fun key(ctx: Context, alias: String, hmac: Boolean = false, create: Boolean): SecretKey = synchronized(lock) {
        val marker = AtomicFile(File(ctx.noBackupFilesDir, alias + ".initialized"))
        fun initialized() = marker.baseFile.exists() || File(marker.baseFile.path + ".bak").exists()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun recordInitialization() {
            if (!initialized()) {
                val stream = marker.startWrite()
                try {
                    stream.write(1)
                    marker.finishWrite(stream)
                    check(marker.baseFile.exists()) { "Storage initialization marker was not committed" }
                }
                catch (e: Exception) { marker.failWrite(stream); throw e }
            }
        }
        if (store.containsAlias(alias)) {
            recordInitialization()
            return@synchronized store.getKey(alias, null) as SecretKey
        }
        check(create && !initialized()) { "Required storage key is unavailable" }
        val algorithm = if (hmac) KeyProperties.KEY_ALGORITHM_HMAC_SHA256 else KeyProperties.KEY_ALGORITHM_AES
        val purposes = if (hmac) KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            else KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec = KeyGenParameterSpec.Builder(alias, purposes).setKeySize(256).apply {
            if (hmac) setDigests(KeyProperties.DIGEST_SHA256)
            else { setBlockModes(KeyProperties.BLOCK_MODE_GCM); setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE) }
        }.build()
        KeyGenerator.getInstance(algorithm, "AndroidKeyStore").apply { init(spec) }.generateKey().also { recordInitialization() }
    }

    private inline fun <T> protectedOperation(action: () -> T): T = try { action() }
        catch (e: Exception) { throw SecurityException("Encrypted storage is unavailable; existing data was retained", e) }

    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

    fun seal(ctx: Context, purpose: String, plaintext: String): String = protectedOperation {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(ctx, AES_ALIAS, create = false))
        cipher.updateAAD((ctx.packageName + ":" + purpose).toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        PREFIX + encoder.encodeToString(cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun open(ctx: Context, purpose: String, sealed: String): String = protectedOperation {
        require(isSealed(sealed)) { "Unencrypted storage value" }
        val bytes = decoder.decode(sealed.substring(PREFIX.length))
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(ctx, AES_ALIAS, create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD((ctx.packageName + ":" + purpose).toByteArray(Charsets.UTF_8))
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))).toString()
    }

    fun blindIndex(ctx: Context, purpose: String, value: String): String = protectedOperation {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key(ctx, HMAC_ALIAS, hmac = true, create = false))
        mac.update((ctx.packageName + ":" + purpose + "\u0000").toByteArray(Charsets.UTF_8))
        mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun initializeArchiveKeys(ctx: Context) = protectedOperation {
        key(ctx, AES_ALIAS, create = true)
        key(ctx, HMAC_ALIAS, hmac = true, create = true)
        Unit
    }

    fun requireArchiveKeys(ctx: Context) = protectedOperation {
        key(ctx, AES_ALIAS, create = false)
        key(ctx, HMAC_ALIAS, hmac = true, create = false)
        Unit
    }

    fun preferences(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        val raw = app.getSharedPreferences("ig_guard_prefs", Context.MODE_PRIVATE)
        synchronized(lock) {
            when (raw.getInt(VERSION, 0)) {
                0 -> {
                    protectedOperation { key(app, AES_ALIAS, create = true) }
                    val editor = raw.edit()
                    // One atomic preferences transaction: no mixed clear/encrypted state after a crash.
                    for ((name, value) in raw.all) when (value) {
                        is String -> editor.putString(name, seal(app, "prefs:$name", value))
                        is Set<*> -> editor.putStringSet(name, value.map { seal(app, "prefs:$name", it as String) }.toSet())
                    }
                    check(editor.putInt(VERSION, 1).commit()) { "Encrypted preferences migration failed" }
                }
                1 -> Unit
                else -> throw SecurityException("Unsupported encrypted preferences version")
            }
        }
        return VaultPreferences(app, raw)
    }

    private class VaultPreferences(val ctx: Context, val raw: SharedPreferences) : SharedPreferences by raw {
        override fun getString(key: String, defValue: String?): String? = synchronized(lock) {
            if (!raw.contains(key)) defValue else raw.getString(key, null)?.let { open(ctx, "prefs:$key", it) }
        }
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(lock) {
            if (!raw.contains(key)) defValues else raw.getStringSet(key, null)?.map { open(ctx, "prefs:$key", it) }?.toMutableSet()
        }
        override fun getAll(): MutableMap<String, *> = synchronized(lock) {
            raw.all.filterKeys { it != VERSION }.mapValues { (name, value) -> when (value) {
                is String -> open(ctx, "prefs:$name", value)
                is Set<*> -> value.map { open(ctx, "prefs:$name", it as String) }.toSet()
                else -> value
            } }.toMutableMap()
        }
        override fun edit(): SharedPreferences.Editor = VaultEditor(ctx, raw.edit())
    }

    private class VaultEditor(val ctx: Context, val raw: SharedPreferences.Editor) : SharedPreferences.Editor by raw {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            require(key != VERSION)
            raw.putString(key, value?.let { seal(ctx, "prefs:$key", it) })
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            require(key != VERSION)
            raw.putStringSet(key, values?.map { seal(ctx, "prefs:$key", it) }?.toSet())
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { require(key != VERSION);raw.putInt(key, value) }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { raw.putLong(key, value) }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { raw.putFloat(key, value) }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { raw.putBoolean(key, value) }
        override fun remove(key: String): SharedPreferences.Editor = apply { require(key != VERSION);raw.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { raw.clear() }
        override fun commit(): Boolean = synchronized(lock) { raw.putInt(VERSION, 1).commit() }
        override fun apply() { synchronized(lock) { raw.putInt(VERSION, 1).apply() } }
    }
}
