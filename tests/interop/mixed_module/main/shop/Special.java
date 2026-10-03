package shop;

/** A Java class extending a J# base class. */
public class Special extends Item {
    public Special(String name, int priceCents) { super(name, priceCents, Catalog.Category.FOOD); }
    @Override public String label() { return "*" + super.label() + "*"; }
}
