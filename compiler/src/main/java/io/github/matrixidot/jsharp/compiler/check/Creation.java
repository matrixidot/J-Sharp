package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.FieldInit;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.resolve.TypeResolver;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.PropertySymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Object creation: {@code new} with constructor resolution (diamond and target-typed forms),
 * anonymous classes, object initializers ({@code new T { x = 1 }}) and the {@code required} members
 * they must set, and the default implementations of interfaces created directly.
 */
final class Creation {
  private final Attr a;

  Creation(Attr a) {
    this.a = a;
  }

  BExpr newExpr(Expr.New n, Type pt) {
    Type t;
    if (n.type() == null) {
      if (pt == null || pt.isError()) {
        if (pt == null) {
          a.report(
              a.err(Code.CANNOT_INFER, n.span(), "cannot infer the type of 'new()' here")
                  .help("write the type: new SomeType(...)"));
        }
        return new BExpr.Error(Type.ErrorType.INSTANCE, n.span());
      }
      t = pt.withNullness(Nullness.NON_NULL);
      if (t instanceof ClassType ct
          && ct.args().stream().anyMatch(a -> a instanceof Type.WildcardType)) {
        t = a.types.nonWildcard(ct);
      }
      if (t instanceof ClassType ict && (ict.sym().isInterface() || ict.sym().isAbstract())) {
        ClassSymbol impl = defaultImplementation(ict.sym());
        if (impl != null) {
          t = new ClassType(impl, ict.args(), Nullness.NON_NULL);
        }
      }
      if (!(t instanceof ClassType)) {
        a.error(
            Code.TYPE_MISMATCH, n.span(), "'new()' cannot create a value of type " + pt.display());
        return new BExpr.Error(Type.ErrorType.INSTANCE, n.span());
      }
    } else {
      t = a.resolveType(n.type(), TypeResolver.RawMode.INFER);
      if (t.isError()) {
        if (n.args() != null) {
          for (var arg : n.args()) {
            a.speculate(() -> a.value(arg.value(), null));
          }
        }
        return new BExpr.Error(Type.ErrorType.INSTANCE, n.span());
      }
      if (!(t instanceof ClassType)) {
        a.error(Code.TYPE_MISMATCH, n.type().span(), "cannot instantiate " + t.display());
        return new BExpr.Error(Type.ErrorType.INSTANCE, n.span());
      }
    }
    ClassType ct = (ClassType) t;
    ClassSymbol c = ct.sym();
    if (n.anonBody() != null) {
      return a.patterns.anonymousClass(n, ct, pt);
    }
    if (c.isAbstract() || c.isInterface()) {
      Diagnostic.Builder d =
          a.err(
              Code.ABSTRACT_INSTANTIATION,
              n.span(),
              "cannot create an instance of "
                  + (c.isInterface() ? "interface " : "abstract class ")
                  + c.displayName());
      if (c.isInterface()) {
        d.help(
            "implement it with a class, an anonymous class 'new "
                + c.name()
                + "() { ... }', or a lambda");
      }
      a.report(d);
      return new BExpr.Error(ct, n.span());
    }
    if (c.isEnum()) {
      a.error(
          Code.ABSTRACT_INSTANTIATION,
          n.span(),
          "enum " + c.displayName() + " cannot be instantiated; use one of its constants");
      return new BExpr.Error(ct, n.span());
    }
    BExpr created =
        a.calls.construct(
            ct,
            n.args() == null ? List.of() : n.args(),
            pt,
            n.span(),
            n.type() == null || isDiamondOrRaw(n.type(), c));
    if (n.init() != null) {
      return objectInit(created, n.init(), n.span());
    }
    checkRequiredMembers(created.type(), List.of(), n.span());
    return created;
  }

  /**
   * The class {@code new()} instantiates for a collection interface target (spec 3.4's {@code
   * List<String> xs = new();}): ArrayList, HashMap, HashSet, ArrayDeque, TreeMap, TreeSet.
   */
  ClassSymbol defaultImplementation(ClassSymbol iface) {
    String impl =
        switch (iface.binaryName()) {
          case "java/util/List",
              "java/util/Collection",
              "java/lang/Iterable",
              "java/util/SequencedCollection" ->
              "java/util/ArrayList";
          case "java/util/Map" -> "java/util/HashMap";
          case "java/util/SequencedMap" -> "java/util/LinkedHashMap";
          case "java/util/Set" -> "java/util/HashSet";
          case "java/util/SequencedSet" -> "java/util/LinkedHashSet";
          case "java/util/Queue", "java/util/Deque" -> "java/util/ArrayDeque";
          case "java/util/SortedMap", "java/util/NavigableMap" -> "java/util/TreeMap";
          case "java/util/SortedSet", "java/util/NavigableSet" -> "java/util/TreeSet";
          case "java/util/concurrent/ConcurrentMap" -> "java/util/concurrent/ConcurrentHashMap";
          default -> null;
        };
    return impl == null ? null : a.syms.lookup(impl);
  }

