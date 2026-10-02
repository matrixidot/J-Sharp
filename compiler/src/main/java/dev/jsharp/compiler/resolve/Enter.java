package dev.jsharp.compiler.resolve;

import dev.jsharp.compiler.Context;
import dev.jsharp.compiler.LanguageInfo;
import dev.jsharp.compiler.ast.Annotation;
import dev.jsharp.compiler.ast.AstPrinter;
import dev.jsharp.compiler.ast.CompilationUnit;
import dev.jsharp.compiler.ast.Decl;
import dev.jsharp.compiler.ast.Expr;
import dev.jsharp.compiler.ast.Modifier;
import dev.jsharp.compiler.ast.Modifiers;
import dev.jsharp.compiler.diag.Code;
import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.Flags;
import dev.jsharp.compiler.types.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 of semantic analysis: creates class symbols for every type declared in source (and the
 * synthetic module class of each file with top-level members), resolves imports, and completes
 * class headers and sealed hierarchies. Member signatures are completed lazily by {@link
 * MemberEnter}.
 */
public final class Enter {
  private final Context ctx;
  private final MemberEnter memberEnter;
  private final List<CompilationUnit> units = new ArrayList<>();
  private final List<ClassSymbol> entered = new ArrayList<>();
  private final Map<CompilationUnit, ClassSymbol> modules = new LinkedHashMap<>();

  public Enter(Context ctx, MemberEnter memberEnter) {
    this.ctx = ctx;
    this.memberEnter = memberEnter;
  }

  public List<ClassSymbol> enteredClasses() {
    return entered;
  }

  public Map<CompilationUnit, ClassSymbol> moduleClasses() {
    return modules;
  }

  /** Enters all files, resolves imports, then completes headers and members of every class. */
  public void enterAll(List<CompilationUnit> all) {
    units.addAll(all);
    for (CompilationUnit u : all) {
      FileScope fs = new FileScope(ctx, u);
      ctx.setFileScope(u, fs);
      for (Decl d : u.members()) {
        if (d instanceof Decl.TypeDecl td) {
          ClassSymbol c = enterClass(td, u, null);
          if (c != null) {
            fs.addUnitType(c);
          }
        }
      }
      enterModuleClass(u, fs);
    }
    checkSingleEntryPoint();
    for (CompilationUnit u : all) {
      ctx.fileScope(u).resolveImports();
    }
    for (ClassSymbol c : List.copyOf(entered)) {
      c.typeParams(); // completes the header
    }
    computePermits();
    for (ClassSymbol c : List.copyOf(entered)) {
      c.completeAll();
    }
  }

  // ------------------------------------------------------------------ classes

