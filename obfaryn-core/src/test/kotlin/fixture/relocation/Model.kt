package fixture.relocation

open class Model<T : Model<T>>(val value: String) {
  class Nested(val model: Model<*>)
  companion object {
    fun create() = Leaf("ok")
  }
}

class Leaf(value: String) : Model<Leaf>(value)

sealed class Outcome {
  class Success(val model: Leaf) : Outcome()
}

typealias ModelAlias = Leaf

fun identity(model: ModelAlias): ModelAlias = model