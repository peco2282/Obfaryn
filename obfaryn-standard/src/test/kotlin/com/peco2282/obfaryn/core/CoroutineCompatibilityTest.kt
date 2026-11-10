package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.api.protection.ControlFlowMode
import com.peco2282.obfaryn.api.protection.TransformationBudget
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Kotlin coroutine compatibility (Phase 1/6 follow-up): suspend-function state preservation across
 * suspension points, exception propagation out of a suspended call, and structured-concurrency
 * cancellation, run through the full obfuscation pipeline (string/number obfuscation, lightweight
 * control flow, call indirection + prelink, integer arithmetic). Every scenario is exposed as an
 * ordinary blocking Kotlin function (`runBlocking` internally) so it can be driven by plain
 * reflection without needing a `Continuation` at the call site; the coroutine machinery itself
 * (suspend/resume, the compiler-generated continuation classes, cancellation) still runs for real
 * inside each one. Cancellation uses cooperative `yield()`, not wall-clock `delay()`, so the
 * interleaving -- and therefore the result -- is deterministic regardless of host speed or how
 * much slower the obfuscated side runs.
 */
class CoroutineCompatibilityTest {
  @TempDir lateinit var dir: Path
  private val stdlib: File get() = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
  private val coroutinesCore: File get() =
    File(kotlinx.coroutines.CoroutineScope::class.java.protectionDomain.codeSource.location.toURI())

  private fun compile(source: Path, output: Path) {
    val diagnostics = ByteArrayOutputStream()
    val classpath = listOf(stdlib, coroutinesCore).joinToString(File.pathSeparator) { it.toString() }
    val exit = PrintStream(diagnostics).use {
      K2JVMCompiler().exec(it, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-classpath", classpath, "-d", output.toString(), source.toString())
    }
    assertEquals(ExitCode.OK, exit, diagnostics.toString())
  }

  private val source = """
    package coro
    import kotlinx.coroutines.*

    object Workload {
      // Local variables computed before a suspension point and used after it: exercises the
      // compiler's continuation state-machine spilling, the part obfuscation is most likely to
      // disturb if it misidentifies a continuation's own synthetic fields/methods.
      private suspend fun spill(x: Int): Int {
        val before = x * 3
        yield()
        val after = before + 7
        delay(1)
        return after - x
      }

      private suspend fun step(x: Int): Int { yield(); return spill(x) }

      private suspend fun risky(x: Int): Int {
        if (x == 0) throw IllegalStateException("zero")
        return step(x) / x
      }

      // Exception propagation across a suspended call chain, caught by the blocking caller.
      fun runSuspendChain(x: Int): String = runBlocking {
        val a = step(x)
        val b = try { risky(x) } catch (e: IllegalStateException) { -1 }
        "${'$'}a:${'$'}b"
      }

      // Structured-concurrency cancellation: CancellationException propagation out of a child
      // coroutine, observed via a locally captured counter. `yield()`-based, not `delay()`-based,
      // so the interleaving (and thus `ticks`) is the same on every run regardless of host speed.
      fun runCancellation(): Int = runBlocking {
        var ticks = 0
        val job = launch {
          try {
            repeat(1000) { yield(); ticks++ }
          } catch (e: CancellationException) {
            ticks += 1000
            throw e
          }
        }
        yield(); yield(); yield()
        job.cancelAndJoin()
        ticks
      }

      // Parallel composition: async/awaitAll, each branch itself suspending and resuming.
      fun runParallel(x: Int): Int = runBlocking {
        (1..4).map { i -> async { spill(x + i) } }.awaitAll().sum()
      }
    }
  """.trimIndent()

  @Test fun suspendStateExceptionPropagationAndCancellationSurviveObfuscation() {
    val input = dir.resolve("coro.jar")
    val sourceFile = dir.resolve("Workload.kt")
    Files.write(sourceFile, source.toByteArray())
    val classesDir = dir.resolve("classes")
    compile(sourceFile, classesDir)
    java.util.jar.JarOutputStream(Files.newOutputStream(input)).use { jar ->
      Files.walk(classesDir).use { paths ->
        paths.filter { it.toString().endsWith(".class") }.sorted().forEach { path ->
          jar.putNextEntry(java.util.jar.JarEntry(classesDir.relativize(path).toString().replace('\\', '/')))
          jar.write(Files.readAllBytes(path)); jar.closeEntry()
        }
      }
    }
    val stdlibUri = stdlib.toURI().toURL()
    val coroutinesUri = coroutinesCore.toURI().toURL()
    for (seed in listOf(0L, 1L, 20261110L, Long.MIN_VALUE)) {
      val output = dir.resolve("coro-$seed.jar")
      val config = ObfuscationConfig(
        applicationPackages = listOf("coro"), seed = seed,
        protectionLevel = ProtectionLevel.STRONG, controlFlowMode = ControlFlowMode.LIGHT,
        stringEncryption = true, numberObfuscation = true,
        integerArithmetic = true, maxArithmeticOperationsPerMethod = 4,
        callIndirection = true, prelinkCalls = true,
        renameClasses = false, renameMethods = false, renameFields = false,
        verifyOutput = true, excludeVirtualization = listOf(".*"),
        transformationBudget = TransformationBudget(
          maxInputInstructions = 10000, maxInputBranches = 256, maxAddedInstructions = 4096, maxGrowthPercent = 400),
        classpath = listOf(stdlib.toPath(), coroutinesCore.toPath()),
      )
      Obfuscator(config).obfuscate(input, output)
      URLClassLoader(arrayOf(input.toUri().toURL(), stdlibUri, coroutinesUri), null).use { originalLoader ->
        URLClassLoader(arrayOf(output.toUri().toURL(), stdlibUri, coroutinesUri), null).use { obfuscatedLoader ->
          fun workload(loader: URLClassLoader) = loader.loadClass("coro.Workload").getField("INSTANCE").get(null)
          val original = workload(originalLoader)
          val obfuscated = workload(obfuscatedLoader)

          fun call(target: Any, name: String, vararg args: Any): Any = try {
            val method = target.javaClass.getMethod(name, *args.map { it.javaClass.let {
              c -> if (c == java.lang.Integer::class.java) Int::class.javaPrimitiveType!! else c } }.toTypedArray())
            listOf("return", method.invoke(target, *args))
          } catch (e: InvocationTargetException) {
            listOf("throw", e.targetException.javaClass.name, e.targetException.message)
          }

          for (arg in intArrayOf(Int.MIN_VALUE, -5, -1, 0, 1, 2, 7, 1000, Int.MAX_VALUE)) {
            assertEquals(call(original, "runSuspendChain", arg), call(obfuscated, "runSuspendChain", arg),
              "seed=$seed runSuspendChain($arg)")
            assertEquals(call(original, "runParallel", arg), call(obfuscated, "runParallel", arg),
              "seed=$seed runParallel($arg)")
          }
          assertEquals(call(original, "runCancellation"), call(obfuscated, "runCancellation"),
            "seed=$seed runCancellation")

          // Independent oracle: spill(x) = 2x+7, risky(x) = spill(x)/x (caught -1 at x=0).
          assertEquals(listOf("return", "17:3"), call(original, "runSuspendChain", 5))
          assertEquals(listOf("return", "17:3"), call(obfuscated, "runSuspendChain", 5))
          assertEquals(listOf("return", "7:-1"), call(original, "runSuspendChain", 0))
          assertEquals(listOf("return", "7:-1"), call(obfuscated, "runSuspendChain", 0))
        }
      }
    }
  }
}
