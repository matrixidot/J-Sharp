package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Annotation;
import io.github.matrixidot.jsharp.compiler.ast.Arg;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.Param;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.symbols.AnnotationUse;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.PropertySymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks the annotations written on a class's declarations and records them on the symbols, for
 * code generation (D109). An annotation must name an annotation type that applies to the
 * declaration ({@code @Target}); its arguments must name the type's elements, give every element
 * without a default, and be constants, enum constants, class literals or arrays of them. Its
 * {@code @Retention} decides whether it is kept, and whether reflection sees it.
 *
 * <p>A property's annotations go on its getter, or on its backing field when only fields may carry
 * them; {@code @get:}, {@code @set:} and {@code @field:} choose. A record component's go where Java
 * puts them: the field, the generated accessor and the canonical constructor's parameter, as far as
 * the annotation applies to each.
 */
final class Annotations {
  private final Attr a;

  private Annotations(Attr a) {
    this.a = a;
  }

  /** Checks the annotations of {@code c} and its members; {@code a.env} is the class's. */
  static void check(Attr a, ClassSymbol c) {
    new Annotations(a).checkClass(c);
  }

  private void checkClass(ClassSymbol c) {
    if (c.decl() != null) {
      String kind = c.isAnnotation() ? "ANNOTATION_TYPE" : "TYPE";
      c.setAnnotations(onDeclaration(c.decl().modifiers().annotations(), kind));
    }
    for (FieldSymbol f : c.fields()) {
      if (f.decl() instanceof Decl.Field d) {
        f.setAnnotations(onDeclaration(d.modifiers().annotations(), "FIELD"));
      }
    }
    for (MethodSymbol m : c.allMethods()) {
      switch (m.decl()) {
        case Decl.Method d -> {
          m.setAnnotations(onDeclaration(d.modifiers().annotations(), "METHOD"));
          m.setParameterAnnotations(parameters(d.params()));
        }
        case Decl.Constructor d -> {
          m.setAnnotations(onDeclaration(d.modifiers().annotations(), "CONSTRUCTOR"));
          if (d.params() != null) {
            m.setParameterAnnotations(parameters(d.params()));
          }
        }
        case null, default -> {}
      }
    }
    for (PropertySymbol p : c.properties()) {
      if (p.decl() != null) {
        property(p);
      }
    }
    if (c.isRecord() && c.decl() != null && c.decl().header() != null) {
      recordComponents(c, c.decl().header());
    }
  }

  private List<List<AnnotationUse>> parameters(List<Param> params) {
    List<List<AnnotationUse>> out = new ArrayList<>();
    boolean any = false;
    for (Param p : params) {
      List<AnnotationUse> anns = onDeclaration(p.modifiers().annotations(), "PARAMETER");
      any |= !anns.isEmpty();
      out.add(anns);
    }
    return any ? out : List.of();
  }

  /** Annotations of a declaration of one kind: use-site targets do not apply. */
  private List<AnnotationUse> onDeclaration(List<Annotation> anns, String kind) {
    List<AnnotationUse> out = new ArrayList<>();
    Set<ClassSymbol> seen = new HashSet<>();
    for (Annotation an : anns) {
      if ("file".equals(an.target())) {
        continue; // file annotations (@file:ClassName) are the file's, checked by Enter
      }
      if (an.target() != null) {
        a.error(
            Code.ANNOTATION_NOT_APPLICABLE,
            an.span(),
            "'@" + an.target() + ":' applies to properties only");
        continue;
      }
      Checked checked = resolve(an);
      if (checked == null) {
        continue;
      }
      if (!seen.add(checked.type)) {
        a.error(Code.ANNOTATION_NOT_APPLICABLE, an.span(), repeated(checked.type));
        continue;
      }
      if (!applies(an, checked.type, kind)) {
        continue;
      }
      if (checked.use != null && kindKeeps(checked.type, kind)) {
        out.add(checked.use);
      }
    }
    return out;
  }

  private void property(PropertySymbol p) {
    List<AnnotationUse> getter = new ArrayList<>();
    List<AnnotationUse> setter = new ArrayList<>();
    List<AnnotationUse> field = new ArrayList<>();
    for (Annotation an : p.decl().modifiers().annotations()) {
      Checked checked = resolve(an);
      if (checked == null) {
        continue;
      }
      String target = an.target();
      if (target == null) {
        // The getter, or the backing field for annotations only fields take.
        target =
            allows(checked.type, "METHOD")
                    || p.backingField() == null
                    || !allows(checked.type, "FIELD")
                ? "get"
                : "field";
      }
      switch (target) {
        case "get" -> add(an, checked, "METHOD", p.getter(), getter);
        case "set" -> add(an, checked, "METHOD", p.setter(), setter);
        case "field" -> add(an, checked, "FIELD", p.backingField(), field);
        default ->
            a.error(
                Code.ANNOTATION_NOT_APPLICABLE,
                an.span(),
                "unknown use-site target '@"
                    + target
                    + ":' (use @get:, @set: or @field: on a property)");
      }
    }
    if (p.getter() != null && !getter.isEmpty()) {
      p.getter().setAnnotations(getter);
    }
    if (p.setter() != null && !setter.isEmpty()) {
      p.setter().setAnnotations(setter);
    }
    if (p.backingField() != null && !field.isEmpty()) {
      p.backingField().setAnnotations(field);
    }
  }

