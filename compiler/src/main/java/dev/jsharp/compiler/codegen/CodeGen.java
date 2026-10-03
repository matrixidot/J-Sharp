package dev.jsharp.compiler.codegen;

import dev.jsharp.compiler.bound.BExpr;
import dev.jsharp.compiler.bound.BExpr.BinOp;
import dev.jsharp.compiler.bound.BLValue;
import dev.jsharp.compiler.bound.BStmt;
import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.FieldSymbol;
import dev.jsharp.compiler.symbols.Flags;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.Symtab;
import dev.jsharp.compiler.symbols.VarSymbol;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ClassType;
import dev.jsharp.compiler.types.Type.PrimType;
import dev.jsharp.compiler.types.Types;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates bytecode for one method body from the lowered bound tree. Stack map frames are computed
 * by the ClassFile API; this class tracks local slots, jump targets, pending {@code finally}
 * blocks, line numbers and the local variable table.
 */
final class CodeGen {
  private final CodeBuilder cb;
  private final Types types;
  private final Symtab syms;
  private final ClassSymbol cls;
  private final SourceFile file;
  private final Type returnType;
  private final boolean debug;
  private final Map<VarSymbol, Integer> slots = new IdentityHashMap<>();
  private final Map<VarSymbol, Label> varStart = new IdentityHashMap<>();
  private int nextSlot;
  private int lastLine = -1;

  /** Whether the next emitted instruction can be reached. */
  private boolean reachable = true;

  /** Labels some emitted branch targets (binding one makes the code after it reachable). */
  private final java.util.Set<Label> targeted =
      java.util.Collections.newSetFromMap(new IdentityHashMap<>());

  private void jump(Label l) {
    if (reachable) {
      targeted.add(l);
      cb.goto_(l);
    }
    reachable = false;
  }

  private void bind(Label l) {
    cb.labelBinding(l);
    reachable |= targeted.contains(l);
  }

  /** Marks the current position as an exception handler entry. */
  private void handlerEntry(Label l) {
    cb.labelBinding(l);
    reachable = true;
  }

  /** Break/continue labels per loop/labeled statement. */
  private record Target(Label breakLabel, Label continueLabel, int finallyDepth) {}

  private final Map<BStmt.Label, Target> targets = new IdentityHashMap<>();

  /** Pending cleanup (finally bodies, monitor exits) to run when jumping out of a region. */
  private sealed interface Cleanup permits FinallyCleanup, MonitorCleanup {}

  private record FinallyCleanup(BStmt body) implements Cleanup {}

  private record MonitorCleanup(int slot) implements Cleanup {}

  private final Deque<Cleanup> cleanups = new ArrayDeque<>();

  CodeGen(
      CodeBuilder cb,
      Types types,
      ClassSymbol cls,
      SourceFile file,
      Type returnType,
      boolean isStatic,
      List<VarSymbol> params,
      boolean debug) {
    this.cb = cb;
    this.types = types;
    this.syms = types.syms();
    this.cls = cls;
    this.file = file;
    this.returnType = returnType;
    this.debug = debug;
    nextSlot = isStatic ? 0 : 1;
    Label start = cb.startLabel();
    for (VarSymbol p : params) {
      slots.put(p, nextSlot);
      varStart.put(p, start);
      nextSlot += Descs.kind(p.type()).slotSize();
    }
  }

  /** Emits the body; adds a trailing return for void methods that fall off the end. */
  void body(BStmt body, Span methodSpan) {
    stmt(body);
    if (reachable) {
      if (returnType == PrimType.VOID) {
        cb.return_();
      } else {
        // The checker guarantees non-void bodies cannot complete normally.
        cb.aconst_null();
        cb.athrow();
      }
    }
  }

  /** Emits the local variable table (named, non-synthetic variables). */
  void finish() {
    if (!debug) {
      return;
    }
    Label end = cb.endLabel();
    for (Map.Entry<VarSymbol, Integer> e : slots.entrySet()) {
      VarSymbol v = e.getKey();
      Label start = varStart.get(v);
      if (start == null
          || v.name().startsWith("$")
          || v.name().equals("_")
          || v.has(Flags.SYNTHETIC)) {
        continue;
      }
      cb.localVariable(e.getValue(), v.name(), Descs.of(v.type()), start, end);
    }
  }

  private int slot(VarSymbol v) {
    Integer s = slots.get(v);
    if (s == null) {
      s = nextSlot;
      slots.put(v, s);
      nextSlot += Descs.kind(v.type()).slotSize();
    }
    return s;
  }

  private void markStart(VarSymbol v) {
    if (!varStart.containsKey(v)) {
      Label l = cb.newLabel();
      cb.labelBinding(l);
      varStart.put(v, l);
    }
  }

  private void line(Span span) {
    if (!debug || file == null || span == null || span == Span.NONE) {
      return;
    }
    int line = file.line(span.start());
    if (line != lastLine) {
      lastLine = line;
      cb.lineNumber(line);
    }
  }

  private void load(VarSymbol v) {
    cb.loadLocal(Descs.kind(v.type()), slot(v));
  }

  private void store(VarSymbol v) {
    cb.storeLocal(Descs.kind(v.type()), slot(v));
    markStart(v);
  }

  // ------------------------------------------------------------------ statements

