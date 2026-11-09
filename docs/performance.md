# Performance measurement

These benchmarks are synthetic and host-sensitive. They establish relative, repeatable numbers for
regression tracking on a fixed machine; they are not Paper/Spring Boot server guarantees and do not
establish absolute throughput for any real application. Run them apart from builds and tests, and
treat small deltas on shared CI runners as noise.

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
