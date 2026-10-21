package reg;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Shaped like NeoForge's DeferredRegister: method type variables bounded by the class's. */
public class Registry<T> {
  private final List<String> names = new ArrayList<>();

  public <I extends T> Holder<T, I> register(String name, Supplier<? extends I> factory) {
    names.add(name);
    return new Holder<>(name, factory.get());
  }

  public <I extends T> Holder<T, I> register(String name, Function<String, ? extends I> factory) {
    names.add(name);
    return new Holder<>(name, factory.apply(name));
  }

  public List<String> names() {
    return names;
  }

  public static final class Holder<R, V extends R> {
    private final String name;
    private final V value;

    Holder(String name, V value) {
      this.name = name;
      this.value = value;
    }

    public V get() {
      return value;
    }

    public String name() {
      return name;
    }
  }
}
