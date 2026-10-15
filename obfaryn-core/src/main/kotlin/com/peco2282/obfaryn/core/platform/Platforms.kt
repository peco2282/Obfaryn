package com.peco2282.obfaryn.core.platform

import com.peco2282.obfaryn.api.*
import java.util.ServiceLoader

class GenericPlatform : Platform {
  override val metadata: PlatformMetadata = DefaultPlatformMetadata("generic", "Generic", "builtin")
  override fun detect(context: PlatformContext): PlatformDetection =
    DefaultPlatformDetection(1, listOf("generic fallback"))
}

object Platforms {
  fun all(): List<Platform> = ServiceLoader.load(PlatformProvider::class.java).map { provider ->
      require(provider.metadata.apiVersion == PlatformApi.VERSION) {
        "Platform '${provider.metadata.id}' requires API ${provider.metadata.apiVersion}, supported API is ${PlatformApi.VERSION}"
      }
      provider.create().also { platform ->
        require(platform.metadata.id == provider.metadata.id) { "Platform provider metadata ID does not match implementation ID" }
      }
    } + GenericPlatform()

  fun select(id: String, context: PlatformContext): Platform = select(id, context, all())

  internal fun select(id: String, context: PlatformContext, platforms: List<Platform>): Platform {
    require(platforms.map { it.id }.distinct().size == platforms.size) { "Duplicate platform IDs" }
    require(platforms.none { it.id.isBlank() || it.id == "auto" }) { "Invalid platform ID" }
    require(platforms.all { it.metadata.apiVersion == PlatformApi.VERSION }) { "Unsupported platform API version" }
    return if (id == "auto") {
      val detected = platforms.map { it to it.detect(context) }.filter { it.second.detected }
      val confidence = detected.maxOfOrNull { it.second.confidence } ?: error("No platform detected")
      val best = detected.filter { it.second.confidence == confidence }
      require(best.size == 1) { "Ambiguous platform detection: ${best.joinToString { it.first.id }}" }
      best.single().first
    }
    else platforms.firstOrNull { it.id == id } ?: error("Unknown platform: $id")
  }
}
