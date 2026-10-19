package com.peco2282.obfaryn.platform.paper

import com.peco2282.obfaryn.api.Platform
import com.peco2282.obfaryn.api.PlatformApi
import com.peco2282.obfaryn.api.PlatformMetadata
import com.peco2282.obfaryn.api.PlatformProvider
import com.peco2282.obfaryn.api.PlatformProvider.Companion.metadata

class PaperPlatformProvider : PlatformProvider {
  override val metadata: PlatformMetadata = metadata {
    id = "paper"
    displayName = "Paper platform obfuscate"
    implementationVersion = "1.0"
    apiVersion = PlatformApi.VERSION
    description = "Obfuscation support for Paper plugins"
  }

  override fun create(): Platform = PaperPlatform(metadata)
}
