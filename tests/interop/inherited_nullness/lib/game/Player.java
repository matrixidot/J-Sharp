package game;

/** Inherits location() from both: an online player always has one (Bukkit's Player). */
public interface Player extends OfflinePlayer, Entity {
  static Player at(String where) {
    return () -> where;
  }
}
