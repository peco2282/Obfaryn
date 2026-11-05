package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.MapPlatformOptions
import com.peco2282.obfaryn.api.PlatformOptions
import com.peco2282.obfaryn.api.transformer.PipelineConfigurer
import com.peco2282.obfaryn.api.transformer.PipelineConfigurerProvider
import java.util.ServiceLoader

/** Explicit pipeline-configurer selection from providers installed on the host's context classloader. */
object PipelineConfigurers {
  fun providers(): List<PipelineConfigurerProvider> =
    ServiceLoader.load(PipelineConfigurerProvider::class.java).toList().also { providers ->
      require(providers.all { it.id.isNotBlank() && it.id != "none" }) { "Invalid pipeline configurer provider ID" }
      require(providers.map { it.id }.distinct().size == providers.size) { "Duplicate pipeline configurer provider IDs" }
    }

  fun select(ids: List<String>, options: PlatformOptions = MapPlatformOptions()): List<PipelineConfigurer> {
    require(ids.distinct().size == ids.size) { "Duplicate pipeline configurer selections" }
    if (ids.isEmpty()) return emptyList()
    val providers = providers().associateBy { it.id }
    return ids.map { id -> (providers[id] ?: error("Unknown pipeline configurer: $id")).create(options) }
  }
}
