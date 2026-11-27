package com.peco2282.obfaryn.platform.mcmod

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.platformkit.isAnnotatedWith

/**
 * Forge mods declare a `@Mod`-annotated class (`net.minecraftforge.fml.common.Mod`) and a
 * `META-INF/mods.toml` descriptor. `@SubscribeEvent` handlers are kept as plain bytecode. Detection
 * from `mods.toml` alone is reported only when the NeoForge descriptor is absent, so a NeoForge
 * archive is not misread as Forge.
 *
 * `SubscribeEvent` moved package between eventbus releases: `net.minecraftforge.eventbus.api`
 * (eventbus up to 6.x) and `net.minecraftforge.eventbus.api.listener` (eventbus 7.x+). Both are
 * recognized so mods built against either still have their event handlers excluded correctly.
 */
class ForgePlatformProvider : PlatformProvider {
  override val metadata: PlatformMetadata = PlatformProvider.metadata {
    id = "forge"
    displayName = "Minecraft Forge Mod-Obfuscator"
    implementationVersion = "1.0"
    apiVersion = PlatformApi.VERSION
    description = "Obfuscation support for Minecraft Forge mods"
  }

  override fun create(): Platform = Forge(metadata)

  private class Forge(metadata: PlatformMetadata) : BasePlatform(metadata) {
    override val eventHandlerAnnotations = listOf(SUBSCRIBE_EVENT, SUBSCRIBE_EVENT_LISTENER)

    override fun detect(context: PlatformContext): PlatformDetection = when {
      context.classes.values.any { it.isAnnotatedWith(MOD) } ->
        DefaultPlatformDetection(100, listOf("@Mod (Forge) class found"))

      MOD_METADATA in context.resources && NEOFORGE_METADATA !in context.resources ->
        DefaultPlatformDetection(70, listOf("$MOD_METADATA found"))

      else -> DefaultPlatformDetection(0)
    }

    override fun entrypoints(context: PlatformContext, options: PlatformOptions): Set<String> =
      context.classes.values.filter { it.isAnnotatedWith(MOD) }.map { it.name }.toSet()

    private companion object {
      const val MOD = "net.minecraftforge.fml.common.Mod"
      const val SUBSCRIBE_EVENT = "net.minecraftforge.eventbus.api.SubscribeEvent"
      const val SUBSCRIBE_EVENT_LISTENER = "net.minecraftforge.eventbus.api.listener.SubscribeEvent"
      const val MOD_METADATA = "META-INF/mods.toml"
      const val NEOFORGE_METADATA = "META-INF/neoforge.mods.toml"
    }
  }
}