  void stmt(BStmt s) {
    if (!reachable) {
      return; // statically dead: structured code cannot jump into it
    }
    switch (s) {
      case BStmt.Block b -> {
        for (BStmt x : b.stmts()) {
          stmt(x);
        }
      }
      case BStmt.LocalDecl d -> {
        if (d.init() != null) {
          line(d.span());
          expr(d.init());
          store(d.var());
        }
      }
      case BStmt.ExprStmt es -> {
        line(es.span());
        effect(es.expr());
      }
      case BStmt.If i -> {
        line(i.span());
        Label otherwise = cb.newLabel();
        Label end = cb.newLabel();
        cond(i.cond(), otherwise, false);
        stmt(i.then());
        if (i.otherwise() != null) {
          jump(end);
          bind(otherwise);
          stmt(i.otherwise());
          bind(end);
        } else {
          bind(otherwise);
        }
      }
      case BStmt.While w -> {
        line(w.span());
        Label top = cb.newLabel();
        Label end = cb.newLabel();
        targets.put(w.label(), new Target(end, top, cleanups.size()));
        bind(top);
        cond(w.cond(), end, false);
        stmt(w.body());
        jump(top);
        bind(end);
      }
      case BStmt.DoWhile d -> {
        Label top = cb.newLabel();
        Label cont = cb.newLabel();
        Label end = cb.newLabel();
        targets.put(d.label(), new Target(end, cont, cleanups.size()));
        bind(top);
        stmt(d.body());
        bind(cont);
        line(d.cond().span());
        cond(d.cond(), top, true);
        bind(end);
      }
      case BStmt.For f -> {
        line(f.span());
        for (BStmt i : f.init()) {
          stmt(i);
        }
        Label top = cb.newLabel();
        Label cont = cb.newLabel();
        Label end = cb.newLabel();
        targets.put(f.label(), new Target(end, cont, cleanups.size()));
        bind(top);
        if (f.cond() != null) {
          cond(f.cond(), end, false);
        }
        stmt(f.body());
        bind(cont);
        for (BExpr u : f.update()) {
          effect(u);
        }
        jump(top);
        bind(end);
      }
      case BStmt.Labeled l -> {
        Label end = cb.newLabel();
        targets.put(l.label(), new Target(end, null, cleanups.size()));
        stmt(l.body());
        bind(end);
      }
      case BStmt.Break b -> {
        Target t = targets.get(b.target());
        runCleanups(t.finallyDepth());
        jump(t.breakLabel());
      }
      case BStmt.Continue c -> {
        Target t = targets.get(c.target());
        runCleanups(t.finallyDepth());
        jump(t.continueLabel());
      }
      case BStmt.Return r -> {
        line(r.span());
        if (r.value() == null) {
          runCleanups(0);
          cb.return_();
          reachable = false;
        } else if (cleanups.isEmpty()) {
          expr(r.value());
          cb.return_(Descs.kind(returnType));
          reachable = false;
        } else {
          expr(r.value());
          int tmp = nextSlot;
          TypeKind k = Descs.kind(returnType);
          nextSlot += k.slotSize();
          cb.storeLocal(k, tmp);
          runCleanups(0);
          cb.loadLocal(k, tmp);
          cb.return_(k);
          reachable = false;
        }
      }
      case BStmt.Throw t -> {
        line(t.span());
        expr(t.exception());
        cb.athrow();
        reachable = false;
      }
      case BStmt.Try t -> tryStmt(t);
      case BStmt.Sync sy -> sync(sy);
      case BStmt.Empty e -> {}
      case BStmt.Foreach f -> throw new IllegalStateException("foreach not lowered");
      case BStmt.Using u -> throw new IllegalStateException("using not lowered");
      case BStmt.Switch sw -> throw new IllegalStateException("switch not lowered");
    }
  }

  /** Emits cleanups (innermost first) down to {@code depth}, without removing them. */
  private void runCleanups(int depth) {
    List<Cleanup> list = new ArrayList<>(cleanups);
    // ArrayDeque used as a stack: iteration order is innermost first.
    int n = list.size() - depth;
    Deque<Cleanup> saved = new ArrayDeque<>(cleanups);
    for (int i = 0; i < n; i++) {
      Cleanup c = list.get(i);
      // While running a cleanup, the cleanups it is nested in are still active, but not itself.
      cleanups.clear();
      for (int j = i + 1; j < list.size(); j++) {
        cleanups.addLast(list.get(j));
      }
      switch (c) {
        case FinallyCleanup f -> stmt(f.body());
        case MonitorCleanup m -> {
          cb.aload(m.slot());
          cb.monitorexit();
        }
      }
    }
    cleanups.clear();
    cleanups.addAll(saved);
  }

