package com.example.core.runtime

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-GCM Encrypted Vault for sovereign secrets & API keys.
 * Raw API keys are never stored unencrypted in Room or plain SharedPreferences.
 */
class KeyStoreVault(private val context: Context) {

  companion object {
    private const val TAG = "KeyStoreVault"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "purpclaw_vault_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFS_NAME = "purpclaw_encrypted_vault_prefs"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
  }

  private val sharedPreferences: SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  init {
    ensureMasterKeyExists()
  }

  private fun ensureMasterKeyExists() {
    try {
      val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
      if (!keyStore.containsAlias(KEY_ALIAS)) {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build()

        keyGenerator.init(spec)
        keyGenerator.generateKey()
        Log.i(TAG, "Generated master AES-256 GCM key in Android Keystore")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error ensuring master key in Keystore: ${e.message}", e)
    }
  }

  private fun getMasterKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    return keyStore.getKey(KEY_ALIAS, null) as SecretKey
  }

  fun storeSecret(keyName: String, secretValue: String): Boolean {
    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
      val iv = cipher.iv
      val cipherText = cipher.doFinal(secretValue.toByteArray(Charsets.UTF_8))

      val combined = ByteArray(iv.size + cipherText.size)
      System.arraycopy(iv, 0, combined, 0, iv.size)
      System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

      val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
      sharedPreferences.edit().putString(keyName, encoded).apply()
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to encrypt and store secret '$keyName': ${e.message}")
      false
    }
  }

  fun retrieveSecret(keyName: String): String? {
    return try {
      val encoded = sharedPreferences.getString(keyName, null) ?: return null
      val combined = Base64.decode(encoded, Base64.NO_WRAP)
      if (combined.size <= GCM_IV_LENGTH) return null

      val iv = ByteArray(GCM_IV_LENGTH)
      val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
      System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
      System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

      val cipher = Cipher.getInstance(TRANSFORMATION)
      val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
      cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)

      val decryptedBytes = cipher.doFinal(cipherText)
      String(decryptedBytes, Charsets.UTF_8)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to decrypt secret '$keyName': ${e.message}")
      // STALE CIPHERTEXT: a reinstall invalidates the Keystore master key
      // while SharedPreferences survive — the entry can never decrypt again.
      // Delete it so callers see MISSING (→ re-provision) instead of silently
      // failing forever.
      if (e is javax.crypto.AEADBadTagException || e is java.security.KeyStoreException ||
          e is java.security.UnrecoverableKeyException || e is java.security.InvalidKeyException) {
        Log.w(TAG, "Removing undecryptable secret '$keyName' — master key was invalidated (reinstall?)")
        sharedPreferences.edit().remove(keyName).apply()
      }
      null
    }
  }

  fun hasSecret(keyName: String): Boolean {
    return sharedPreferences.contains(keyName)
  }

  /**
   * SELF-HEAL PASS: probe each stored secret by decrypting it. Entries whose
   * ciphertext can no longer be decrypted (master key invalidated by app
   * reinstall) are removed, so seedVaultFromBuildConfig will re-seed them
   * with the fresh master key. Call once at boot before seeding.
   */
  fun purgeUndecryptableSecrets(): Int {
    var purged = 0
    for (keyName in listStoredKeys().toList()) {
      if (retrieveSecret(keyName) == null && sharedPreferences.contains(keyName)) {
        // retrieveSecret already removed crypto-invalid entries; this catch is
        // for any entry that still exists but returned null for another reason.
        sharedPreferences.edit().remove(keyName).apply()
        purged++
        Log.w(TAG, "Purged undecryptable secret '$keyName' at boot")
      }
    }
    return purged
  }

  fun deleteSecret(keyName: String) {
    sharedPreferences.edit().remove(keyName).apply()
  }

  fun listStoredKeys(): List<String> {
    return sharedPreferences.all.keys.toList()
  }
}
