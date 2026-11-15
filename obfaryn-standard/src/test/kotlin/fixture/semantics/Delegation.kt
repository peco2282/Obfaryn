package fixture.semantics

import kotlin.properties.Delegates
import kotlin.reflect.KProperty

/**
 * Property delegation and a type-safe DSL builder: constructs the other `fixture.semantics` fixtures
 * do not exercise. Each kind produces synthetic shapes the transformer must preserve -- `lazy`'s
 * synchronized init holder, `observable`/`vetoable` change callbacks, a custom `getValue`/`setValue`
 * operator pair bound through `KProperty`, map-backed properties keyed by property name, and a
 * `@DslMarker` receiver-lambda builder. `run(op, arg)` is the deterministic differential entry point.
 */

/** Custom delegate: stores twice the assigned value, bound via the operator convention + KProperty. */
class Doubling {
  private var stored = 0
  operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = stored
  operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { stored = value * 2 }
}

@DslMarker
annotation class ReportDsl

/** Type-safe builder: a receiver lambda scope with a nested receiver-lambda section. */
@ReportDsl
class ReportBuilder {
  private val sb = StringBuilder()
  fun line(text: String) { sb.append(text).append(';') }
  fun section(title: String, block: ReportBuilder.() -> Unit) {
    sb.append('[').append(title).append(':')
    this.block()
    sb.append(']')
  }
  fun build(): String = sb.toString()
}

fun report(block: ReportBuilder.() -> Unit): String = ReportBuilder().apply(block).build()

class DelegationWorkshop(private val session: String) {
  private val changeLog = StringBuilder()

  /** `by lazy`: a synchronized init holder, initialized once on first access. */
  val lazyValue: String by lazy { "lazy:" + session.length }

  /** `Delegates.observable`: records each transition into the change log. */
  var observed: Int by Delegates.observable(0) { _, old, new ->
    changeLog.append(old).append("->").append(new).append(';')
  }

  /** `Delegates.vetoable`: rejects negative assignments, keeping the previous value. */
  var checked: Int by Delegates.vetoable(0) { _, _, new -> new >= 0 }

  /** Custom operator delegate. */
  var doubled: Int by Doubling()

  private val attributes: Map<String, Any?> = mapOf("name" to session, "level" to 3)
  /** Map-backed delegated properties, keyed by the property name. */
  val name: String by attributes
  val level: Int by attributes

  fun run(op: Int, arg: Int): String = when (op) {
    0 -> lazyValue
    1 -> { observed = arg; changeLog.toString() }
    2 -> { checked = arg; checked.toString() }
    3 -> { doubled = arg; doubled.toString() }
    4 -> "$name/$level"
    5 -> report {
      line("a")
      section("s") { line("b"); line(arg.toString()) }
    }
    6 -> lazyValue + "|" + name + "/" + level
    else -> error("op")
  }
}