  private void tryStmt(BStmt.Try t) {
    Label start = cb.newLabel();
    Label end = cb.newLabel();
    Label after = cb.newLabel();
    if (t.finallyBody() != null) {
      cleanups.push(new FinallyCleanup(t.finallyBody()));
    }
    bind(start);
    stmt(t.body());
    bind(end);
    if (t.finallyBody() != null) {
      cleanups.pop();
      stmt(t.finallyBody());
      cleanups.push(new FinallyCleanup(t.finallyBody()));
    }
    jump(after);
    List<Label[]> handlerRanges = new ArrayList<>();
    for (BStmt.Catch c : t.catches()) {
      Label handler = cb.newLabel();
      Label hEnd = cb.newLabel();
      handlerEntry(handler);
      store(c.var());
      stmt(c.body());
      bind(hEnd);
      if (t.finallyBody() != null) {
        cleanups.pop();
        stmt(t.finallyBody());
        cleanups.push(new FinallyCleanup(t.finallyBody()));
      }
      jump(after);
      for (ClassType ct : c.types()) {
        cb.exceptionCatch(start, end, handler, Descs.of(ct.sym()));
      }
      handlerRanges.add(new Label[] {handler, hEnd});
    }
    if (t.finallyBody() != null) {
      cleanups.pop();
      Label catchAll = cb.newLabel();
      handlerEntry(catchAll);
      int tmp = nextSlot++;
      cb.astore(tmp);
      stmt(t.finallyBody());
      cb.aload(tmp);
      cb.athrow();
      reachable = false;
      cb.exceptionCatchAll(start, end, catchAll);
      for (Label[] r : handlerRanges) {
        cb.exceptionCatchAll(r[0], r[1], catchAll);
      }
    }
    bind(after);
  }

  private void sync(BStmt.Sync sy) {
    line(sy.span());
    expr(sy.monitor());
    cb.dup();
    int slot = nextSlot++;
    cb.astore(slot);
    cb.monitorenter();
    Label start = cb.newLabel();
    Label end = cb.newLabel();
    Label after = cb.newLabel();
    Label handler = cb.newLabel();
    cleanups.push(new MonitorCleanup(slot));
    bind(start);
    stmt(sy.body());
    cleanups.pop();
    cb.aload(slot);
    cb.monitorexit();
    bind(end);
    jump(after);
    handlerEntry(handler);
    int ex = nextSlot++;
    cb.astore(ex);
    cb.aload(slot);
    cb.monitorexit();
    cb.aload(ex);
    cb.athrow();
    reachable = false;
    cb.exceptionCatchAll(start, end, handler);
    bind(after);
  }

  // ------------------------------------------------------------------ expressions

  /** Evaluates {@code e} for its side effects, leaving nothing on the stack. */
  void effect(BExpr e) {
    switch (e) {
      case BExpr.Assign a -> assign(a, false);
      case BExpr.IncDec i -> incDec(i, false);
      case BExpr.CompoundAssign c -> compoundLocal(c, false);
      case BExpr.Nop n -> {}
      case BExpr.Let l -> {
        expr(l.init());
        store(l.var());
        effect(l.body());
      }
      case BExpr.Block b -> {
        for (BStmt s : b.stmts()) {
          stmt(s);
        }
        effect(b.value());
      }
      case BExpr.Conditional c when c.type() == PrimType.VOID || true -> {
        Label otherwise = cb.newLabel();
        Label end = cb.newLabel();
        cond(c.cond(), otherwise, false);
        effect(c.then());
        jump(end);
        bind(otherwise);
        effect(c.otherwise());
        bind(end);
      }
      default -> {
        expr(e);
        pop(e.type());
      }
    }
  }

  private void pop(Type t) {
    if (t == PrimType.VOID || t instanceof Type.NeverType) {
      return;
    }
    if (Descs.kind(t).slotSize() == 2) {
      cb.pop2();
    } else {
      cb.pop();
    }
  }

  void expr(BExpr e) {
    switch (e) {
      case BExpr.Const c -> constant(c);
      case BExpr.Local l -> load(l.var());
      case BExpr.This t -> cb.aload(0);
      case BExpr.OuterThis o -> throw new IllegalStateException("outer this not lowered");
      case BExpr.Field f -> {
        FieldSymbol fs = f.field();
        if (fs.isStatic()) {
          if (f.receiver() != null) {
            effect(f.receiver());
          }
          cb.getstatic(Descs.of(fs.owner()), fs.name(), Descs.of(fs.type()));
        } else {
          expr(f.receiver());
          cb.getfield(Descs.of(fs.owner()), fs.name(), Descs.of(fs.type()));
        }
        castIfNeeded(fs.type(), f.type());
      }
      case BExpr.Call c -> call(c);
      case BExpr.New n -> {
        ClassDesc owner = Descs.of(n.type().sym());
        cb.new_(owner);
        cb.dup();
        for (BExpr a : n.args()) {
          expr(a);
        }
        cb.invokespecial(owner, MethodSymbol.CONSTRUCTOR, Descs.of(n.ctor()));
      }
      case BExpr.NewArray na -> newArray(na);
      case BExpr.ArrayElem ae -> {
        expr(ae.array());
        expr(ae.index());
        Type elem = ae.array().type() instanceof Type.ArrayType at ? at.elem() : ae.type();
        cb.arrayLoad(Descs.kind(elem));
        castIfNeeded(elem, ae.type());
      }
      case BExpr.ArrayLength al -> {
        expr(al.array());
        cb.arraylength();
      }
      case BExpr.Unary u -> unary(u);
      case BExpr.Binary b -> binary(b);
      case BExpr.Assign a -> assign(a, true);
      case BExpr.CompoundAssign c -> compoundLocal(c, true);
      case BExpr.IncDec i -> incDec(i, true);
      case BExpr.Conv c -> conv(c);
      case BExpr.InstanceOf io -> {
        expr(io.expr());
        cb.instanceOf(Descs.of(io.target()));
      }
      case BExpr.Conditional c -> {
        if (c.type() == PrimType.VOID) {
          effect(c);
          return;
        }
        Label otherwise = cb.newLabel();
        Label end = cb.newLabel();
        cond(c.cond(), otherwise, false);
        expr(c.then());
        jump(end);
        bind(otherwise);
        expr(c.otherwise());
        bind(end);
      }
      case BExpr.Concat c -> concat(c.parts());
      case BExpr.Indy i -> indy(i);
      case BExpr.ClassLit cl -> classLit(cl.target());
      case BExpr.Let l -> {
        expr(l.init());
        store(l.var());
        expr(l.body());
      }
      case BExpr.Block b -> {
        for (BStmt s : b.stmts()) {
          stmt(s);
        }
        expr(b.value());
      }
      case BExpr.Throw t -> {
        expr(t.exception());
        cb.athrow();
        reachable = false;
      }
      case BExpr.Nop n -> {}
      default -> throw new IllegalStateException("not lowered: " + e.getClass().getSimpleName());
    }
  }

