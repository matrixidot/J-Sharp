package shop;

/** A Java class implementing a J# interface. */
public class PercentOff implements Discount {
    private final int percent;
    public PercentOff(int percent) { this.percent = percent; }
    @Override public int apply(int cents) { return cents - cents * percent / 100; }
}
