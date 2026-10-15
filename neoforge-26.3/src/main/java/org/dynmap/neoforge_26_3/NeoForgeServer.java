package org.dynmap.neoforge_26_3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.dynmap.DynmapChunk;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.common.BiomeMap;
import org.dynmap.common.DynmapListenerManager;
import org.dynmap.common.DynmapPlayer;
import org.dynmap.common.DynmapServerInterface;
import org.dynmap.neoforge_26_3.event.DynmapEvents;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.VisibilityLimit;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;

/**
 * Server access abstraction class
 */
public class NeoForgeServer extends DynmapServerInterface {
    /* Server thread scheduler */
    private final Object schedlock = new Object();
    private final DynmapPlugin plugin;
    private final MinecraftServer server;
    private final net.minecraft.core.Registry<Biome> biomeRegistry;
    private long cur_tick;
    private long next_id;
    private long cur_tick_starttime;
    private PriorityQueue<TaskRecord> runqueue = new PriorityQueue<TaskRecord>();

    public NeoForgeServer(DynmapPlugin plugin, MinecraftServer server) {
        this.plugin = plugin;
        this.server = server;
        this.biomeRegistry = server.registryAccess().lookupOrThrow(Registries.BIOME);
    }

    public final net.minecraft.core.Registry<Biome> getBiomeRegistry() {
        return biomeRegistry;
    }

    private Biome[] biomelist = null;

    public final Biome[] getBiomeList(net.minecraft.core.Registry<Biome> biomeRegistry) {
        if (biomelist == null) {
            biomelist = new Biome[256];
            Iterator<Biome> iter = biomeRegistry.iterator();
            while (iter.hasNext()) {
                Biome b = iter.next();
                int bidx = biomeRegistry.getId(b);
                if (bidx >= biomelist.length) {
                    biomelist = Arrays.copyOf(biomelist, bidx + biomelist.length);
                }
                biomelist[bidx] = b;
            }
        }
        return biomelist;
    }

    @Override
    public int getBlockIDAt(String wname, int x, int y, int z) {
        return -1;
    }

    @Override
    public int isSignAt(String wname, int x, int y, int z) {
        NeoForgeWorld nfw = plugin.getWorldByName(wname);
        if (nfw == null) return -1;
        Level world = nfw.getWorld();
        if (world == null) return -1;

        BlockPos pos = new BlockPos(x, y, z);
        if (!world.hasChunkAt(pos))
            return -1;

        Block block = world.getBlockState(pos).getBlock();
        return (block instanceof SignBlock ? 1 : 0);
    }

    @Override
    public void scheduleServerTask(Runnable run, long delay) {
        /* Add task record to queue */
        synchronized (schedlock) {
            TaskRecord tr = new TaskRecord(cur_tick + delay, next_id++, new FutureTask<Object>(run, null));
            runqueue.add(tr);
        }
    }

    @Override
    public DynmapPlayer[] getOnlinePlayers() {
        if (server.getPlayerList() == null) return new DynmapPlayer[0];

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        int playerCount = players.size();
        DynmapPlayer[] dplay = new DynmapPlayer[players.size()];

        for (int i = 0; i < playerCount; i++) {
            ServerPlayer player = players.get(i);
            dplay[i] = plugin.getOrAddPlayer(player);
        }

        return dplay;
    }

    @Override
    public void reload() {
        plugin.onDisable();
        plugin.onEnable();
        plugin.onStart();
    }

    @Override
    public DynmapPlayer getPlayer(String name) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        for (ServerPlayer player : players) {

            if (player.getName().getString().equalsIgnoreCase(name)) {
                return plugin.getOrAddPlayer(player);
            }
        }

