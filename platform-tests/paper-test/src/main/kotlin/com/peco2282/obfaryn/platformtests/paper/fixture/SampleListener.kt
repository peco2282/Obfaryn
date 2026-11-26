package com.peco2282.obfaryn.platformtests.paper.fixture

import com.peco2282.obfaryn.platformtests.paper.probe.Probe
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Deliberately not referenced by plugin.yml, so Obfaryn is free to rename this class and the
 * method below. Bukkit discovers @EventHandler methods by reflecting over whatever instance was
 * passed to registerEvents(), not by name, so this is exactly the path renaming could silently
 * break (annotation retention, descriptor matching) without the obfuscated plugin ever throwing.
 */
class SampleListener : Listener {
  @EventHandler
  fun onJoin(event: PlayerJoinEvent) {
    Probe.record("join:${event.player.name}")
  }
}
