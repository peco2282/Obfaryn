package com.peco2282.obfaryn.core.keep

import com.peco2282.obfaryn.api.PlatformConfiguration
import org.objectweb.asm.tree.ClassNode

/**
 * Mutable keep and performance-policy rules for one obfuscation run.
 *
 * Class-wide rules preserve both the name and members; [keepClassNameOnly] preserves the class
 * name alone. [applyTo] copies every rule category into another [KeepRules] instance.
 * Class and owner names passed to mutators may use dots or JVM slashes and are stored as internal
 * names. Query methods expect internal names; resource paths and JVM descriptors are used verbatim.
 * These rules protect names and members from renaming, not method bodies from transformation.
 */
class KeepRules : PlatformConfiguration {
  private val classes = mutableSetOf<String>()
  private val classNamesOnly = mutableSetOf<String>()
  private val publicMembers = mutableSetOf<String>()
  private val packages = mutableSetOf<String>()
  private val classGlobs = mutableListOf<Regex>()
  private val methods = mutableSetOf<Triple<String, String, String>>()
  private val fields = mutableSetOf<Pair<String, String>>()
  private val resources = mutableSetOf<String>()
  private val reflectiveMethods = mutableSetOf<String>()
  private val reflectiveFields = mutableSetOf<String>()
  private val virtualizationExclusions = linkedMapOf<Triple<String, String, String>, String>()

  /** Protects every method with this simple name, regardless of owner or descriptor. */
  fun keepReflectiveMethod(name: String) { reflectiveMethods += name }
  /** Protects every field with this simple name, regardless of owner or descriptor. */
  fun keepReflectiveField(name: String) { reflectiveFields += name }

  /** Merges into another [KeepRules]; self-application is a no-op and other implementations are rejected. */
  override fun applyTo(target: PlatformConfiguration) {
    require(target is KeepRules) { "KeepRules requires a KeepRules target" }
    if (target === this) return
    target.classes += classes
    target.classNamesOnly += classNamesOnly
    target.publicMembers += publicMembers
    target.packages += packages
    target.classGlobs += classGlobs
    target.methods += methods
    target.fields += fields
    target.resources += resources
    target.reflectiveMethods += reflectiveMethods
    target.reflectiveFields += reflectiveFields
    target.virtualizationExclusions.putAll(virtualizationExclusions)
  }

  override fun keepClass(name: String) = apply { classes += name.replace('.', '/') }
  override fun keepClassNameOnly(name: String) = apply { classNamesOnly += name.replace('.', '/') }
  fun keepClassNameAndPublicMembers(name: String) = apply {
    val internalName = name.replace('.', '/')
    classNamesOnly += internalName
    publicMembers += internalName
  }
  override fun keepPackage(name: String) = apply { packages += name.replace('.', '/').trimEnd('/') + "/" }
  override fun keepResource(name: String) = apply { resources += name }
  override fun keepMethod(owner: String, name: String, descriptor: String) = apply {
    methods += Triple(owner.replace('.', '/'), name, descriptor)
  }
  override fun keepField(owner: String, name: String) = apply { fields += owner.replace('.', '/') to name }
  /** Records an exact method exclusion; the first reason for that identity is retained. */
  override fun excludeMethodFromVirtualization(owner: String, name: String, descriptor: String, reason: String) = apply {
    virtualizationExclusions.putIfAbsent(Triple(owner.replace('.', '/'), name, descriptor), reason)
  }

  /** Returns a snapshot keyed by `owner.name` followed immediately by the JVM method descriptor. */
  fun virtualizationExclusions(): Map<String, String> = virtualizationExclusions.mapKeys { (method, _) ->
    method.first + "." + method.second + method.third
  }

  /**
   * Adds an exact class, `package.*`, `package.**`, `Class#method(JvmDescriptor)`, or `Class#field` rule.
   * Both package suffix forms include subpackages: `.*` uses a package-prefix rule and `.**`
   * uses a recursive class glob. Method descriptors include the return type, for example
   * `example.Service#run()V`. Field rules match the name without a descriptor.
   * This parser does not validate that a named class/member exists or that a descriptor is valid.
   */
  fun add(spec: String) {
    when {
      spec.endsWith(".**") -> classGlobs += glob(spec.replace('.', '/'))
      spec.endsWith(".*") -> keepPackage(spec.dropLast(2))
      '#' in spec -> {
        val owner = spec.substringBefore('#')
        val member = spec.substringAfter('#')
        if ('(' in member) keepMethod(owner, member.substringBefore('('), member.substring(member.indexOf('(')))
        else keepField(owner, member)
      }
      else -> keepClass(spec)
    }
  }

  private fun glob(spec: String): Regex {
    val pattern = buildString {
      append('^')
      var i = 0
      while (i < spec.length) {
        when {
          spec.startsWith("**", i) -> { append(".*"); i += 2 }
          spec[i] == '*' -> { append("[^/]*"); i++ }
          else -> { append(Regex.escape(spec[i].toString())); i++ }
        }
      }
      append('$')
    }
    return Regex(pattern)
  }

  private fun entireClass(name: String) = name in classes || packages.any { name.startsWith(it) } || classGlobs.any { it.matches(name) }
  fun className(name: String) = entireClass(name) || name in classNamesOnly
  fun publicMembers(name: String) = name in publicMembers
  fun method(owner: String, name: String, desc: String) = entireClass(owner) || Triple(owner, name, desc) in methods || name in reflectiveMethods
  fun field(owner: String, name: String) = entireClass(owner) || (owner to name) in fields || name in reflectiveFields
  fun resource(name: String) = name in resources
  fun kotlinClass(node: ClassNode) = (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
    .any { it.desc == "Lkotlin/Metadata;" }
}
