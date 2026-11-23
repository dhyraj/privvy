package com.privvy.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN + biometric gate (FR-02, dev version).
 *
 * The PIN is stored as a salted PBKDF2-HMAC-SHA256 hash in private prefs.
 * Milestone 3 replaces this with Argon2id + a Keystore-bound key; the public
 * API here is intentionally small so that swap stays local.
 */
object AuthManager {

    private const val PREFS = "privvy_auth"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val ITERATIONS = 150_000
    private const val KEY_BITS = 256

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 8

    fun isValidPinFormat(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

    fun hasPin(context: Context): Boolean =
        prefs(context).contains(KEY_PIN_HASH)

    fun setPin(context: Context, pin: String): Boolean {
        if (!isValidPinFormat(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        prefs(context).edit()
            .putString(KEY_PIN_SALT, android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP))
            .apply()
        return true
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val saltB64 = p.getString(KEY_PIN_SALT, null) ?: return false
        val hashB64 = p.getString(KEY_PIN_HASH, null) ?: return false
        val salt = android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP)
        val expected = android.util.Base64.decode(hashB64, android.util.Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, derive(pin, salt))
    }

    fun clearPin(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** BIOMETRIC_SUCCESS when the device has enrolled biometrics. */
    fun biometricStatus(context: Context): Int =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)

    fun canUseBiometric(context: Context): Boolean =
        biometricStatus(context) == BiometricManager.BIOMETRIC_SUCCESS

    fun biometricStatusLabel(status: Int): String = when (status) {
        BiometricManager.BIOMETRIC_SUCCESS -> "Available"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No biometric hardware"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric hardware unavailable"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No biometrics enrolled"
        else -> "Unavailable"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }
}
