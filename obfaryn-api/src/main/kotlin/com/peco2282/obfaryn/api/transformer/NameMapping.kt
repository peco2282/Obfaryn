package com.peco2282.obfaryn.api.transformer

/**
 * Member identities always describe the input to the renaming pass.
 *
 * @property owner Original internal class name declaring the member.
 * @property name Original member name, before renaming.
 * @property desc JVM descriptor of the member (method descriptor, or field type descriptor).
 */
data class MemberKey(val owner: String, val name: String, val desc: String) {
  companion object {
    /**
     * Builds a key with [owner] normalized to JVM internal form (dots to slashes), so a caller
     * holding a class name in dotted form (`com.example.Foo`) still produces a key equal to one
     * built from bytecode-level internal names (`com/example/Foo`). [name] and [desc] are taken
     * verbatim; [desc] must already be a JVM descriptor. Prefer this over the raw constructor
     * whenever [owner] comes from an external or hand-written source rather than straight from a
     * `ClassNode`/instruction, which are already internal.
     */
    @JvmStatic
    fun of(owner: String, name: String, desc: String): MemberKey = MemberKey(owner.replace('.', '/'), name, desc)
  }
}

/** A renamer must rewrite all affected bytecode/resource references itself and publish its changes. */
interface RenamingTransformer : Transformer {
  /** Original-to-new internal class name, for every class this renamer renamed. */
  val classMapping: Map<String, String>

  /** Original-to-new method name, keyed by the method's original identity. */
  val methodMapping: Map<MemberKey, String>

  /** Original-to-new field name, keyed by the field's original identity. */
  val fieldMapping: Map<MemberKey, String>

  /**
   * Original-to-new internal package name (`/`-separated, no trailing `/`), for a renamer that
   * relocates whole packages (e.g. package flattening). Empty by default. A `module-info` descriptor's
   * `exports`/`opens` directives are rewritten from this map by host renaming infrastructure; a
   * package absent from this map is reported unchanged, so a renamer should only publish an entry
   * when it moved every class originally in that package — never a partial/best-effort guess.
   */
  val packageMapping: Map<String, String> get() = emptyMap()

  /**
   * Renders this renamer's [classMapping]/[methodMapping]/[fieldMapping]/[packageMapping] as the
   * canonical textual mapping via [MappingFormat], the single authority for the on-disk format.
   */
  fun mappingText(): String = MappingFormat.render(classMapping, methodMapping, fieldMapping, packageMapping)
}

/**
 * One prior artifact whose renames a mapping re-applies, recorded so a chained apply-mapping run
 * (A → B → C) can verify each contributing artifact is unchanged.
 *
 * @property identifier Locates the contributing artifact on the apply-time classpath (JAR file name
 *   or coordinate); the exact form is chosen where provenance is produced, not by this contract.
 * @property digest Content hash of that artifact, matched against the artifact actually present when
 *   the mapping is re-applied; a mismatch is a stale-mapping error.
 */
data class MappingSource(val identifier: String, val digest: String)

/**
 * The full content of a name mapping in the forward (original → obfuscated) direction: class,
 * method, field and package tables plus the provenance of any prior artifacts whose renames it
 * carries. Member tables are keyed by the member's original [MemberKey] identity, so overloads stay
 * distinct — the identity a mapping must preserve to be re-applied, not just read for de-obfuscation.
 */
data class NameMapping(
  val classes: Map<String, String> = emptyMap(),
  val methods: Map<MemberKey, String> = emptyMap(),
  val fields: Map<MemberKey, String> = emptyMap(),
  val packages: Map<String, String> = emptyMap(),
  val sources: List<MappingSource> = emptyList(),
) {
  val isEmpty: Boolean
    get() = classes.isEmpty() && methods.isEmpty() && fields.isEmpty() && packages.isEmpty() && sources.isEmpty()
}

