package io.github.matrixidot.jsharp.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Hand-written Java equivalents of the J# kernels in {@code src/jsharp/bench/kernels.jsharp}. */
final class JavaKernels {
  private JavaKernels() {}

  sealed interface Shape permits Circle, Rect, Tri {}

  record Circle(double r) implements Shape {}

  record Rect(double w, double h) implements Shape {}

  record Tri(double b, double h) implements Shape {}

  static final class Particle {
    private double x;
    private double y;
    private double vx;
    private double vy;

    Particle(double x, double y, double vx, double vy) {
      this.x = x;
      this.y = y;
      this.vx = vx;
      this.vy = vy;
    }

    double getX() {
      return x;
    }

    void setX(double v) {
      x = v;
    }

    double getY() {
      return y;
    }

    void setY(double v) {
      y = v;
    }

    double getVx() {
      return vx;
    }

    void setVx(double v) {
      vx = v;
    }

    double getVy() {
      return vy;
    }

    void setVy(double v) {
      vy = v;
    }
  }

  static int fib(int n) {
    return n < 2 ? n : fib(n - 1) + fib(n - 2);
  }

  static long sumArray(int[] xs) {
    long total = 0;
    for (int x : xs) {
      total += x;
    }
    return total;
  }

  static long sumSquaresOfEvens(List<Integer> xs) {
    return xs.stream().filter(x -> x % 2 == 0).mapToLong(x -> (long) x * x).sum();
  }

