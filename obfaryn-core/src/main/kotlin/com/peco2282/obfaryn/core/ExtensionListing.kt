package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.platform.Platforms

/**
 * Human-readable listing of every platform, transformation-package and pipeline-configurer provider
 * the host's ServiceLoader currently sees. Call this from inside [withExtensionClassLoader] (or any
 * other classloader scanning a given set of extension JARs) so the listing reflects that
 * classloader's providers; this does not itself load or construct a classloader.
 *
 * Shared by every host discovery entry point (CLI `list-extensions`, Gradle `listObfarynExtensions`)
 * so the three sections stay in sync as a single implementation.
 */
object ExtensionListing {
  fun lines(): List<String> {
    val lines = mutableListOf<String>()
    lines += "Platforms:"
    for (platform in Platforms.all().sortedBy { it.id }) {
      lines += "  ${platform.id} - ${platform.metadata.displayName} (API ${platform.metadata.apiVersion}, impl ${platform.metadata.implementationVersion})"
    }
    lines += "Transformation packages:"
    val packages = TransformationPackages.providers().sortedBy { it.id }
    if (packages.isEmpty()) lines += "  (none found; scan an extension JAR for a provider)"
    packages.forEach { lines += "  ${it.id}" }
    lines += "Pipeline configurers:"
    val configurers = PipelineConfigurers.providers().sortedBy { it.id }
    if (configurers.isEmpty()) lines += "  (none found; scan an extension JAR for a provider)"
    configurers.forEach { lines += "  ${it.id}" }
    return lines
  }
}
