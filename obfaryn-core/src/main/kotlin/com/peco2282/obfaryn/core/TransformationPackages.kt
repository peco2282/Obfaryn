package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.assembly.*
import java.util.ServiceLoader

/** Explicit package selection from providers installed on the host's context classloader. */
object TransformationPackages {
  fun providers(): List<TransformationPackageProvider> =
    ServiceLoader.load(TransformationPackageProvider::class.java).toList().also { providers ->
      require(providers.all { it.id.isNotBlank() && it.id != "none" }) { "Invalid package provider ID" }
      require(providers.map { it.id }.distinct().size == providers.size) { "Duplicate package provider IDs" }
    }

  fun select(ids: List<String>, options: PlatformOptions = MapPlatformOptions()): List<TransformationPackage> {
    require(ids.distinct().size == ids.size) { "Duplicate package selections" }
    if (ids.isEmpty()) return emptyList()
    val providers = providers().associateBy { it.id }
    return ids.map { id -> (providers[id] ?: error("Unknown transformation package: $id")).create(options) }
  }
}
