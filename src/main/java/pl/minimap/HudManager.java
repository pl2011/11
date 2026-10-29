package pl.minimap;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Minimapa jako HUD na ekranie: kolorowe bloki w bocznym scoreboardzie (prawa strona ekranu).
 * Każdy wiersz scoreboardu = jeden rząd mapy, tytuł = koordynaty. Północ u góry.
 */
final class HudManager {

    private static final int ROWS = 15; // maksimum wierszy w sidebarze
    private static final int RED = 0xDC1E1E;
    private static final int WHITE = 0xFFFFFF;
    private static final int UNLOADED = 0x282828;
    private static final int VOID = 0x000000;

    private static final class Hud {
        final Scoreboard board;
        final Objective obj;
        final Scoreboard previous;
        final int[][] last = new int[ROWS][];
        String title = "";

        Hud(Scoreboard board, Objective obj, Scoreboard previous) {
            this.board = board;
            this.obj = obj;
            this.previous = previous;
        }
    }

    private final MinimapPlugin plugin;
    private final Map<UUID, Hud> huds = new HashMap<>();
    private BukkitTask task;
    private boolean errorLogged = false;
    private boolean hijackLogged = false;

    HudManager(MinimapPlugin plugin) {
        this.plugin = plugin;
    }

    // ===== CYKL ŻYCIA =====
    void start() {
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, plugin.hudUpdateTicks);
    }

    void shutdown() {
        if (task != null) task.cancel();
        task = null;
        for (Map.Entry<UUID, Hud> e : huds.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.setScoreboard(e.getValue().previous);
        }
        huds.clear();
    }

    boolean isOn(Player p) {
        return huds.containsKey(p.getUniqueId());
    }

    void remove(UUID id) {
        huds.remove(id);
    }

    void toggle(Player p) {
        if (isOn(p)) {
            disable(p);
            plugin.msg(p, "Minimapa HUD wyłączona.", NamedTextColor.YELLOW);
        } else {
            enable(p);
            plugin.msg(p, "Minimapa HUD włączona. Zoom: /minimap zoom", NamedTextColor.GREEN);
        }
    }

    void enable(Player p) {
        if (isOn(p)) return;
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("minimap", Criteria.DUMMY,
                Component.text("Minimapa", NamedTextColor.GOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.numberFormat(NumberFormat.blank());
        for (int r = 0; r < ROWS; r++) {
            Score s = obj.getScore(entry(r));
            s.setScore(ROWS - r);
            s.customName(Component.text(" "));
        }
        Hud h = new Hud(board, obj, p.getScoreboard());
        huds.put(p.getUniqueId(), h);
        p.setScoreboard(board);
        update(p, h);
    }

    void disable(Player p) {
        Hud h = huds.remove(p.getUniqueId());
        if (h != null) p.setScoreboard(h.previous);
    }

    private static String entry(int r) {
        return "\u00A7" + Integer.toHexString(r) + "\u00A7r";
    }

    // ===== PĘTLA =====
    private void tick() {
        for (UUID id : new ArrayList<>(huds.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Hud h = huds.get(id);
            if (p == null || !p.isOnline() || h == null) {
                huds.remove(id);
                continue;
            }
            try {
                if (p.getScoreboard() != h.board) {
                    // inny plugin przejął scoreboard gracza - przywracamy HUD
                    p.setScoreboard(h.board);
                    if (!hijackLogged) {
                        hijackLogged = true;
                        plugin.getLogger().warning("Inny plugin podmienia scoreboard gracza "
                                + p.getName() + " - przywracam HUD minimapy.");
                    }
                }
                update(p, h);
            } catch (Exception ex) {
                if (!errorLogged) {
                    errorLogged = true;
                    plugin.getLogger().warning("Błąd HUD minimapy: " + ex);
                }
            }
        }
    }

    private void update(Player p, Hud h) {
        Location loc = p.getLocation();
        World w = loc.getWorld();
        int cols = plugin.hudCols;
        int zoom = plugin.zoomFor(p);
        int[][] grid = sample(w, loc, cols, zoom);

        int mr = ROWS / 2;
        int mc = cols / 2;
        double yaw = Math.toRadians(loc.getYaw());
        int fr = mr + (int) Math.round(Math.cos(yaw));
        int fc = mc + (int) Math.round(-Math.sin(yaw));
        grid[fr][fc] = WHITE;
        grid[mr][mc] = RED;

        for (int r = 0; r < ROWS; r++) {
            if (h.last[r] != null && Arrays.equals(h.last[r], grid[r])) continue;
            h.last[r] = grid[r];
            h.obj.getScore(entry(r)).customName(line(grid[r]));
        }

        String t = loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
        if (!t.equals(h.title)) {
            h.title = t;
            h.obj.displayName(Component.text(t, NamedTextColor.GOLD));
        }
    }

    private static Component line(int[] row) {
        TextComponent.Builder b = Component.text();
        int c = 0;
        while (c < row.length) {
            int col = row[c];
            int n = 1;
            while (c + n < row.length && row[c + n] == col) n++;
            b.append(Component.text("\u2588".repeat(n), TextColor.color(col)));
            c += n;
        }
        return b.build();
    }

    // ===== PRÓBKOWANIE TERENU =====
    // Znak w sidebarze jest ok. 6x9 px, więc krok w osi Z jest 1.5x większy niż w osi X,
    // dzięki temu mapa nie jest rozciągnięta.
    private int[][] sample(World w, Location loc, int cols, int zoom) {
        int cx = loc.getBlockX();
        int cz = loc.getBlockZ();
        int py = loc.getBlockY();
        World.Environment env = w.getEnvironment();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;

        boolean cave = env == World.Environment.NETHER;
        if (env == World.Environment.NORMAL && w.isChunkLoaded(cx >> 4, cz >> 4)) {
            cave = py < w.getHighestBlockYAt(cx, cz, HeightMap.WORLD_SURFACE) - 4;
        }
        int caveTop = Math.min(py + 3, maxY);
        int caveBottom = Math.max(minY, caveTop - plugin.caveDepth);

        int[][] grid = new int[ROWS][cols];
        int[] prev = new int[cols];
        Arrays.fill(prev, Integer.MIN_VALUE);

        for (int r = 0; r < ROWS; r++) {
            int wz = cz + (int) Math.round((r - ROWS / 2) * zoom * 1.5);
            for (int c = 0; c < cols; c++) {
                int wx = cx + (c - cols / 2) * zoom;
                if (!w.isChunkLoaded(wx >> 4, wz >> 4)) {
                    grid[r][c] = UNLOADED;
                    continue;
                }

                Block b = null;
                int y = minY - 1;
                if (cave) {
                    for (int yy = caveTop; yy >= caveBottom; yy--) {
                        Block t = w.getBlockAt(wx, yy, wz);
                        if (!t.getType().isAir()) {
                            b = t;
                            y = yy;
                            break;
                        }
                    }
                } else {
                    y = w.getHighestBlockYAt(wx, wz, HeightMap.WORLD_SURFACE);
                    if (y >= minY) b = w.getBlockAt(wx, y, wz);
                }
                if (b == null || b.getType().isAir()) {
                    grid[r][c] = VOID;
                    continue;
                }

                Color base = b.getBlockData().getMapColor();
                int tries = 0;
                while (base.asRGB() == 0 && tries++ < 8 && y > minY) {
                    y--;
                    b = w.getBlockAt(wx, y, wz);
                    base = b.getBlockData().getMapColor();
                }

                double f;
                if (b.getType() == Material.WATER) {
                    int depth = 0;
                    for (int yy = y - 1; yy >= minY && depth < 12; yy--) {
                        if (w.getBlockAt(wx, yy, wz).getType() != Material.WATER) break;
                        depth++;
                    }
                    f = Math.max(0.55, 1.0 - depth * 0.04);
                } else if (prev[c] == Integer.MIN_VALUE) {
                    f = 1.0;
                } else {
                    f = y > prev[c] ? 1.12 : (y < prev[c] ? 0.86 : 1.0);
                }
                prev[c] = y;

                grid[r][c] = (c255(base.getRed() * f) << 16)
                        | (c255(base.getGreen() * f) << 8)
                        | c255(base.getBlue() * f);
            }
        }
        return grid;
    }

    private static int c255(double v) {
        return (int) Math.min(255, Math.max(0, Math.round(v)));
    }
}
