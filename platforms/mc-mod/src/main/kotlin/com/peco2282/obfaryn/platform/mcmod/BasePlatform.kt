package com.peco2282.obfaryn.platform.mcmod

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.transformer.MemberKey
import com.peco2282.obfaryn.platformkit.isAnnotatedWith
import com.peco2282.obfaryn.platformkit.methodCallClosure
import org.objectweb.asm.tree.ClassNode

/**
 * Shared behavior for the Minecraft mod-loader platforms (Fabric, Forge, NeoForge).
 *
 * Detection and entrypoint discovery work from the compiled classes and the loader's own metadata
 * resource (`fabric.mod.json`, `META-INF/mods.toml`, `META-INF/neoforge.mods.toml`) without the
 * loader jars on the classpath. Each loader resolves its mod/entrypoint classes by the names written
 * in those resources, so [configure] keeps the resources verbatim and keeps the entrypoint class
 * names. A loader whose hot callbacks are annotation-driven (Forge/NeoForge's `@SubscribeEvent`)
 * declares that annotation in [eventHandlerAnnotations]; those handlers and the methods they call are
 * kept as plain bytecode. Nothing here infers a hot path from a method name such as `tick`.
 */
abstract class BasePlatform(override val metadata: PlatformMetadata) : Platform {
  /** Annotations marking a performance-sensitive event handler for this loader, if it has any. */
  protected open val eventHandlerAnnotations: List<String> = emptyList()

  override fun configure(context: PlatformContext, options: PlatformOptions, target: PlatformConfiguration) {
    context.resources.keys.forEach(target::keepResource)
    entrypoints(context, options).forEach(target::keepClassNameOnly)
    configurePerformanceSensitiveMethods(context, options, target)
  }

  private fun configurePerformanceSensitiveMethods(
    context: PlatformContext,
    options: PlatformOptions,
    config: PlatformConfiguration,
  ) {
    if (eventHandlerAnnotations.isEmpty()) return
    if (!options.boolean("virtualization-protect-hot-paths", true)) return

    val entrypoints = entrypoints(context, options)
    val applicationRoots =
      entrypoints.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) }.distinct()
    val inScope: (ClassNode) -> Boolean = { owner ->
      applicationRoots.isEmpty() || applicationRoots.any { owner.name == it || owner.name.startsWith("$it/") }
    }

    val reasons = linkedMapOf<MemberKey, String>()
    for (owner in context.classes.values.filter(inScope)) for (method in owner.methods) {
      if (eventHandlerAnnotations.any(method::isAnnotatedWith)) {
        reasons[MemberKey(owner.name, method.name, method.desc)] = "minecraft mod event handler"
      }
    }
    for (method in context.methodCallClosure(reasons.keys, inScope)) {
      config.excludeMethodFromVirtualization(
        method.owner, method.name, method.desc,
        reasons[method] ?: "minecraft mod hot callback dependency",
      )
    }
  }
}
