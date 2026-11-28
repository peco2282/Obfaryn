package net.neoforged.fml.common

/**
 * Standalone stub, same reasoning as the Forge one (net.minecraftforge.fml.common.Mod): the real
 * annotation lives in NeoForge's full modlauncher-based loader toolchain, far heavier than a
 * headless test needs, and Obfaryn's NeoForgePlatformProvider only checks this descriptor string.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Mod(val value: String = "")
