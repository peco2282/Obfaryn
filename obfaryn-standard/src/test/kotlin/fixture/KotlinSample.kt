package fixture

data class KotlinData(val text: String, val count: Int = 7)

object KotlinObject {
  fun suffix() = "!"
}

class KotlinSample {
  private fun secret() = ""
  inline fun <reified T> typeName(): String = T::class.java.simpleName
  fun run(prefix: String = "Hello"): String {
    val action: (String) -> String = { "$it World" }
    return action(prefix) + KotlinObject.suffix() + KotlinData("x").count + typeName<Long>() + secret()
  }
}
