package workload;

/** Synthetic entity loop. This is not a Minecraft server/TPS benchmark. */
public final class TickWorkload {
  public static double tick(double[] positions, double[] velocity, int tick) {
    double checksum = 0;
    for (int i = 0; i < positions.length; i++) {
      double value = positions[i] + velocity[i] * 0.016;
      if (value > 100) value -= 200;
      if (value < -100) value += 200;
      positions[i] = value;
      checksum += Math.abs(value) + ((i ^ tick) & 7);
    }
    return checksum;
  }
}
