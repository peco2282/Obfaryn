package com.peco2282.obfaryn.platformtests.paper.fixture

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * Entry point named by plugin.yml's `main:`. Obfaryn's Paper platform keeps this class's name
 * stable (see PaperPlatform.configure -> keepClassNameOnly), so the test loads it by this exact
 * name from the obfuscated jar; everything else in this fixture (the listener, its method names)
 * is free to be renamed and the test must not depend on those names surviving.
 */
class SamplePlugin : JavaPlugin() {
  override fun onEnable() {
    Bukkit.getPluginManager().registerEvents(SampleListener(), this)
  }
}
