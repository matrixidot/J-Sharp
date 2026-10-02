package dev.jsharp.compiler.resolve;

import dev.jsharp.compiler.Context;
import dev.jsharp.compiler.ast.Modifier;
import dev.jsharp.compiler.ast.Modifiers;
import dev.jsharp.compiler.diag.Code;
import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.symbols.Flags;

/** Shared modifier validation and access-flag computation. */
final class ModifierRules {
  private ModifierRules() {}

  /**
   * Access flags from modifiers. The default is package-private ("internal"), except for members of
   * interfaces, which are public.
   */
  static long accessFlags(
      Context ctx, Modifiers mods, SourceFile file, boolean isMember, boolean inInterface) {
    long f = 0;
    int count = 0;
    for (Modifiers.Item item : mods.list()) {
      switch (item.modifier()) {
        case PUBLIC -> {
          f |= Flags.PUBLIC;
          count++;
        }
        case PROTECTED -> {
          f |= Flags.PROTECTED;
          count++;
          if (!isMember) {
            invalid(ctx, file, item, "top-level declarations cannot be 'protected'");
          } else if (inInterface) {
            invalid(ctx, file, item, "interface members cannot be 'protected'");
          }
        }
        case PRIVATE -> {
          f |= Flags.PRIVATE;
          count++;
        }
        case INTERNAL -> count++;
        default -> {}
      }
    }
    if (count > 1) {
      ctx.report(Code.INVALID_MODIFIER, file, mods.span(), "conflicting access modifiers");
    }
    if (count == 0 && inInterface) {
      f |= Flags.PUBLIC;
    }
    return f;
  }

  static void invalid(Context ctx, SourceFile file, Modifiers.Item item, String message) {
    ctx.report(Code.INVALID_MODIFIER, file, item.span(), message);
  }

  static Modifiers.Item itemOf(Modifiers mods, Modifier m) {
    for (Modifiers.Item i : mods.list()) {
      if (i.modifier() == m) {
        return i;
      }
    }
    throw new IllegalArgumentException(m.toString());
  }

  /** Reports every modifier not in {@code allowed}. */
  static void allowOnly(
      Context ctx, SourceFile file, Modifiers mods, String what, Modifier... allowed) {
    outer:
    for (Modifiers.Item item : mods.list()) {
      for (Modifier a : allowed) {
        if (item.modifier() == a) {
          continue outer;
        }
      }
      invalid(ctx, file, item, "'" + item.modifier().keyword() + "' is not allowed on " + what);
    }
  }
}
