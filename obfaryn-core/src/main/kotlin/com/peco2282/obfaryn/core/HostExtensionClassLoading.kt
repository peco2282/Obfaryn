package com.peco2282.obfaryn.core

import java.net.URL
import java.net.URLClassLoader

/**
 * Runs [block] with a fresh [URLClassLoader] over [urls] installed as the current thread's context
 * classloader, so ServiceLoader-based discovery ([Platforms], [TransformationPackages],
 * [PipelineConfigurers]) sees providers packaged in those JARs. The previous context classloader is
 * restored afterward, even if [block] throws; the created loader is closed when this call returns.
 *
 * Shared by every host entry point that scans extension JARs (CLI `obfuscate`/`list-extensions`,
 * Gradle `obfuscateJar`/`listObfarynExtensions`) so the classloader-swap contract stays in one place.
 */
fun <T> withExtensionClassLoader(urls: List<URL>, parent: ClassLoader, block: () -> T): T =
  URLClassLoader(urls.toTypedArray(), parent).use { loader ->
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    try {
      thread.contextClassLoader = loader
      block()
    } finally {
      thread.contextClassLoader = previous
    }
  }
