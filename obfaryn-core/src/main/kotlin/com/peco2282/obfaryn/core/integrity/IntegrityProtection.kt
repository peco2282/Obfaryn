package com.peco2282.obfaryn.core.integrity

import com.peco2282.obfaryn.core.context.ObfuscationContext
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Adds self-contained integrity checks and signs the final JAR entry manifest.
 *
 * [inject] inserts a verifier call at eligible class initialization points. [seal] hashes the
 * finished archive and signs its entry list with SHA256withRSA; the private key is never embedded.
 * This detects changes to the file-backed JAR when verification runs, but does not protect a
 * replaced verifier or continuously monitor the process.
 */
class IntegrityProtection(privateKey: Path, publicKey: Path) {
  private val factory = KeyFactory.getInstance("RSA")
  private val secret = factory.generatePrivate(PKCS8EncodedKeySpec(readKey(privateKey)))
  private val public = factory.generatePublic(X509EncodedKeySpec(readKey(publicKey)))

  init {
    require((public as java.security.interfaces.RSAKey).modulus.bitLength() >= 2048) { "Integrity RSA key must be at least 2048 bits" }
    val probe = byteArrayOf(1, 2, 3)
    require(Signature.getInstance("SHA256withRSA").run {
      initVerify(public); update(probe); verify(sign(probe))
    }) { "Integrity private/public keys do not match" }
  }

  /** Adds verification calls and a public-key-only runtime helper before archive writing. */
  fun inject(context: ObfuscationContext, excluded: Set<String>, random: java.util.Random) {
    require(context.resources.keys.none { it == MANIFEST || it == SIGNATURE }) { "Input already contains integrity metadata" }
    val targets = context.classes.values.filter { it.name !in excluded && it.name != "module-info" }
    require(targets.isNotEmpty()) { "Integrity verification requires at least one non-excluded class" }
    val original = "com/peco2282/obfaryn/core/integrity/IntegrityRuntime"
    var name: String
    do { name = "obfaryn/integrity/V" + java.lang.Long.toUnsignedString(random.nextLong(), 36) }
    while (context.hasClass(name) || context.hasResource("$name.class"))
    val helper = ClassNode()
    val runtime = javaClass.getResourceAsStream("/$original.class")
      ?: error("Missing integrity runtime resource: /$original.class")
    runtime.use {
      ClassReader(it).accept(ClassRemapper(helper, SimpleRemapper(original, name)), 0)
    }
    helper.version = Opcodes.V1_8
    for (node in targets) {
      val initializer = node.methods.firstOrNull { it.name == "<clinit>" } ?: MethodNode(
        Opcodes.ACC_STATIC, "<clinit>", "()V", null, null,
      ).also { it.instructions.add(InsnNode(Opcodes.RETURN)); node.methods.add(it) }
      initializer.instructions.insert(InsnList().apply {
        add(LdcInsnNode(Type.getObjectType(node.name)))
        add(LdcInsnNode(Base64.getEncoder().encodeToString(public.encoded)))
        add(MethodInsnNode(Opcodes.INVOKESTATIC, name, "verify", "(Ljava/lang/Class;Ljava/lang/String;)V", false))
      })
    }
    context.putClass(helper)
  }

  /** Signs sorted, non-directory JAR entries and appends integrity metadata to [output]. */
  fun seal(output: Path, reproducible: Boolean) {
    val temporary = Files.createTempFile(output.toAbsolutePath().parent, "obfaryn-integrity-", ".jar")
    try {
      JarFile(output.toFile()).use { jar ->
        val entries = jar.entries().toList().filterNot { it.isDirectory }.sortedBy { it.name }
        require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate JAR entries" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { manifest ->
          manifest.writeInt(0x56424931)
          manifest.writeInt(entries.size)
          for (entry in entries) {
            manifest.writeUTF(entry.name)
            val digest = MessageDigest.getInstance("SHA-256")
            jar.getInputStream(entry).use { input ->
              val buffer = ByteArray(8192)
              while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            manifest.writeInt(32)
            manifest.write(digest.digest())
          }
        }
        JarOutputStream(Files.newOutputStream(temporary)).use { out ->
          for (entry in entries) {
            out.putNextEntry(JarEntry(entry.name).apply { time = if (reproducible) 0 else entry.time })
            jar.getInputStream(entry).use { it.copyTo(out) }
            out.closeEntry()
          }
          for ((name, data) in listOf(MANIFEST to bytes.toByteArray(), SIGNATURE to sign(bytes.toByteArray()))) {
            out.putNextEntry(JarEntry(name).apply { if (reproducible) time = 0 })
            out.write(data); out.closeEntry()
          }
        }
      }
      Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
  }

  private fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
    initSign(secret); update(data); sign()
  }

  private fun readKey(path: Path): ByteArray {
    val bytes = Files.readAllBytes(path)
    val text = bytes.toString(Charsets.US_ASCII)
    return if (text.startsWith("-----BEGIN")) Base64.getDecoder().decode(
      text.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").filterNot(Char::isWhitespace),
    ) else bytes
  }

  companion object {
    const val MANIFEST = "META-INF/obfaryn/integrity.bin"
    const val SIGNATURE = "META-INF/obfaryn/integrity.sig"
  }
}
