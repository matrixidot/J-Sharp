package dev.jsharp.bench;

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
}
