package io.github.matrixidot.jsharp.compiler.resolve;

import io.github.matrixidot.jsharp.compiler.Context;
import io.github.matrixidot.jsharp.compiler.ast.Accessor;
import io.github.matrixidot.jsharp.compiler.ast.Body;
import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.EnumConstant;
import io.github.matrixidot.jsharp.compiler.ast.LocalKind;
import io.github.matrixidot.jsharp.compiler.ast.Modifier;
import io.github.matrixidot.jsharp.compiler.ast.Modifiers;
import io.github.matrixidot.jsharp.compiler.ast.Param;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.ast.TypeParam;
import io.github.matrixidot.jsharp.compiler.ast.VarDeclarator;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.PropertySymbol;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Descriptors;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completer for source classes: resolves headers (type parameters, supertypes, permits) and member
 * signatures (fields, methods, constructors, properties, record components, enum constants, module
 * members), synthesizing the implicit members the JVM model needs (default constructors, record
 * accessors and canonical constructors, enum {@code values}/{@code valueOf}, property accessors and
 * backing fields, the entry point for top-level statements).
 *
 * <p>Inferred member types ({@code val} fields, omitted return types) are left null here and filled
 * in by the checker.
 */
public final class MemberEnter implements ClassSymbol.Completer {
  private final Context ctx;
  private final TypeResolver resolver;
  private final Map<ClassSymbol, TypeScope> scopes = new IdentityHashMap<>();

  public MemberEnter(Context ctx) {
    this.ctx = ctx;
    this.resolver = new TypeResolver(ctx);
  }

  public TypeResolver resolver() {
    return resolver;
  }

  /** The type scope of a class body. */
  public TypeScope classScope(ClassSymbol c) {
    TypeScope s = scopes.get(c);
    if (s == null) {
      TypeScope parent =
          c.outer() != null
              ? classScope(c.outer())
              : new TypeScope.FileLevel(ctx.fileScope(c.unit()));
      s = new TypeScope.ClassLevel(c, parent, c.has(Flags.LOCAL) || c.has(Flags.ANONYMOUS));
      scopes.put(c, s);
    }
    return s;
  }

  /** Registers a scope for a local/anonymous class created by the checker. */
  public void setClassScope(ClassSymbol c, TypeScope s) {
    scopes.put(c, s);
  }

  private SourceFile file(ClassSymbol c) {
    return c.unit().file();
  }

  // ------------------------------------------------------------------ header

  @Override
  public void completeHeader(ClassSymbol c) {
    if (c.has(Flags.MODULE)) {
      c.setSuperclass(ctx.syms.objectType());
      return;
    }
    if (c.has(Flags.ANONYMOUS)) {
      return; // supertypes are set by the checker from the 'new' expression
    }
    Decl.TypeDecl decl = c.decl();
    List<TypeVarSymbol> tvs = TypeResolver.declare(decl.typeParams(), c);
    c.setTypeParams(tvs);
    TypeScope scope = classScope(c);
    resolver.resolveBounds(decl.typeParams(), tvs, scope);
    if (decl.kind() != Decl.TypeKind.INTERFACE) {
      for (TypeParam tp : decl.typeParams()) {
        if (tp.variance() != TypeParam.Variance.INVARIANT) {
          ctx.report(
              Code.INVALID_VARIANCE,
              file(c),
              tp.span(),
              "variance annotations ('in'/'out') are only allowed on interface type parameters");
        }
      }
    }

    ClassType superclass = null;
    List<ClassType> interfaces = new ArrayList<>();
    for (int i = 0; i < decl.supertypes().size(); i++) {
      TypeNode tn = decl.supertypes().get(i);
      Type t = resolver.resolve(tn, scope);
      if (t.isError()) {
        continue;
      }
      if (!(t instanceof ClassType ct)) {
        ctx.report(
            Code.INVALID_SUPERTYPE, file(c), tn.span(), "cannot inherit from " + t.display());
        continue;
      }
      ClassSymbol s = ct.sym();
      if (s == c) {
        ctx.report(
            Code.CYCLIC_INHERITANCE,
            file(c),
            tn.span(),
            c.kindName() + " " + c.name() + " cannot inherit from itself");
        continue;
      }
      if (s.isInterface()) {
        if (interfaces.stream().anyMatch(x -> x.sym() == s)) {
          ctx.report(
              Code.INVALID_SUPERTYPE,
              file(c),
              tn.span(),
              "interface " + s.displayName() + " is listed twice");
          continue;
        }
        if (s.isAnnotation()) {
          ctx.report(
              Code.INVALID_SUPERTYPE,
              file(c),
              tn.span(),
              "cannot implement annotation type " + s.displayName());
          continue;
        }
        interfaces.add(ct);
        continue;
      }
      switch (decl.kind()) {
        case INTERFACE -> {
          ctx.report(
              Code.INVALID_SUPERTYPE,
              file(c),
              tn.span(),
              "an interface can only extend interfaces, but "
                  + s.displayName()
                  + " is a "
                  + s.kindName());
          continue;
        }
        case RECORD, ENUM -> {
          ctx.report(
              Code.INVALID_SUPERTYPE,
              file(c),
              tn.span(),
              decl.kind().keyword()
                  + "s cannot extend classes; "
                  + s.displayName()
                  + " is a "
                  + s.kindName());
          continue;
        }
        default -> {}
      }
      if (i > 0 || superclass != null) {
        ctx.error(
                Code.INVALID_SUPERTYPE,
                file(c),
                tn.span(),
                "the base class must be listed first and only once")
            .help("write 'class " + c.name() + " : " + s.name() + ", ...'")
            .report(ctx.diags);
        continue;
      }
      String bn = s.binaryName();
      if (bn.equals("java/lang/Enum") || bn.equals("java/lang/Record")) {
        ctx.report(
            Code.INVALID_SUPERTYPE,
            file(c),
            tn.span(),
            "cannot extend "
                + s.qualifiedName()
                + " directly; use '"
                + (bn.endsWith("Enum") ? "enum" : "record")
                + "'");
        continue;
      }
      if (s.isFinal() && !s.isEnum() && !s.isRecord() || s.isEnum() || s.isRecord()) {
        var b =
            ctx.error(
                Code.CANNOT_INHERIT_FINAL,
                file(c),
                tn.span(),
                "cannot inherit from final " + s.kindName() + " " + s.displayName());
        if (s.isSource() && !s.isEnum() && !s.isRecord()) {
          b.help("J# classes are final by default; declare it 'base class " + s.name() + "'");
        }
        b.report(ctx.diags);
        continue;
      }
      superclass = ct;
    }
    switch (decl.kind()) {
      case CLASS -> {
        if (superclass == null) {
          superclass = ctx.syms.objectType();
        }
      }
      case RECORD -> superclass = ctx.syms.wellKnown("java/lang/Record");
      case ENUM ->
          superclass =
              new ClassType(
                  ctx.syms.wellKnown("java/lang/Enum").sym(),
                  List.of(ClassType.of(c)),
                  Nullness.NON_NULL);
      case INTERFACE -> superclass = null;
    }
    c.setSuperclass(superclass);
    c.setInterfaces(interfaces);
    // Cycle detection; break cycles so later phases terminate.
    if (superclass != null && reaches(superclass.sym(), c, new HashSet<>())) {
      ctx.report(
          Code.CYCLIC_INHERITANCE,
          file(c),
          decl.nameSpan(),
          "cyclic inheritance involving " + c.name());
      c.setSuperclass(decl.kind() == Decl.TypeKind.CLASS ? ctx.syms.objectType() : superclass);
    }
    List<ClassType> acyclic = new ArrayList<>();
    for (ClassType it : interfaces) {
      if (reaches(it.sym(), c, new HashSet<>())) {
        ctx.report(
            Code.CYCLIC_INHERITANCE,
            file(c),
            decl.nameSpan(),
            "cyclic inheritance involving " + c.name());
      } else {
        acyclic.add(it);
      }
    }
    c.setInterfaces(acyclic);
    if (decl.permits() != null) {
      if (!c.has(Flags.SEALED)) {
        ctx.error(
                Code.INVALID_PERMITS,
                file(c),
                decl.nameSpan(),
                "'permits' requires a sealed " + c.kindName())
            .help("declare it 'sealed " + decl.kind().keyword() + " " + c.name() + "'")
            .report(ctx.diags);
      }
      List<ClassSymbol> permitted = new ArrayList<>();
      for (TypeNode tn : decl.permits()) {
        Type t = resolver.resolve(tn, scope, false, TypeResolver.RawMode.WILDCARD);
        if (t instanceof ClassType ct) {
          permitted.add(ct.sym());
        }
      }
      c.setPermitted(permitted);
    }
  }

