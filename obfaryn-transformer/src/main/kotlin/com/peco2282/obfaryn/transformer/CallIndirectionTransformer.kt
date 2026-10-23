package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.protection.BodyFeature
import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import com.peco2282.obfaryn.runtime.internal.CallLinker
import com.peco2282.obfaryn.transformer.linkage.PrelinkGenerator
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.CodeSizeEvaluator
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.*
import java.util.Random

/**
 * Hides ordinary call references behind encrypted linkage metadata and constant JVM call sites.
 * Must run after renaming: encrypted names are deliberately opaque to ASM remappers.
 * [includeClass] receives final internal names and should restrict fat JARs to application code.
 */
class CallIndirectionTransformer @JvmOverloads constructor(
  private val random: Random,
  private val prelink: Boolean = false,
  private val includeClass: ((String) -> Boolean)? = null,
) : Transformer {
  constructor(random: Random, includeClass: (String) -> Boolean) : this(random, false, includeClass)
  override val isolatedBodyFeature = BodyFeature.CALLS
  override val id = "call-indirection"
  override val order = 45
  override val requiresFrameRecomputation get() = prelink
  override val after = setOf("rename", "control-flow", "virtualization", "string-encryption", "number-obfuscation")
  private var calls = 0L
  private var skipped = 0L
  override fun report() = TransformerReport(id, mapOf("indirectedCalls" to calls, "skippedCalls" to skipped))

  override fun skipReason(context: ObfuscationContext, owner: org.objectweb.asm.tree.ClassNode,
    method: MethodNode): String = when {
    !(includeClass?.invoke(owner.name) ?: context.transformationInputs.isApplicationClass(owner.name)) -> "outside-application-scope"
    owner.version and 0xffff < Opcodes.V1_8 -> "requires-java8-classfile"
    method.name.startsWith('<') -> "constructor-or-class-initializer"
    method.access and Opcodes.ACC_SYNTHETIC != 0 -> "synthetic-method"
    else -> "no-supported-invocations-or-classfile-capacity"
  }

  override fun transform(context: ObfuscationContext) {
    calls = 0
    skipped = 0
    val templateName = Type.getInternalName(CallLinker::class.java)
    var helperName: String
    do {
      helperName = "obf/" + "L" + java.lang.Long.toUnsignedString(random.nextLong(), 36)
    } while (context.hasClass(helperName) || context.hasResource("$helperName.class") ||
      context.hasClass(helperName + "\$C") || context.hasResource(helperName + "\$C.class"))
    val bootstrap = Handle(Opcodes.H_INVOKESTATIC, helperName, "link",
      "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
        "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)Ljava/lang/invoke/CallSite;", false)
    for (owner in context.classes.values.toList()) {
      if (!(includeClass?.invoke(owner.name) ?: context.transformationInputs.isApplicationClass(owner.name)) || owner.version and 0xffff < Opcodes.V1_8) continue
      // Reserve constant-pool headroom before expanding each target into three encoded strings.
      // The conservative estimate also covers the new descriptor, name, bootstrap and key entries.
      val bytes = ClassWriter(0).also { owner.accept(it) }.toByteArray()
      val poolSize = ((bytes[8].toInt() and 255) shl 8) or (bytes[9].toInt() and 255)
      var budget = ((65535 - poolSize - 64) / 16).coerceAtLeast(0)
      val ownerSites = mutableListOf<InvokeDynamicInsnNode>()
      val siteNames = hashSetOf<String>()
      for (method in owner.methods) {
        // Constructors can contain uninitialized references. Leave their entire bodies alone.
        if (method.name.startsWith('<') || method.access and Opcodes.ACC_SYNTHETIC != 0 ||
          !context.transformationPolicy.canTransformBody(owner, method, BodyFeature.CALLS)) continue
        val size = CodeSizeEvaluator(null)
        method.accept(size)
        var codeBudget = ((65535 - size.maxSize) / 2).coerceAtLeast(0)
        for (call in method.instructions.toArray().filterIsInstance<MethodInsnNode>()) {
          if (budget == 0 || codeBudget == 0 || maxOf(call.owner.length, call.name.length, call.desc.length) > 16383 ||
            call.opcode !in setOf(Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE) ||
            call.owner.startsWith('[') || call.owner == "java/lang/Object" && call.name == "clone" ||
            call.owner == "java/lang/invoke/MethodHandle" ||
            call.owner == "java/lang/invoke/VarHandle" ||
            call.owner == "java/lang/invoke/MethodHandles" ||
            call.owner == "java/lang/Class" && call.name == "forName") {
            skipped++
            continue
          }
          // Do not turn generated literal decoders and runtime helpers into extra linkage sites.
          val target = context.findClass(call.owner)?.methods?.firstOrNull { it.name == call.name && it.desc == call.desc }
          if (target != null && target.access and Opcodes.ACC_SYNTHETIC != 0) { skipped++; continue }
          val descriptor = if (call.opcode == Opcodes.INVOKESTATIC) call.desc else {
            // Protected superclass access can narrow the handle's receiver to the lookup class.
            val receiver = if (target != null && target.access and Opcodes.ACC_PROTECTED != 0 &&
              call.owner.substringBeforeLast('/', "") != owner.name.substringBeforeLast('/', "")) owner.name else call.owner
            Type.getMethodDescriptor(Type.getReturnType(call.desc),
              Type.getObjectType(receiver), *Type.getArgumentTypes(call.desc))
          }
          // Unknown external protected methods cannot safely use a statically narrowed receiver.
          if (target == null && call.opcode != Opcodes.INVOKESTATIC && call.owner != owner.name &&
            call.owner != "java/lang/Object" && call.name == "clone") { skipped++; continue }
          val key = random.nextInt()
          var siteName: String
          do { siteName = "c" + java.lang.Integer.toUnsignedString(random.nextInt(), 36) } while (!siteNames.add(siteName))
          val replacement = InvokeDynamicInsnNode(siteName,
            descriptor, bootstrap, encode(call.owner, key), encode(call.name, key), encode(call.desc, key), key, call.opcode)
          method.instructions.set(call, replacement)
          ownerSites += replacement
          calls++
          budget--
          codeBudget--
        }
      }
      if (prelink && ownerSites.isNotEmpty()) PrelinkGenerator.generate(owner, ownerSites, bootstrap)
    }
    if (calls > 0) {
      val names = mapOf(templateName to helperName, templateName + "\$SiteCache" to helperName + "\$C")
      for ((templateClass, outputClass) in names) {
        val template = ClassNode()
        CallLinker::class.java.getResourceAsStream("/$templateClass.class")!!.use {
          ClassReader(it).accept(template, ClassReader.SKIP_DEBUG)
        }
        val helper = ClassNode()
        template.accept(ClassRemapper(helper, SimpleRemapper(Opcodes.ASM9, names)))
        check(helper.name == outputClass)
        context.putClass(helper)
      }
    }
    if (prelink) com.peco2282.obfaryn.transformer.linkage.PrelinkRuntimeTransformer.inject(context)
  }

  private fun encode(text: String, initialKey: Int): String {
    var key = initialKey
    return buildString(text.length * 4) {
      for (character in text) {
        key = key * 1664525 + 1013904223
        append((character.code xor (key ushr 16)).toString(16).padStart(4, '0'))
      }
    }
  }
}
