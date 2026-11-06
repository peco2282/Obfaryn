package com.peco2282.obfaryn.platform.springboot

import com.peco2282.obfaryn.api.Platform
import com.peco2282.obfaryn.api.PlatformApi
import com.peco2282.obfaryn.api.PlatformMetadata
import com.peco2282.obfaryn.api.PlatformProvider
import com.peco2282.obfaryn.api.PlatformProvider.Companion.metadata

class SpringBootPlatformProvider : PlatformProvider {
  override val metadata: PlatformMetadata = metadata {
    id = "springboot"
    displayName = "Spring Boot platform obfuscate"
    implementationVersion = "1.0"
    apiVersion = PlatformApi.VERSION
    description = "Obfuscation support for Spring Boot applications"
  }

  override fun create(): Platform = SpringBootPlatform(metadata)
}
