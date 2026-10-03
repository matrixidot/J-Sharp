import inventory.*;
import java.util.List;

public class Main {
  public static void main(String[] args) {
    Inventory<Integer> inv = new Inventory<>();
    inv.add(new Item("hammer", Category.Tool, 2));
    inv.add(new Item("apple", Category.Food, 10));
    inv.add(new Item("saw", Category.Tool, 1));
    System.out.println(inv.getSize());
    System.out.println(inv.getOwner());
    inv.setOwner("ann");
    System.out.println(inv.getOwner());
    inv.setLocked(true);
    System.out.println(inv.isLocked());
    System.out.println(inv.getNote());
    Item h = inv.find("hammer");
    System.out.println(h.name() + " " + h.category().getLabel() + " " + h.quantity());
    System.out.println(inv.find("nails"));
    List<Item> tools = inv.byCategory(Category.Tool);
    System.out.println(InventoryModule.total(tools));
    System.out.println(inv.describe("x", 2));
    System.out.println(inv.describe("y"));
    System.out.println(inv.describe());
    Integer tag = inv.tag(42);
    System.out.println(tag + 1);
    Event e = new Added(h);
    String s =
        switch (e) {
          case Added a -> "java saw " + a.item().name();
          case Removed r -> "java saw removal of " + r.name();
        };
    System.out.println(s);
    System.out.println(InventoryModule.summarize(new Removed("saw")));
    System.out.println(InventoryModule.shout("hi"));
    System.out.println(Event.class.isSealed() + " " + Event.class.getPermittedSubclasses().length);
    System.out.println(new Item("a", Category.Food, 1).equals(new Item("a", Category.Food, 1)));
    System.out.println(Category.valueOf("Food").ordinal());
    System.out.println(Item.plus(h, 3).quantity());
  }
}
