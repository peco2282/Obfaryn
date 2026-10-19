package com.peco2282.obfaryn.core.transformer;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code VirtualizationRuntime} class provides an environment for executing
 * a virtual instruction set. It operates on a program encoded as a Base64 string
 * and executes instructions defined by a custom virtual machine. The class is
 * primarily designed to interpret and execute sequences of operations on an
 * operand stack and a local variable array.
 *
 * <h2>Instruction Set</h2>
 * The virtual machine defines a variety of instructions that manipulate an operand
 * stack, initialize local variables, and perform arithmetic, bitwise, conditional,
 * and control operations. Each instruction is represented by an opcode and may
 * require additional operands.
 *
 * <h2>Execution Model</h2>
 * The virtual machine decodes a Base64-encoded program into a sequence of operations.
 * The program operates using:
 * - A local variable array for holding variables.
 * - A stack for intermediate computations.
 * - A program counter to track the current instruction.
 * The virtual machine continues execution until a termination instruction is
 * encountered or an exception is thrown for invalid operations.
 *
 * <h2>Thread Safety</h2>
 * Generated runtimes use invocation-local state and support concurrent execution.
 * Callers must not mutate the argument array during execution.
 *
 * @see VirtualizationTransformer#transform
 */
@SuppressWarnings({"unused", "UnnecessaryUnboxing", "DataFlowIssue"})
/* Build-time template. Generated runtimes have invocation-local execution state. */
public final class VirtualizationRuntime {
  private VirtualizationRuntime() {}

  /* Each generated runtime serves only a small method group, so this cache is bounded by that group. */
  private static final ConcurrentHashMap<String, int[]> PROGRAMS = new ConcurrentHashMap<>();
  private static final ThreadLocal<ArrayDeque<Object[]>> FRAMES = new ThreadLocal<>();
  private static final Object[] EMPTY_REFERENCES = new Object[0];
  private static final long[] EMPTY_WIDE = new long[0];

  private static int configuration(int index) {
    throw new IllegalStateException("VM template has not been specialized");
  }

  public static int execute(String encoded, int[] arguments) {
    return executeInt(encoded, arguments, EMPTY_REFERENCES);
  }

  public static int executeInt(String encoded, int[] arguments, Object[] references) {
    return executeInt(encoded, arguments, references, EMPTY_WIDE);
  }

  public static int executeInt(String encoded, int[] arguments, Object[] references, long[] doubles) {
    return ((Integer) executeTyped(encoded, arguments, references, doubles)).intValue();
  }

  public static Object executeReference(String encoded, int[] arguments, Object[] references) {
    return executeReference(encoded, arguments, references, EMPTY_WIDE);
  }

  public static Object executeReference(String encoded, int[] arguments, Object[] references, long[] doubles) {
    return executeTyped(encoded, arguments, references, doubles);
  }

  public static void executeVoid(String encoded, int[] arguments, Object[] references) {
    executeVoid(encoded, arguments, references, EMPTY_WIDE);
  }

  public static void executeVoid(String encoded, int[] arguments, Object[] references, long[] doubles) {
    executeTyped(encoded, arguments, references, doubles);
  }

  public static double executeDouble(String encoded, int[] arguments, Object[] references, long[] doubles) {
    return ((Double) executeTyped(encoded, arguments, references, doubles)).doubleValue();
  }

  public static long executeLong(String encoded, int[] arguments, Object[] references, long[] doubles) {
    return ((Long) executeTyped(encoded, arguments, references, doubles)).longValue();
  }

