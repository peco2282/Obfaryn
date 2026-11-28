package net.minecraftforge.fml.common

/**
 * Standalone stub: the real `net.minecraftforge.fml.common.Mod` lives in Forge's full
 * modlauncher-based `fmlloader` toolchain (Mojang-asset-gated, far heavier than a headless test
 * needs). Obfaryn's ForgePlatformProvider only checks this descriptor string for detection, so a
 * locally-declared annotation under the exact same fully-qualified name is equivalent for that
 * purpose. Must live under this real package (not the fixture's own package) for detection to
 * actually see it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Mod(val value: String = "")
