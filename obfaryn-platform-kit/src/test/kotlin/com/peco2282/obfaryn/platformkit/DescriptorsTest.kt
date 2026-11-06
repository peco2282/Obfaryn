package com.peco2282.obfaryn.platformkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DescriptorsTest {
  @Test
  fun `object type descriptor`() {
    assertEquals("Ljava/lang/String;", String::class.java.descriptor)
  }

  @Test
  fun `primitive type descriptor`() {
    assertEquals("I", Integer.TYPE.descriptor)
  }

  @Test
  fun `method descriptor matches real parameter and return types`() {
    val method = java.lang.String::class.java.getMethod("substring", Int::class.java, Int::class.java)
    assertEquals("(II)Ljava/lang/String;", method.descriptor)
  }

  @Test
  fun `constructor descriptor matches real parameter types`() {
    val constructor = String::class.java.getConstructor(ByteArray::class.java)
    assertEquals("([B)V", constructor.descriptor)
  }
}