  private void add(
      Annotation an, Checked checked, String kind, Object member, List<AnnotationUse> into) {
    if (member == null) {
      a.error(
          Code.ANNOTATION_NOT_APPLICABLE,
          an.span(),
          "the property has no " + (kind.equals("FIELD") ? "backing field" : "such accessor"));
      return;
    }
    boolean duplicate = into.stream().anyMatch(u -> u.type() == checked.type);
    if (duplicate) {
      a.error(Code.ANNOTATION_NOT_APPLICABLE, an.span(), repeated(checked.type));
    } else if (applies(an, checked.type, kind)
        && checked.use != null
        && kindKeeps(checked.type, kind)) {
      into.add(checked.use);
    }
  }

  private void recordComponents(ClassSymbol c, List<Param> header) {
    MethodSymbol canonical = null;
    for (MethodSymbol m : c.allMethods()) {
      if (m.isConstructor() && m.has(Flags.RECORD) && m.has(Flags.GENERATED)) {
        canonical = m;
      }
    }
    List<List<AnnotationUse>> ctorParams = new ArrayList<>();
    boolean anyParam = false;
    for (Param p : header) {
      List<AnnotationUse> field = new ArrayList<>();
      List<AnnotationUse> accessor = new ArrayList<>();
      List<AnnotationUse> param = new ArrayList<>();
      for (Annotation an : p.modifiers().annotations()) {
        Checked checked = resolve(an);
        if (checked == null || checked.use == null) {
          continue;
        }
        boolean placed = false;
        for (String kind : List.of("FIELD", "METHOD", "PARAMETER", "RECORD_COMPONENT")) {
          if (allows(checked.type, kind)) {
            placed = true;
            switch (kind) {
              case "FIELD" -> field.add(checked.use);
              case "METHOD" -> accessor.add(checked.use);
              case "PARAMETER" -> param.add(checked.use);
              default -> {} // the Record attribute's annotations are not written
            }
          }
        }
        if (!placed && !allows(checked.type, "TYPE_USE")) {
          a.error(
              Code.ANNOTATION_NOT_APPLICABLE,
              an.span(),
              "@" + checked.type.name() + " does not apply to record components");
        }
      }
      FieldSymbol f = c.field(p.name());
      if (f != null && !field.isEmpty()) {
        f.setAnnotations(field);
      }
      for (MethodSymbol m : c.methods(p.name())) {
        if (m.params().isEmpty() && m.has(Flags.GENERATED) && !accessor.isEmpty()) {
          m.setAnnotations(accessor);
        }
      }
      anyParam |= !param.isEmpty();
      ctorParams.add(param);
    }
    if (canonical != null && anyParam) {
      canonical.setParameterAnnotations(ctorParams);
    }
  }

  // ------------------------------------------------------------------ one annotation

  /**
   * A resolved annotation: its type, and what to write ({@code use} is null for {@code SOURCE}
   * retention, or when the arguments had errors).
   */
  private record Checked(ClassSymbol type, AnnotationUse use) {}

