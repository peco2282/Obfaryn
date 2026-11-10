# Performance measurement

These benchmarks are synthetic and host-sensitive. They establish relative, repeatable numbers for
regression tracking on a fixed machine; they are not Paper/Spring Boot server guarantees and do not
establish absolute throughput for any real application. Run them apart from builds and tests, and
treat small deltas on shared CI runners as noise.

Four kinds of measurement exist here, each for a different purpose; none duplicates another:

- `obfaryn-benchmarks` (JMH): steady-state, JIT-warmed per-call overhead of one transformation pass
  at a time, against the same unobfuscated method.
- `scripts/performance/run.py`: a whole synthetic tick workload across `original`/`protected`/
  `prepared`/`hotpath` variants, multi-fork, with percentiles.
- `scripts/performance/virtualization.py`: isolated VM-dispatch cost (virtualized vs plain
  bytecode), multi-fork, with percentiles and an allocation-per-call figure.
- `scripts/performance/process.py`: the obfuscation engine's own process footprint -- wall/CPU
  time, peak RSS, GC activity -- not the output jar's runtime behavior.

Every `results.json` these write now also carries `os`, `cpu`, `cpuCount` and `gitCommit`
(from `scripts/performance/environment.py`, or JMH's own `# VM version` line) so a result can be
traced back to the machine and commit that produced it.

## Per-call overhead — `obfaryn-benchmarks` (JMH)

A JMH module measuring one transformation pass's isolated per-call cost: `@Setup(Level.Trial)`
obfuscates a small fixture (`bench.Workload`) once per trial with exactly one pass enabled
(string encryption, number obfuscation, lightweight control flow, call indirection, or call
indirection + prelink), binds the original and transformed method as a [`MethodHandle`] via
`unreflect`, and each `@Benchmark` calls it through `invokeExact` with a JMH `Blackhole` sink so
the JIT cannot eliminate the call (`PassOverheadBenchmark`). `ReflectionBenchmark` separately
measures a direct call vs. `MethodHandle#invokeExact` vs. `Method#invoke`, which is the floor the
other benchmarks' choice of `invokeExact` is read against. `VmShapeBenchmark` measures
VM-virtualization overhead on 4 instruction shapes — an ordinary method call, array access,
allocation (boxing plus array/`Object[]` construction), and exception handling (both the
non-throwing and throwing path) — built the same way `virtualization.py` builds its `hot`/`tiny`
pair (every other pass off in both jars, so the only difference per pair is whether that one
method is virtualized). Renaming is explicitly disabled in the fixture config so every variant
resolves the same class/method name without needing a mapping file.

This does not replace VM-dispatch percentile/variance reporting across JVM forks
(`virtualization.py` already does that, for 2 instruction shapes) or a whole tick workload
(`run.py` already does that); it exists to isolate one pass's — or, for `VmShapeBenchmark`, one
instruction shape's — per-call cost under JIT-warmed, steady-state conditions, which neither of
those two scripts is designed to report, and across more shapes than `virtualization.py`'s `hot`/
`tiny` pair.

```bash
./gradlew :obfaryn-benchmarks:jmhJar
# Prefer running the self-contained fat jar directly; see the note below.
java -jar obfaryn-benchmarks/build/libs/obfaryn-benchmarks-*-jmh.jar -f 2 -wi 3 -i 5
```

**Known issue — run the jar directly, not `./gradlew :obfaryn-benchmarks:jmh`.** In this
repository's environment, invoking the `jmh` Gradle task directly fails every benchmark with
`NoSuchMethodError: ClassWriter.hasFlags` from `org.objectweb.asm.util.CheckMethodAdapter`
(`--verify-output`'s bytecode verification step), even though `:obfaryn-benchmarks:dependencies
--configuration jmhRuntimeClasspath` resolves `org.ow2.asm:*` to a consistent `9.11` throughout.
Building `jmhJar` and running the resulting fat jar directly with `java -jar` (JMH's own documented
primary invocation, not a workaround specific to this project) runs cleanly. This points at
Gradle's own worker-process classloading rather than anything in `obfaryn-benchmarks`'s declared
dependencies, but has not been root-caused further; re-check it if the Gradle/JMH-plugin version
changes.

A short (`-wi 2 -i 3 -w 300ms -r 300ms`, one fork) run on this JDK 21 Temurin host gave (ns/op,
noisy at this iteration length — treat only the order of magnitude and direction as indicative,
not the precise values):

| Benchmark | Original | Transformed |
| --- | --- | --- |
| String encryption | ~15.5 | ~24.6 |
| Number obfuscation | ~4.0 | ~5.5 |
| Control flow (light) | ~6.2 | ~7.7 |
| Call indirection | ~4.1 | ~3.6 |
| Prelink | ~4.1 | ~3.8 |
| Reflection: direct / `MethodHandle#invokeExact` / `Method#invoke` | 0.4 / 3.7 / 7.1 | — |

`VmShapeBenchmark` (same short run) — virtualization's interpreter overhead is large and shape-dependent, as expected; `exceptionHandlingVirtualized` only covers the non-throwing path, `*Throwing` the throwing one (JVM exception fill-in-stack-trace cost dominates there regardless of virtualization):

| Shape | Original (ns) | Virtualized (ns) |
| --- | --- | --- |
| Method call | ~5 | ~100–130 |
| Array access | ~5 | ~1300–1850 |
| Allocation | ~5 | ~600–3500 |
| Exception handling (no throw) | ~5 | ~80–110 |
| Exception handling (throwing) | ~1400–2300 | ~2200–2900 |

These figures came from 1 fork, 1–2 iterations of 200–300ms each — enough to confirm the benchmarks run correctly and that virtualized array/allocation shapes cost far more than the `hot`/`tiny` pair's ~14×/~2.3× (consistent with array/allocation bridging more JDK calls per op than a tight integer loop), not enough to treat the specific multiples as stable. A longer run is needed before drawing conclusions about which shape to prioritize for Phase 3's allocation work.

Call indirection and prelink show no measurable overhead at this scale — expected, since once the
`invokedynamic` call site is linked (prelink forces this up front; call indirection links lazily on
first call) a steady-state call is an ordinary `MethodHandle` invocation, not meaningfully different
from the direct call it replaced. String encryption's decode cost (Base64 decode + a per-byte XOR
loop) does not amortize away, since it is not cached: each call to the obfuscated method re-runs
it. A longer, multi-fork run (`-f 3 -wi 5 -i 10`, no explicit `-w`/`-r`, ~15–20 minutes) is needed
before treating any of these as more than a smoke-test-scale sanity check; this was not run here.

## Synthetic tick workload — `scripts/performance/run.py`

Compiles a Java 8 tick workload, transforms it (strong protection, control flow, call indirection,
integer arithmetic), and compares `original`, `protected`, `prepared` (prelinked) and `hotpath`
(a class-level `@HotPath` control group) over several JVM forks. Reports cold invocation, warm
latency percentiles, per-tick allocation, and a bit-identical checksum across variants. Writes
`build/performance/results.json`. Optional local gates: `--max-p99-ratio`, `--max-extra-bytes-per-tick`.

## Virtualization dispatch — `scripts/performance/virtualization.py`

Isolates the interpreter cost of method virtualization. Both jars run through the engine with every
ordinary pass disabled; the only difference is that the measured method is virtualized in one and
left as plain bytecode in the other. Two targets are measured:

- `hot` — a fixed, dispatch-heavy integer loop, so a call's cost is dominated by VM instruction
  dispatch/decode.
- `tiny` — a few operations, so a call's cost is dominated by per-invocation plumbing (frame
  acquire/release, thread-local pool, argument marshalling, return boxing).

