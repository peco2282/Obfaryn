package com.peco2282.obfaryn.platformkit

import com.peco2282.obfaryn.api.transformer.MemberKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MemberKeyTest {
  @Test
  fun `method member key uses the declaring class' internal name`() {
    val method = String::class.java.getMethod("substring", Int::class.java, Int::class.java)
    assertEquals(MemberKey("java/lang/String", "substring", "(II)Ljava/lang/String;"), method.memberKey)
  }

  @Test
  fun `constructor member key uses the JVM's own init name`() {
    val constructor = String::class.java.getConstructor(ByteArray::class.java)
    assertEquals(MemberKey("java/lang/String", "<init>", "([B)V"), constructor.memberKey)
  }

  @Test
  fun `field member key uses its type's descriptor`() {
    val field = Holder::class.java.getDeclaredField("count")
    assertEquals(MemberKey("com/peco2282/obfaryn/platformkit/MemberKeyTest\$Holder", "count", "I"), field.memberKey)
  }

  class Holder {
    @JvmField val count: Int = 0
  }
}