  private void constant(BExpr.Const c) {
    Object v = c.value();
    Type t = c.type();
    if (v == null) {
      cb.aconst_null();
      return;
    }
    if (t instanceof PrimType p) {
      ConstantDesc d =
          switch (p) {
            case BOOLEAN -> ((Boolean) v) ? 1 : 0;
            case CHAR -> v instanceof Character ch ? (int) ch : ((Number) v).intValue();
            case BYTE, SHORT, INT -> v instanceof Character ch ? (int) ch : ((Number) v).intValue();
            case LONG -> ((Number) v).longValue();
            case FLOAT -> ((Number) v).floatValue();
            case DOUBLE -> ((Number) v).doubleValue();
            case VOID -> throw new IllegalStateException();
          };
      cb.loadConstant(d);
      return;
    }
    if (v instanceof String s) {
      cb.loadConstant(s);
      return;
    }
    // A boxed constant in reference position.
    cb.loadConstant(String.valueOf(v));
  }

  /** Inserts a checkcast when a generic member's erased type is wider than the expected type. */
  private void castIfNeeded(Type declared, Type actual) {
    if (declared == null
        || actual == null
        || actual instanceof PrimType
        || declared instanceof PrimType) {
      return;
    }
    if (actual instanceof Type.NeverType || actual.isError() || actual instanceof Type.NullType) {
      return;
    }
    ClassDesc d = Descs.of(declared);
    ClassDesc a = Descs.of(actual);
    if (d.equals(a) || a.equals(ConstantDescs.CD_Object)) {
      return;
    }
    if (types.isSubtype(declared.erasure(), actual.erasure())) {
      return;
    }
    cb.checkcast(a);
  }

  private void call(BExpr.Call c) {
    MethodSymbol m = c.method();
    if (c.receiver() != null) {
      expr(c.receiver());
    }
    for (BExpr a : c.args()) {
      expr(a);
    }
    ClassSymbol ownerSym = m.owner();
    ClassDesc owner = Descs.of(ownerSym);
    if (m.name().equals("clone")
        && m.params().isEmpty()
        && c.receiver() != null
        && c.receiver().type() instanceof Type.ArrayType at) {
      owner = Descs.of(at);
      cb.invokevirtual(owner, "clone", MethodTypeDesc.of(ConstantDescs.CD_Object));
      cb.checkcast(owner);
      return;
    }
    MethodTypeDesc desc = Descs.of(m);
    String name = m.jvmName();
    boolean itf = ownerSym.isInterface();
    switch (c.kind()) {
      case STATIC -> cb.invokestatic(owner, name, desc, itf);
      case SPECIAL -> cb.invokespecial(owner, name, desc, itf);
      case INTERFACE -> cb.invokeinterface(owner, name, desc);
      case VIRTUAL -> {
        if (itf) {
          cb.invokeinterface(owner, name, desc);
        } else {
          cb.invokevirtual(owner, name, desc);
        }
      }
    }
    Type declaredRet = m.jvmReturnType() != null ? m.jvmReturnType() : m.returnType();
    if (m.returnType() instanceof Type.NeverType) {
      // The JVM does not know the call never returns: end the path explicitly.
      pop(declaredRet);
      cb.aconst_null();
      cb.athrow();
      reachable = false;
      return;
    }
    if (!m.isConstructor()) {
      castIfNeeded(declaredRet, c.type());
    }
  }

  private void newArray(BExpr.NewArray na) {
    Type.ArrayType at = na.type();
    if (na.elems() != null) {
      cb.loadConstant(na.elems().size());
      newArrayOf(at.elem());
      TypeKind k = Descs.kind(at.elem());
      for (int i = 0; i < na.elems().size(); i++) {
        cb.dup();
        cb.loadConstant(i);
        expr(na.elems().get(i));
        cb.arrayStore(k);
      }
      return;
    }
    for (BExpr d : na.dims()) {
      expr(d);
    }
    if (na.dims().size() == 1) {
      newArrayOf(at.elem());
    } else {
      cb.multianewarray(Descs.of(at), na.dims().size());
    }
  }

  private void newArrayOf(Type elem) {
    if (elem instanceof PrimType) {
      cb.newarray(Descs.kind(elem));
    } else {
      cb.anewarray(Descs.of(elem));
    }
  }

