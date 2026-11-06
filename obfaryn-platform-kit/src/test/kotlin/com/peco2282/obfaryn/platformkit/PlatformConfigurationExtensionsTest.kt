package com.peco2282.obfaryn.platformkit

import com.peco2282.obfaryn.api.PlatformConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private class RecordingPlatformConfiguration : PlatformConfiguration {
  val keptClasses = mutableListOf<String>()
  val keptClassNamesOnly = mutableListOf<String>()
  val keptMethods = mutableListOf<Triple<String, String, String>>()
  val keptFields = mutableListOf<Pair<String, String>>()
  val excludedFromVirtualization = mutableListOf<Triple<String, String, String>>()

  override fun applyTo(target: PlatformConfiguration) = Unit
  override fun keepClass(name: String) = also { keptClasses += name }
  override fun keepClassNameOnly(name: String) = also { keptClassNamesOnly += name }
  override fun keepPackage(name: String) = this
  override fun keepResource(name: String) = this
  override fun keepMethod(owner: String, name: String, descriptor: String) =
    also { keptMethods += Triple(owner, name, descriptor) }

  override fun keepField(owner: String, name: String) = also { keptFields += owner to name }
  override fun excludeMethodFromVirtualization(owner: String, name: String, descriptor: String, reason: String) =
    also { excludedFromVirtualization += Triple(owner, name, descriptor) }
}

class PlatformConfigurationExtensionsTest {
  private val config = RecordingPlatformConfiguration()

  @Test
  fun `keeps a class by its real type`() {
    config.keepClass(String::class.java)
    assertEquals(listOf("java/lang/String"), config.keptClasses)
  }

  @Test
  fun `keeps a class name only by its real type`() {
    config.keepClassNameOnly(String::class.java)
    assertEquals(listOf("java/lang/String"), config.keptClassNamesOnly)
  }

  @Test
  fun `keeps a method from a real reflected Method`() {
    config.keepMethod(String::class.java.getMethod("length"))
    assertEquals(listOf(Triple("java/lang/String", "length", "()I")), config.keptMethods)
  }

  @Test
  fun `keeps a constructor under the init name`() {
    config.keepConstructor(String::class.java.getConstructor())
    assertEquals(listOf(Triple("java/lang/String", "<init>", "()V")), config.keptMethods)
  }

  @Test
  fun `keeps a field from a real reflected Field`() {
    config.keepField(Holder::class.java.getDeclaredField("count"))
    assertEquals(listOf("com/peco2282/obfaryn/platformkit/PlatformConfigurationExtensionsTest\$Holder" to "count"), config.keptFields)
  }

  @Test
  fun `excludes a method from virtualization by real reflected Method`() {
    config.excludeMethodFromVirtualization(String::class.java.getMethod("length"), "test")
    assertEquals(listOf(Triple("java/lang/String", "length", "()I")), config.excludedFromVirtualization)
  }

  class Holder {
    @JvmField val count: Int = 0
  }
}