  public static float executeFloat(String encoded, int[] arguments, Object[] references, long[] doubles) {
    return ((Float) executeTyped(encoded, arguments, references, doubles)).floatValue();
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void throwUnchecked(Throwable throwable) throws T {
    throw (T) throwable;
  }

  private static Object bridge(int id, Object[] arguments) {
    throw new IllegalStateException("VM bridge has not been specialized");
  }

  private static int[] decodeProgram(String encoded) {
    int rotation = configuration(7), increment = configuration(6), variant = configuration(8);
    int state = Integer.rotateLeft(configuration(4) ^ configuration(5), rotation);
    ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
    int[] program = new int[bytes.remaining() / 4];
    for (int i = 0; i < program.length; i++) {
      state = variant == 0 ? Integer.rotateLeft(state + increment + i, rotation)
        : Integer.rotateRight(state ^ (increment + i), rotation);
      program[i] = bytes.getInt() ^ state;
      state ^= program[i];
    }
    return program;
  }

  private static Object[] acquireFrame(int localCount, int stackCount) {
    ArrayDeque<Object[]> frames = FRAMES.get();
    if (frames == null) {
      frames = new ArrayDeque<>();
      FRAMES.set(frames);
    }
    Object[] frame = frames.pollFirst();
    if (frame == null || ((int[]) frame[0]).length < localCount || ((int[]) frame[3]).length < stackCount) {
      frame = new Object[] { new int[localCount], new Object[localCount], new long[localCount],
        new int[stackCount], new Object[stackCount], new long[stackCount], new byte[stackCount] };
    }
    return frame;
  }

  private static void releaseFrame(Object[] frame) {
    Arrays.fill((int[]) frame[0], 0);
    Arrays.fill((Object[]) frame[1], null);
    Arrays.fill((long[]) frame[2], 0L);
    Arrays.fill((Object[]) frame[4], null);
    ArrayDeque<Object[]> frames = FRAMES.get();
    if (frames.size() < 4) frames.addFirst(frame);
  }

  @SuppressWarnings({"LocalVariableUsedAndDeclaredInDifferentSwitchBranches", "DuplicateBranchesInSwitch"})
  private static Object executeTyped(String encoded, int[] arguments, Object[] references, long[] doubles) {
    int width = configuration(0), opSlot = configuration(1), operandSlot = configuration(2), extraSlot = configuration(3);
    int[] program = PROGRAMS.get(encoded);
    if (program == null) {
      int[] decoded = decodeProgram(encoded);
      int[] existing = PROGRAMS.putIfAbsent(encoded, decoded);
      program = existing == null ? decoded : existing;
    }
    int localCount = program[configuration(10)] ^ configuration(12);
    int stackCount = program[configuration(11)] ^ configuration(12);
    Object[] frame = acquireFrame(localCount, stackCount);
    int[] locals = (int[]) frame[0];
    Object[] referenceLocals = (Object[]) frame[1];
    long[] doubleLocals = (long[]) frame[2];
    int[] stack = (int[]) frame[3];
    Object[] referenceStack = (Object[]) frame[4];
    long[] doubleStack = (long[]) frame[5];
    byte[] kinds = (byte[]) frame[6];
    if (arguments != null) System.arraycopy(arguments, 0, locals, 0, arguments.length);
    if (references != null) System.arraycopy(references, 0, referenceLocals, 0, references.length);
    if (doubles != null) System.arraycopy(doubles, 0, doubleLocals, 0, doubles.length);
    try {
      int sp = 0, pc = 0;
      while (true) {
      int offset = configuration(9) + pc++ * width;
      int operand = program[offset + operandSlot], extra = program[offset + extraSlot];
      int a, b;
      switch (program[offset + opSlot]) {
        case 0: break;
        case 1: kinds[sp] = 1; stack[sp++] = operand; break;
        case 2: kinds[sp] = 1; stack[sp++] = locals[operand]; break;
        case 3: locals[operand] = stack[--sp]; break;
        case 4: referenceStack[--sp] = null; break;
        case 5:
          stack[sp] = stack[sp - 1]; referenceStack[sp] = referenceStack[sp - 1];
          kinds[sp] = kinds[sp - 1]; sp++; break;
        case 6:
          a = stack[sp - 1]; stack[sp - 1] = stack[sp - 2]; stack[sp - 2] = a;
          Object reference = referenceStack[sp - 1];
          referenceStack[sp - 1] = referenceStack[sp - 2]; referenceStack[sp - 2] = reference;
          byte kind = kinds[sp - 1]; kinds[sp - 1] = kinds[sp - 2]; kinds[sp - 2] = kind; break;
        case 7: b = stack[--sp]; stack[sp - 1] += b; break;
        case 8: b = stack[--sp]; stack[sp - 1] -= b; break;
        case 9: b = stack[--sp]; stack[sp - 1] *= b; break;
        case 10: b = stack[--sp]; stack[sp - 1] /= b; break;
        case 11: b = stack[--sp]; stack[sp - 1] %= b; break;
        case 12: stack[sp - 1] = -stack[sp - 1]; break;
        case 13: b = stack[--sp]; stack[sp - 1] <<= b; break;
        case 14: b = stack[--sp]; stack[sp - 1] >>= b; break;
        case 15: b = stack[--sp]; stack[sp - 1] >>>= b; break;
        case 16: b = stack[--sp]; stack[sp - 1] &= b; break;
        case 17: b = stack[--sp]; stack[sp - 1] |= b; break;
        case 18: b = stack[--sp]; stack[sp - 1] ^= b; break;
        case 19: locals[operand] += extra; break;
        case 20: stack[sp - 1] = (byte) stack[sp - 1]; break;
        case 21: stack[sp - 1] = (char) stack[sp - 1]; break;
        case 22: stack[sp - 1] = (short) stack[sp - 1]; break;
        case 23: if (stack[--sp] == 0) pc = operand; break;
        case 24: if (stack[--sp] != 0) pc = operand; break;
        case 25: if (stack[--sp] < 0) pc = operand; break;
        case 26: if (stack[--sp] >= 0) pc = operand; break;
        case 27: if (stack[--sp] > 0) pc = operand; break;
        case 28: if (stack[--sp] <= 0) pc = operand; break;
        case 29: b = stack[--sp]; if (stack[--sp] == b) pc = operand; break;
        case 30: b = stack[--sp]; if (stack[--sp] != b) pc = operand; break;
        case 31: b = stack[--sp]; if (stack[--sp] < b) pc = operand; break;
        case 32: b = stack[--sp]; if (stack[--sp] >= b) pc = operand; break;
        case 33: b = stack[--sp]; if (stack[--sp] > b) pc = operand; break;
        case 34: b = stack[--sp]; if (stack[--sp] <= b) pc = operand; break;
        case 35: pc = operand; break;
        case 36: return stack[--sp];
        case 37: stack[sp - 1] += operand; break;
        case 38: stack[sp - 1] *= operand; break;
        case 39: kinds[sp] = 2; referenceStack[sp++] = referenceLocals[operand]; break;
        case 40: referenceLocals[operand] = referenceStack[--sp]; referenceStack[sp] = null; break;
        case 41: kinds[sp] = 2; referenceStack[sp++] = null; break;
        case 42:
          Object right = referenceStack[--sp]; referenceStack[sp] = null;
          Object left = referenceStack[--sp]; referenceStack[sp] = null;
          if (left == right) pc = operand;
          break;
        case 43:
          right = referenceStack[--sp]; referenceStack[sp] = null;
          left = referenceStack[--sp]; referenceStack[sp] = null;
          if (left != right) pc = operand;
          break;
        case 44:
          Object value = referenceStack[--sp]; referenceStack[sp] = null;
          if (value == null) pc = operand;
          break;
        case 45:
          value = referenceStack[--sp]; referenceStack[sp] = null;
          if (value != null) pc = operand;
          break;
        case 46: return referenceStack[--sp];
        case 47: return null;
        case 48:
          int count = extra >>> 3;
          Object[] bridgeArguments = new Object[count];
          for (int i = count - 1; i >= 0; i--) {
            --sp;
            bridgeArguments[i] = kinds[sp] == 1 ? Integer.valueOf(stack[sp]) :
              kinds[sp] == 3 ? Double.valueOf(Double.longBitsToDouble(doubleStack[sp])) :
              kinds[sp] == 4 ? Long.valueOf(doubleStack[sp]) :
              kinds[sp] == 5 ? Float.valueOf(Float.intBitsToFloat(stack[sp])) : referenceStack[sp];
            referenceStack[sp] = null;
          }
          Object result = bridge(operand, bridgeArguments);
          if ((extra & 7) == 1) { kinds[sp] = 1; stack[sp++] = ((Integer) result).intValue(); }
          else if ((extra & 7) == 2) { kinds[sp] = 2; referenceStack[sp++] = result; }
          else if ((extra & 7) == 3) {
            kinds[sp] = 3; doubleStack[sp++] = Double.doubleToRawLongBits((Double) result);
          }
          else if ((extra & 7) == 4) { kinds[sp] = 4; doubleStack[sp++] = (Long) result; }
          else if ((extra & 7) == 5) {
            kinds[sp] = 5; stack[sp++] = Float.floatToRawIntBits((Float) result);
          }
          break;
        case 49:
          kinds[sp] = 3; doubleStack[sp++] = ((long) operand << 32) | (extra & 0xffffffffL); break;
        case 50: kinds[sp] = 3; doubleStack[sp++] = doubleLocals[operand]; break;
        case 51: doubleLocals[operand] = doubleStack[--sp]; break;
        case 52: --sp; break;
        case 53: kinds[sp] = 3; doubleStack[sp] = doubleStack[sp - 1]; sp++; break;
        case 54:
          doubleStack[sp - 2] = Double.doubleToRawLongBits(Double.longBitsToDouble(doubleStack[sp - 2]) +
            Double.longBitsToDouble(doubleStack[sp - 1])); --sp; break;
        case 55:
          doubleStack[sp - 2] = Double.doubleToRawLongBits(Double.longBitsToDouble(doubleStack[sp - 2]) -
            Double.longBitsToDouble(doubleStack[sp - 1])); --sp; break;
        case 56:
          doubleStack[sp - 2] = Double.doubleToRawLongBits(Double.longBitsToDouble(doubleStack[sp - 2]) *
            Double.longBitsToDouble(doubleStack[sp - 1])); --sp; break;
        case 57:
          doubleStack[sp - 2] = Double.doubleToRawLongBits(Double.longBitsToDouble(doubleStack[sp - 2]) /
            Double.longBitsToDouble(doubleStack[sp - 1])); --sp; break;
        case 58:
          doubleStack[sp - 2] = Double.doubleToRawLongBits(Double.longBitsToDouble(doubleStack[sp - 2]) %
            Double.longBitsToDouble(doubleStack[sp - 1])); --sp; break;
        case 59:
          doubleStack[sp - 1] = Double.doubleToRawLongBits(-Double.longBitsToDouble(doubleStack[sp - 1])); break;
        case 60: {
          double doubleRight = Double.longBitsToDouble(doubleStack[--sp]);
          double doubleLeft = Double.longBitsToDouble(doubleStack[sp - 1]);
          kinds[sp - 1] = 1; stack[sp - 1] = Double.isNaN(doubleLeft) || Double.isNaN(doubleRight) ? -1 :
            Double.compare(doubleLeft, doubleRight); break;
        }
        case 61: {
          double doubleRight = Double.longBitsToDouble(doubleStack[--sp]);
          double doubleLeft = Double.longBitsToDouble(doubleStack[sp - 1]);
          kinds[sp - 1] = 1; stack[sp - 1] = Double.isNaN(doubleLeft) || Double.isNaN(doubleRight) ? 1 :
            Double.compare(doubleLeft, doubleRight); break;
        }
        case 62:
          doubleStack[sp - 1] = Double.doubleToRawLongBits(stack[sp - 1]); kinds[sp - 1] = 3; break;
        case 63:
          stack[sp - 1] = (int) Double.longBitsToDouble(doubleStack[sp - 1]); kinds[sp - 1] = 1; break;
        case 64: return Double.longBitsToDouble(doubleStack[--sp]);
        case 65:
          kinds[sp] = 4; doubleStack[sp++] = ((long) operand << 32) | (extra & 0xffffffffL); break;
        case 66: kinds[sp] = 4; doubleStack[sp++] = doubleLocals[operand]; break;
        case 67: doubleLocals[operand] = doubleStack[--sp]; break;
        case 68: --sp; break;
        case 69: kinds[sp] = 4; doubleStack[sp] = doubleStack[sp - 1]; sp++; break;
        case 70: doubleStack[sp - 2] += doubleStack[sp - 1]; --sp; break;
        case 71: doubleStack[sp - 2] -= doubleStack[sp - 1]; --sp; break;
        case 72: doubleStack[sp - 2] *= doubleStack[sp - 1]; --sp; break;
        case 73: doubleStack[sp - 2] /= doubleStack[sp - 1]; --sp; break;
        case 74: doubleStack[sp - 2] %= doubleStack[sp - 1]; --sp; break;
        case 75: doubleStack[sp - 1] = -doubleStack[sp - 1]; break;
        case 76: b = stack[--sp]; doubleStack[sp - 1] <<= b; break;
        case 77: b = stack[--sp]; doubleStack[sp - 1] >>= b; break;
        case 78: b = stack[--sp]; doubleStack[sp - 1] >>>= b; break;
        case 79: doubleStack[sp - 2] &= doubleStack[sp - 1]; --sp; break;
        case 80: doubleStack[sp - 2] |= doubleStack[sp - 1]; --sp; break;
        case 81: doubleStack[sp - 2] ^= doubleStack[sp - 1]; --sp; break;
        case 82:
          long longRight = doubleStack[--sp], longLeft = doubleStack[sp - 1];
          kinds[sp - 1] = 1; stack[sp - 1] = Long.compare(longLeft, longRight); break;
        case 83: doubleStack[sp - 1] = stack[sp - 1]; kinds[sp - 1] = 4; break;
        case 84: stack[sp - 1] = (int) doubleStack[sp - 1]; kinds[sp - 1] = 1; break;
        case 85:
          doubleStack[sp - 1] = Double.doubleToRawLongBits((double) doubleStack[sp - 1]); kinds[sp - 1] = 3; break;
        case 86:
          doubleStack[sp - 1] = (long) Double.longBitsToDouble(doubleStack[sp - 1]); kinds[sp - 1] = 4; break;
        case 87: return doubleStack[--sp];
        case 88:
          value = referenceStack[sp - 1]; referenceStack[sp - 1] = null;
          stack[sp - 1] = Array.getLength(value); kinds[sp - 1] = 1; break;
        case 89:
          b = stack[--sp]; value = referenceStack[sp - 1]; referenceStack[sp - 1] = null;
          stack[sp - 1] = ((int[]) value)[b]; kinds[sp - 1] = 1; break;
        case 90:
          b = stack[--sp]; value = referenceStack[sp - 1]; referenceStack[sp - 1] = null;
          doubleStack[sp - 1] = ((long[]) value)[b]; kinds[sp - 1] = 4; break;
        case 91:
          b = stack[--sp]; value = referenceStack[sp - 1]; referenceStack[sp - 1] = null;
          doubleStack[sp - 1] = Double.doubleToRawLongBits(((double[]) value)[b]); kinds[sp - 1] = 3; break;
        case 92:
          b = stack[--sp]; value = referenceStack[sp - 1];
          referenceStack[sp - 1] = ((Object[]) value)[b]; kinds[sp - 1] = 2; break;
        case 93:
          b = stack[--sp]; value = referenceStack[sp - 1]; referenceStack[sp - 1] = null;
          stack[sp - 1] = value instanceof boolean[] ? (((boolean[]) value)[b] ? 1 : 0) : ((byte[]) value)[b];
          kinds[sp - 1] = 1; break;
        case 94:
          b = stack[--sp]; value = referenceStack[sp - 1]; referenceStack[sp - 1] = null;
          stack[sp - 1] = ((char[]) value)[b]; kinds[sp - 1] = 1; break;
        case 95:
          b = stack[--sp]; value = referenceStack[sp - 1]; referenceStack[sp - 1] = null;
          stack[sp - 1] = ((short[]) value)[b]; kinds[sp - 1] = 1; break;
        case 96:
          a = stack[--sp]; b = stack[--sp]; value = referenceStack[--sp]; referenceStack[sp] = null;
          ((int[]) value)[b] = a; break;
        case 97:
          longRight = doubleStack[--sp]; b = stack[--sp]; value = referenceStack[--sp]; referenceStack[sp] = null;
          ((long[]) value)[b] = longRight; break;
        case 98:
          longRight = doubleStack[--sp]; b = stack[--sp]; value = referenceStack[--sp]; referenceStack[sp] = null;
          ((double[]) value)[b] = Double.longBitsToDouble(longRight); break;
        case 99:
          Object arrayValue = referenceStack[--sp]; referenceStack[sp] = null;
          b = stack[--sp]; value = referenceStack[--sp]; referenceStack[sp] = null;
          ((Object[]) value)[b] = arrayValue; break;
        case 100:
          a = stack[--sp]; b = stack[--sp]; value = referenceStack[--sp]; referenceStack[sp] = null;
          if (value instanceof boolean[]) ((boolean[]) value)[b] = a != 0; else ((byte[]) value)[b] = (byte) a; break;
        case 101:
          a = stack[--sp]; b = stack[--sp]; value = referenceStack[--sp]; referenceStack[sp] = null;
          ((char[]) value)[b] = (char) a; break;
        case 102:
          a = stack[--sp]; b = stack[--sp]; value = referenceStack[--sp]; referenceStack[sp] = null;
          ((short[]) value)[b] = (short) a; break;
        case 103:
          value = referenceStack[--sp]; referenceStack[sp] = null;
          VirtualizationRuntime.throwUnchecked((Throwable) value); break;
        case 104: kinds[sp] = 5; stack[sp++] = operand; break;
        case 105: kinds[sp] = 5; stack[sp++] = locals[operand]; break;
        case 106: locals[operand] = stack[--sp]; break;
        case 107:
          stack[sp - 2] = Float.floatToRawIntBits(Float.intBitsToFloat(stack[sp - 2]) +
            Float.intBitsToFloat(stack[sp - 1])); --sp; break;
        case 108:
          stack[sp - 2] = Float.floatToRawIntBits(Float.intBitsToFloat(stack[sp - 2]) -
            Float.intBitsToFloat(stack[sp - 1])); --sp; break;
        case 109:
          stack[sp - 2] = Float.floatToRawIntBits(Float.intBitsToFloat(stack[sp - 2]) *
            Float.intBitsToFloat(stack[sp - 1])); --sp; break;
        case 110:
          stack[sp - 2] = Float.floatToRawIntBits(Float.intBitsToFloat(stack[sp - 2]) /
            Float.intBitsToFloat(stack[sp - 1])); --sp; break;
        case 111:
          stack[sp - 2] = Float.floatToRawIntBits(Float.intBitsToFloat(stack[sp - 2]) %
            Float.intBitsToFloat(stack[sp - 1])); --sp; break;
        case 112:
          stack[sp - 1] = Float.floatToRawIntBits(-Float.intBitsToFloat(stack[sp - 1])); break;
        case 113: {
          float floatRight = Float.intBitsToFloat(stack[--sp]), floatLeft = Float.intBitsToFloat(stack[sp - 1]);
          kinds[sp - 1] = 1; stack[sp - 1] = Float.isNaN(floatLeft) || Float.isNaN(floatRight) ? -1 :
            Float.compare(floatLeft, floatRight); break;
        }
        case 114: {
          float floatRight = Float.intBitsToFloat(stack[--sp]), floatLeft = Float.intBitsToFloat(stack[sp - 1]);
          kinds[sp - 1] = 1; stack[sp - 1] = Float.isNaN(floatLeft) || Float.isNaN(floatRight) ? 1 :
            Float.compare(floatLeft, floatRight); break;
        }
        case 115: stack[sp - 1] = Float.floatToRawIntBits((float) stack[sp - 1]); kinds[sp - 1] = 5; break;
        case 116: stack[sp - 1] = (int) Float.intBitsToFloat(stack[sp - 1]); kinds[sp - 1] = 1; break;
        case 117:
          stack[sp - 1] = Float.floatToRawIntBits((float) doubleStack[sp - 1]); kinds[sp - 1] = 5; break;
        case 118:
          doubleStack[sp - 1] = (long) Float.intBitsToFloat(stack[sp - 1]); kinds[sp - 1] = 4; break;
        case 119:
          doubleStack[sp - 1] = Double.doubleToRawLongBits(Float.intBitsToFloat(stack[sp - 1])); kinds[sp - 1] = 3; break;
        case 120:
          stack[sp - 1] = Float.floatToRawIntBits((float) Double.longBitsToDouble(doubleStack[sp - 1])); kinds[sp - 1] = 5; break;
        case 121: return Float.intBitsToFloat(stack[--sp]);
        case 122:
          b = stack[--sp]; value = referenceStack[sp - 1]; referenceStack[sp - 1] = null;
          stack[sp - 1] = Float.floatToRawIntBits(((float[]) value)[b]); kinds[sp - 1] = 5; break;
        case 123:
          a = stack[--sp]; b = stack[--sp]; value = referenceStack[--sp]; referenceStack[sp] = null;
          ((float[]) value)[b] = Float.intBitsToFloat(a); break;
        default: throw new IllegalStateException("Invalid VM instruction");
      }
      }
    } finally {
      releaseFrame(frame);
    }
  }
}
