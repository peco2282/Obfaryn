package example.extension

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.assembly.*
import com.peco2282.obfaryn.api.protection.BodyFeature
import com.peco2282.obfaryn.api.transformer.*
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

/** This entire extension compiles with obfaryn-api alone. */
class ExamplePackageProvider : TransformationPackageProvider {
  override val id = "example-padding"
  override fun create(options: PlatformOptions) = TransformationPackage { _, registry ->
    registry.add(PaddingTransformer(options.integer("padding-rounds", 1)))
  }
}

/** Adds reversible padding around integer constants, while respecting method policy and budgets. */
class PaddingTransformer(private val rounds: Int) : Transformer {
  init { require(rounds in 1..32) { "padding-rounds must be in 1..32" } }
  override val id = "example-padding"
  override val isolatedBodyFeature = BodyFeature("example:padding")
  private var count = 0
  override val transformedCount get() = count
  override fun transform(context: ObfuscationContext) {
    count = 0
    for (owner in context.classes.values) for (method in owner.methods) {
      if (!context.transformationPolicy.canTransformBody(owner, method, isolatedBodyFeature)) continue
      for (instruction in method.instructions.toArray()) {
        if (instruction !is IntInsnNode || instruction.opcode !in setOf(Opcodes.BIPUSH, Opcodes.SIPUSH)) continue
        val padding = InsnList()
        repeat(rounds) {
          padding.add(InsnNode(Opcodes.ICONST_0))
          padding.add(InsnNode(Opcodes.IXOR))
        }
        method.instructions.insert(instruction, padding)
        count++
      }
    }
  }
}

/** Dedicated framework tools may supply ordinary packages alongside their keep rules. */
class ExamplePlatformProvider : PlatformProvider {
  override val metadata = DefaultPlatformMetadata("example", "Example framework", "1")
  override fun create() = object : Platform {
    override val metadata = this@ExamplePlatformProvider.metadata
    override fun detect(context: PlatformContext) = DefaultPlatformDetection(
      if ("example.properties" in context.resources) 100 else 0)
    override fun configure(context: PlatformContext, options: PlatformOptions, target: PlatformConfiguration) {
      target.keepClass("example.Main")
    }
    override fun entrypoints(context: PlatformContext, options: PlatformOptions) = setOf("example/Main")
    override fun extensions() = listOf(PlatformPackages { _, options ->
      listOf(ExamplePackageProvider().create(options))
    })
  }
}
