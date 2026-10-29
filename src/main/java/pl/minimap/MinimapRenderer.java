package pl.minimap;

import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rysuje teren wokół gracza (widok z góry, północ u góry).
 * Teren jest cache'owany i przeliczany co moveRefreshMs / idleRefreshMs,
 * marker i kierunek odświeżają się tylko przy zmianie kierunku o ~15 stopni.
 */
final class MinimapRenderer extends MapRenderer {

    private static final int SIZE = 128;
    private static final int HALF = 64;

    private static final class State {
        boolean valid;
        UUID world;
        int x, z, zoom, bucket;
        long time;
        final byte[] terrain = new byte[SIZE * SIZE];
    }

    private final MinimapPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<Integer, Byte> colorCache = new HashMap<>();
    private boolean errorLogged = false;

    MinimapRenderer(MinimapPlugin plugin) {
        super(true); // contextual: osobny canvas dla każdego gracza
        this.plugin = plugin;
    }

    void forget(UUID id) {
        states.remove(id);
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        PlayerInventory inv = player.getInventory();
        if (!plugin.isMinimap(inv.getItemInMainHand()) && !plugin.isMinimap(inv.getItemInOffHand())) return;
        try {
            draw(canvas, player);
        } catch (Exception ex) {
            if (!errorLogged) {
                errorLogged = true;
                plugin.getLogger().warning("Błąd renderowania minimapy: " + ex);
            }
        }
    }

    private void draw(MapCanvas canvas, Player player) {
        Location loc = player.getLocation();
        World w = loc.getWorld();
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        int zoom = plugin.zoomFor(player);
        long now = System.currentTimeMillis();

        State s = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        boolean same = s.valid && s.world.equals(w.getUID()) && s.zoom == zoom;
        boolean moved = !same || s.x != bx || s.z != bz;
        boolean terrainDue = !same || now - s.time >= (moved ? plugin.moveRefreshMs : plugin.idleRefreshMs);
        int bucket = Math.round(((loc.getYaw() % 360f) + 360f) % 360f / 15f) % 24;
        boolean overlayDue = terrainDue || bucket != s.bucket;
        if (!overlayDue) return;

        if (terrainDue) {
            scan(w, player, bx, bz, zoom, s.terrain);
            s.world = w.getUID();
            s.x = bx;
            s.z = bz;
            s.zoom = zoom;
            s.time = now;
            s.valid = true;
        }
        s.bucket = bucket;

        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                canvas.setPixel(x, z, s.terrain[z * SIZE + x]);
            }
        }
        overlay(canvas, loc);
    }

    // ===== SKAN TERENU =====
    private void scan(World w, Player p, int cx, int cz, int zoom, byte[] out) {
        World.Environment env = w.getEnvironment();
        int py = p.getLocation().getBlockY();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;

        boolean cave = env == World.Environment.NETHER;
        if (env == World.Environment.NORMAL && w.isChunkLoaded(cx >> 4, cz >> 4)) {
            cave = py < w.getHighestBlockYAt(cx, cz, HeightMap.WORLD_SURFACE) - 4;
        }
        int caveTop = Math.min(py + 3, maxY);
        int caveBottom = Math.max(minY, caveTop - plugin.caveDepth);

        byte unloaded = color(40, 40, 40);
        byte voidColor = color(0, 0, 0);
        Map<Long, Boolean> loaded = new HashMap<>();
        int[] prev = new int[SIZE];
        Arrays.fill(prev, Integer.MIN_VALUE);

        for (int pz = 0; pz < SIZE; pz++) {
            for (int px = 0; px < SIZE; px++) {
                int wx = cx + (px - HALF) * zoom;
                int wz = cz + (pz - HALF) * zoom;
                int chx = wx >> 4;
                int chz = wz >> 4;
                long key = ((long) chx << 32) | (chz & 0xFFFFFFFFL);
                Boolean ok = loaded.get(key);
                if (ok == null) {
                    ok = w.isChunkLoaded(chx, chz);
                    loaded.put(key, ok);
                }
                int idx = pz * SIZE + px;
                if (!ok) {
                    out[idx] = unloaded;
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
                    out[idx] = voidColor;
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
                } else if (prev[px] == Integer.MIN_VALUE) {
                    f = 1.0;
                } else {
                    f = y > prev[px] ? 1.12 : (y < prev[px] ? 0.86 : 1.0);
                }
                prev[px] = y;

                out[idx] = color(c255(base.getRed() * f), c255(base.getGreen() * f), c255(base.getBlue() * f));
            }
        }
    }

    // ===== NAKŁADKA: ramka, marker, kierunek, N, koordynaty =====
    private void overlay(MapCanvas c, Location loc) {
        byte white = color(255, 255, 255);
        byte red = color(220, 30, 30);
        byte dark = color(25, 25, 25);
        byte grey = color(70, 70, 70);

        for (int i = 0; i < SIZE; i++) {
            c.setPixel(i, 0, grey);
            c.setPixel(i, SIZE - 1, grey);
            c.setPixel(0, i, grey);
            c.setPixel(SIZE - 1, i, grey);
        }

        if (plugin.showDirection) {
            double yaw = Math.toRadians(loc.getYaw());
            double dx = -Math.sin(yaw);
            double dz = Math.cos(yaw);
            for (int i = 4; i <= 10; i++) {
                put(c, HALF + (int) Math.round(dx * i), HALF + (int) Math.round(dz * i), white);
            }
        }
        for (int y = HALF - 2; y <= HALF + 2; y++) {
            for (int x = HALF - 2; x <= HALF + 2; x++) put(c, x, y, white);
        }
        for (int y = HALF - 1; y <= HALF + 1; y++) {
            for (int x = HALF - 1; x <= HALF + 1; x++) put(c, x, y, red);
        }

        String wc = "\u00A7" + (white & 0xFF) + ";";
        for (int y = 1; y <= 9; y++) {
            for (int x = HALF - 4; x <= HALF + 4; x++) put(c, x, y, dark);
        }
        drawText(c, HALF - 2, 1, wc + "N");

        if (plugin.showCoords) {
            for (int y = SIZE - 11; y < SIZE - 1; y++) {
                for (int x = 1; x < SIZE - 1; x++) put(c, x, y, dark);
            }
            String t = loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
            drawText(c, 4, SIZE - 10, wc + t);
        }
    }

    private void drawText(MapCanvas c, int x, int y, String text) {
        try {
            c.drawText(x, y, MinecraftFont.Font, text);
        } catch (RuntimeException ignored) {
            // znak spoza czcionki - pomijamy
        }
    }

    private void put(MapCanvas c, int x, int y, byte color) {
        if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) c.setPixel(x, y, color);
    }

    private static int c255(double v) {
        return (int) Math.min(255, Math.max(0, Math.round(v)));
    }

    @SuppressWarnings("deprecation")
    private byte color(int r, int g, int b) {
        int key = (r << 16) | (g << 8) | b;
        Byte v = colorCache.get(key);
        if (v == null) {
            v = MapPalette.matchColor(r, g, b);
            colorCache.put(key, v);
        }
        return v;
    }
}
