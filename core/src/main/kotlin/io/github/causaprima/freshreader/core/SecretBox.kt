package io.github.causaprima.freshreader.core

/**
 * Encrypts small secrets before they are written to the on-device store.
 * On Android this is backed by a key in the Android Keystore.
 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}
