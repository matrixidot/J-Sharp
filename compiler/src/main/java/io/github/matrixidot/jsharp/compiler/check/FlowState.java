package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Flow facts at a program point: reachability, definite assignment (locals by id, final fields of
 * the class being constructed), possible assignment (for single assignment of finals) and
 * smart-cast narrowings of locals and of stable paths ({@link Attr.StablePath}, D089). Copied at
 * branches and joined at merges.
 */
final class FlowState {
  boolean alive = true;
  final BitSet assigned;
  final Set<FieldSymbol> assignedFields;

  /** Keys are {@link VarSymbol}s and {@link Attr.StablePath}s. */
  final Map<Object, Type> narrowed;

  /** Variables that may have been assigned on some path. */
  final BitSet maybeAssigned;

  FlowState() {
    this(new BitSet(), new HashSet<>(), new HashMap<>(), new BitSet());
  }

  private FlowState(
      BitSet assigned, Set<FieldSymbol> fields, Map<Object, Type> narrowed, BitSet maybe) {
    this.assigned = assigned;
    this.assignedFields = fields;
    this.narrowed = narrowed;
    this.maybeAssigned = maybe;
  }

  FlowState copy() {
    FlowState f =
        new FlowState(
            (BitSet) assigned.clone(),
            new HashSet<>(assignedFields),
            new HashMap<>(narrowed),
            (BitSet) maybeAssigned.clone());
    f.alive = alive;
    return f;
  }

  /** A dead state: everything is definitely assigned (vacuously). */
  static FlowState dead() {
    FlowState f = new FlowState();
    f.alive = false;
    f.assigned.set(0, 1 << 16);
    return f;
  }

  /** Merges {@code other} into this state (control-flow join). */
  void join(FlowState other) {
    maybeAssigned.or(other.maybeAssigned);
    if (!other.alive) {
      return;
    }
    if (!alive) {
      alive = true;
      assigned.clear();
      assigned.or(other.assigned);
      assignedFields.clear();
      assignedFields.addAll(other.assignedFields);
      narrowed.clear();
      narrowed.putAll(other.narrowed);
      return;
    }
    assigned.and(other.assigned);
    assignedFields.retainAll(other.assignedFields);
    narrowed.entrySet().removeIf(e -> !e.getValue().equals(other.narrowed.get(e.getKey())));
  }

  /** Replaces the contents of this state with {@code other}'s. */
  void set(FlowState other) {
    alive = other.alive;
    assigned.clear();
    assigned.or(other.assigned);
    assignedFields.clear();
    assignedFields.addAll(other.assignedFields);
    narrowed.clear();
    narrowed.putAll(other.narrowed);
    maybeAssigned.clear();
    maybeAssigned.or(other.maybeAssigned);
  }
}