/**
 * The single reader/writer for the textual mapping format, so no caller re-implements the syntax.
 *
 * Lines: a class is `original -> replacement` at the left margin; a method is an indented
 * `owner.name(descriptor) -> replacement`; a field is an indented `owner.name:descriptor -> replacement`;
 * a relocated package is `package original -> replacement` at the left margin. Blank lines are ignored.
 * Comment lines begin with `#`; a `# source <identifier> <digest>` comment records one [MappingSource],
 * and other comments are ignored, so a header stays forward-compatible. [parse] and [render] round-trip.
 */
object MappingFormat {
  /** Current on-disk format revision, emitted only once provenance or other header data is present. */
  const val FORMAT_VERSION: Int = 1
  private const val SOURCE_PREFIX = "# source "
  private const val PACKAGE_PREFIX = "package "

  /** Renders the given forward tables; returns `""` when every table and the provenance are empty. */
  fun render(
    classes: Map<String, String>,
    methods: Map<MemberKey, String>,
    fields: Map<MemberKey, String>,
    packages: Map<String, String> = emptyMap(),
    sources: List<MappingSource> = emptyList(),
  ): String {
    if (classes.isEmpty() && methods.isEmpty() && fields.isEmpty() && packages.isEmpty() && sources.isEmpty()) return ""
    return buildString {
      for (source in sources) appendLine("$SOURCE_PREFIX${source.identifier} ${source.digest}")
      for ((old, new) in classes) appendLine("$old -> $new")
      for ((key, new) in methods) appendLine("  ${key.owner}.${key.name}${key.desc} -> $new")
      for ((key, new) in fields) appendLine("  ${key.owner}.${key.name}:${key.desc} -> $new")
      for ((old, new) in packages) appendLine("$PACKAGE_PREFIX$old -> $new")
    }
  }

  fun render(mapping: NameMapping): String =
    render(mapping.classes, mapping.methods, mapping.fields, mapping.packages, mapping.sources)

  /**
   * Parses mapping text. Repeated class/package keys keep the last value; member and source entries
   * are retained in input order. Descriptor correctness is not validated.
   *
   * @throws IllegalArgumentException if a non-comment line lacks the required mapping structure.
   */
  fun parse(text: String): NameMapping {
    val classes = LinkedHashMap<String, String>()
    val methods = LinkedHashMap<MemberKey, String>()
    val fields = LinkedHashMap<MemberKey, String>()
    val packages = LinkedHashMap<String, String>()
    val sources = mutableListOf<MappingSource>()
    for (raw in text.lineSequence()) {
      if (raw.isBlank()) continue
      if (raw.startsWith("#")) {
        if (raw.startsWith(SOURCE_PREFIX)) {
          val rest = raw.substring(SOURCE_PREFIX.length).trim()
          val identifier = rest.substringBeforeLast(' ', "")
          val digest = rest.substringAfterLast(' ', "")
          require(identifier.isNotEmpty() && digest.isNotEmpty()) { "Invalid mapping source line: $raw" }
          sources += MappingSource(identifier, digest)
        }
        continue
      }
      val indented = raw.first().isWhitespace()
      val parts = raw.trim().split(" -> ", limit = 2)
      require(parts.size == 2) { "Invalid mapping line: $raw" }
      val (old, new) = parts
      when {
        !indented && old.startsWith(PACKAGE_PREFIX) -> {
          val pkg = old.substring(PACKAGE_PREFIX.length).trim()
          require(pkg.isNotEmpty() && new.isNotBlank()) { "Invalid package mapping: $raw" }
          packages[pkg] = new
        }
        !indented -> {
          require(old.isNotBlank() && new.isNotBlank()) { "Invalid class mapping: $raw" }
          classes[old] = new
        }
        else -> {
          val owner = old.substringBeforeLast('.', "")
          val member = old.substringAfterLast('.')
          require(owner.isNotEmpty()) { "Invalid member mapping: $raw" }
          if ('(' in member) {
            methods[MemberKey(owner, member.substringBefore('('), member.substring(member.indexOf('(')))] = new
          } else {
            require(':' in member) { "Invalid field mapping: $raw" }
            fields[MemberKey(owner, member.substringBefore(':'), member.substringAfter(':'))] = new
          }
        }
      }
    }
    return NameMapping(classes, methods, fields, packages, sources)
  }
}