  private ClassSymbol enterClass(Decl.TypeDecl td, CompilationUnit u, ClassSymbol outer) {
    SourceFile file = u.file();
    String pkg = u.packageName();
    String binary =
        outer != null
            ? outer.binaryName() + "$" + td.name()
            : (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + td.name();
    if (td.name().equals("<error>")) {
      return null;
    }
    if (ctx.syms.isDeclaredInSource(binary)) {
      ClassSymbol prev = ctx.syms.lookup(binary);
      ctx.error(
              Code.DUPLICATE_TYPE,
              file,
              td.nameSpan(),
              "duplicate " + td.kind().keyword() + " '" + td.name() + "'")
          .note(
              "first declared here",
              prev.unit() != null ? prev.unit().file() : file,
              prev.decl() != null ? prev.decl().nameSpan() : td.nameSpan())
          .report(ctx.diags);
      return null;
    }
    ClassSymbol c = new ClassSymbol(binary, td.name(), pkg, memberEnter);
    c.setFlags(classFlags(td, outer, file) | Flags.SOURCE);
    c.setSource(td, u);
    c.setOuter(outer);
    c.setSourceFileName(file.fileName());
    ctx.syms.enterSource(c);
    if (outer != null) {
      outer.addMemberType(c);
    }
    entered.add(c);
    for (Decl m : td.members()) {
      if (m instanceof Decl.TypeDecl nested) {
        enterClass(nested, u, c);
      }
    }
    return c;
  }

  /** Computes JVM + J# flags of a type declaration and reports invalid modifiers. */
  long classFlags(Decl.TypeDecl td, ClassSymbol outer, SourceFile file) {
    Modifiers mods = td.modifiers();
    long f = accessFlags(mods, file, outer != null, outer != null && outer.isInterface());
    for (Modifiers.Item item : mods.list()) {
      switch (item.modifier()) {
        case PUBLIC, PROTECTED, PRIVATE, INTERNAL -> {}
        case STATIC -> {
          if (outer == null) {
            invalidModifier(file, item, "top-level types are never 'static'");
          }
        }
        case FINAL -> {
          if (td.kind() != Decl.TypeKind.CLASS) {
            invalidModifier(file, item, td.kind().keyword() + "s cannot be 'final'");
          }
        }
        case ABSTRACT, OPEN -> {
          if (td.kind() != Decl.TypeKind.CLASS) {
            invalidModifier(
                file,
                item,
                td.kind().keyword() + "s cannot be '" + item.modifier().keyword() + "'");
          }
        }
        case SEALED -> {
          if (td.kind() != Decl.TypeKind.CLASS && td.kind() != Decl.TypeKind.INTERFACE) {
            invalidModifier(file, item, td.kind().keyword() + "s cannot be 'sealed'");
          }
        }
        default ->
            invalidModifier(
                file,
                item,
                "'" + item.modifier().keyword() + "' is not allowed on a type declaration");
      }
    }
    if (mods.has(Modifier.OPEN) && mods.has(Modifier.FINAL)) {
      invalidModifier(
          file, itemOf(mods, Modifier.OPEN), "a class cannot be both 'open' and 'final'");
    }
    if (mods.has(Modifier.SEALED) && (mods.has(Modifier.OPEN) || mods.has(Modifier.FINAL))) {
      invalidModifier(
          file,
          itemOf(mods, Modifier.SEALED),
          "'sealed' cannot be combined with 'open' or 'final'");
    }
    if (outer != null) {
      f |= Flags.STATIC; // J# named nested types never capture an outer instance
    }
    switch (td.kind()) {
      case CLASS -> {
        if (mods.has(Modifier.ABSTRACT)) {
          f |= Flags.ABSTRACT;
        }
        if (mods.has(Modifier.SEALED)) {
          f |= Flags.SEALED | Flags.ABSTRACT;
        }
        if (mods.has(Modifier.OPEN)) {
          f |= Flags.OPEN;
        }
        if (!mods.has(Modifier.OPEN)
            && !mods.has(Modifier.ABSTRACT)
            && !mods.has(Modifier.SEALED)) {
          f |= Flags.FINAL;
        }
      }
      case INTERFACE -> {
        f |= Flags.INTERFACE | Flags.ABSTRACT;
        if (mods.has(Modifier.SEALED)) {
          f |= Flags.SEALED;
        }
      }
      case RECORD -> f |= Flags.FINAL | Flags.RECORD;
      case ENUM -> f |= Flags.FINAL | Flags.ENUM;
    }
    return f;
  }

  long accessFlags(Modifiers mods, SourceFile file, boolean isMember, boolean inInterface) {
    return ModifierRules.accessFlags(ctx, mods, file, isMember, inInterface);
  }

  void invalidModifier(SourceFile file, Modifiers.Item item, String message) {
    ModifierRules.invalid(ctx, file, item, message);
  }

  static Modifiers.Item itemOf(Modifiers mods, Modifier m) {
    return ModifierRules.itemOf(mods, m);
  }

  // ------------------------------------------------------------------ module classes

  private void enterModuleClass(CompilationUnit u, FileScope fs) {
    boolean needed = false;
    for (Decl d : u.members()) {
      if (!(d instanceof Decl.TypeDecl)) {
        needed = true;
      }
    }
    if (!needed) {
      return;
    }
    String name = moduleClassName(u);
    String pkg = u.packageName();
    String binary = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + name;
    if (ctx.syms.isDeclaredInSource(binary)) {
      ClassSymbol prev = ctx.syms.lookup(binary);
      ctx.error(
              Code.MODULE_NAME_CLASH,
              u.file(),
              new dev.jsharp.compiler.source.Span(0, 0),
              "the top-level members of this file compile to class "
                  + name
                  + ", which is already declared")
          .note(
              "other declaration",
              prev.unit() != null ? prev.unit().file() : u.file(),
              prev.decl() != null
                  ? prev.decl().nameSpan()
                  : new dev.jsharp.compiler.source.Span(0, 0))
          .help("rename the file or add @file:ClassName(\"OtherName\")")
          .report(ctx.diags);
      return;
    }
    ClassSymbol m = new ClassSymbol(binary, name, pkg, memberEnter);
    m.setFlags(Flags.PUBLIC | Flags.FINAL | Flags.MODULE | Flags.SOURCE);
    m.setSource(null, u);
    m.setOuter(null);
    m.setSourceFileName(u.file().fileName());
    ctx.syms.enterSource(m);
    fs.setModuleClass(m);
    modules.put(u, m);
    entered.add(m);
  }

  /** {@code hello.jsharp} -> {@code HelloModule}, overridable with {@code @file:ClassName("X")}. */
  String moduleClassName(CompilationUnit u) {
    for (Annotation a : u.fileAnnotations()) {
      String n = AstPrinter.typeStr(a.name());
      if (n.equals("ClassName") || n.equals("jsharp.lang.ClassName")) {
        if (a.args().size() == 1
            && a.args().getFirst().value() instanceof Expr.Literal lit
            && lit.value() instanceof String s
            && isJavaIdentifier(s)) {
          return s;
        }
        ctx.report(
            Code.INVALID_FILE_ANNOTATION,
            u.file(),
            a.span(),
            "@ClassName needs one string argument that is a valid class name");
      } else {
        ctx.report(
            Code.INVALID_FILE_ANNOTATION, u.file(), a.span(), "unknown file annotation @" + n);
      }
    }
    String file = u.file().fileName();
    if (file.endsWith(LanguageInfo.FILE_EXTENSION)) {
      file = file.substring(0, file.length() - LanguageInfo.FILE_EXTENSION.length());
    }
    StringBuilder sb = new StringBuilder();
    boolean upper = true;
    for (char ch : file.toCharArray()) {
      if (Character.isJavaIdentifierPart(ch) && ch != '$' && ch != '_') {
        sb.append(upper ? Character.toUpperCase(ch) : ch);
        upper = false;
      } else {
        upper = true; // my-app / my_app -> MyApp
      }
    }
    if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.charAt(0))) {
      sb.insert(0, '_');
    }
    return sb + LanguageInfo.MODULE_CLASS_SUFFIX;
  }

  private static boolean isJavaIdentifier(String s) {
    if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
      return false;
    }
    for (char c : s.toCharArray()) {
      if (!Character.isJavaIdentifierPart(c)) {
        return false;
      }
    }
    return true;
  }

  private void checkSingleEntryPoint() {
    CompilationUnit first = null;
    for (CompilationUnit u : units) {
      Decl.TopLevelStmt stmt = null;
      for (Decl d : u.members()) {
        if (d instanceof Decl.TopLevelStmt t) {
          stmt = t;
          break;
        }
      }
      if (stmt == null) {
        continue;
      }
      if (first == null) {
        first = u;
      } else {
        ctx.error(
                Code.MULTIPLE_ENTRY_POINTS,
                u.file(),
                stmt.span(),
                "only one file may contain top-level statements")
            .note("top-level statements also appear in " + first.file().path())
            .help("move these statements into a function, or compile the files separately")
            .report(ctx.diags);
      }
    }
  }

  // ------------------------------------------------------------------ sealed hierarchies

  /**
   * Fills in implicit {@code permits} lists (direct subtypes declared in the same file) and checks
   * that every direct subtype of a sealed type is permitted.
   */
  private void computePermits() {
    Map<ClassSymbol, List<ClassSymbol>> implicit = new LinkedHashMap<>();
    for (ClassSymbol c : entered) {
      if (c.has(Flags.SEALED) && c.decl() != null && c.decl().permits() == null) {
        implicit.put(c, new ArrayList<>());
      }
    }
    for (ClassSymbol sub : entered) {
      for (Type.ClassType sup : directSupertypes(sub)) {
        List<ClassSymbol> list = implicit.get(sup.sym());
        if (list != null && sub.unit() == sup.sym().unit()) {
          list.add(sub);
        }
      }
    }
    implicit.forEach(
        (sealedType, subs) -> {
          if (subs.isEmpty()) {
            ctx.report(
                Code.INVALID_PERMITS,
                sealedType.unit().file(),
                sealedType.decl().nameSpan(),
                "sealed "
                    + sealedType.kindName()
                    + " "
                    + sealedType.name()
                    + " has no subtypes in this file");
          }
          sealedType.setPermitted(subs);
        });
    for (ClassSymbol sub : entered) {
      for (Type.ClassType sup : directSupertypes(sub)) {
        ClassSymbol s = sup.sym();
        if (s.has(Flags.SEALED) && !s.permitted().contains(sub)) {
          SourceFile file = sub.unit().file();
          var b =
              ctx.error(
                  Code.INVALID_PERMITS,
                  file,
                  sub.decl() != null
                      ? sub.decl().nameSpan()
                      : new dev.jsharp.compiler.source.Span(0, 0),
                  sub.kindName()
                      + " "
                      + sub.name()
                      + " is not permitted to extend sealed "
                      + s.kindName()
                      + " "
                      + s.name());
          if (s.isSource() && s.decl() != null && s.decl().permits() != null) {
            b.help("add " + sub.name() + " to the 'permits' list of " + s.name());
          } else if (s.isSource()) {
            b.help(
                "declare "
                    + sub.name()
                    + " in the same file as "
                    + s.name()
                    + ", or list it with 'permits'");
          }
          b.report(ctx.diags);
        }
      }
    }
    for (ClassSymbol c : entered) {
      if (c.has(Flags.SEALED) && c.decl() != null) {
        for (ClassSymbol p : c.permitted()) {
          if (!directSupertypes(p).stream().anyMatch(t -> t.sym() == c)) {
            ctx.report(
                Code.INVALID_PERMITS,
                c.unit().file(),
                c.decl().nameSpan(),
                p.kindName()
                    + " "
                    + p.name()
                    + " is listed in 'permits' but does not extend "
                    + c.name());
          } else if (!p.packageName().equals(c.packageName())) {
            ctx.report(
                Code.INVALID_PERMITS,
                c.unit().file(),
                c.decl().nameSpan(),
                "permitted subtype " + p.name() + " must be in the same package as " + c.name());
          }
        }
      }
    }
  }

  private static List<Type.ClassType> directSupertypes(ClassSymbol c) {
    List<Type.ClassType> out = new ArrayList<>();
    if (c.superclass() != null) {
      out.add(c.superclass());
    }
    out.addAll(c.interfaces());
    return out;
  }
}
