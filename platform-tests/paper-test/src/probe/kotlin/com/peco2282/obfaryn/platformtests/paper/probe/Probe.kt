package com.peco2282.obfaryn.platformtests.paper.probe

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Compiled into its own source set, never bundled into the fixture jar that gets obfuscated, so
 * it keeps its original name regardless of what the obfuscated fixture's internal class/method
 * names become. The fixture calls into this to report observable side effects that the test can
 * assert on without ever referencing a renamed symbol.
 */
object Probe {
  val events: MutableList<String> = CopyOnWriteArrayList()

  fun record(event: String) {
    events += event
  }

  fun reset() {
    events.clear()
  }
}