  private void unary(BExpr.Unary u) {
    expr(u.operand());
    TypeKind k = Descs.kind(u.type());
    switch (u.op()) {
      case NEG -> {
        switch (k) {
          case LONG -> cb.lneg();
          case FLOAT -> cb.fneg();
          case DOUBLE -> cb.dneg();
          default -> cb.ineg();
        }
      }
      case BIT_NOT -> {
        if (k == TypeKind.LONG) {
          cb.loadConstant(-1L);
          cb.lxor();
        } else {
          cb.iconst_m1();
          cb.ixor();
        }
      }
      case NOT -> {
        cb.iconst_1();
        cb.ixor();
      }
    }
  }

  private void binary(BExpr.Binary b) {
    BinOp op = b.op();
    if (op.isComparison() || op == BinOp.COND_AND || op == BinOp.COND_OR) {
      Label f = cb.newLabel();
      Label end = cb.newLabel();
      cond(b, f, false);
      cb.iconst_1();
      jump(end);
      bind(f);
      cb.iconst_0();
      bind(end);
      return;
    }
    expr(b.left());
    expr(b.right());
    arith(op, Descs.kind(b.left().type()));
  }

  private void arith(BinOp op, TypeKind k) {
    switch (k) {
      case LONG -> {
        switch (op) {
          case ADD -> cb.ladd();
          case SUB -> cb.lsub();
          case MUL -> cb.lmul();
          case DIV -> cb.ldiv();
          case REM -> cb.lrem();
          case SHL -> cb.lshl();
          case SHR -> cb.lshr();
          case USHR -> cb.lushr();
          case AND -> cb.land();
          case OR -> cb.lor();
          case XOR -> cb.lxor();
          default -> throw new IllegalStateException(op.toString());
        }
      }
      case FLOAT -> {
        switch (op) {
          case ADD -> cb.fadd();
          case SUB -> cb.fsub();
          case MUL -> cb.fmul();
          case DIV -> cb.fdiv();
          case REM -> cb.frem();
          default -> throw new IllegalStateException(op.toString());
        }
      }
      case DOUBLE -> {
        switch (op) {
          case ADD -> cb.dadd();
          case SUB -> cb.dsub();
          case MUL -> cb.dmul();
          case DIV -> cb.ddiv();
          case REM -> cb.drem();
          default -> throw new IllegalStateException(op.toString());
        }
      }
      default -> {
        switch (op) {
          case ADD -> cb.iadd();
          case SUB -> cb.isub();
          case MUL -> cb.imul();
          case DIV -> cb.idiv();
          case REM -> cb.irem();
          case SHL -> cb.ishl();
          case SHR -> cb.ishr();
          case USHR -> cb.iushr();
          case AND -> cb.iand();
          case OR -> cb.ior();
          case XOR -> cb.ixor();
          default -> throw new IllegalStateException(op.toString());
        }
      }
    }
  }

  // ------------------------------------------------------------------ conditions

  /** Jumps to {@code target} if {@code e} evaluates to {@code jumpIf}; otherwise falls through. */
  void cond(BExpr e, Label target, boolean jumpIf) {
    if (!(e instanceof BExpr.Const k && k.value() instanceof Boolean kb && kb != jumpIf)) {
      targeted.add(target);
    }
    switch (e) {
      case BExpr.Const c when c.value() instanceof Boolean b -> {
        if (b == jumpIf) {
          jump(target);
        }
      }
      case BExpr.Unary u when u.op() == BExpr.UnOp.NOT -> cond(u.operand(), target, !jumpIf);
      case BExpr.Binary b when b.op() == BinOp.COND_AND -> {
        if (!jumpIf) {
          cond(b.left(), target, false);
          cond(b.right(), target, false);
        } else {
          Label skip = cb.newLabel();
          cond(b.left(), skip, false);
          cond(b.right(), target, true);
          bind(skip);
        }
      }
      case BExpr.Binary b when b.op() == BinOp.COND_OR -> {
        if (jumpIf) {
          cond(b.left(), target, true);
          cond(b.right(), target, true);
        } else {
          Label skip = cb.newLabel();
          cond(b.left(), skip, true);
          cond(b.right(), target, false);
          bind(skip);
        }
      }
      case BExpr.Binary b when b.op() == BinOp.REF_EQ || b.op() == BinOp.REF_NE -> {
        boolean eq = (b.op() == BinOp.REF_EQ) == jumpIf;
        if (isNull(b.right())) {
          expr(b.left());
          if (eq) {
            cb.ifnull(target);
          } else {
            cb.ifnonnull(target);
          }
        } else if (isNull(b.left())) {
          expr(b.right());
          if (eq) {
            cb.ifnull(target);
          } else {
            cb.ifnonnull(target);
          }
        } else {
          expr(b.left());
          expr(b.right());
          if (eq) {
            cb.if_acmpeq(target);
          } else {
            cb.if_acmpne(target);
          }
        }
      }
      case BExpr.Binary b when b.op().isComparison() -> compare(b, target, jumpIf);
      case BExpr.Let l -> {
        expr(l.init());
        store(l.var());
        cond(l.body(), target, jumpIf);
      }
      case BExpr.Block bl -> {
        for (BStmt s : bl.stmts()) {
          stmt(s);
        }
        cond(bl.value(), target, jumpIf);
      }
      default -> {
        expr(e);
        if (jumpIf) {
          cb.ifne(target);
        } else {
          cb.ifeq(target);
        }
      }
    }
  }

