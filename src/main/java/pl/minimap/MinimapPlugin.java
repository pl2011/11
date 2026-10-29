package pl.minimap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MinimapPlugin extends JavaPlugin implements Listener {

    // ===== USTAWIENIA (czytane z config.yml) =====
    int defaultZoom = 1;
    long moveRefreshMs = 250;
    long idleRefreshMs = 1000;
    int caveDepth = 24;
    boolean showCoords = true;
    boolean showDirection = true;
    boolean useOffhand = true;
    boolean giveOnJoin = false;
    boolean blockDrop = true;
    int hudCols = 21;
    int hudUpdateTicks = 5;

    // ===== STAN =====
    private NamespacedKey itemKey;
    private MinimapRenderer renderer;
    private HudManager hud;
    private File dataFile;
    private int mapId = -1;
    private final Map<UUID, Integer> zoomByPlayer = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "minimap");
        dataFile = new File(getDataFolder(), "data.yml");
        renderer = new MinimapRenderer(this);
        hud = new HudManager(this);
        loadSettings();
        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        if (mapId >= 0) {
            MapView v = Bukkit.getMap(mapId);
            if (v != null) attach(v);
        }
        getLogger().info("Minimap włączony (zoom=" + defaultZoom + ").");
    }

    @Override
    public void onDisable() {
        if (hud != null) hud.shutdown();
    }

    // ===== KONFIGURACJA I DANE =====
    void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        defaultZoom = sanitizeZoom(c.getInt("default-zoom", 1));
        moveRefreshMs = Math.max(50L, c.getLong("move-refresh-ms", 250L));
        idleRefreshMs = Math.max(100L, c.getLong("idle-refresh-ms", 1000L));
        caveDepth = Math.max(4, Math.min(64, c.getInt("cave-depth", 24)));
        showCoords = c.getBoolean("show-coords", true);
        showDirection = c.getBoolean("show-direction", true);
        useOffhand = c.getBoolean("use-offhand", true);
        giveOnJoin = c.getBoolean("give-on-join", false);
        blockDrop = c.getBoolean("block-drop", true);
        int cols = Math.max(9, Math.min(41, c.getInt("hud-cols", 21)));
        hudCols = (cols % 2 == 0) ? cols + 1 : cols;
        hudUpdateTicks = Math.max(2, Math.min(40, c.getInt("hud-update-ticks", 5)));
        if (hud != null) hud.start();
    }

    private void loadData() {
        if (dataFile.exists()) {
            mapId = YamlConfiguration.loadConfiguration(dataFile).getInt("map-id", -1);
        }
    }

    private void saveData() {
        try {
            getDataFolder().mkdirs();
            YamlConfiguration y = new YamlConfiguration();
            y.set("map-id", mapId);
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Nie udało się zapisać data.yml: " + e.getMessage());
        }
    }

    static int sanitizeZoom(int z) {
        return (z == 2 || z == 4 || z == 8) ? z : 1;
    }

    int zoomFor(Player p) {
        return zoomByPlayer.getOrDefault(p.getUniqueId(), defaultZoom);
    }

    // ===== MAPA / PRZEDMIOT =====
    private MapView getOrCreateView() {
        MapView v = mapId >= 0 ? Bukkit.getMap(mapId) : null;
        if (v == null) {
            v = Bukkit.createMap(Bukkit.getWorlds().get(0));
            mapId = v.getId();
            saveData();
        }
        attach(v);
        return v;
    }

    private void attach(MapView v) {
        for (MapRenderer r : v.getRenderers()) {
            if (r instanceof MinimapRenderer) return;
        }
        for (MapRenderer r : new ArrayList<>(v.getRenderers())) {
            v.removeRenderer(r);
        }
        v.addRenderer(renderer);
        v.setTrackingPosition(false);
        v.setUnlimitedTracking(false);
        v.setScale(MapView.Scale.NORMAL);
    }

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(getOrCreateView());
        meta.displayName(Component.text("Minimapa", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Teren wokół Ciebie na żywo", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    boolean isMinimap(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private boolean hasMinimap(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i <= 40; i++) {
            if (isMinimap(inv.getItem(i))) return true;
        }
        return false;
    }

    private void toggle(Player p) {
        PlayerInventory inv = p.getInventory();
        boolean removed = false;
        for (int i = 0; i <= 40; i++) {
            if (isMinimap(inv.getItem(i))) {
                inv.setItem(i, null);
                removed = true;
            }
        }
        if (removed) {
            msg(p, "Minimapa wyłączona.", NamedTextColor.YELLOW);
            return;
        }
        ItemStack item = createItem();
        if (useOffhand && inv.getItemInOffHand().getType().isAir()) {
            inv.setItemInOffHand(item);
            msg(p, "Minimapa włączona (lewa ręka). Zoom: /minimap zoom", NamedTextColor.GREEN);
        } else {
            Map<Integer, ItemStack> left = inv.addItem(item);
            if (!left.isEmpty()) {
                msg(p, "Brak miejsca w ekwipunku.", NamedTextColor.RED);
                return;
            }
            msg(p, "Minimapa dodana do ekwipunku - weź ją do ręki.", NamedTextColor.GREEN);
        }
    }

    void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text("[Minimap] ", NamedTextColor.GOLD)
                .append(Component.text(text, color)));
    }

    // ===== KOMENDY =====
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("minimap.admin")) {
                msg(sender, "Brak uprawnień.", NamedTextColor.RED);
                return true;
            }
            loadSettings();
            msg(sender, "Konfiguracja przeładowana.", NamedTextColor.GREEN);
            return true;
        }
        if (!(sender instanceof Player p)) {
            msg(sender, "Ta komenda jest tylko dla graczy.", NamedTextColor.RED);
            return true;
        }
        if (!p.hasPermission("minimap.use")) {
            msg(p, "Brak uprawnień.", NamedTextColor.RED);
            return true;
        }
        if (args.length == 0) {
            hud.toggle(p);
            return true;
        }
        if (args[0].equalsIgnoreCase("item")) {
            toggle(p);
            return true;
        }
        if (args[0].equalsIgnoreCase("zoom")) {
            int z;
            if (args.length >= 2) {
                try {
                    z = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    msg(p, "Zoom musi być liczbą: 1, 2, 4 lub 8.", NamedTextColor.RED);
                    return true;
                }
                if (z != 1 && z != 2 && z != 4 && z != 8) {
                    msg(p, "Dozwolony zoom: 1, 2, 4, 8.", NamedTextColor.RED);
                    return true;
                }
            } else {
                int cur = zoomFor(p);
                z = cur == 1 ? 2 : cur == 2 ? 4 : cur == 4 ? 8 : 1;
            }
            zoomByPlayer.put(p.getUniqueId(), z);
            msg(p, "Zoom: " + z + " blok(ów) na punkt mapy.", NamedTextColor.GREEN);
            return true;
        }
        msg(p, "Użycie: /minimap | /minimap zoom [1|2|4|8] | /minimap item", NamedTextColor.YELLOW);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        if (args.length == 1) {
            List<String> o = new ArrayList<>(List.of("zoom", "item"));
            if (s.hasPermission("minimap.admin")) o.add("reload");
            String pre = args[0].toLowerCase();
            return o.stream().filter(x -> x.startsWith(pre)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("zoom")) {
            return List.of("1", "2", "4", "8");
        }
        return List.of();
    }

    // ===== EVENTY =====
    @EventHandler
    public void onMapInit(MapInitializeEvent e) {
        if (mapId >= 0 && e.getMap().getId() == mapId) attach(e.getMap());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (giveOnJoin && p.hasPermission("minimap.use")) hud.enable(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        renderer.forget(e.getPlayer().getUniqueId());
        hud.remove(e.getPlayer().getUniqueId());
        zoomByPlayer.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (blockDrop && isMinimap(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isMinimap);
    }

    @EventHandler
    public void onFrame(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof ItemFrame
                && isMinimap(e.getPlayer().getInventory().getItem(e.getHand()))) {
            e.setCancelled(true);
        }
    }
}
