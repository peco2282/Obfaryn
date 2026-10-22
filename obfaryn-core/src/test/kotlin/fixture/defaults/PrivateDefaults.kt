package fixture.defaults

class PrivateDefaults {
  private fun privateDefault(amount: Int = 2): Int = 3 + amount
  fun usePrivateDefault(): Int = privateDefault()
}
