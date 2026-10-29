package fixture.semantics

interface Mode { fun advance(delta: Int = 1): Int }
data class Totals(val ticks: Int, val score: Int)
@JvmInline value class Amount(val value: Int)
enum class Phase { READY, RUNNING, ENDED }

class Session : Mode {
  private var phase = Phase.READY
  private var totals = Totals(0, 0)
  private var effects = 0
  private val label by lazy { "ready" }
  private fun amount(value: Amount = Amount(2)) = value.value
  override fun advance(delta: Int): Int {
    check(phase == Phase.RUNNING) { "update" }
    try {
      require(delta >= 0) { "negative" }
      val (ticks, score) = totals
      val update: (Int) -> Int = { effects++; it + amount() }
      totals = totals.copy(ticks = ticks + delta, score = update(score))
      if (totals.ticks >= 3) phase = Phase.ENDED
      return totals.score
    } finally { effects++ }
  }
  fun apply(op: Int, value: Int): String {
    when (op) {
      0 -> { phase = Phase.RUNNING }
      1 -> advance(value)
      2 -> (this as Mode).advance()
      3 -> { val f = ::advance; f(value) }
      4 -> if (phase == Phase.RUNNING && totals.ticks >= 3) phase = Phase.ENDED
      else -> error("op")
    }
    return snapshot()
  }
  fun snapshot() = "${phase.name}:${totals.ticks}:${totals.score}:$effects:$label"
}
