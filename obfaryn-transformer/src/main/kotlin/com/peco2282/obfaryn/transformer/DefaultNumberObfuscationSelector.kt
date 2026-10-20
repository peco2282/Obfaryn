package com.peco2282.obfaryn.transformer

import com.peco2282.obfaryn.api.transformer.NumberObfuscationSelector
import com.peco2282.obfaryn.api.transformer.NumberObfuscationSite
import java.util.Random

/** Small integer constants are frequent in control flow and expanding them adds little value. */
class DefaultNumberObfuscationSelector : NumberObfuscationSelector {
  override fun shouldTransform(site: NumberObfuscationSite, random: Random): Boolean {
    val value = site.value
    return !(value is Int && value in -1..1 || value is Long && value in -1L..1L)
  }
}