package com.peco2282.obfaryn.platformtests.mcmod.neoforge

import com.peco2282.obfaryn.platformtests.mcmod.probe.Probe
import net.neoforged.bus.api.BusBuilder
import net.neoforged.bus.api.Event
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.Mod

class TestEvent : Event()

/**
 * Entry point kept stable by name (NeoForgePlatformProvider.entrypoints -> keepClassNameOnly), so
 * the test loads it by this exact name from the obfuscated jar; the listener it registers is free
 * to be renamed. Unlike Forge's eventbus 7.x, NeoForge's `bus` library's IEventBus.register(Object)
 * is classic reflection-based scanning with no caller-Lookup requirement.
 */
@Mod("obfaryn-neoforge-fixture")
class NeoForgeMod {
  val bus: IEventBus = BusBuilder.builder().build()

  init {
    bus.register(NeoForgeListener())
  }

  // Called by the test via reflection on this stable name: the bus field itself isn't public API
  // surface the test should poke at.
  fun fireEvent() {
    bus.post(TestEvent())
  }
}

/**
 * Deliberately not referenced by neoforge.mods.toml, so Obfaryn is free to rename this class and
 * the method below. The event bus discovers @SubscribeEvent methods by reflecting over whatever
 * instance was passed to register(), not by name, so this is exactly the path renaming could
 * silently break (annotation retention, descriptor matching) without the obfuscated mod ever
 * throwing.
 */
class NeoForgeListener {
  @SubscribeEvent
  fun onTest(event: TestEvent) {
    Probe.record("neoforge:test")
  }
}
