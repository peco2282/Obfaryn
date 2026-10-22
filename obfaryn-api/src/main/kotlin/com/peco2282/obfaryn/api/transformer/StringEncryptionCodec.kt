package com.peco2282.obfaryn.api.transformer

import org.objectweb.asm.tree.MethodNode

/**
 * Constants passed to the generated decryptor, including the effective key.
 *
 * @property payload Encoded string loaded as a JVM string constant; it must fit the class-file
 * constant-pool string representation. Encryption can expand data, so account for that limit.
 * @property key Actual key supplied to the runtime decryptor; it may differ from the requested key.
 */
data class StringEncryptionResult(val payload: String, val key: Int)

/**
 * Couples build-time string encryption with runtime decryption bytecode generation.
 * Implementations need only depend on obfaryn-api, not on the standard transformers.
 * The invariant is `decrypt(encrypt(value, key).payload, encrypt(value, key).key) == value`
 * for one encryption result, including empty strings and Unicode UTF-16 code units.
 * Both payload and key appear in output bytecode; this API does not guarantee cryptographic secrecy.
 */
interface StringEncryptionCodec {
  /**
   * Encodes one literal at build time without modifying application bytecode.
   *
   * @param value Original JVM string, including any surrogate code units.
   * @param key Host-selected key; use it or return a different effective key explicitly.
   * @return Arguments understood by the body emitted by [generateDecryptor].
   */
  fun encrypt(value: String, key: Int): StringEncryptionResult

  /**
   * Fills a fresh static method with descriptor `(Ljava/lang/String;I)Ljava/lang/String;`.
   * Local slots 0 and 1 contain the payload and effective key returned by [encrypt].
   * Preserve the supplied name, descriptor and access flags; emit a body returning the
   * original string with ARETURN and set maxStack/maxLocals. Supply valid stack map
   * frames for branches unless the host explicitly enables frame recomputation.
   * The method belongs to [owner], which can also be an interface. It is generated once
   * per class with encrypted strings. The host caches and interns decoded literals in fixed
   * per-class slots. Concurrent first calls may decode the same slot more than once, so
   * generated decoding must be deterministic and safe for concurrent invocation.
   * Referenced runtime code must exist in the output
   * or on its runtime classpath; the codec implementation itself is not bundled.
   *
   * @param owner JVM internal name of the class receiving the generated method.
   * @param method Fresh method whose body and stack/local limits must be populated.
   */
  fun generateDecryptor(owner: String, method: MethodNode)
}
