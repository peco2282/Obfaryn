package com.peco2282.obfaryn.platformkit

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.PlatformContext
import org.objectweb.asm.tree.ClassNode

/**
 * True if [name] is or extends/implements [supertypeName], walking superclasses and interfaces
 * through this map. Both names may use dotted or JVM internal form. A class absent from this
 * map (a dependency not loaded into the run) ends that branch of the walk rather than failing it,
 * so a hierarchy that crosses outside the application scope may under-report.
 */
fun Map<String, ClassNode>.isSubtypeOf(name: String, supertypeName: String): Boolean {
  val target = supertypeName.toInternalName()
  val visited = hashSetOf<String>()
  fun walk(internalName: String): Boolean {
    if (internalName == target) return true
    if (!visited.add(internalName)) return false
    val node = this[internalName] ?: return false
    return node.superName != null && walk(node.superName) || node.interfaces.orEmpty().any(::walk)
  }
  return walk(name.toInternalName())
}

/** @see Map.isSubtypeOf */
fun PlatformContext.isSubtypeOf(name: String, supertypeName: String): Boolean = classes.isSubtypeOf(name, supertypeName)

/** @see Map.isSubtypeOf */
fun ObfuscationContext.isSubtypeOf(name: String, supertypeName: String): Boolean = classes.isSubtypeOf(name, supertypeName)

/**
 * True if [name] is or extends/implements [supertype], computed from the real class instead of a
 * hand-written name. Only usable when [supertype] is actually on the current classpath; a target
 * framework's type that a platform provider deliberately does not depend on still needs the
 * string-based [isSubtypeOf] overload above.
 */
fun Map<String, ClassNode>.isSubtypeOf(name: String, supertype: Class<*>): Boolean = isSubtypeOf(name, supertype.name)

/** @see Map.isSubtypeOf */
fun PlatformContext.isSubtypeOf(name: String, supertype: Class<*>): Boolean = classes.isSubtypeOf(name, supertype)

/** @see Map.isSubtypeOf */
fun ObfuscationContext.isSubtypeOf(name: String, supertype: Class<*>): Boolean = classes.isSubtypeOf(name, supertype)

/** `classes.isSubtypeOf<Runnable>(name)`; see the [Class]-based overload above. */
inline fun <reified T> Map<String, ClassNode>.isSubtypeOf(name: String): Boolean = isSubtypeOf(name, T::class.java)

/** @see Map.isSubtypeOf */
inline fun <reified T> PlatformContext.isSubtypeOf(name: String): Boolean = classes.isSubtypeOf(name, T::class.java)

/** @see Map.isSubtypeOf */
inline fun <reified T> ObfuscationContext.isSubtypeOf(name: String): Boolean = classes.isSubtypeOf(name, T::class.java)

/** Converts a dotted class name (`java.lang.Runnable`) to JVM internal form (`java/lang/Runnable`). Internal names pass through unchanged. */
fun String.toInternalName(): String = replace('.', '/')