`VmBenchmark.java` reports cold first call, warm p50/p95/p99, per-call thread allocation, and a
checksum that must match between the original and virtualized jars (behavioral equivalence guard).
Writes `build/performance-vm/results.json`. `--max-p99-ratio` is an optional local gate for a fixed,
quiet host only.

### Measured characterization (JDK 21 Temurin, Linux x86-64, shared host)

Indicative medians over three forks (300k warm calls each); absolute numbers vary by host:

| Target | native p50 | virtualized p50 | virtualized/native | alloc/call |
| --- | --- | --- | --- | --- |
| `hot` (dispatch-bound) | ~0.64 µs | ~9.1 µs | ~14× | ~40 B |
| `tiny` (invocation-bound) | ~30 ns | ~70 ns | ~2.3× | ~40 B |

Run-to-run p50 variance on the shared host was ≈ ±9%. The ~40 bytes/call is the boxed interpreter
return plus the argument array built at the call site.

### Optimization notes

Virtualization deliberately trades execution speed for analysis resistance, so the interpreter's
overhead is largely inherent and must not be reduced by weakening the VM (e.g. lowering virtualized
code back toward plain JVM instructions). Two protection-neutral candidates were prototyped and
measured against the baseline above:

- Hoisting the per-instruction exception-handler search out of the dispatch loop.
- Collapsing the two per-invocation thread-local lookups into one.

