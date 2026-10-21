package fixture.relocation

open class Model<T : Model<T>>(val value: String) {
  class Nested(val model: Model<*>) {
    class Deep(val model: Model<*>)
  }
  inner class Inner(val text: String)
  data class Snapshot(val text: String)
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

class NamedCompanion {
  class Child
  companion object a
}
