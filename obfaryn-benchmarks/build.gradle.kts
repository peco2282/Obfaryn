// JMH micro-benchmarks for individual obfuscated-code-path overheads (steady-state, JIT'd cost),
// kept separate in purpose from the synthetic end-to-end/fork-based scripts under
// scripts/performance/: those isolate VM-virtualization dispatch cost (virtualization.py) and a
// whole-tick workload across original/protected/prepared/hotpath variants (run.py) with explicit
// multi-fork, warmup and percentile handling this module does not duplicate. See docs/performance.md.
plugins {
  `java-library`
  kotlin("jvm")
  id("me.champeau.jmh")
}

repositories { mavenCentral() }

dependencies {
  // Main (Kotlin): builds/obfuscates the fixture once per trial via the public Obfuscator API.
  // The `jmh` source set (Java `@Benchmark` classes) sees this automatically as its own dependency.
  implementation(project(":obfaryn-standard"))
}

jmh {
  jmhVersion.set(libs.versions.jmh)
  // Defaults favor CI-reasonable runtime; override on the command line for a real measurement run,
  // e.g. `./gradlew :obfaryn-benchmarks:jmh -Pjmh.iterations=10 -Pjmh.warmupIterations=5 -Pjmh.fork=3`.
  fork.set((project.findProperty("jmh.fork") as String?)?.toInt() ?: 2)
  warmupIterations.set((project.findProperty("jmh.warmupIterations") as String?)?.toInt() ?: 3)
  iterations.set((project.findProperty("jmh.iterations") as String?)?.toInt() ?: 5)
  benchmarkMode.set(listOf("AverageTime"))
  timeUnit.set("ns")
  // GC/allocation profiling for the allocation-sensitive benchmarks; harmless for the others.
  profilers.set(listOf("gc"))
  resultFormat.set("JSON")
  resultsFile.set(project.layout.buildDirectory.file("results/jmh/results.json"))
  humanOutputFile.set(project.layout.buildDirectory.file("results/jmh/human.txt"))
}
