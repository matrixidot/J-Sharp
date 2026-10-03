import plugins.Counter;
import plugins.Host;
import plugins.Plugin;

public class Main {
  static class LoudHost extends Host {
    @Override
    protected void onRegister(Plugin p) {
      System.out.println("LOUD " + p.name());
    }

    @Override
    public String banner() {
      return "LoudHost";
    }
  }

  public static void main(String[] args) {
    Host h = Host.create();
    h.register(() -> "lambda");
    h.register(
        new Plugin() {
          public String name() {
            return "anon";
          }

          public String greet(String who) {
            return "anon waves at " + who;
          }
        });
    System.out.println(h.run("you"));
    Host loud = new LoudHost();
    loud.register(() -> "p");
    System.out.println(loud.run("me"));
    Counter c =
        new Counter() {
          public int step() {
            return 5;
          }
        };
    c.tick();
    System.out.println(c.tick());
  }
}
