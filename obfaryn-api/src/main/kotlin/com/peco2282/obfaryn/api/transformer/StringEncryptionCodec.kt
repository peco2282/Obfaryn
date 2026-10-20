package com.peco2282.obfaryn.api.transformer

import org.objectweb.asm.tree.MethodNode

/** The constants passed to the generated decryptor, including the effective key. */
data class StringEncryptionResult(val payload: String, val key: Int)

/**
 * Couples build-time string encryption with runtime decryption bytecode generation.
 * Implementations need only depend on obfaryn-api, not on the standard transformers.
 */
interface StringEncryptionCodec {
  fun encrypt(value: String, key: Int): StringEncryptionResult

  /**
   * Fills a fresh static method with descriptor `(Ljava/lang/String;I)Ljava/lang/String;`.
   * Local slots 0 and 1 contain the payload and effective key returned by [encrypt].
   * Preserve the supplied name, descriptor and access flags; emit a body returning the
   * original string with ARETURN and set maxStack/maxLocals. Supply valid stack map
   * frames for branches unless the host explicitly enables frame recomputation.
   * The method belongs to [owner], which can also be an interface. It is generated once
   * per class with encrypted strings. Referenced runtime code must exist in the output
   * or on its runtime classpath; the codec implementation itself is not bundled.
   */
  fun generateDecryptor(owner: String, method: MethodNode)
}