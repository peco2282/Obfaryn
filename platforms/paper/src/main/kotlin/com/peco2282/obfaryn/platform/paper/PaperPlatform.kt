package com.peco2282.obfaryn.platform.paper

import com.peco2282.obfaryn.api.*
import org.objectweb.asm.tree.MethodInsnNode

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
    data class MethodKey(val owner: String, val name: String, val descriptor: String)

    val entrypoints = entrypoints(context, options)
    val applicationRoots =
      entrypoints.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) }.distinct()
    val methods = context.classes.values.filter { owner ->
      applicationRoots.isEmpty() || applicationRoots.any { owner.name == it || owner.name.startsWith("$it/") }
    }.flatMap { owner ->
      owner.methods.map { method -> MethodKey(owner.name, method.name, method.desc) to method }
    }.toMap()

    fun inherits(owner: String, target: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
      if (!visited.add(owner)) return false
      if (owner == target) return true
      val node = context.classes[owner] ?: return false
      return listOfNotNull(node.superName).plus(node.interfaces.orEmpty())
        .any { it == target || inherits(it, target, visited) }
    }

    val reasons = linkedMapOf<MethodKey, String>()
    for ((key, method) in methods) {
      val annotations = method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()
      val reason = when {
        annotations.any { it.desc == "Lorg/bukkit/event/EventHandler;" } -> "paper event callback"
        key.descriptor == "()V" && key.name in setOf("tick", "onTick") -> "paper tick callback"
        key.descriptor == "()V" && key.name == "run" &&
          (inherits(key.owner, "org/bukkit/scheduler/BukkitRunnable") || inherits(key.owner, "java/lang/Runnable")) ->
          "paper scheduled callback"

        key.owner in entrypoints && key.descriptor == "()V" && key.name in setOf("onLoad", "onEnable", "onDisable") ->
          "paper plugin lifecycle"

        else -> null
      }
      if (reason != null) reasons[key] = reason
    }
    val queue = ArrayDeque(reasons.keys)
    while (queue.isNotEmpty()) {
      val caller = queue.removeFirst()
      val method = methods.getValue(caller)
      for (instruction in method.instructions) {
        val invoke = instruction as? MethodInsnNode ?: continue
        if (invoke.name.startsWith("<")) continue
        val target = MethodKey(invoke.owner, invoke.name, invoke.desc)
        if (target in methods && target !in reasons) {
          reasons[target] = "paper hot callback dependency"
          queue.addLast(target)
        }
      }
    }
    for ((method, reason) in reasons) {
      config.excludeMethodFromVirtualization(method.owner, method.name, method.descriptor, reason)
    }
  }
}