  static boolean isDiamondOrRaw(TypeNode tn, ClassSymbol c) {
    if (c.typeParams().isEmpty()) {
      return false;
    }
    TypeNode inner = tn;
    if (inner instanceof TypeNode.Named named) {
      TypeNode.Segment last = named.last();
      return last.diamond() || last.typeArgs().isEmpty();
    }
    return false;
  }

  BExpr objectInit(BExpr created, List<FieldInit> inits, Span span) {
    Type site = created.type();
    if (site.isError()) {
      for (FieldInit fi : inits) {
        a.value(fi.value(), null);
      }
      return created;
    }
    List<BExpr.MemberInit> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (FieldInit fi : inits) {
      if (!seen.add(fi.name())) {
        a.error(Code.DUPLICATE_MEMBER, fi.nameSpan(), "'" + fi.name() + "' is initialized twice");
      }
      PropertySymbol p = a.lookup.findProperty(site, fi.name());
      if (p != null) {
        if (p.setter() == null) {
          a.error(Code.NOT_ASSIGNABLE, fi.nameSpan(), "property '" + fi.name() + "' is read-only");
          a.value(fi.value(), null);
          continue;
        }
        a.members.checkAccess(p.setter(), p.owner(), site, fi.nameSpan());
        Type pt = a.members.memberType(a.members.captureSite(site), p.owner(), p.type());
        out.add(new BExpr.MemberInit(p, a.exprCoerced(fi.value(), pt, fi.value().span())));
        continue;
      }
      FieldSymbol f = a.lookup.findField(site, fi.name());
      if (f != null && a.members.hiddenByAccessor(f, site, a.lookup.findSetter(site, fi.name()))) {
        f = null; // a property of a compiled J# class: its private field yields to the setter
      }
      if (f != null && !f.isStatic()) {
        a.members.checkAccess(f, f.owner(), site, fi.nameSpan());
        if (f.has(Flags.FINAL)) {
          a.error(Code.NOT_ASSIGNABLE, fi.nameSpan(), "field '" + fi.name() + "' is final");
        }
        out.add(
            new BExpr.MemberInit(
                f,
                a.exprCoerced(
                    fi.value(),
                    a.members.memberType(site, f.owner(), f.type()),
                    fi.value().span())));
        continue;
      }
      MethodSymbol setter = a.lookup.findSetter(site, fi.name());
      if (setter != null) {
        a.members.checkAccess(setter, setter.owner(), site, fi.nameSpan());
        Type pt =
            a.members.memberType(
                a.members.captureSite(site), setter.owner(), setter.params().getFirst().type());
        out.add(new BExpr.MemberInit(setter, a.exprCoerced(fi.value(), pt, fi.value().span())));
        continue;
      }
      a.members.reportNoMember(site, fi.name(), fi.nameSpan());
      a.value(fi.value(), null);
    }
    checkRequiredMembers(site, new ArrayList<>(seen), span);
    return new BExpr.ObjectInit(created, out, site, span);
  }

  /** Every {@code required} property/field of the created type must be set by the initializer. */
  void checkRequiredMembers(Type site, List<String> initialized, Span span) {
    if (!(site instanceof ClassType ct) || !ct.sym().isSource() && !ct.sym().has(Flags.JSHARP)) {
      return;
    }
    List<String> missing = new ArrayList<>();
    for (ClassSymbol c : a.lookup.hierarchy(site)) {
      for (PropertySymbol p : c.properties()) {
        if (p.isRequired() && !initialized.contains(p.name())) {
          missing.add(p.name());
        }
      }
      for (FieldSymbol f : c.fields()) {
        if (f.has(Flags.REQUIRED)
            && !f.has(Flags.BACKING_FIELD)
            && !initialized.contains(f.name())) {
          missing.add(f.name());
        }
      }
    }
    if (!missing.isEmpty()) {
      a.report(
          a.err(
                  Code.REQUIRED_MEMBER_MISSING,
                  span,
                  "required member"
                      + (missing.size() > 1 ? "s " : " ")
                      + String.join(", ", missing.stream().map(m -> "'" + m + "'").toList())
                      + " must be set")
              .help(
                  "add an object initializer: new "
                      + ct.sym().name()
                      + "(...) { "
                      + missing.getFirst()
                      + " = ... }"));
    }
  }
}