  private static boolean isNull(BExpr e) {
    return e instanceof BExpr.Const c && c.value() == null;
  }

  private void compare(BExpr.Binary b, Label target, boolean jumpIf) {
    BinOp op = b.op();
    BinOp effective = jumpIf ? op : negate(op);
    TypeKind k = Descs.kind(b.left().type());
    expr(b.left());
    expr(b.right());
    switch (k) {
      case LONG -> {
        cb.lcmp();
        zeroBranch(effective, target);
      }
      case FLOAT, DOUBLE -> {
        // NaN: comparisons are false (and != true). Choose cmpg/cmpl so NaN makes the original
        // comparison fail; the same instruction then works for the negated branch.
        boolean useG = op == BinOp.LT || op == BinOp.LE;
        if (k == TypeKind.FLOAT) {
          if (useG) {
            cb.fcmpg();
          } else {
            cb.fcmpl();
          }
        } else {
          if (useG) {
            cb.dcmpg();
          } else {
            cb.dcmpl();
          }
        }
        zeroBranch(effective, target);
      }
      default -> {
        switch (effective) {
          case EQ -> cb.if_icmpeq(target);
          case NE -> cb.if_icmpne(target);
          case LT -> cb.if_icmplt(target);
          case GE -> cb.if_icmpge(target);
          case GT -> cb.if_icmpgt(target);
          case LE -> cb.if_icmple(target);
          default -> throw new IllegalStateException(effective.toString());
        }
      }
    }
  }

  private void zeroBranch(BinOp op, Label target) {
    switch (op) {
      case EQ -> cb.ifeq(target);
      case NE -> cb.ifne(target);
      case LT -> cb.iflt(target);
      case GE -> cb.ifge(target);
      case GT -> cb.ifgt(target);
      case LE -> cb.ifle(target);
      default -> throw new IllegalStateException(op.toString());
    }
  }

  private static BinOp negate(BinOp op) {
    return switch (op) {
      case EQ -> BinOp.NE;
      case NE -> BinOp.EQ;
      case LT -> BinOp.GE;
      case GE -> BinOp.LT;
      case GT -> BinOp.LE;
      case LE -> BinOp.GT;
      default -> throw new IllegalStateException(op.toString());
    };
  }

  // ------------------------------------------------------------------ assignment

  private void assign(BExpr.Assign a, boolean needValue) {
    switch (a.target()) {
      case BLValue.LocalLV l -> {
        expr(a.value());
        if (needValue) {
          dup(l.var().type());
        }
        store(l.var());
      }
      case BLValue.FieldLV f -> {
        FieldSymbol fs = f.field();
        if (fs.isStatic()) {
          expr(a.value());
          if (needValue) {
            dup(fs.type());
          }
          cb.putstatic(Descs.of(fs.owner()), fs.name(), Descs.of(fs.type()));
        } else {
          expr(f.receiver());
          expr(a.value());
          if (needValue) {
            dupX1(fs.type());
          }
          cb.putfield(Descs.of(fs.owner()), fs.name(), Descs.of(fs.type()));
        }
      }
      case BLValue.ArrayLV ar -> {
        expr(ar.array());
        expr(ar.index());
        expr(a.value());
        Type elem = ar.array().type() instanceof Type.ArrayType at ? at.elem() : ar.type();
        if (needValue) {
          dupX2(elem);
        }
        cb.arrayStore(Descs.kind(elem));
      }
      case BLValue.PropertyLV p ->
          throw new IllegalStateException("property assignment not lowered");
    }
  }

  private void dup(Type t) {
    if (Descs.kind(t).slotSize() == 2) {
      cb.dup2();
    } else {
      cb.dup();
    }
  }

  private void dupX1(Type t) {
    if (Descs.kind(t).slotSize() == 2) {
      cb.dup2_x1();
    } else {
      cb.dup_x1();
    }
  }

  private void dupX2(Type t) {
    if (Descs.kind(t).slotSize() == 2) {
      cb.dup2_x2();
    } else {
      cb.dup_x2();
    }
  }

  /** {@code x op= v} on a local (other targets are lowered to explicit reads and writes). */
  private void compoundLocal(BExpr.CompoundAssign c, boolean needValue) {
    BLValue.LocalLV l = (BLValue.LocalLV) c.target();
    VarSymbol v = l.var();
    Type vt = v.type();
    if (!(c.opType() instanceof PrimType)) {
      concat(List.of(new BExpr.Local(v, c.span()), c.value()));
    } else {
      PrimType op = (PrimType) c.opType();
      if (op == PrimType.INT
          && vt == PrimType.INT
          && (c.op() == BinOp.ADD || c.op() == BinOp.SUB)
          && c.value() instanceof BExpr.Const k
          && k.value() instanceof Integer n) {
        int delta = c.op() == BinOp.ADD ? n : -n;
        if (delta >= Short.MIN_VALUE && delta <= Short.MAX_VALUE) {
          cb.iinc(slot(v), delta);
          if (needValue) {
            load(v);
          }
          return;
        }
      }
      load(v);
      convertPrim((PrimType) vt, op);
      expr(c.value());
      arith(c.op(), Descs.kind(op));
      convertPrim(op, (PrimType) vt);
    }
    if (needValue) {
      dup(vt);
    }
    store(v);
  }

