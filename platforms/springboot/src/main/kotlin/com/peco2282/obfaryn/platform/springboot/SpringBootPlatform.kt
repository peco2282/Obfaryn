package com.peco2282.obfaryn.platform.springboot

import com.peco2282.obfaryn.api.DefaultPlatformDetection
import com.peco2282.obfaryn.api.Platform
import com.peco2282.obfaryn.api.PlatformConfiguration
import com.peco2282.obfaryn.api.PlatformContext
import com.peco2282.obfaryn.api.PlatformDetection
import com.peco2282.obfaryn.api.PlatformMetadata
import com.peco2282.obfaryn.api.PlatformOptions
import com.peco2282.obfaryn.api.transformer.MemberKey
import com.peco2282.obfaryn.platformkit.isAnnotatedWith
import com.peco2282.obfaryn.platformkit.methodCallClosure
import org.objectweb.asm.tree.ClassNode
import java.util.jar.Manifest

/**
 * Obfuscation support for Spring Boot applications.
 *
 * Detection and entrypoint discovery work from the compiled classes and `META-INF/MANIFEST.MF`
 * alone, so this platform does not need the actual Spring Boot/Framework jars on the classpath
 * and does not unpack a Spring Boot executable jar's nested `BOOT-INF/classes`/`BOOT-INF/lib`
 * layout; point the host at an already-exploded set of classes, or a plain (non-fat) jar.
 */
class SpringBootPlatform(override val metadata: PlatformMetadata) : Platform {
  override fun detect(context: PlatformContext): PlatformDetection = when {
    context.classes.values.any { it.isAnnotatedWith(SPRING_BOOT_APPLICATION) } ->
      DefaultPlatformDetection(100, listOf("@SpringBootApplication class found"))

    startClass(context) != null ->
      DefaultPlatformDetection(60, listOf("MANIFEST.MF Start-Class found"))

    else -> DefaultPlatformDetection(0)
  }

  override fun entrypoints(context: PlatformContext, options: PlatformOptions): Set<String> = buildSet {
    context.classes.values.filter { it.isAnnotatedWith(SPRING_BOOT_APPLICATION) }.forEach { add(it.name) }
    startClass(context)?.let { add(it.replace('.', '/')) }
  }

  override fun configure(context: PlatformContext, options: PlatformOptions, target: PlatformConfiguration) {
    entrypoints(context, options).forEach(target::keepClassNameOnly)
    configurePerformanceSensitiveMethods(context, options, target)
  }

  private fun startClass(context: PlatformContext): String? =
    context.resources["META-INF/MANIFEST.MF"]?.let { Manifest(it.data.inputStream()).mainAttributes.getValue("Start-Class") }

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
        method.isAnnotatedWith(SCHEDULED) -> "spring scheduled task"
        method.isAnnotatedWith(EVENT_LISTENER) -> "spring event listener"
        method.isAnnotatedWith(ASYNC) -> "spring async method"
        key.owner in entrypoints && key.name == "main" && key.desc == "([Ljava/lang/String;)V" ->
          "spring boot application entrypoint"

        else -> null
      }
      if (reason != null) reasons[key] = reason
    }

    for (method in context.methodCallClosure(reasons.keys, inScope)) {
      config.excludeMethodFromVirtualization(
        method.owner, method.name, method.desc,
        reasons[method] ?: "spring hot callback dependency",
      )
    }
  }

  private companion object {
    const val SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication"
    const val SCHEDULED = "org.springframework.scheduling.annotation.Scheduled"
    const val EVENT_LISTENER = "org.springframework.context.event.EventListener"
    const val ASYNC = "org.springframework.scheduling.annotation.Async"
  }
}
