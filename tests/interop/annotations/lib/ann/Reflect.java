package ann;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

/** Prints annotations as reflection sees them, elements sorted by name (J#'s own left out). */
public final class Reflect {
  private Reflect() {}

  public static String of(AnnotatedElement e) {
    return all(e.getAnnotations());
  }

  public static String parameters(Class<?> c, String method) {
    for (Method m : c.getDeclaredMethods()) {
      if (m.getName().equals(method)) {
        return Arrays.stream(m.getParameterAnnotations())
            .map(Reflect::all)
            .collect(Collectors.joining(" "));
      }
    }
    return "no method " + method;
  }

  private static String all(Annotation[] anns) {
    return Arrays.stream(anns)
        .filter(a -> !a.annotationType().getName().startsWith("jsharp."))
        .map(Reflect::one)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  private static String one(Annotation a) {
    return "@"
        + a.annotationType().getSimpleName()
        + Arrays.stream(a.annotationType().getDeclaredMethods())
            .sorted(Comparator.comparing(Method::getName))
            .map(m -> m.getName() + "=" + value(a, m))
            .collect(Collectors.joining(", ", "(", ")"));
  }

  private static String value(Annotation a, Method m) {
    try {
      Object v = m.invoke(a);
      return v instanceof Object[] arr
          ? Arrays.toString(arr)
          : v instanceof Class<?> c ? c.getSimpleName() : String.valueOf(v);
    } catch (ReflectiveOperationException e) {
      return e.toString();
    }
  }
}