  private void incDec(BExpr.IncDec i, boolean needValue) {
    BLValue.LocalLV l = (BLValue.LocalLV) i.target();
    VarSymbol v = l.var();
    PrimType t = (PrimType) v.type();
    if (t == PrimType.INT) {
      if (needValue && !i.prefix()) {
        load(v);
      }
      cb.iinc(slot(v), i.increment() ? 1 : -1);
      if (needValue && i.prefix()) {
        load(v);
      }
      return;
    }
    PrimType op = Types.promote(t);
    load(v);
    if (needValue && !i.prefix()) {
      dup(t);
    }
    convertPrim(t, op);
    switch (op) {
      case LONG -> cb.loadConstant(1L);
      case FLOAT -> cb.loadConstant(1f);
      case DOUBLE -> cb.loadConstant(1d);
      default -> cb.iconst_1();
    }
    arith(i.increment() ? BinOp.ADD : BinOp.SUB, Descs.kind(op));
    convertPrim(op, t);
    if (needValue && i.prefix()) {
      dup(t);
    }
    store(v);
  }

  // ------------------------------------------------------------------ conversions

  private void conv(BExpr.Conv c) {
    expr(c.expr());
    Type from = c.expr().type();
    switch (c.kind()) {
      case PRIMITIVE -> convertPrim((PrimType) from, (PrimType) c.type());
      case BOX -> {
        PrimType p = (PrimType) from;
        ClassDesc box = ClassDesc.ofInternalName(p.boxBinaryName());
        cb.invokestatic(box, "valueOf", MethodTypeDesc.of(box, Descs.of(p)));
      }
      case UNBOX -> {
        PrimType p = (PrimType) c.type();
        ClassDesc box = ClassDesc.ofInternalName(p.boxBinaryName());
        if (!Descs.of(from).equals(box)) {
          PrimType fromPrim = types.unboxedType(from);
          if (fromPrim != null && fromPrim != p) {
            box = ClassDesc.ofInternalName(fromPrim.boxBinaryName());
            p = fromPrim;
          }
          if (!Descs.of(from).equals(box)) {
            cb.checkcast(box);
          }
        }
        cb.invokevirtual(box, p.display() + "Value", MethodTypeDesc.of(Descs.of(p)));
        if (p != c.type()) {
          convertPrim(p, (PrimType) c.type());
        }
      }
      case CHECKCAST -> {
        if (c.type() instanceof PrimType) {
          return;
        }
        ClassDesc to = Descs.of(c.type());
        if (!to.equals(ConstantDescs.CD_Object)
            && !(from instanceof Type.NullType)
            && !(types.isSubtype(from.erasure(), c.type().erasure())
                && Descs.of(from).equals(to))) {
          cb.checkcast(to);
        }
      }
      case RETYPE -> {}
      case NULL_CHECK, NON_NULL_ASSERT -> throw new IllegalStateException("null check not lowered");
    }
  }

  private void convertPrim(PrimType from, PrimType to) {
    if (from == to) {
      return;
    }
    TypeKind f = Descs.kind(from);
    TypeKind t = Descs.kind(to);
    // byte/short/char/boolean are ints on the operand stack.
    TypeKind fs = stackKind(f);
    if (t == TypeKind.BYTE || t == TypeKind.SHORT || t == TypeKind.CHAR) {
      if (fs != TypeKind.INT) {
        cb.conversion(fs, TypeKind.INT);
      }
      if (from == PrimType.BYTE && to == PrimType.SHORT) {
        return;
      }
      cb.conversion(TypeKind.INT, t);
      return;
    }
    TypeKind ts = stackKind(t);
    if (fs != ts) {
      cb.conversion(fs, ts);
    }
  }

  private static TypeKind stackKind(TypeKind k) {
    return switch (k) {
      case BYTE, SHORT, CHAR, BOOLEAN, INT -> TypeKind.INT;
      default -> k;
    };
  }

  private void classLit(Type t) {
    if (t instanceof PrimType p) {
      ClassDesc box =
          ClassDesc.ofInternalName(p == PrimType.VOID ? "java/lang/Void" : p.boxBinaryName());
      cb.getstatic(box, "TYPE", ConstantDescs.CD_Class);
      return;
    }
    cb.loadConstant(Descs.of(t));
  }

  // ------------------------------------------------------------------ invokedynamic

  private static final DirectMethodHandleDesc CONCAT_BSM =
      MethodHandleDesc.ofMethod(
          DirectMethodHandleDesc.Kind.STATIC,
          ClassDesc.of("java.lang.invoke.StringConcatFactory"),
          "makeConcatWithConstants",
          MethodTypeDesc.of(
              ConstantDescs.CD_CallSite,
              ConstantDescs.CD_MethodHandles_Lookup,
              ConstantDescs.CD_String,
              ConstantDescs.CD_MethodType,
              ConstantDescs.CD_String,
              ConstantDescs.CD_Object.arrayType()));

