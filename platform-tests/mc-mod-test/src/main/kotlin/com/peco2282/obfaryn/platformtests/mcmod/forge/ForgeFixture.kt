package com.peco2282.obfaryn.platformtests.mcmod.forge

import com.peco2282.obfaryn.platformtests.mcmod.probe.Probe
import net.minecraftforge.eventbus.api.bus.BusGroup
import net.minecraftforge.eventbus.api.bus.EventBus
import net.minecraftforge.eventbus.api.event.MutableEvent
import net.minecraftforge.eventbus.api.listener.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.lang.invoke.MethodHandles

/**
 * `@SubscribeEvent` and the event bus below are the *real* net.minecraftforge:eventbus library
 * (see net.minecraftforge.fml.common.Mod's own doc for why `@Mod` itself is a local stub), since
 * the event bus is what actually has to discover and invoke a renamed method at runtime.
 */
class TestEvent : MutableEvent()

// eventbus refuses BusGroup.register(Lookup, Object) for a class with only one @SubscribeEvent
// method ("you should directly call addListener() instead"), so a second, distinct event type is
// needed purely to keep the reflective multi-listener registration path in play.
class OtherEvent : MutableEvent()

/**
 * Entry point kept stable by name (ForgePlatformProvider.entrypoints -> keepClassNameOnly), so the
 * test loads it by this exact name from the obfuscated jar; the listener it registers is free to
 * be renamed.
 */
@Mod("obfaryn-forge-fixture")
class ForgeMod {
  init {
    ForgeListener()
  }

  // Called by the test via reflection on this stable name: TestEvent/OtherEvent are free to be
  // renamed, so the test cannot look them up by their original names to construct and post one
  // itself.
  fun fireEvents() {
    EventBus.create(BusGroup.DEFAULT, TestEvent::class.java).post(TestEvent())
    EventBus.create(BusGroup.DEFAULT, OtherEvent::class.java).post(OtherEvent())
  }
}

/**
 * Deliberately not referenced by mods.toml, so Obfaryn is free to rename this class and the
 * method below. The event bus discovers @SubscribeEvent methods by reflecting over whatever
 * instance was passed to register(), not by name, so this is exactly the path renaming could
 * silently break (annotation retention, descriptor matching) without the obfuscated mod ever
 * throwing.
 *
 * Registers with its own MethodHandles.lookup(), not a caller-supplied public lookup: eventbus's
 * EventListenerFactory builds the invoker via LambdaMetafactory, which requires a lookup whose
 * origin class actually has access to the target method -- MethodHandles.publicLookup() fails
 * with "LambdaConversionException: Invalid caller: java.lang.Object" because it has no real
 * owning class.
 */
class ForgeListener {
  init {
    BusGroup.DEFAULT.register(MethodHandles.lookup(), this)
  }

  @SubscribeEvent
  fun onTest(event: TestEvent) {
    Probe.record("forge:test")
  }

  @SubscribeEvent
  fun onOther(event: OtherEvent) {
    Probe.record("forge:other")
  }
}
