package com.peco2282.obfaryn.platform.mcmod

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.platformkit.isSubtypeOf
import org.objectweb.asm.tree.ClassNode

/**
 * Fabric mods ship a root `fabric.mod.json` and implement one of Fabric's initializer interfaces
 * (`ModInitializer`, `ClientModInitializer`, `DedicatedServerModInitializer`), named in that file.
 * Fabric event callbacks are registered as lambdas rather than annotated, so no method is excluded
 * from virtualization by this platform.
 */
class FabricPlatformProvider : PlatformProvider {
  override val metadata: PlatformMetadata = PlatformProvider.metadata {
    id = "fabric"
    displayName = "Minecraft Fabric Mod-Obfuscator"
    implementationVersion = "1.0"
    apiVersion = PlatformApi.VERSION
    description = "Obfuscation support for Minecraft Fabric mods"
  }

  override fun create(): Platform = Fabric(metadata)

  private class Fabric(metadata: PlatformMetadata) : BasePlatform(metadata) {
    override fun detect(context: PlatformContext): PlatformDetection = when {
      MOD_METADATA in context.resources ->
        DefaultPlatformDetection(100, listOf("$MOD_METADATA found"))

      context.classes.values.any { isEntrypoint(context, it) } ->
        DefaultPlatformDetection(80, listOf("Fabric mod initializer implementation found"))

      else -> DefaultPlatformDetection(0)
    }

    override fun entrypoints(context: PlatformContext, options: PlatformOptions): Set<String> =
      context.classes.values.filter { isEntrypoint(context, it) }.map { it.name }.toSet()

    private fun isEntrypoint(context: PlatformContext, node: ClassNode): Boolean =
      ENTRYPOINT_INTERFACES.any { context.isSubtypeOf(node.name, it) }

    private companion object {
      const val MOD_METADATA = "fabric.mod.json"
      val ENTRYPOINT_INTERFACES = listOf(
        "net.fabricmc.api.ModInitializer",
        "net.fabricmc.api.ClientModInitializer",
        "net.fabricmc.api.DedicatedServerModInitializer",
      )
    }
  }
}
