package com.peco2282.obfaryn.platform.mcmod

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.platformkit.isAnnotatedWith

/**
 * NeoForge mods declare a `@Mod`-annotated class (`net.neoforged.fml.common.Mod`) and a
 * `META-INF/neoforge.mods.toml` descriptor. `@SubscribeEvent` handlers (NeoForge's own event bus)
 * are kept as plain bytecode. The NeoForge-specific annotation and descriptor keep this distinct
 * from [ForgePlatformProvider].
 */
class NeoForgePlatformProvider : PlatformProvider {
  override val metadata: PlatformMetadata = PlatformProvider.metadata {
    id = "neoforge"
    displayName = "Minecraft NeoForge Mod-Obfuscator"
    implementationVersion = "1.0"
    apiVersion = PlatformApi.VERSION
    description = "Obfuscation support for Minecraft NeoForge mods"
  }

  override fun create(): Platform = NeoForge(metadata)

  private class NeoForge(metadata: PlatformMetadata) : BasePlatform(metadata) {
    override val eventHandlerAnnotations = listOf(SUBSCRIBE_EVENT)

    override fun detect(context: PlatformContext): PlatformDetection = when {
      context.classes.values.any { it.isAnnotatedWith(MOD) } ->
        DefaultPlatformDetection(100, listOf("@Mod (NeoForge) class found"))

      NEOFORGE_METADATA in context.resources ->
        DefaultPlatformDetection(90, listOf("$NEOFORGE_METADATA found"))

      else -> DefaultPlatformDetection(0)
    }

    override fun entrypoints(context: PlatformContext, options: PlatformOptions): Set<String> =
      context.classes.values.filter { it.isAnnotatedWith(MOD) }.map { it.name }.toSet()

    private companion object {
      const val MOD = "net.neoforged.fml.common.Mod"
      const val SUBSCRIBE_EVENT = "net.neoforged.bus.api.SubscribeEvent"
      const val NEOFORGE_METADATA = "META-INF/neoforge.mods.toml"
    }
  }
}
