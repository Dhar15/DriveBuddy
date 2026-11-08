package app.drivebuddy.features.music.spotify

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Pkce {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun newVerifier(length: Int = 64): String {
        val random = SecureRandom()
        return (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}