  /**
   * True if {@code target} is a (reflexive) supertype of {@code from}, following resolved edges.
   */
  private static boolean reaches(ClassSymbol from, ClassSymbol target, Set<ClassSymbol> seen) {
    if (from == target) {
      return true;
    }
    if (!seen.add(from) || from.headerInProgress()) {
      return false;
    }
    if (from.superclass() != null && reaches(from.superclass().sym(), target, seen)) {
      return true;
    }
    for (ClassType i : from.interfaces()) {
      if (reaches(i.sym(), target, seen)) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------ members

  @Override
  public void completeMembers(ClassSymbol c) {
    if (c.has(Flags.MODULE)) {
      completeModule(c);
      return;
    }
    Decl.TypeDecl decl = c.decl();
    TypeScope scope = classScope(c);
    if (decl.kind() == Decl.TypeKind.RECORD) {
      enterRecordComponents(c, decl, scope);
    }
    if (decl.kind() == Decl.TypeKind.ENUM) {
      enterEnum(c, decl, scope);
    }
    boolean hasCtor = false;
    for (Decl m : decl.members()) {
      switch (m) {
        case Decl.Field f -> enterField(c, f, scope, false);
        case Decl.Method md -> enterMethod(c, md, scope, false);
        case Decl.Constructor k -> {
          enterConstructor(c, k, scope);
          hasCtor = true;
        }
        case Decl.Property p -> enterProperty(c, p, scope);
        case Decl.Initializer init -> {
          if (c.isInterface()) {
            ctx.report(
                Code.UNEXPECTED_BODY,
                file(c),
                init.span(),
                "interfaces cannot have initializer blocks");
          }
        }
        case Decl.TypeDecl nested -> {}
        case Decl.TopLevelStmt t -> {}
      }
    }
    if (decl.kind() == Decl.TypeKind.RECORD) {
      ensureCanonicalConstructor(c, decl);
    } else if (decl.kind() == Decl.TypeKind.ENUM) {
      if (!hasCtor && decl.header() == null) {
        addGeneratedCtor(c, Flags.PRIVATE, List.of());
      }
    } else if (decl.kind() == Decl.TypeKind.CLASS && !hasCtor && !c.has(Flags.ANONYMOUS)) {
      long access = c.flags() & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE);
      if (c.isAbstract() && Flags.is(access, Flags.PUBLIC)) {
        access = Flags.PROTECTED;
      }
      addGeneratedCtor(c, access, List.of());
    }
    checkDuplicates(c);
  }

  private MethodSymbol addGeneratedCtor(
      ClassSymbol c, long access, List<MethodSymbol.Param> params) {
    MethodSymbol k = new MethodSymbol(MethodSymbol.CONSTRUCTOR, c, access | Flags.GENERATED);
    k.setParams(params);
    k.setReturnType(PrimType.VOID);
    c.addMethod(k);
    return k;
  }

  // ------------------------------------------------------------------ fields

  private void enterField(ClassSymbol c, Decl.Field f, TypeScope scope, boolean topLevel) {
    SourceFile file = file(c);
    Modifiers mods = f.modifiers();
    ModifierRules.allowOnly(
        ctx,
        file,
        mods,
        "a field",
        Modifier.PUBLIC,
        Modifier.PROTECTED,
        Modifier.PRIVATE,
        Modifier.INTERNAL,
        Modifier.STATIC,
        Modifier.FINAL,
        Modifier.VOLATILE,
        Modifier.TRANSIENT,
        Modifier.REQUIRED);
    long flags = ModifierRules.accessFlags(ctx, mods, file, !topLevel, c.isInterface());
    if (mods.has(Modifier.STATIC) || topLevel) {
      flags |= Flags.STATIC;
    }
    if (mods.has(Modifier.FINAL) || f.kind() == LocalKind.VAL) {
      flags |= Flags.FINAL;
    }
    if (mods.has(Modifier.VOLATILE)) {
      flags |= Flags.VOLATILE;
    }
    if (mods.has(Modifier.TRANSIENT)) {
      flags |= Flags.TRANSIENT;
    }
    if (mods.has(Modifier.REQUIRED)) {
      flags |= Flags.REQUIRED;
    }
    if (c.isInterface()) {
      if (!mods.has(Modifier.STATIC)) {
        ctx.error(Code.INVALID_MODIFIER, file, f.span(), "interfaces cannot have instance fields")
            .help("declare a property instead, or make it 'static'")
            .report(ctx.diags);
      }
      flags |= Flags.STATIC | Flags.FINAL | Flags.PUBLIC;
    }
    if (c.isRecord() && !Flags.is(flags, Flags.STATIC)) {
      ctx.error(Code.INVALID_MODIFIER, file, f.span(), "records cannot declare instance fields")
          .help("add a record component, or make the field 'static'")
          .report(ctx.diags);
    }
    if (Flags.is(flags, Flags.FINAL) && Flags.is(flags, Flags.VOLATILE)) {
      ctx.report(
          Code.INVALID_MODIFIER,
          file,
          mods.span(),
          "a field cannot be both 'final' and 'volatile'");
    }
    Type type = null;
    if (f.kind() == LocalKind.TYPED) {
      type = resolver.resolve(f.type(), scope);
    } else {
      flags |= Flags.INFERRED_TYPE;
    }
    for (VarDeclarator v : f.vars()) {
      if (f.kind() != LocalKind.TYPED && v.init() == null) {
        ctx.report(
            Code.INVALID_PROPERTY,
            file,
            v.nameSpan(),
            "'"
                + (f.kind() == LocalKind.VAL ? "val" : "var")
                + "' needs an initializer to infer the type of '"
                + v.name()
                + "'");
      }
      if (c.isInterface() && v.init() == null) {
        ctx.report(
            Code.INVALID_PROPERTY, file, v.nameSpan(), "interface constants need an initializer");
      }
      if (!claimName(c, v.name(), v.nameSpan())) {
        continue;
      }
      FieldSymbol fs = new FieldSymbol(v.name(), c, flags, type);
      fs.setSource(f, v);
      c.addField(fs);
    }
  }

  // ------------------------------------------------------------------ methods

  private void enterMethod(ClassSymbol c, Decl.Method md, TypeScope scope, boolean topLevel) {
    SourceFile file = file(c);
    Modifiers mods = md.modifiers();
    ModifierRules.allowOnly(
        ctx,
        file,
        mods,
        "a method",
        Modifier.PUBLIC,
        Modifier.PROTECTED,
        Modifier.PRIVATE,
        Modifier.INTERNAL,
        Modifier.STATIC,
        Modifier.FINAL,
        Modifier.ABSTRACT,
        Modifier.BASE,
        Modifier.OVERRIDE,
        Modifier.ASYNC,
        Modifier.DEFAULT,
        Modifier.SYNCHRONIZED,
        Modifier.NATIVE);
    long flags = ModifierRules.accessFlags(ctx, mods, file, !topLevel, c.isInterface());
    boolean isStatic = mods.has(Modifier.STATIC) || topLevel;
    if (isStatic) {
      flags |= Flags.STATIC;
    }
    if (topLevel && md.name().equals("main")) {
      flags = (flags & ~(Flags.PRIVATE | Flags.PROTECTED)) | Flags.PUBLIC;
    }
    boolean hasBody = md.body() != null;
    boolean isAbstract =
        mods.has(Modifier.ABSTRACT)
            || (c.isInterface() && !hasBody && !isStatic && !mods.has(Modifier.PRIVATE));
    if (isAbstract) {
      flags |= Flags.ABSTRACT;
      if (hasBody) {
        ctx.report(
            Code.UNEXPECTED_BODY,
            file,
            md.nameSpan(),
            "abstract method '" + md.name() + "' cannot have a body");
      }
      if (isStatic) {
        ctx.report(
            Code.INVALID_MODIFIER,
            file,
            md.nameSpan(),
            "a method cannot be both 'static' and 'abstract'");
      }
      if (!c.isInterface() && !c.isAbstract()) {
        ctx.error(
                Code.INVALID_MODIFIER,
                file,
                md.nameSpan(),
                "abstract method '"
                    + md.name()
                    + "' in non-abstract "
                    + c.kindName()
                    + " "
                    + c.name())
            .help("declare the class 'abstract'")
            .report(ctx.diags);
      }
    } else if (!hasBody && !mods.has(Modifier.NATIVE)) {
      ctx.report(Code.MISSING_BODY, file, md.nameSpan(), "method '" + md.name() + "' needs a body");
    }
    if (c.isInterface() && hasBody && !isStatic && !mods.has(Modifier.PRIVATE)) {
      flags |= Flags.DEFAULT;
    }
    if (mods.has(Modifier.DEFAULT) && !c.isInterface()) {
      ModifierRules.invalid(
          ctx,
          file,
          ModifierRules.itemOf(mods, Modifier.DEFAULT),
          "'default' is only allowed on interface methods");
    }
    if (mods.has(Modifier.BASE)) {
      flags |= Flags.BASE;
      if (isStatic || mods.has(Modifier.PRIVATE)) {
        ModifierRules.invalid(
            ctx,
            file,
            ModifierRules.itemOf(mods, Modifier.BASE),
            "static and private methods cannot be 'base'");
      }
    }
    if (mods.has(Modifier.OVERRIDE)) {
      flags |= Flags.OVERRIDE;
      if (isStatic) {
        ModifierRules.invalid(
            ctx,
            file,
            ModifierRules.itemOf(mods, Modifier.OVERRIDE),
            "static methods cannot override");
      }
    }
    if (mods.has(Modifier.ASYNC)) {
      flags |= Flags.ASYNC;
    }
    if (mods.has(Modifier.SYNCHRONIZED)) {
      flags |= Flags.SYNCHRONIZED;
    }
    if (mods.has(Modifier.NATIVE)) {
      flags |= Flags.NATIVE;
    }
    flags |= finalityFlag(c, mods, flags);
    MethodSymbol m = new MethodSymbol(md.name(), c, flags);
    m.setDecl(md);
    List<TypeVarSymbol> tvs = TypeResolver.declare(md.typeParams(), m);
    m.setTypeParams(tvs);
    TypeScope mscope = tvs.isEmpty() ? scope : new TypeScope.MethodLevel(tvs, scope);
    resolver.resolveBounds(md.typeParams(), tvs, mscope);
    for (TypeParam tp : md.typeParams()) {
      if (tp.variance() != TypeParam.Variance.INVARIANT) {
        ctx.report(
            Code.INVALID_VARIANCE,
            file,
            tp.span(),
            "method type parameters cannot declare variance");
      }
    }
    m.setParams(enterParams(c, m, md.params(), mscope, file));
    if (m.isExtension() && !isStatic) {
      ctx.error(
              Code.EXTENSION_NOT_STATIC,
              file,
              md.nameSpan(),
              "extension method '" + md.name() + "' must be static")
          .help("add 'static', or declare it at the top level of a file")
          .report(ctx.diags);
    }
    if (md.returnType() != null) {
      m.setReturnType(resolver.resolveReturn(md.returnType(), mscope));
    } else {
      m.addFlags(Flags.INFERRED_TYPE);
      boolean privateExpr = Flags.is(flags, Flags.PRIVATE) && md.body() instanceof Body.ExprBody;
      if (!privateExpr) {
        ctx.error(
                Code.MISSING_RETURN_TYPE,
                file,
                md.nameSpan(),
                "method '" + md.name() + "' must declare a return type")
            .help("return types may only be omitted on private expression-bodied methods")
            .report(ctx.diags);
        m.setReturnType(Type.ErrorType.INSTANCE);
      }
    }
    c.addMethod(m);
  }

  /** Adds FINAL to overridable-looking instance methods of base classes that are not base. */
  private static long finalityFlag(ClassSymbol c, Modifiers mods, long flags) {
    boolean classAllowsSubclasses = !c.isFinal() && !c.isInterface();
    if (!classAllowsSubclasses || Flags.is(flags, Flags.STATIC | Flags.PRIVATE | Flags.ABSTRACT)) {
      return 0;
    }
    if (mods.has(Modifier.FINAL)) {
      return Flags.FINAL;
    }
    if (Flags.is(flags, Flags.BASE) || Flags.is(flags, Flags.OVERRIDE)) {
      return 0;
    }
    return Flags.FINAL;
  }

  private List<MethodSymbol.Param> enterParams(
      ClassSymbol c, MethodSymbol m, List<Param> params, TypeScope scope, SourceFile file) {
    List<MethodSymbol.Param> out = new ArrayList<>();
    Set<String> names = new HashSet<>();
    boolean sawDefault = false;
    for (int i = 0; i < params.size(); i++) {
      Param p = params.get(i);
      if (!p.name().equals("_") && !names.add(p.name())) {
        ctx.report(
            Code.DUPLICATE_PARAMETER, file, p.nameSpan(), "duplicate parameter '" + p.name() + "'");
      }
      Type t = resolver.resolve(p.type(), scope);
      if (p.isThis()) {
        if (i != 0) {
          ctx.report(
              Code.EXTENSION_NOT_STATIC,
              file,
              p.span(),
              "only the first parameter can be the extension receiver 'this'");
        } else {
          m.addFlags(Flags.EXTENSION);
        }
      }
      if (p.isParams()) {
        if (i != params.size() - 1) {
          ctx.report(
              Code.INVALID_DEFAULT_ARGUMENT,
              file,
              p.span(),
              "a 'params' parameter must be the last parameter");
        } else if (!(t instanceof Type.ArrayType) && !t.isError()) {
          ctx.report(
              Code.INVALID_DEFAULT_ARGUMENT,
              file,
              p.type().span(),
              "a 'params' parameter must have an array type");
        } else {
          m.addFlags(Flags.VARARGS);
        }
      }
      if (p.defaultValue() != null) {
        sawDefault = true;
        if (p.isParams()) {
          ctx.report(
              Code.INVALID_DEFAULT_ARGUMENT,
              file,
              p.defaultValue().span(),
              "a 'params' parameter cannot have a default value");
        }
      } else if (sawDefault && !p.isParams()) {
        ctx.error(
                Code.INVALID_DEFAULT_ARGUMENT,
                file,
                p.nameSpan(),
                "parameter '" + p.name() + "' without a default value follows a parameter with one")
            .help("move parameters with default values to the end")
            .report(ctx.diags);
      }
      out.add(
          new MethodSymbol.Param(
              p.name(), t, p.isParams(), p.defaultValue(), p.defaultValue() != null, null));
    }
    return out;
  }

  private void enterConstructor(ClassSymbol c, Decl.Constructor k, TypeScope scope) {
    SourceFile file = file(c);
    Modifiers mods = k.modifiers();
    ModifierRules.allowOnly(
        ctx,
        file,
        mods,
        "a constructor",
        Modifier.PUBLIC,
        Modifier.PROTECTED,
        Modifier.PRIVATE,
        Modifier.INTERNAL);
    if (c.isInterface()) {
      ctx.report(Code.INVALID_TOP_LEVEL, file, k.nameSpan(), "interfaces cannot have constructors");
      return;
    }
    long flags = ModifierRules.accessFlags(ctx, mods, file, true, false);
    if (c.isEnum()) {
      if (Flags.is(flags, Flags.PUBLIC | Flags.PROTECTED)) {
        ctx.report(
            Code.INVALID_MODIFIER, file, mods.span(), "enum constructors are always private");
      }
      flags = Flags.PRIVATE;
    }
    if (k.body() == null) {
      ctx.report(Code.MISSING_BODY, file, k.nameSpan(), "constructor needs a body");
    }
    MethodSymbol m = new MethodSymbol(MethodSymbol.CONSTRUCTOR, c, flags);
    m.setDecl(k);
    m.setReturnType(PrimType.VOID);
    if (k.params() == null) {
      if (!c.isRecord()) {
        ctx.report(
            Code.INVALID_TOP_LEVEL,
            file,
            k.nameSpan(),
            "only records can have a compact constructor");
        return;
      }
      m.addFlags(Flags.COMPACT_CTOR);
      List<MethodSymbol.Param> ps = new ArrayList<>();
      for (FieldSymbol rc : c.recordComponents()) {
        ps.add(MethodSymbol.Param.of(rc.name(), rc.type()));
      }
      m.setParams(ps);
    } else {
      m.setParams(enterParams(c, m, k.params(), scope, file));
      if (m.isExtension()) {
        ctx.report(
            Code.EXTENSION_NOT_STATIC,
            file,
            k.nameSpan(),
            "a constructor cannot have an extension receiver");
      }
    }
    c.addMethod(m);
  }

  // ------------------------------------------------------------------ properties

  /** {@code name} -> {@code getName}; primitive boolean {@code active} -> {@code isActive}. */
  public static String getterName(String name, Type type) {
    if (type == PrimType.BOOLEAN) {
      if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
        return name;
      }
      return "is" + capitalize(name);
    }
    return "get" + capitalize(name);
  }

