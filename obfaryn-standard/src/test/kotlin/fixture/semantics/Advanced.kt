package fixture.semantics

/** Sealed class hierarchy; `when` over it stays exhaustive after transformation. */
sealed class Event {
  data class Clicked(val id: Int) : Event()
  data class Scrolled(val delta: Int) : Event()
  object Reset : Event()
}

/** Inline function with a non-local return and an extension receiver. */
inline fun List<Int>.firstEvenOrDefault(default: Int): Int {
  for (value in this) if (value % 2 == 0) return value
  return default
}

/** Extension function on a type outside this file. */
fun Int.doubledPlus(extra: Int = 1): Int = this * 2 + extra

/** Object declaration: a true JVM singleton with a `fun` and mutable state. */
object Registry {
  private var calls = 0
  fun register(name: String): Int { calls++; return calls }
  fun callCount(): Int = calls
}

class Workshop {
  // Companion object: factory function and a shared counter visible via the owning class name.
  companion object {
    private var created = 0
    fun create(label: String): Workshop { created++; return Workshop(label) }
    fun createdCount(): Int = created
  }

  val label: String
  constructor(label: String) { this.label = label }

  // Named arguments reorder relative to declaration; default arguments fill the rest.
  fun describe(prefix: String = "<", suffix: String = ">", upper: Boolean = false): String {
    val body = if (upper) label.uppercase() else label
    return prefix + body + suffix
  }

  fun dispatch(event: Event): String = when (event) {
    is Event.Clicked -> "clicked:${event.id}"
    is Event.Scrolled -> "scrolled:${event.delta}"
    Event.Reset -> "reset"
  }

  fun run(op: Int, arg: Int): String = when (op) {
    0 -> listOf(1, 3, 5, arg).firstEvenOrDefault(-1).toString()
    1 -> arg.doubledPlus(extra = 7).toString()
    2 -> arg.doubledPlus().toString()
    3 -> dispatch(Event.Clicked(arg))
    4 -> dispatch(Event.Scrolled(arg))
    5 -> dispatch(Event.Reset)
    6 -> describe(suffix = "]", prefix = "[")
    7 -> Registry.register(label).toString() + ":" + Registry.callCount()
    8 -> Phase.entries.joinToString(",") { it.name } + ":" + Phase.entries.size
    9 -> createdCount().toString()
    10 -> { create(label); createdCount().toString() }
    else -> error("op")
  }
}
