package shapes;

/** A Java abstract class with a template method, extended from J#. */
public abstract class Shape implements Comparable<Shape> {
  private final String name;

  protected Shape(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public abstract double area();

  public final String describe() {
    return String.format("%s with area %.2f", name, area());
  }

  @Override
  public int compareTo(Shape o) {
    return Double.compare(area(), o.area());
  }
}