  public static String setterName(String name, Type type) {
    if (type == PrimType.BOOLEAN
        && name.length() > 2
        && name.startsWith("is")
        && Character.isUpperCase(name.charAt(2))) {
      return "set" + name.substring(2);
    }
    return "set" + capitalize(name);
  }

  static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private void enterProperty(ClassSymbol c, Decl.Property p, TypeScope scope) {
    SourceFile file = file(c);
    Modifiers mods = p.modifiers();
    ModifierRules.allowOnly(
        ctx,
        file,
        mods,
        "a property",
        Modifier.PUBLIC,
        Modifier.PROTECTED,
        Modifier.PRIVATE,
        Modifier.INTERNAL,
        Modifier.STATIC,
        Modifier.ABSTRACT,
        Modifier.BASE,
        Modifier.OVERRIDE,
        Modifier.REQUIRED,
        Modifier.FINAL);
    long access = ModifierRules.accessFlags(ctx, mods, file, true, c.isInterface());
    boolean isStatic = mods.has(Modifier.STATIC);
    Type type = resolver.resolve(p.type(), scope);
    long pflags = access | (isStatic ? Flags.STATIC : 0);
    if (mods.has(Modifier.REQUIRED)) {
      pflags |= Flags.REQUIRED;
      if (isStatic) {
        ctx.report(
            Code.INVALID_PROPERTY, file, p.nameSpan(), "a static property cannot be 'required'");
      }
    }
    Accessor get = null;
    Accessor set = null;
    if (p.accessors() != null) {
      for (Accessor a : p.accessors()) {
        if (a.kind() == Accessor.Kind.GET) {
          if (get != null) {
            ctx.report(Code.INVALID_PROPERTY, file, a.span(), "duplicate 'get' accessor");
          }
          get = a;
        } else {
          if (set != null) {
            ctx.report(
                Code.INVALID_PROPERTY,
                file,
                a.span(),
                "a property can have only one 'set' or 'init' accessor");
          }
          set = a;
        }
      }
      if (get == null) {
        ctx.report(
            Code.INVALID_PROPERTY,
            file,
            p.nameSpan(),
            "property '" + p.name() + "' needs a 'get' accessor");
        return;
      }
    }
    boolean isInterfaceMember = c.isInterface() && !isStatic;
    boolean declaredAbstract = mods.has(Modifier.ABSTRACT);
    boolean bodiless =
        p.accessors() != null && p.accessors().stream().anyMatch(a -> a.body() == null);
    boolean isAbstract =
        declaredAbstract
            || (isInterfaceMember
                && p.accessors() != null
                && p.accessors().stream().allMatch(a -> a.body() == null));
    boolean usesField =
        p.accessors() != null
            && p.accessors().stream()
                .anyMatch(a -> a.body() != null && FieldKeywordScan.usesField(a.body()));
    boolean hasBacking = !isAbstract && p.accessors() != null && (bodiless || usesField);
    if (isAbstract) {
      if (p.accessors() != null && p.accessors().stream().anyMatch(a -> a.body() != null)) {
        ctx.report(
            Code.UNEXPECTED_BODY,
            file,
            p.nameSpan(),
            "abstract property '" + p.name() + "' cannot have accessor bodies");
      }
      if (!c.isInterface() && !c.isAbstract()) {
        ctx.report(
            Code.INVALID_MODIFIER,
            file,
            p.nameSpan(),
            "abstract property in non-abstract " + c.kindName() + " " + c.name());
      }
    }
    if (isInterfaceMember && hasBacking) {
      ctx.report(
          Code.INVALID_PROPERTY,
          file,
          p.nameSpan(),
          "interface properties cannot have a backing field");
      hasBacking = false;
    }
    if (c.isRecord() && hasBacking && !isStatic) {
      ctx.report(
          Code.INVALID_PROPERTY,
          file,
          p.nameSpan(),
          "records cannot have properties with backing fields (only computed properties)");
      hasBacking = false;
    }
    if (p.initializer() != null && !hasBacking) {
      ctx.report(
          Code.INVALID_PROPERTY,
          file,
          p.initializer().span(),
          "only auto-properties can have an initializer");
    }
    if (!claimName(c, p.name(), p.nameSpan())) {
      return;
    }
    PropertySymbol ps = new PropertySymbol(p.name(), c, pflags, type);
    ps.setDecl(p);
    if (set != null && set.kind() == Accessor.Kind.INIT) {
      ps.addFlags(Flags.INIT_ONLY);
    }
    long methodFlags = access | (isStatic ? Flags.STATIC : 0) | (isAbstract ? Flags.ABSTRACT : 0);
    if (mods.has(Modifier.BASE)) {
      methodFlags |= Flags.BASE;
    }
    if (mods.has(Modifier.OVERRIDE)) {
      methodFlags |= Flags.OVERRIDE;
    }
    if (isInterfaceMember && !isAbstract) {
      methodFlags |= Flags.DEFAULT;
    }
    methodFlags |= finalityFlag(c, mods, methodFlags);
    MethodSymbol getter =
        new MethodSymbol(
            getterName(p.name(), type),
            c,
            methodFlags | Flags.GETTER | accessorAccess(get, access, file));
    if (get != null) {
      getter.setFlags(
          (getter.flags() & ~(Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE))
              | accessorAccess(get, access, file));
    }
    getter.setReturnType(type);
    getter.setProperty(ps);
    getter.setDecl(p);
    MethodSymbol setter = null;
    if (set != null) {
      long sAccess = accessorAccess(set, access, file);
      long sflags =
          (methodFlags & ~(Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE))
              | sAccess
              | Flags.SETTER;
      if (set.kind() == Accessor.Kind.INIT) {
        sflags |= Flags.INIT_ONLY | Flags.SYNTHETIC;
        if (isStatic) {
          ctx.report(
              Code.INVALID_PROPERTY,
              file,
              set.span(),
              "a static property cannot have an 'init' accessor");
        }
      }
      setter = new MethodSymbol(setterName(p.name(), type), c, sflags);
      setter.setParams(List.of(MethodSymbol.Param.of("value", type)));
      setter.setReturnType(PrimType.VOID);
      setter.setProperty(ps);
      setter.setDecl(p);
    }
    FieldSymbol backing = null;
    if (hasBacking) {
      long bflags = Flags.PRIVATE | Flags.BACKING_FIELD | (isStatic ? Flags.STATIC : 0);
      if (setter == null) {
        bflags |= Flags.FINAL;
      }
      backing = new FieldSymbol(p.name(), c, bflags, type);
      backing.setProperty(ps);
      backing.setSource(p, null);
      c.addField(backing);
    }
    ps.setAccessors(getter, setter, backing);
    c.addProperty(ps);
    c.addMethod(getter);
    if (setter != null) {
      c.addMethod(setter);
    }
  }

