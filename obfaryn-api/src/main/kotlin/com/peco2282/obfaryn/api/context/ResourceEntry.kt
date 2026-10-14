package com.peco2282.obfaryn.api.context

/**
 * Represents a resource entry consisting of a name and associated binary data.
 *
 * The `ResourceEntry` class is used to store and manage entries of resources, where each resource
 * is uniquely identified by its name and holds an array of binary data.
 *
 * Equality between two instances of `ResourceEntry` is determined by comparing both their `name` values
 * and the contents of the `data` arrays. The custom implementation of `equals` ensures that two resource
 * entries are considered equal only if their names and binary data are identical. Similarly, the `hashCode`
 * function is overridden to generate hash codes based on both properties.
 *
 * This class is typically utilized in contexts where resources are identified by a string key
 * and need to be stored, retrieved, or modified in bulk.
 *
 * @property name The unique identifier for the resource.
 * @property data The binary data associated with the resource.
 */
data class ResourceEntry(
  val name: String,
  val data: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ResourceEntry

    if (name != other.name) return false
    if (!data.contentEquals(other.data)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = name.hashCode()
    result = 31 * result + data.contentHashCode()
    return result
  }
}
