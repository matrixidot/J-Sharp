package dev.jsharp.compiler.testing;

import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.FieldSymbol;
import dev.jsharp.compiler.symbols.Flags;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.PropertySymbol;
import dev.jsharp.compiler.symbols.TypeVarSymbol;
import dev.jsharp.compiler.types.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Deterministic dump of class symbols for golden tests. */
public final class SymbolPrinter {
  private SymbolPrinter() {}

  private static final long[] FLAG_BITS = {
    Flags.PUBLIC,
    Flags.PROTECTED,
    Flags.PRIVATE,
    Flags.STATIC,
    Flags.FINAL,
    Flags.ABSTRACT,
    Flags.SEALED,
    Flags.OPEN,
    Flags.OVERRIDE,
    Flags.ASYNC,
    Flags.REQUIRED,
    Flags.DEFAULT,
    Flags.EXTENSION,
    Flags.GETTER,
    Flags.SETTER,
    Flags.INIT_ONLY,
    Flags.SYNTHETIC,
    Flags.GENERATED,
    Flags.VARARGS,
    Flags.ENTRY_POINT,
    Flags.COMPACT_CTOR,
    Flags.BACKING_FIELD,
    Flags.ENUM_CONSTANT,
    Flags.INFERRED_TYPE,
    Flags.MODULE,
    Flags.RECORD,
    Flags.ENUM,
    Flags.INTERFACE
  };
  private static final String[] FLAG_NAMES = {
    "public",
    "protected",
    "private",
    "static",
    "final",
    "abstract",
    "sealed",
    "open",
    "override",
    "async",
    "required",
    "default",
    "extension",
    "getter",
    "setter",
    "init-only",
    "synthetic",
    "generated",
    "varargs",
    "entry-point",
    "compact",
    "backing",
    "enum-constant",
    "inferred",
    "module",
    "record",
    "enum",
    "interface"
  };

  static String flags(long f, boolean isMethod) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < FLAG_BITS.length; i++) {
      long bit = FLAG_BITS[i];
      if (!isMethod && bit == Flags.VARARGS) {
        continue; // same bit as TRANSIENT on fields
      }
      if ((f & bit) != 0) {
        out.add(FLAG_NAMES[i]);
      }
    }
    return out.isEmpty() ? "" : " [" + String.join(" ", out) + "]";
  }

  static String type(Type t) {
    return t == null ? "<inferred>" : t.display();
  }

  public static String print(List<ClassSymbol> classes) {
    StringBuilder sb = new StringBuilder();
    List<ClassSymbol> sorted = new ArrayList<>(classes);
    sorted.sort(Comparator.comparing(ClassSymbol::binaryName));
    for (ClassSymbol c : sorted) {
      sb.append(c.kindName()).append(' ').append(c.binaryName());
      if (!c.typeParams().isEmpty()) {
        sb.append('<');
        for (int i = 0; i < c.typeParams().size(); i++) {
          TypeVarSymbol tv = c.typeParams().get(i);
          if (i > 0) {
            sb.append(", ");
          }
          if (tv.variance() != dev.jsharp.compiler.ast.TypeParam.Variance.INVARIANT) {
            sb.append(tv.variance().name().toLowerCase(Locale.ROOT)).append(' ');
          }
          sb.append(tv.name());
          sb.append(" : ")
              .append(String.join(" & ", tv.bounds().stream().map(Type::display).toList()));
        }
        sb.append('>');
      }
      List<String> sups = new ArrayList<>();
      if (c.superclass() != null) {
        sups.add(c.superclass().display());
      }
      c.interfaces().forEach(i -> sups.add(i.display()));
      if (!sups.isEmpty()) {
        sb.append(" : ").append(String.join(", ", sups));
      }
      if (!c.permitted().isEmpty()) {
        sb.append(" permits ")
            .append(String.join(", ", c.permitted().stream().map(ClassSymbol::name).toList()));
      }
      sb.append(flags(c.flags() & ~Flags.SOURCE, false)).append('\n');
      List<FieldSymbol> fields = new ArrayList<>(c.fields());
      for (FieldSymbol f : fields) {
        sb.append("  field ")
            .append(f.name())
            .append(": ")
            .append(type(f.type()))
            .append(flags(f.flags(), false))
            .append('\n');
      }
      for (PropertySymbol p : c.properties()) {
        sb.append("  property ").append(p.name()).append(": ").append(type(p.type()));
        sb.append(" get=").append(p.getter() == null ? "-" : p.getter().jvmName());
        sb.append(" set=").append(p.setter() == null ? "-" : p.setter().jvmName());
        sb.append(p.backingField() != null ? " backed" : "")
            .append(flags(p.flags(), false))
            .append('\n');
      }
      List<MethodSymbol> methods = new ArrayList<>(c.allMethods());
      for (MethodSymbol m : methods) {
        sb.append("  method ");
        if (!m.typeParams().isEmpty()) {
          sb.append('<')
              .append(String.join(", ", m.typeParams().stream().map(TypeVarSymbol::name).toList()))
              .append("> ");
        }
        sb.append(m.jvmName()).append('(');
        for (int i = 0; i < m.params().size(); i++) {
          MethodSymbol.Param p = m.params().get(i);
          if (i > 0) {
            sb.append(", ");
          }
          sb.append(type(p.type())).append(' ').append(p.name());
          if (p.hasDefault()) {
            sb.append(" = ?");
          }
        }
        sb.append("): ").append(type(m.returnType())).append(flags(m.flags(), true)).append('\n');
      }
    }
    return sb.toString();
  }
}