  private Checked resolve(Annotation an) {
    Type t = a.resolveType(an.name());
    if (t.isError() || !(t instanceof Type.ClassType ct)) {
      return null;
    }
    ClassSymbol type = ct.sym();
    if (!type.isAnnotation()) {
      a.error(
          Code.NOT_AN_ANNOTATION,
          an.name().span(),
          "'" + type.name() + "' is not an annotation type");
      return null;
    }
    List<AnnotationUse.Element> elements = new ArrayList<>();
    Map<String, MethodSymbol> declared = new HashMap<>();
    for (MethodSymbol m : type.allMethods()) {
      if (!m.isStatic() && m.params().isEmpty()) {
        declared.put(m.name(), m);
      }
    }
    boolean ok = true;
    Set<String> given = new HashSet<>();
    for (Arg arg : an.args()) {
      String name = arg.name() != null ? arg.name() : "value";
      MethodSymbol element = declared.get(name);
      if (element == null) {
        a.error(
            Code.INVALID_ANNOTATION_ARGUMENT,
            arg.span(),
            "@" + type.name() + " has no element '" + name + "'");
        ok = false;
        continue;
      }
      if (!given.add(name)) {
        a.error(
            Code.INVALID_ANNOTATION_ARGUMENT, arg.span(), "element '" + name + "' is given twice");
        ok = false;
        continue;
      }
      AnnotationUse.Value v = value(arg.value(), element.returnType());
      if (v == null) {
        ok = false;
      } else {
        elements.add(new AnnotationUse.Element(name, v));
      }
    }
    List<String> missing = new ArrayList<>();
    for (MethodSymbol m : declared.values()) {
      if (!given.contains(m.name()) && !m.has(Flags.ANNOTATION_DEFAULT)) {
        missing.add(m.name());
      }
    }
    if (!missing.isEmpty()) {
      missing.sort(null);
      a.error(
          Code.INVALID_ANNOTATION_ARGUMENT,
          an.span(),
          "@" + type.name() + " needs a value for '" + String.join("', '", missing) + "'");
      ok = false;
    }
    boolean kept = !type.retention().equals("SOURCE");
    return new Checked(
        type,
        ok && kept ? new AnnotationUse(type, type.retention().equals("RUNTIME"), elements) : null);
  }

  /** An element value of the element type {@code t}, or null after reporting an error. */
  private AnnotationUse.Value value(Expr e, Type t) {
    if (t instanceof Type.ArrayType at) {
      List<Expr> items =
          switch (e) {
            case Expr.ArrayInit ai -> ai.elements();
            case Expr.CollectionLiteral cl -> cl.elements();
            default -> List.of(e); // a single value for an array element, as in Java
          };
      List<AnnotationUse.Value> values = new ArrayList<>();
      for (Expr item : items) {
        AnnotationUse.Value v = value(item, at.elem());
        if (v == null) {
          return null;
        }
        values.add(v);
      }
      return new AnnotationUse.Array(values);
    }
    if (e instanceof Expr.ArrayInit || e instanceof Expr.CollectionLiteral) {
      a.error(
          Code.INVALID_ANNOTATION_ARGUMENT, e.span(), "this element takes one value, not an array");
      return null;
    }
    if (t instanceof Type.ClassType ct && ct.sym().isAnnotation()) {
      a.error(
          Code.INVALID_ANNOTATION_ARGUMENT,
          e.span(),
          "annotations as element values are not supported yet");
      return null;
    }
    BExpr b = a.exprCoerced(e, t);
    if (b.type().isError()) {
      return null; // reported
    }
    return switch (b) {
      case BExpr.Const k when ConstFold.isConst(k) -> new AnnotationUse.Constant(k.value(), t);
      case BExpr.Field f when f.field().has(Flags.ENUM_CONSTANT) ->
          new AnnotationUse.EnumConstant(f.field().owner(), f.field().name());
      case BExpr.ClassLit cl -> new AnnotationUse.ClassValue(cl.target());
      default -> {
        a.error(
            Code.INVALID_ANNOTATION_ARGUMENT,
            e.span(),
            "an annotation value must be a constant, an enum constant, a class literal or an"
                + " array of them");
        yield null;
      }
    };
  }

  // ------------------------------------------------------------------ targets

  /** Whether the annotation type may annotate a declaration of {@code kind} ({@code @Target}). */
  private static boolean allows(ClassSymbol type, String kind) {
    Set<String> targets = type.annotationTargets();
    if (targets == null) {
      return !kind.equals("TYPE_USE");
    }
    return targets.contains(kind) || kind.equals("ANNOTATION_TYPE") && targets.contains("TYPE");
  }

  /** Reports and returns false when the annotation does not apply to a {@code kind}. */
  private boolean applies(Annotation an, ClassSymbol type, String kind) {
    if (allows(type, kind) || allows(type, "TYPE_USE")) {
      return true;
    }
    a.error(
        Code.ANNOTATION_NOT_APPLICABLE,
        an.span(),
        "@" + type.name() + " does not apply to a " + describe(kind));
    return false;
  }

  /**
   * True if the annotation is written as a declaration annotation: a {@code TYPE_USE}-only one is a
   * type annotation, which J# does not write.
   */
  private static boolean kindKeeps(ClassSymbol type, String kind) {
    return allows(type, kind);
  }

  private static String repeated(ClassSymbol type) {
    return "@" + type.name() + " is given twice (repeating annotations are not supported yet)";
  }

  private static String describe(String kind) {
    return switch (kind) {
      case "TYPE" -> "class, interface, enum or record";
      case "ANNOTATION_TYPE" -> "annotation type";
      case "FIELD" -> "field";
      case "METHOD" -> "method";
      case "CONSTRUCTOR" -> "constructor";
      case "PARAMETER" -> "parameter";
      default -> kind.toLowerCase(Locale.ROOT);
    };
  }
}
