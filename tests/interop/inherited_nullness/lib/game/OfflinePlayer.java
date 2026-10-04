package game;

import org.jetbrains.annotations.Nullable;

/** Like Bukkit's: an offline player may have no location. */
public interface OfflinePlayer {
  @Nullable
  String location();
}