  /** Accessor-specific access, which must be more restrictive than the property's. */
  private long accessorAccess(Accessor a, long propertyAccess, SourceFile file) {
    if (a == null || a.modifiers().list().isEmpty()) {
      return propertyAccess & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE);
    }
    ModifierRules.allowOnly(
        ctx,
        file,
        a.modifiers(),
        "an accessor",
        Modifier.PUBLIC,
        Modifier.PROTECTED,
        Modifier.PRIVATE,
        Modifier.INTERNAL);
    long acc = ModifierRules.accessFlags(ctx, a.modifiers(), file, true, false);
    if (rank(acc) >= rank(propertyAccess)) {
      ctx.report(
          Code.INVALID_MODIFIER,
          file,
          a.modifiers().span(),
          "accessor access must be more restrictive than the property's");
    }
    return acc;
  }

  private static int rank(long access) {
    if (Flags.is(access, Flags.PUBLIC)) {
      return 3;
    }
    if (Flags.is(access, Flags.PROTECTED)) {
      return 2;
    }
    if (Flags.is(access, Flags.PRIVATE)) {
      return 0;
    }
    return 1;
  }

  // ------------------------------------------------------------------ records

  private void enterRecordComponents(ClassSymbol c, Decl.TypeDecl decl, TypeScope scope) {
    SourceFile file = file(c);
    Set<String> names = new HashSet<>();
    for (Param p : decl.header()) {
      if (!names.add(p.name())) {
        ctx.report(
            Code.DUPLICATE_PARAMETER,
            file,
            p.nameSpan(),
            "duplicate record component '" + p.name() + "'");
        continue;
      }
      if (p.defaultValue() != null || p.isThis() || p.isParams()) {
        ctx.report(
            Code.INVALID_DEFAULT_ARGUMENT,
            file,
            p.span(),
            "record components cannot have default values, 'this' or 'params'");
      }
      Type t = resolver.resolve(p.type(), scope);
      FieldSymbol f =
          new FieldSymbol(p.name(), c, Flags.PRIVATE | Flags.FINAL | Flags.BACKING_FIELD, t);
      c.addField(f);
      c.addRecordComponent(f);
      MethodSymbol accessor = null;
      for (Decl m : decl.members()) {
        if (m instanceof Decl.Method md && md.name().equals(p.name()) && md.params().isEmpty()) {
          accessor = new MethodSymbol(p.name(), c, Flags.PUBLIC);
          accessor.setDecl(md);
        }
      }
      if (accessor == null) {
        accessor = new MethodSymbol(p.name(), c, Flags.PUBLIC | Flags.GENERATED | Flags.GETTER);
        accessor.setReturnType(t);
        c.addMethod(accessor);
      } else {
        accessor = null; // explicitly declared accessor is entered with the other methods
      }
      PropertySymbol ps = new PropertySymbol(p.name(), c, Flags.PUBLIC, t);
      ps.setAccessors(accessor, null, f);
      f.setProperty(ps);
      if (accessor != null) {
        accessor.setProperty(ps);
      }
      c.addProperty(ps);
    }
  }

  /** Links explicitly declared record accessors and adds the canonical constructor if missing. */
  private void ensureCanonicalConstructor(ClassSymbol c, Decl.TypeDecl decl) {
    for (PropertySymbol ps : c.properties()) {
      if (ps.getter() == null) {
        for (MethodSymbol m : c.methods(ps.name())) {
          if (m.params().isEmpty()) {
            ps.setAccessors(m, null, ps.backingField());
            m.setProperty(ps);
            if (!m.has(Flags.PUBLIC)) {
              ctx.report(
                  Code.INVALID_MODIFIER,
                  file(c),
                  ((Decl.Method) m.decl()).nameSpan(),
                  "record accessor '" + m.name() + "' must be public");
            }
          }
        }
      }
    }
    List<Type> comps = c.recordComponents().stream().map(FieldSymbol::type).toList();
    String canon = Descriptors.params(comps);
    for (MethodSymbol k : c.methods(MethodSymbol.CONSTRUCTOR)) {
      if (Descriptors.params(k.params().stream().map(MethodSymbol.Param::type).toList())
          .equals(canon)) {
        return; // explicit (or compact) canonical constructor
      }
    }
    List<MethodSymbol.Param> ps = new ArrayList<>();
    for (FieldSymbol f : c.recordComponents()) {
      ps.add(MethodSymbol.Param.of(f.name(), f.type()));
    }
    addGeneratedCtor(c, Flags.PUBLIC, ps).addFlags(Flags.RECORD);
  }

  // ------------------------------------------------------------------ enums

  private void enterEnum(ClassSymbol c, Decl.TypeDecl decl, TypeScope scope) {
    SourceFile file = file(c);
    Set<String> names = new HashSet<>();
    ClassType self = ClassType.of(c);
    for (EnumConstant ec : decl.enumConstants()) {
      if (!names.add(ec.name())) {
        ctx.report(
            Code.DUPLICATE_MEMBER,
            file,
            ec.nameSpan(),
            "duplicate enum constant '" + ec.name() + "'");
        continue;
      }
      FieldSymbol f =
          new FieldSymbol(
              ec.name(),
              c,
              Flags.PUBLIC | Flags.STATIC | Flags.FINAL | Flags.ENUM | Flags.ENUM_CONSTANT,
              self);
      c.addField(f);
    }
    if (decl.header() != null) {
      List<MethodSymbol.Param> ctorParams = new ArrayList<>();
      for (Param p : decl.header()) {
        if (!names.add(p.name())) {
          ctx.report(
              Code.DUPLICATE_PARAMETER,
              file,
              p.nameSpan(),
              "duplicate enum component '" + p.name() + "'");
          continue;
        }
        Type t = resolver.resolve(p.type(), scope);
        FieldSymbol f =
            new FieldSymbol(p.name(), c, Flags.PRIVATE | Flags.FINAL | Flags.BACKING_FIELD, t);
        c.addField(f);
        PropertySymbol ps = new PropertySymbol(p.name(), c, Flags.PUBLIC, t);
        MethodSymbol getter =
            new MethodSymbol(
                getterName(p.name(), t), c, Flags.PUBLIC | Flags.GETTER | Flags.GENERATED);
        getter.setReturnType(t);
        getter.setProperty(ps);
        f.setProperty(ps);
        ps.setAccessors(getter, null, f);
        c.addProperty(ps);
        c.addMethod(getter);
        ctorParams.add(
            new MethodSymbol.Param(
                p.name(), t, false, p.defaultValue(), p.defaultValue() != null, null));
      }
      addGeneratedCtor(c, Flags.PRIVATE, ctorParams).addFlags(Flags.ENUM);
    }
    MethodSymbol values =
        new MethodSymbol("values", c, Flags.PUBLIC | Flags.STATIC | Flags.GENERATED);
    values.setReturnType(Type.ArrayType.of(self));
    c.addMethod(values);
    MethodSymbol valueOf =
        new MethodSymbol("valueOf", c, Flags.PUBLIC | Flags.STATIC | Flags.GENERATED);
    valueOf.setParams(List.of(MethodSymbol.Param.of("name", ctx.syms.stringType())));
    valueOf.setReturnType(self);
    c.addMethod(valueOf);
  }

  // ------------------------------------------------------------------ modules (top-level members)

  private void completeModule(ClassSymbol c) {
    CompilationUnit u = c.unit();
    SourceFile file = u.file();
    TypeScope scope = classScope(c);
    boolean hasStatements = false;
    Span firstStmt = null;
    for (Decl d : u.members()) {
      switch (d) {
        case Decl.Method md -> enterMethod(c, md, scope, true);
        case Decl.Field f -> enterField(c, f, scope, true);
        case Decl.TopLevelStmt t -> {
          if (!hasStatements) {
            firstStmt = t.span();
          }
          hasStatements = true;
        }
        case Decl.Property p ->
            ctx.error(
                    Code.INVALID_TOP_LEVEL,
                    file,
                    p.nameSpan(),
                    "properties cannot be declared at the top level")
                .help("use a top-level value ('public val " + p.name() + " = ...;') or a function")
                .report(ctx.diags);
        case Decl.Constructor k ->
            ctx.report(
                Code.INVALID_TOP_LEVEL,
                file,
                k.nameSpan(),
                "constructors must be declared inside a class");
        case Decl.Initializer i ->
            ctx.report(
                Code.INVALID_TOP_LEVEL,
                file,
                i.span(),
                "initializer blocks must be declared inside a class");
        case Decl.TypeDecl t -> {}
      }
    }
    if (hasStatements) {
      boolean conflict = false;
      for (MethodSymbol m : c.methods("main")) {
        if (m.params().size() == 1) {
          ctx.error(
                  Code.DUPLICATE_MEMBER,
                  file,
                  ((Decl.Method) m.decl()).nameSpan(),
                  "a file with top-level statements cannot also declare main(String[])")
              .note("top-level statements start here", file, firstStmt)
              .report(ctx.diags);
          conflict = true;
        }
      }
      if (conflict) {
        addGeneratedCtor(c, Flags.PRIVATE, List.of());
        return;
      }
      MethodSymbol main =
          new MethodSymbol(
              "main", c, Flags.PUBLIC | Flags.STATIC | Flags.ENTRY_POINT | Flags.GENERATED);
      main.setParams(
          List.of(MethodSymbol.Param.of("args", Type.ArrayType.of(ctx.syms.stringType()))));
      main.setReturnType(PrimType.VOID);
      c.addMethod(main);
    }
    addGeneratedCtor(c, Flags.PRIVATE, List.of());
    checkDuplicates(c);
  }

  // ------------------------------------------------------------------ duplicates

  /** Reports a clash if a field or property named {@code name} already exists. */
  private boolean claimName(ClassSymbol c, String name, Span at) {
    FieldSymbol f = c.field(name);
    PropertySymbol p = c.property(name);
    if ((f != null && !f.has(Flags.BACKING_FIELD)) || p != null) {
      String what = p != null ? "property" : f.has(Flags.ENUM_CONSTANT) ? "enum constant" : "field";
      ctx.report(
          Code.DUPLICATE_MEMBER,
          file(c),
          at,
          "'" + name + "' is already declared as a " + what + " in " + c.name());
      return false;
    }
    return true;
  }

  private void checkDuplicates(ClassSymbol c) {
    SourceFile file = file(c);
    Map<String, Span> names = new LinkedHashMap<>();
    for (FieldSymbol f : c.fields()) {
      if (f.has(Flags.BACKING_FIELD)) {
        continue;
      }
      Span s = f.declarator() != null ? f.declarator().nameSpan() : null;
      Span prev = names.putIfAbsent(f.name(), s);
      if (prev != null && s != null) {
        ctx.report(
            Code.DUPLICATE_MEMBER, file, s, "duplicate member '" + f.name() + "' in " + c.name());
      }
    }
    for (PropertySymbol p : c.properties()) {
      Span s = p.decl() != null ? p.decl().nameSpan() : null;
      Span prev = names.putIfAbsent(p.name(), s);
      if (prev != null && s != null && !c.isRecord()) {
        ctx.report(
            Code.DUPLICATE_MEMBER, file, s, "duplicate member '" + p.name() + "' in " + c.name());
      }
    }
    Map<String, MethodSymbol> sigs = new HashMap<>();
    for (MethodSymbol m : c.allMethods()) {
      if (m.params().stream().anyMatch(p -> p.type() == null)) {
        continue;
      }
      String key =
          m.jvmName()
              + Descriptors.params(m.params().stream().map(MethodSymbol.Param::type).toList());
      MethodSymbol prev = sigs.putIfAbsent(key, m);
      if (prev != null) {
        Span s = spanOf(m);
        if (s == null) {
          s = spanOf(prev);
        }
        if (s != null) {
          String what = m.isConstructor() ? "constructor" : "method '" + m.name() + "'";
          var b =
              ctx.error(
                  Code.DUPLICATE_MEMBER,
                  file,
                  s,
                  "duplicate " + what + " with the same parameter types (after erasure)");
          if (m.property() != null || prev.property() != null) {
            b.note("property accessors are named getX/setX/isX");
          }
          b.report(ctx.diags);
        }
      }
    }
  }

  private static Span spanOf(MethodSymbol m) {
    return switch (m.decl()) {
      case Decl.Method md -> md.nameSpan();
      case Decl.Constructor k -> k.nameSpan();
      case Decl.Property p -> p.nameSpan();
      case null, default -> null;
    };
  }
}