        return null;
    }

    @Override
    public Set<String> getIPBans() {
        Set<String> ips = new HashSet<String>();

        for (String s : server.getPlayerList().getIpBans().getUserList()) {
            ips.add(s);
        }

        return ips;
    }

    @Override
    public <T> Future<T> callSyncMethod(Callable<T> task) {
        return callSyncMethod(task, 0);
    }

    public <T> Future<T> callSyncMethod(Callable<T> task, long delay) {
        FutureTask<T> ft = new FutureTask<T>(task);

        /* Add task record to queue */
        synchronized (schedlock) {
            TaskRecord tr = new TaskRecord(cur_tick + delay, next_id++, ft);
            runqueue.add(tr);
        }

        return ft;
    }

    void clearTaskQueue() {
        this.runqueue.clear();
    }

    @Override
    public String getServerName() {
        String sn;
        if (server.isSingleplayer())
            sn = "Integrated";
        else
            sn = server.getLocalIp();
        if (sn == null) sn = "Unknown Server";
        return sn;
    }

    @Override
    public boolean isPlayerBanned(String pid) {
        UserBanList bl = server.getPlayerList().getBans();

        /* Direct lookup (ban entries are keyed by UUID); fall back to matching
         * by name, since we only get a player name here (the upstream Fabric
         * build had the same limitation through PlayerConfigEntry). */
        if (bl.get(NameAndId.createOffline(pid)) != null) {
            return true;
        }
        for (UserBanListEntry entry : bl.getEntries()) {
            if (entry.getUser().name().equalsIgnoreCase(pid)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String stripChatColor(String s) {
        return DynmapPlugin.patternControlCode.matcher(s).replaceAll("");
    }

    private Set<DynmapListenerManager.EventType> registered = new HashSet<DynmapListenerManager.EventType>();

    @Override
    public boolean requestEventNotification(DynmapListenerManager.EventType type) {
        if (registered.contains(type)) {
            return true;
        }

        switch (type) {
            case WORLD_LOAD:
            case WORLD_UNLOAD:
                /* Already called for normal world activation/deactivation */
                break;

            case WORLD_SPAWN_CHANGE:
                /*TODO*/
                break;

            case PLAYER_JOIN:
            case PLAYER_QUIT:
                /* Already handled */
                break;

            case PLAYER_BED_LEAVE:
                /*TODO*/
                break;

            case PLAYER_CHAT:
                if (plugin.chathandler == null) {
                    plugin.setChatHandler(new DynmapPlugin.ChatHandler(plugin));
                    NeoForge.EVENT_BUS.addListener((ServerChatEvent event) ->
                            plugin.chathandler.handleChat(event.getPlayer(), event.getRawText()));
                }
                break;

            case BLOCK_BREAK:
                /* Already handled by block change tracking */
                break;

            case SIGN_CHANGE:
                DynmapEvents.registerSignChange((world, pos, lines, player, front) -> {
                    plugin.core.processSignChange("neoforge", NeoForgeWorld.getWorldName(plugin, world),
                            pos.getX(), pos.getY(), pos.getZ(), lines, player.getName().getString());
                });
                break;

            default:
                Log.severe("Unhandled event type: " + type);
                return false;
        }

        registered.add(type);
        return true;
    }

    @Override
    public boolean sendWebChatEvent(String source, String name, String msg) {
        return DynmapCommonAPIListener.fireWebChatEvent(source, name, msg);
    }

    @Override
    public void broadcastMessage(String msg) {
        Component component = Component.literal(msg);
        server.getPlayerList().broadcastSystemMessage(component, false);
        Log.info(stripChatColor(msg));
    }

    @Override
    public String[] getBiomeIDs() {
        BiomeMap[] b = BiomeMap.values();
        String[] bname = new String[b.length];

        for (int i = 0; i < bname.length; i++) {
            bname[i] = b[i].toString();
        }

        return bname;
    }

    @Override
    public double getCacheHitRate() {
        if (plugin.sscache != null)
            return plugin.sscache.getHitRate();
        return 0.0;
    }

    @Override
    public void resetCacheStats() {
        if (plugin.sscache != null)
            plugin.sscache.resetStats();
    }

    @Override
    public DynmapWorld getWorldByName(String wname) {
        return plugin.getWorldByName(wname);
    }

    @Override
    public DynmapPlayer getOfflinePlayer(String name) {
        return null;
    }

    @Override
    public Set<String> checkPlayerPermissions(String player, Set<String> perms) {
        if (isPlayerBanned(player)) {
            return Collections.emptySet();
        }
        Set<String> rslt = plugin.hasOfflinePermissions(player, perms);
        if (rslt == null) {
            rslt = new HashSet<String>();
            if (plugin.isOp(player)) {
                rslt.addAll(perms);
            }
        }
        return rslt;
    }

    @Override
    public boolean checkPlayerPermission(String player, String perm) {
        if (isPlayerBanned(player)) {
            return false;
        }
        return plugin.hasOfflinePermission(player, perm);
    }

    /**
     * Render processor helper - used by code running on render threads to request chunk snapshot cache from server/sync thread
     */
    @Override
    public MapChunkCache createMapChunkCache(DynmapWorld w, List<DynmapChunk> chunks,
                                             boolean blockdata, boolean highesty, boolean biome, boolean rawbiome) {
        NeoForgeMapChunkCache c = (NeoForgeMapChunkCache) w.getChunkCache(chunks);
        if (c == null) {
            return null;
        }
        if (w.visibility_limits != null) {
            for (VisibilityLimit limit : w.visibility_limits) {
                c.setVisibleRange(limit);
            }

            c.setHiddenFillStyle(w.hiddenchunkstyle);
        }

        if (w.hidden_limits != null) {
            for (VisibilityLimit limit : w.hidden_limits) {
                c.setHiddenRange(limit);
            }

            c.setHiddenFillStyle(w.hiddenchunkstyle);
        }

        if (!c.setChunkDataTypes(blockdata, biome, highesty, rawbiome)) {
            Log.severe("CraftBukkit build does not support biome APIs");
        }

        if (chunks.size() == 0)     /* No chunks to get? */ {
            c.loadChunks(0);
            return c;
        }

        //Now handle any chunks in server thread that are already loaded (on server thread)
        final NeoForgeMapChunkCache cc = c;
        Future<Boolean> f = this.callSyncMethod(new Callable<Boolean>() {
            public Boolean call() throws Exception {
                // Update busy state on world
                //NeoForgeWorld fw = (NeoForgeWorld) cc.getWorld();
                //TODO
                //setBusy(fw.getWorld());
                cc.getLoadedChunks();
                return true;
            }
        }, 0);
        try {
            f.get();
        } catch (CancellationException cx) {
            return null;
        } catch (InterruptedException cx) {
            return null;
        } catch (ExecutionException xx) {
            Log.severe("Exception while loading chunks", xx.getCause());
            return null;
        } catch (Exception ix) {
            Log.severe(ix);
            return null;
        }
        if (!w.isLoaded()) {
            return null;
        }
        // Now, do rest of chunk reading from calling thread
        c.readChunks(chunks.size());

        return c;
    }

    @Override
    public int getMaxPlayers() {
        return server.getPlayerList().getMaxPlayers();
    }

    @Override
    public int getCurrentPlayers() {
        return server.getPlayerList().getPlayerCount();
    }

    public void tickEvent(MinecraftServer server) {
        cur_tick_starttime = System.nanoTime();
        long elapsed = cur_tick_starttime - plugin.lasttick;
        plugin.lasttick = cur_tick_starttime;
        plugin.avgticklen = ((plugin.avgticklen * 99) / 100) + (elapsed / 100);
        plugin.tps = (double) 1E9 / (double) plugin.avgticklen;
        // Tick core
        if (plugin.core != null) {
            plugin.core.serverTick(plugin.tps);
        }

        boolean done = false;
        TaskRecord tr = null;

        while (!plugin.blockupdatequeue.isEmpty()) {
            DynmapPlugin.BlockUpdateRec r = plugin.blockupdatequeue.remove();
            BlockState bs = r.w.getBlockState(new BlockPos(r.x, r.y, r.z));
            int idx = Block.BLOCK_STATE_REGISTRY.getId(bs);
            if (!org.dynmap.hdmap.HDBlockModels.isChangeIgnoredBlock(DynmapPlugin.stateByID[idx])) {
                if (plugin.onblockchange_with_id)
                    plugin.mapManager.touch(r.wid, r.x, r.y, r.z, "blockchange[" + idx + "]");
                else
                    plugin.mapManager.touch(r.wid, r.x, r.y, r.z, "blockchange");
            }
        }

        long now;

        synchronized (schedlock) {
            cur_tick++;
            now = System.nanoTime();
            tr = runqueue.peek();
            /* Nothing due to run */
            if ((tr == null) || (tr.getTickToRun() > cur_tick) || ((now - cur_tick_starttime) > plugin.perTickLimit)) {
                done = true;
            } else {
                tr = runqueue.poll();
            }
        }
        while (!done) {
            tr.run();

            synchronized (schedlock) {
                tr = runqueue.peek();
                now = System.nanoTime();
                /* Nothing due to run */
                if ((tr == null) || (tr.getTickToRun() > cur_tick) || ((now - cur_tick_starttime) > plugin.perTickLimit)) {
                    done = true;
                } else {
                    tr = runqueue.poll();
                }
            }
        }
        while (!plugin.msgqueue.isEmpty()) {
            DynmapPlugin.ChatMessage cm = plugin.msgqueue.poll();
            DynmapPlayer dp = null;
            if (cm.sender != null)
                dp = plugin.getOrAddPlayer(cm.sender);
            else
                dp = new NeoForgePlayer(plugin, null);

            plugin.core.listenerManager.processChatEvent(DynmapListenerManager.EventType.PLAYER_CHAT, dp, cm.message);
        }
        // Check for generated chunks
        if ((cur_tick % 20) == 0) {
        }
    }

    @Override
    public boolean isModLoaded(String name) {
        return ModList.get().isLoaded(name);
    }

    @Override
    public String getModVersion(String name) {
        return ModList.get().getModContainerById(name)    // Try case sensitive lookup
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);
    }

    @Override
    public double getServerTPS() {
        return plugin.tps;
    }

    @Override
    public String getServerIP() {
        if (server.isSingleplayer())
            return "0.0.0.0";
        else
            return server.getLocalIp();
    }

    @Override
    public File getModContainerFile(String name) {
        try {
            // Try case sensitive lookup
            return ModList.get().getModFileById(name).getFile().getFilePath().toFile();
        } catch (Exception ex) {
            //TODO Implement proper jar in jar method for fetching data
            Log.warning("jar in jar method found, skipping: " + ex.getMessage());
        }
        return null;
    }

    @Override
    public List<String> getModList() {
        List<String> lst = new ArrayList<String>();
        for (var mi : ModList.get().getMods()) {
            lst.add(mi.getModId());
        }
        return lst;
    }

    @Override
    public Map<Integer, String> getBlockIDMap() {
        Map<Integer, String> map = new HashMap<Integer, String>();
        return map;
    }

    @Override
    public InputStream openResource(String modid, String rname) {
        if (modid == null) modid = "minecraft";

        if ("minecraft".equals(modid)) {
            return MinecraftServer.class.getClassLoader().getResourceAsStream(rname);
        } else {
            if (rname.startsWith("/") || rname.startsWith("\\")) {
                rname = rname.substring(1);
            }

            // Read the resource from the mod jar (the 26.x ModContainer no
            // longer exposes the mod instance/classloader the way the upstream
            // Forge build used, so go straight to the mod file).
            var mfi = ModList.get().getModFileById(modid);
            if (mfi == null) return null;
            try {
                File jar = mfi.getFile().getFilePath().toFile();
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
                    java.util.jar.JarEntry e = jf.getJarEntry(rname);
                    if (e == null) return null;
                    try (InputStream is = jf.getInputStream(e)) {
                        return new ByteArrayInputStream(is.readAllBytes());
                    }
                }
            } catch (IOException ex) {
                Log.severe("Failed to load resource of mod :" + modid, ex);
                return null;
            }
        }
    }

    /**
     * Get block unique ID map (module:blockid)
     */
    @Override
    public Map<String, Integer> getBlockUniqueIDMap() {
        HashMap<String, Integer> map = new HashMap<String, Integer>();
        return map;
    }

    /**
     * Get item unique ID map (module:itemid)
     */
    @Override
    public Map<String, Integer> getItemUniqueIDMap() {
        HashMap<String, Integer> map = new HashMap<String, Integer>();
        return map;
    }

}
