package com.example.core.runtime

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

/**
 * Hardware-backed / Android Keystore Cryptographic Signer.
 * Generates EC/RSA key pairs inside Android Keystore and generates authentic cryptographic
 * signatures over evidence digests and execution receipts.
 */
class KeystoreReceiptSigner {

  companion object {
    private const val TAG = "KeystoreReceiptSigner"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val DEFAULT_KEY_ALIAS = "purpclaw_hardware_root_key_v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
  }

  init {
    ensureKeyPairExists(DEFAULT_KEY_ALIAS)
  }

  private fun ensureKeyPairExists(alias: String) {
    try {
      val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
      if (!keyStore.containsAlias(alias)) {
        val kpg = KeyPairGenerator.getInstance(
          KeyProperties.KEY_ALGORITHM_EC,
          KEYSTORE_PROVIDER
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
          alias,
          KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
          .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
          .build()

        kpg.initialize(parameterSpec)
        kpg.generateKeyPair()
        Log.i(TAG, "Initialized new hardware-backed Keystore EC KeyPair for PurpClaw alias: $alias")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error initializing Android Keystore keypair: ${e.message}", e)
    }
  }

  /**
   * Signs a payload digest with the device's Keystore private key.
   */
  fun signPayload(payload: String, alias: String = DEFAULT_KEY_ALIAS): SignedReceiptData {
    return try {
      val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
      val privateKeyEntry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        ?: run {
          ensureKeyPairExists(alias)
          keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        }

      if (privateKeyEntry != null) {
        val privateKey: PrivateKey = privateKeyEntry.privateKey
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
          initSign(privateKey)
          update(payload.toByteArray(Charsets.UTF_8))
        }
        val signatureBytes = signer.sign()
        val signatureBase64 = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        SignedReceiptData(
          signingKeyId = alias,
          signature = signatureBase64,
          isHardwareBacked = true,
          algorithm = SIGNATURE_ALGORITHM
        )
      } else {
        SignedReceiptData(
          signingKeyId = "software_fallback",
          signature = "FALLBACK_UNSIGNED_${System.currentTimeMillis()}",
          isHardwareBacked = false,
          algorithm = "NONE"
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Signing failed: ${e.message}", e)
      SignedReceiptData(
        signingKeyId = "error_key",
        signature = "ERROR_${e.javaClass.simpleName}",
        isHardwareBacked = false,
        algorithm = "FAILED"
      )
    }
  }

  /**
   * Verifies a signature against the public certificate in Android Keystore.
   */
  fun verifySignature(payload: String, signatureBase64: String, alias: String = DEFAULT_KEY_ALIAS): Boolean {
    return try {
      val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
      val cert = keyStore.getCertificate(alias) ?: return false
      val signatureBytes = Base64.decode(signatureBase64, Base64.NO_WRAP)
      val verifier = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
        initVerify(cert.publicKey)
        update(payload.toByteArray(Charsets.UTF_8))
      }
      verifier.verify(signatureBytes)
    } catch (e: Exception) {
      Log.e(TAG, "Verification error: ${e.message}")
      false
    }
  }
}

data class SignedReceiptData(
  val signingKeyId: String,
  val signature: String,
  val isHardwareBacked: Boolean,
  val algorithm: String
)
