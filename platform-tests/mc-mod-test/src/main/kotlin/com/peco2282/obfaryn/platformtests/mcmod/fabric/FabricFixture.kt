package com.peco2282.obfaryn.platformtests.mcmod.fabric

import com.peco2282.obfaryn.platformtests.mcmod.probe.Probe
import net.fabricmc.api.ModInitializer

/**
 * Fabric has no annotation-driven reflection discovery like Forge/NeoForge's @SubscribeEvent --
 * fabric.mod.json names this class directly and the loader does Class.forName + no-arg
 * constructor + call onInitialize(), so the only renaming risk worth testing is entrypoint class
 * name stability (FabricPlatformProvider.entrypoints -> keepClassNameOnly), not reflection-based
 * member discovery. net.fabricmc.api.ModInitializer is the real, standalone fabric-loader
 * artifact's interface, not a stub.
 */
class FabricMod : ModInitializer {
  override fun onInitialize() {
    Probe.record("fabric:initialized")
  }
}
