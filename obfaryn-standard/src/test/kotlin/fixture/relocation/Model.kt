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

interface ScoreLimit {
  val maximum: Int
}

enum class KotlinScore(override val maximum: Int, val label: String) : ScoreLimit {
  BALL(4, "ball"), STRIKE(3, "strike");
  fun lamps(count: Int): String = label.repeat(count.coerceAtMost(maximum))
  companion object {
    fun find(name: String): KotlinScore? = entries.firstOrNull { it.label == name }
  }
}

class KotlinCounter(var count: Int) {
  fun add(amount: Int): Int = count + amount
  fun defaultAdd(amount: Int = 1): Int = add(amount)
  fun run(): String {
    count = defaultAdd()
    return KotlinScore.find("ball")!!.lamps(count)
  }
}

class KotlinReferenced(var value: Int) {
  fun read(): Int = value
  fun propertyReference() = this::value
  fun functionReference() = this::read
}

class KotlinCompanionFields {
  companion object {
    const val SECRET_VALUE = 17
    private val payload = "payload"
    fun readPayload() = payload
  }
}