Neither produced a change distinguishable from the ≈ ±9% host variance (the invocation-bound `tiny`
target, where the thread-local change should matter most, showed 0%). HotSpot already optimizes both
paths well on JDK 21, so neither change was kept. Use the benchmark on a fixed, low-noise host to
re-evaluate before adopting any interpreter micro-optimization, and keep `@HotPath`/explicit
exclusion as the supported way to keep latency-critical methods out of virtualization entirely.

### Re-measurement and allocation breakdown (current code, JDK 21 Temurin, same host)

Re-running `virtualization.py` on the current code (`--forks 2 --warmup 50000 --measured 50000`)
reproduced the characterization above: `hot` 640 ns → 9120 ns (14.3×), `tiny` 60 ns → 95 ns (1.6×,
within this host's run-to-run noise of the ~2.3× figure above), ~40 B/call for both — the prior
numbers are still representative, not stale.

`obfaryn-benchmarks`' `VmShapeBenchmark` (JMH, `-prof gc`) breaks the ~40 B/call figure down for
instruction shapes beyond `hot`/`tiny`. Unlike the timing numbers, JMH's `gc.alloc.rate.norm` is
essentially noise-free even over a short run (±0.03 B/op), so these byte counts are trustworthy
even though the accompanying ns/op figures (small sample, reported in the JMH module section
above) are not:

| Shape | B/op (virtualized) |
| --- | --- |
| Method call (`plain`) | 24 |
| Exception handling, no throw | 24 |
| Array access | 112 |
| Allocation (boxing + array/`Object[]` construction) | 200 |
| Exception handling, throwing | 792 (728 of which is the JVM's own stack-trace capture, present unvirtualized too) |

24 B/op matches the documented "boxed interpreter return plus the argument array" explanation —
except the return here isn't actually boxing-allocated: the benchmark's arguments stay within
`Integer`'s `-128..127` internal cache, so only the call site's `int[] arguments` array allocates.
A method called with values outside that cache would allocate the box too; this benchmark's choice
of argument happens to hide that half of the figure.

`arrayWork`'s array construction and `allocWork`'s boxing/array/`Object[]` construction are not
native VM instructions; they go through the generic bridge dispatch (opcode 48 in
`VirtualizationRuntime.executeTyped`), which builds a fresh `Object[] bridgeArguments` per bridge
call (`VirtualizationRuntime.java`'s `case 48`). That — not the already-pooled per-invocation frame
(`acquireFrame`/`releaseFrame`, a `ThreadLocal<ArrayDeque<Object[]>>`) — is the dominant allocation
source once a virtualized method does anything the VM doesn't have a native instruction for: array
construction, object construction, boxing/unboxing method calls, and (per `case` around line 435)
one `new Object[] { failure }` per exception-handler type check while searching for a matching
`catch`.

**Considered and not implemented:** pooling `bridgeArguments` (and the exception-handler-search's
`Object[] { failure }`) the same way frames are already pooled. Not pursued, for two reasons:

- The bytes involved (tens to a few hundred per op) are far smaller than the dispatch/call overhead
  dominating these shapes' actual ns/op cost (hundreds to several thousand ns) — allocation/GC
  pressure is not the bottleneck here at any realistic call rate, so removing it would not move the
  wall-clock number by a measurable amount, which is exactly the "measured improvement within
  noise" case Phase 3's adoption bar excludes.
- Doing it *safely* is nontrivial: unlike the frame pool, a shared mutable buffer for bridge
  arguments must stay correct if executing a bridge call re-enters virtualized execution on the
  same thread (e.g. a bridged constructor or static initializer calling back into virtualized
  code). That needs the same LIFO per-thread stacking the frame pool already uses, not a single
  reusable slot — real complexity to add to a correctness-critical, heavily-tested component for an
  effect this analysis does not expect to be measurable.

No further dispatch-loop candidate was found beyond the two already tried above; `configuration(i)`
calls are build-time-inlined to constant expressions (not real calls) before the output is written,
so the loop's per-iteration `configuration(9) + pc++ * width` is already just inlined-constant
arithmetic, not a method call.

## Obfuscation-process cost: per-pass elapsed time

`PipelineExecutor` records each pass's wall-clock duration (`System.nanoTime()` around that one
pass's `transform()` call) as an `elapsedNanos` metric on its `TransformerReport`, alongside the
existing pass-specific metrics (`transformed`, `indirectedCalls`, …). It is written through
unchanged by `TransformationReportWriter` (schemaVersion 2's `metrics` map is already open-ended —
see [docs/protection.md](protection.md)), so it needs no transformation-report schema bump.

`scripts/report-summary.py` surfaces it as `elapsedMillis` per pass and as a summed
`total.elapsedMillis`, in its JSON, text and HTML output — a `--transformation-report
methods.json` run shows where obfuscation time went, pass by pass, for any already-generated
report (no obfuscation re-run needed):

```
[call-indirection] methods=9 applied=8 skipped=1 rejected=0 growth=238.6% near-limit=0 time=23.533ms
```

This is a one-run, one-process wall-clock figure (profiling-grade, same spirit as the CLI's own
`--report`/`--transformation-report` flags), not a JMH-grade steady-state measurement with forks
and warmup; use `obfaryn-benchmarks` (above) for that. It does, however, directly answer which
pass costs the most of the engine's own processing time on a given input — class-hierarchy
analysis, renaming, frame recomputation and JAR read/write costs are not yet broken out
individually beyond the existing per-pass granularity; doing so would mean instrumenting
`TransformationEngine`/`JarWriter` themselves, which remains for a later pass at this.

## Whole-process cost — `scripts/performance/process.py`

The per-pass `elapsedNanos` figures above only cover time spent inside `transform()` calls. This
script measures the obfuscation JVM process as a whole — wall-clock time, user/system CPU time,
peak resident set size, and garbage-collection activity — which is the figure that actually governs
a CI job's timeout or a build machine's sizing. It generates a synthetic 80-class/15-method-per-
class application (closer to a real plugin jar's class/method count than the single-class fixtures
the other scripts use, since class-hierarchy analysis, rename-mapping and call-graph cost scale
with class/method count, not loop iterations), obfuscates it under `--protection strong` with
control flow, call indirection and integer arithmetic enabled, and wraps each run in GNU `time -v`
(CPU/RSS) plus `-Xlog:gc` (GC count and total pause time). Writes
`build/performance-process/results.json`. Requires Linux's GNU `time` (not macOS's BSD `time`).
Optional local gates: `--max-wall-seconds`, `--max-rss-mb`.

Three forks on this JDK 21 Temurin host (80 classes × 15 methods, default `-Xmx512m`):

| Metric | Median |
| --- | --- |
| Wall-clock time | ~3.2 s |
| User CPU time | ~9.1 s (multi-threaded JIT/GC, hence > wall time) |
| Peak RSS | ~368 MB |
| GC pauses | 7, ~89 ms total |
| Output size | ~617 KB (from a ~145 KB input) |

Most of this is JVM startup and JIT warmup rather than engine work proper — the same synthetic
input obfuscated through `report-summary.py`'s pass-level `elapsedMillis` totals well under a
second of actual `transform()` time (see the section above). This script does not distinguish
start-up/classpath-scan cost from per-pass cost; use it alongside, not instead of, the per-pass
figures when investigating where process time goes.