  /**
   * String concatenation through {@code StringConcatFactory} (constants folded into the recipe).
   */
  private void concat(List<BExpr> parts) {
    List<List<BExpr>> chunks = new ArrayList<>();
    List<BExpr> current = new ArrayList<>();
    int slotsUsed = 0;
    for (BExpr p : parts) {
      int size = isRecipeConstant(p) ? 0 : Descs.kind(p.type()).slotSize();
      if (slotsUsed + size > 190) {
        chunks.add(current);
        current = new ArrayList<>();
        slotsUsed = 0;
      }
      current.add(p);
      slotsUsed += size;
    }
    chunks.add(current);
    boolean first = true;
    for (List<BExpr> chunk : chunks) {
      StringBuilder recipe = new StringBuilder();
      List<ClassDesc> argTypes = new ArrayList<>();
      if (!first) {
        recipe.append('\u0001');
        argTypes.add(ConstantDescs.CD_String);
      }
      for (BExpr p : chunk) {
        if (isRecipeConstant(p)) {
          recipe.append(String.valueOf(((BExpr.Const) p).value()));
          continue;
        }
        expr(p);
        recipe.append('\u0001');
        argTypes.add(concatArgType(p.type()));
      }
      cb.invokedynamic(
          DynamicCallSiteDesc.of(
              CONCAT_BSM,
              "makeConcatWithConstants",
              MethodTypeDesc.of(ConstantDescs.CD_String, argTypes),
              recipe.toString()));
      first = false;
    }
  }

  private static boolean isRecipeConstant(BExpr p) {
    if (!(p instanceof BExpr.Const c) || c.value() == null) {
      return false;
    }
    String s = String.valueOf(c.value() instanceof Character ch ? ch : c.value());
    if (c.type() == PrimType.CHAR && c.value() instanceof Integer i) {
      s = String.valueOf((char) i.intValue());
    }
    return s.indexOf('\u0001') < 0
        && s.indexOf('\u0002') < 0
        && !(c.type() == PrimType.CHAR && c.value() instanceof Integer)
        && (c.type() instanceof PrimType || c.value() instanceof String);
  }

  private static ClassDesc concatArgType(Type t) {
    if (t instanceof PrimType) {
      return Descs.of(t);
    }
    ClassDesc d = Descs.of(t);
    return d.equals(ConstantDescs.CD_String) ? d : ConstantDescs.CD_Object;
  }

  private static final DirectMethodHandleDesc LMF =
      MethodHandleDesc.ofMethod(
          DirectMethodHandleDesc.Kind.STATIC,
          ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
          "metafactory",
          MethodTypeDesc.of(
              ConstantDescs.CD_CallSite,
              ConstantDescs.CD_MethodHandles_Lookup,
              ConstantDescs.CD_String,
              ConstantDescs.CD_MethodType,
              ConstantDescs.CD_MethodType,
              ConstantDescs.CD_MethodHandle,
              ConstantDescs.CD_MethodType));

  private void indy(BExpr.Indy i) {
    List<ClassDesc> capturedTypes = new ArrayList<>();
    for (BExpr c : i.captured()) {
      expr(c);
      capturedTypes.add(Descs.of(c.type()));
    }
    MethodSymbol impl = i.impl();
    ClassSymbol owner = impl.owner();
    boolean itf = owner.isInterface();
    DirectMethodHandleDesc.Kind kind =
        switch (i.implKind()) {
          case STATIC ->
              itf
                  ? DirectMethodHandleDesc.Kind.INTERFACE_STATIC
                  : DirectMethodHandleDesc.Kind.STATIC;
          case VIRTUAL ->
              itf
                  ? DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL
                  : DirectMethodHandleDesc.Kind.VIRTUAL;
          case INTERFACE ->
              owner == cls && impl.has(Flags.PRIVATE)
                  ? DirectMethodHandleDesc.Kind.INTERFACE_SPECIAL
                  : DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL;
          case SPECIAL ->
              itf
                  ? DirectMethodHandleDesc.Kind.INTERFACE_SPECIAL
                  : DirectMethodHandleDesc.Kind.SPECIAL;
          case NEW -> DirectMethodHandleDesc.Kind.CONSTRUCTOR;
        };
    MethodTypeDesc implDesc = Descs.of(impl);
    if (kind == DirectMethodHandleDesc.Kind.CONSTRUCTOR) {
      implDesc = MethodTypeDesc.of(ConstantDescs.CD_void, implDesc.parameterList());
    }
    DirectMethodHandleDesc handle =
        MethodHandleDesc.ofMethod(kind, Descs.of(owner), impl.jvmName(), implDesc);
    MethodSymbol sam = i.sam();
    MethodTypeDesc samType = Descs.of(sam);
    List<ClassDesc> instParams = new ArrayList<>();
    for (Type t : i.instantiatedParams()) {
      instParams.add(Descs.of(t));
    }
    Type ret = i.instantiatedReturn();
    MethodTypeDesc instantiated =
        MethodTypeDesc.of(ret == null ? ConstantDescs.CD_void : Descs.of(ret), instParams);
    MethodTypeDesc callSiteType = MethodTypeDesc.of(Descs.of(i.type().sym()), capturedTypes);
    cb.invokedynamic(
        DynamicCallSiteDesc.of(LMF, sam.jvmName(), callSiteType, samType, handle, instantiated));
  }

  /** The symbol table (for record codegen helpers). */
  Symtab syms() {
    return syms;
  }
}
