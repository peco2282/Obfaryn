package com.peco2282.obfaryn.platform.paper

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.transformer.MemberKey
import com.peco2282.obfaryn.platformkit.isAnnotatedWith
import com.peco2282.obfaryn.platformkit.isSubtypeOf
import com.peco2282.obfaryn.platformkit.methodCallClosure
import org.objectweb.asm.tree.ClassNode

class PaperPlatform(override val metadata: PlatformMetadata) : Platform {
  override fun extensions(): Collection<PlatformExtension> = listOf(PaperCacheContributor())

  override fun detect(context: PlatformContext): PlatformDetection = when {
    context.resources.containsKey("paper-plugin.yml") ->
      DefaultPlatformDetection(100, listOf("paper-plugin.yml found"))

    context.resources.containsKey("plugin.yml") ->
      DefaultPlatformDetection(80, listOf("plugin.yml found"))

    else -> DefaultPlatformDetection(0)
  }

  override fun entrypoints(context: PlatformContext, options: PlatformOptions): Set<String> = buildSet {
    for (path in listOf("plugin.yml", "paper-plugin.yml")) {
      val data = context.resources[path]?.data ?: continue
      val yaml = data.toString(Charsets.UTF_8)
      for (line in yaml.lineSequence()) {
        val match = Regex("^(main|bootstrapper|loader):\\s*['\"]?([^\\s#'\"]+)").find(line) ?: continue
        add(match.groupValues[2].replace('.', '/'))
      }
    }
  }

  override fun configure(context: PlatformContext, options: PlatformOptions, target: PlatformConfiguration) {
    for (path in listOf("plugin.yml", "paper-plugin.yml")) if (path in context.resources) target.keepResource(path)
    entrypoints(context, options).forEach(target::keepClassNameOnly)
    configurePerformanceSensitiveMethods(context, options, target)
  }

  private fun configurePerformanceSensitiveMethods(
    context: PlatformContext,
    options: PlatformOptions,
    config: PlatformConfiguration,
  ) {
    if (!options.boolean("virtualization-protect-hot-paths", true)) return

    val entrypoints = entrypoints(context, options)
    val applicationRoots =
      entrypoints.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) }.distinct()
    val inScope: (ClassNode) -> Boolean = { owner ->
      applicationRoots.isEmpty() || applicationRoots.any { owner.name == it || owner.name.startsWith("$it/") }
    }

    val reasons = linkedMapOf<MemberKey, String>()
    for (owner in context.classes.values.filter(inScope)) for (method in owner.methods) {
      val key = MemberKey(owner.name, method.name, method.desc)
      val reason = when {
        method.isAnnotatedWith("org.bukkit.event.EventHandler") -> "paper event callback"
        key.desc == "()V" && key.name == "run" &&
          (context.isSubtypeOf(key.owner, "org.bukkit.scheduler.BukkitRunnable") ||
            context.isSubtypeOf<Runnable>(key.owner)) -> "paper scheduled callback"

        key.owner in entrypoints && context.isSubtypeOf(key.owner, "org.bukkit.plugin.java.JavaPlugin") &&
          key.desc == "()V" && key.name in setOf("onLoad", "onEnable", "onDisable") ->
          "paper plugin lifecycle"

        else -> null
      }
      if (reason != null) reasons[key] = reason
    }

    for (method in context.methodCallClosure(reasons.keys, inScope)) {
      config.excludeMethodFromVirtualization(
        method.owner, method.name, method.desc,
        reasons[method] ?: "paper hot callback dependency",
      )
    }
  }
}