  static List<Shape> makeShapes(int n) {
    List<Shape> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(
          switch (i % 3) {
            case 0 -> new Circle(i % 7 + 1);
            case 1 -> new Rect(i % 5 + 1, i % 3 + 1);
            default -> new Tri(i % 4 + 1, i % 6 + 1);
          });
    }
    return out;
  }

  static double area(Shape s) {
    return switch (s) {
      case Circle c -> Math.PI * c.r() * c.r();
      case Rect r -> r.w() * r.h();
      case Tri t -> 0.5 * t.b() * t.h();
    };
  }

  static double totalArea(List<Shape> shapes) {
    double total = 0;
    for (Shape s : shapes) {
      total += area(s);
    }
    return total;
  }

  static int buildStrings(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append("item " + i + ": " + i * 2 + ", ");
    }
    return sb.length();
  }

  static int wordCount(String[] words) {
    HashMap<String, Integer> counts = new HashMap<>();
    for (String w : words) {
      counts.merge(w, 1, Integer::sum);
    }
    return counts.size();
  }

  static double simulate(Particle[] ps, int steps) {
    for (int s = 0; s < steps; s++) {
      for (Particle p : ps) {
        p.setX(p.getX() + p.getVx() * 0.01);
        p.setY(p.getY() + p.getVy() * 0.01);
        if (p.getX() < 0 || p.getX() > 100) {
          p.setVx(-p.getVx());
        }
        if (p.getY() < 0 || p.getY() > 100) {
          p.setVy(-p.getVy());
        }
      }
    }
    double sum = 0;
    for (Particle p : ps) {
      sum += p.getX() + p.getY();
    }
    return sum;
  }

  static int interpret(int[] ops) {
    int acc = 0;
    for (int op : ops) {
      switch (op) {
        case 0 -> acc += 1;
        case 1 -> acc -= 1;
        case 2 -> acc *= 2;
        case 3 -> acc /= 2;
        case 4 -> acc ^= 0x5f;
        case 5 -> acc = acc << 1;
        case 6 -> acc = acc >> 1;
        case 7 -> acc += 7;
        default -> acc = 0;
      }
    }
    return acc;
  }

  static int dayNumber(String day) {
    return switch (day) {
      case "mon" -> 1;
      case "tue" -> 2;
      case "wed" -> 3;
      case "thu" -> 4;
      case "fri" -> 5;
      case "sat" -> 6;
      case "sun" -> 7;
      default -> 0;
    };
  }

  static int sumDays(String[] days) {
    int total = 0;
    for (String d : days) {
      total += dayNumber(d);
    }
    return total;
  }

  static int wide(int[] ops) {
    int acc = 0;
    for (int op : ops) {
      switch (op) {
        case 0 -> acc += 3;
        case 1 -> acc += 10;
        case 2 -> acc += 17;
        case 3 -> acc += 24;
        case 4 -> acc += 31;
        case 5 -> acc += 38;
        case 6 -> acc += 45;
        case 7 -> acc += 52;
        case 8 -> acc += 59;
        case 9 -> acc += 66;
        case 10 -> acc += 73;
        case 11 -> acc += 80;
        case 12 -> acc += 87;
        case 13 -> acc += 94;
        case 14 -> acc += 101;
        case 15 -> acc += 108;
        case 16 -> acc += 115;
        case 17 -> acc += 122;
        case 18 -> acc += 129;
        case 19 -> acc += 136;
        case 20 -> acc += 143;
        case 21 -> acc += 150;
        case 22 -> acc += 157;
        case 23 -> acc += 164;
        case 24 -> acc += 171;
        case 25 -> acc += 178;
        case 26 -> acc += 185;
        case 27 -> acc += 192;
        case 28 -> acc += 199;
        case 29 -> acc += 206;
        case 30 -> acc += 213;
        case 31 -> acc += 220;
        default -> acc = 0;
      }
    }
    return acc;
  }

  sealed interface Op permits Op0, Op1, Op2, Op3, Op4, Op5, Op6, Op7, Op8, Op9 {}

  record Op0(int v) implements Op {}

  record Op1(int v) implements Op {}

  record Op2(int v) implements Op {}

  record Op3(int v) implements Op {}

  record Op4(int v) implements Op {}

  record Op5(int v) implements Op {}

  record Op6(int v) implements Op {}

  record Op7(int v) implements Op {}

  record Op8(int v) implements Op {}

  record Op9(int v) implements Op {}

  static int weigh(Op op) {
    return switch (op) {
      case Op0 o -> o.v() * 1;
      case Op1 o -> o.v() * 2;
      case Op2 o -> o.v() * 3;
      case Op3 o -> o.v() * 4;
      case Op4 o -> o.v() * 5;
      case Op5 o -> o.v() * 6;
      case Op6 o -> o.v() * 7;
      case Op7 o -> o.v() * 8;
      case Op8 o -> o.v() * 9;
      case Op9 o -> o.v() * 10;
    };
  }

  static int weighAll(List<Op> ops) {
    int t = 0;
    for (Op o : ops) {
      t += weigh(o);
    }
    return t;
  }

  static List<Op> makeOps(int count) {
    List<Op> out = new ArrayList<>();
    java.util.Random rnd = new java.util.Random(3);
    for (int i = 0; i < count; i++) {
      int k = rnd.nextInt(10);
      out.add(
          switch (k) {
            case 0 -> new Op0(i % 13);
            case 1 -> new Op1(i % 13);
            case 2 -> new Op2(i % 13);
            case 3 -> new Op3(i % 13);
            case 4 -> new Op4(i % 13);
            case 5 -> new Op5(i % 13);
            case 6 -> new Op6(i % 13);
            case 7 -> new Op7(i % 13);
            case 8 -> new Op8(i % 13);
            case 9 -> new Op9(i % 13);
            default -> new Op0(0);
          });
    }
    return out;
  }

  record Point3(int x, int y, int z) {}

  static int distinctPoints(int n) {
    java.util.HashSet<Point3> set = new java.util.HashSet<>();
    for (int i = 0; i < n; i++) {
      set.add(new Point3(i % 50, i % 30, i % 7));
    }
    return set.size();
  }

  private static final java.util.concurrent.ExecutorService VIRTUAL =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

  static long fanOut(int n) {
    List<java.util.concurrent.CompletableFuture<Long>> tasks = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int k = i;
      tasks.add(
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                long s = 0;
                for (int j = 0; j < 1000; j++) {
                  s += j ^ k;
                }
                return s;
              },
              VIRTUAL));
    }
    long total = 0;
    for (var t : tasks) {
      total += t.join();
    }
    return total;
  }
}
