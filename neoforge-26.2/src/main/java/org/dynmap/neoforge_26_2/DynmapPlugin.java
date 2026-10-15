package org.dynmap.neoforge_26_2;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.IdMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.tags.BlockTags;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.dynmap.*;
import org.dynmap.common.BiomeMap;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.common.DynmapListenerManager;
import org.dynmap.common.DynmapPlayer;
import org.dynmap.common.chunk.GenericChunkCache;
import org.dynmap.neoforge_26_2.command.DmapCommand;
import org.dynmap.neoforge_26_2.command.DmarkerCommand;
import org.dynmap.neoforge_26_2.command.DynmapCommand;
import org.dynmap.neoforge_26_2.command.DynmapExpCommand;
import org.dynmap.neoforge_26_2.event.DynmapEvents;
import org.dynmap.neoforge_26_2.permissions.*;
import org.dynmap.permissions.PermissionsHandler;
import org.dynmap.renderer.DynmapBlockState;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

public class DynmapPlugin {
    // FIXME: Fix package-private fields after splitting is done
    DynmapCore core;
    private PermissionProvider permissions;
    private boolean core_enabled;
    public GenericChunkCache sscache;
    public PlayerList playerList;
    MapManager mapManager;
    /**
     * Server is set when running and unset at shutdown.
     */
    private MinecraftServer server;
    public static DynmapPlugin plugin;
    ChatHandler chathandler;
    private HashMap<String, Integer> sortWeights = new HashMap<String, Integer>();
    private HashMap<String, NeoForgeWorld> worlds = new HashMap<String, NeoForgeWorld>();
    private Level last_world;
    private NeoForgeWorld last_fworld;
    private Map<String, NeoForgePlayer> players = new HashMap<String, NeoForgePlayer>();
    private NeoForgeServer fserver;
    private boolean tickregistered = false;
    // TPS calculator
    double tps;
    long lasttick;
    long avgticklen;
    // Per tick limit, in nsec
    long perTickLimit = (50000000); // 50 ms
    private boolean useSaveFolder = true;

    private static final String[] TRIGGER_DEFAULTS = {"blockupdate", "chunkpopulate", "chunkgenerate"};

    static final Pattern patternControlCode = Pattern.compile("(?i)\\u00A7[0-9A-FK-OR]");

    DynmapPlugin() {
        plugin = this;
        // NeoForge game bus events are wired by DynmapMod and this class; they
        // persist between server instances, exactly like the Fabric events did.
    }

    int getSortWeight(String name) {
        return sortWeights.getOrDefault(name, 0);
    }

    void setSortWeight(String name, int wt) {
        sortWeights.put(name, wt);
    }

    void dropSortWeight(String name) {
        sortWeights.remove(name);
    }

    public static class BlockUpdateRec {
        Level w;
        String wid;
        int x, y, z;
    }

    ConcurrentLinkedQueue<BlockUpdateRec> blockupdatequeue = new ConcurrentLinkedQueue<BlockUpdateRec>();

    public static DynmapBlockState[] stateByID;

    /**
     * Initialize block states (org.dynmap.renderer.DynmapBlockState)
     */
    public void initializeBlockStates() {
        stateByID = new DynmapBlockState[512 * 32];    // Simple map - scale as needed
        Arrays.fill(stateByID, DynmapBlockState.AIR); // Default to air

        IdMapper<BlockState> bsids = Block.BLOCK_STATE_REGISTRY;

        DynmapBlockState basebs = null;
        Block baseb = null;
        int baseidx = 0;

        Iterator<BlockState> iter = bsids.iterator();
        DynmapBlockState.Builder bld = new DynmapBlockState.Builder();
        while (iter.hasNext()) {
            BlockState bs = iter.next();
            int idx = bsids.getId(bs);
            if (idx >= stateByID.length) {
                int plen = stateByID.length;
                stateByID = Arrays.copyOf(stateByID, idx * 11 / 10); // grow array by 10%
                Arrays.fill(stateByID, plen, stateByID.length, DynmapBlockState.AIR);
            }
            Block b = bs.getBlock();
            // If this is new block vs last, it's the base block state
            if (b != baseb) {
                basebs = null;
                baseidx = idx;
                baseb = b;
            }

            Identifier ui = BuiltInRegistries.BLOCK.getKey(b);
            if (ui == null) {
                continue;
            }
            String bn = ui.getNamespace() + ":" + ui.getPath();
            // Only do defined names, and not "air"
            if (!bn.equals(DynmapBlockState.AIR_BLOCK)) {
                String statename = "";
                for (Property<?> p : bs.getProperties()) {
                    if (statename.length() > 0) {
                        statename += ",";
                    }
                    statename += p.getName() + "=" + bs.getValue(p).toString();
                }
                int lightAtten = 15;
                try {    // Workaround for mods with broken block state logic...
                    lightAtten = bs.isSolidRender() ? 15 : (bs.propagatesSkylightDown() ? 0 : 1);
                } catch (Exception x) {
                    Log.warning(String.format("Exception while checking lighting data for block state: %s[%s]", bn, statename));
                    Log.verboseinfo("Exception: " + x.toString());
                }
                // Fill in base attributes
                bld.setBaseState(basebs).setStateIndex(idx - baseidx).setBlockName(bn).setStateName(statename).setLegacyBlockID(idx).setAttenuatesLight(lightAtten);
                if (bs.getSoundType() != null) { bld.setMaterial(bs.getSoundType().toString()); }
                if (bs.isSolid()) { bld.setSolid(); }
                if (bs.isAir()) { bld.setAir(); }
                if (bs.is(BlockTags.LOGS)) { bld.setLog(); }
                if (bs.is(BlockTags.LEAVES)) { bld.setLeaves(); }
                if ((!bs.getFluidState().isEmpty()) && !(bs.getBlock() instanceof LiquidBlock)) {
                    bld.setWaterlogged();
                }
                DynmapBlockState dbs = bld.build(); // Build state
                stateByID[idx] = dbs;
                if (basebs == null) { basebs = dbs; }
            }
        }
    }

    public static final Item getItemByID(int id) {
        return Item.byId(id);
    }

    NeoForgePlayer getOrAddPlayer(ServerPlayer player) {
        String name = player.getName().getString();
        NeoForgePlayer fp = players.get(name);
        if (fp != null) {
            fp.player = player;
        } else {
            fp = new NeoForgePlayer(this, player);
            players.put(name, fp);
        }
        return fp;
    }

    static class ChatMessage {
        String message;
        ServerPlayer sender;
    }

    ConcurrentLinkedQueue<ChatMessage> msgqueue = new ConcurrentLinkedQueue<ChatMessage>();

    public static class ChatHandler {
        private final DynmapPlugin plugin;

        ChatHandler(DynmapPlugin plugin) {
            this.plugin = plugin;
        }

        public void handleChat(ServerPlayer player, String message) {
            if (!message.startsWith("/")) {
                ChatMessage cm = new ChatMessage();
                cm.message = message;
                cm.sender = player;
                plugin.msgqueue.add(cm);
            }
        }
    }

    public NeoForgeServer getNeoForgeServer() {
        return fserver;
    }

    void serverStart(MinecraftServer server) {
        // Set the server so we don't NPE during setup (upstream: SERVER_STARTING)
        this.server = server;
        this.fserver = new NeoForgeServer(this, server);
        this.onEnable();
    }

    void serverStarted(MinecraftServer server) {
        // Upstream ran this from a loadWorld-HEAD mixin, i.e. before worlds load
        this.onStart();
        if (core != null) {
            core.serverStarted();
        }
    }

    void serverStop(MinecraftServer server) {
        this.onDisable();
        this.server = null;
    }

    public boolean isOp(String player) {
        String[] ops = server.getPlayerList().getOpNames();

        for (String op : ops) {
            if (op.equalsIgnoreCase(player)) {
                return true;
            }
        }

        // Integrated server host (singleplayer owner) is treated as op
        ServerPlayer sp = server.getPlayerList().getPlayerByName(player);
        return server.isSingleplayer() && (sp != null) && server.isSingleplayerOwner(sp.nameAndId());
    }

    boolean hasPerm(Player psender, String permission) {
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if ((ph != null) && (psender != null) && ph.hasPermission(psender.getName().getString(), permission)) {
            return true;
        }
        return permissions.has(psender, permission);
    }

    boolean hasPermNode(Player psender, String permission) {
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if ((ph != null) && (psender != null) && ph.hasPermissionNode(psender.getName().getString(), permission)) {
            return true;
        }
        return permissions.hasPermissionNode(psender, permission);
    }

    Set<String> hasOfflinePermissions(String player, Set<String> perms) {
        Set<String> rslt = null;
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if (ph != null) {
            rslt = ph.hasOfflinePermissions(player, perms);
        }
        Set<String> rslt2 = hasOfflinePermissions(player, perms);
        if ((rslt != null) && (rslt2 != null)) {
            Set<String> newrslt = new HashSet<String>(rslt);
            newrslt.addAll(rslt2);
            rslt = newrslt;
        } else if (rslt2 != null) {
            rslt = rslt2;
        }
        return rslt;
    }

    boolean hasOfflinePermission(String player, String perm) {
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if (ph != null) {
            if (ph.hasOfflinePermission(player, perm)) {
                return true;
            }
        }
        return permissions.hasOfflinePermission(player, perm);
    }

    void setChatHandler(ChatHandler chatHandler) {
        plugin.chathandler = chatHandler;
    }

    public class TexturesPayload {
        public long timestamp;
        public String profileId;
        public String profileName;
        public boolean isPublic;
        public Map<String, ProfileTexture> textures;

    }

    public class ProfileTexture {
        public String url;
    }

    public void loadExtraBiomes(String mcver) {
        int cnt = 0;
        BiomeMap.loadWellKnownByVersion(mcver);

        net.minecraft.core.Registry<Biome> biomeRegistry = getNeoForgeServer().getBiomeRegistry();
        Biome[] list = getNeoForgeServer().getBiomeList(biomeRegistry);

        for (int i = 0; i < list.length; i++) {
            Biome bb = list[i];
            if (bb != null) {
                String id = biomeRegistry.getKey(bb).getPath();
                String rl = biomeRegistry.getKey(bb).toString();
                float tmp = bb.getBaseTemperature(), hum = bb.getModifiedClimateSettings().downfall();
                int watermult = bb.getWaterColor();
                Log.verboseinfo("biome[" + i + "]: hum=" + hum + ", tmp=" + tmp + ", mult=" + Integer.toHexString(watermult));

                BiomeMap bmap = BiomeMap.NULL;
                if (rl != null) {    // If resource location, lookup by this
                    bmap = BiomeMap.byBiomeResourceLocation(rl);
                } else {
                    bmap = BiomeMap.byBiomeID(i);
                }
                if (bmap.isDefault() || (bmap == BiomeMap.NULL)) {
                    bmap = new BiomeMap((rl != null) ? BiomeMap.NO_INDEX : i, id, tmp, hum, rl);
                    Log.verboseinfo("Add custom biome [" + bmap.toString() + "] (" + i + ")");
                    cnt++;
                } else {
                    bmap.setTemperature(tmp);
                    bmap.setRainfall(hum);
                }
                if (watermult != -1) {
                    bmap.setWaterColorMultiplier(watermult);
                    Log.verboseinfo("Set watercolormult for " + bmap.toString() + " (" + i + ") to " + Integer.toHexString(watermult));
                }
                bmap.setBiomeObject(bb);
            }
        }
        if (cnt > 0)
            Log.info("Added " + cnt + " custom biome mappings");
    }

    private String[] getBiomeNames() {
        net.minecraft.core.Registry<Biome> biomeRegistry = getNeoForgeServer().getBiomeRegistry();
        Biome[] list = getNeoForgeServer().getBiomeList(biomeRegistry);
        String[] lst = new String[list.length];
        for (int i = 0; i < list.length; i++) {
            Biome bb = list[i];
            if (bb != null) {
                lst[i] = biomeRegistry.getKey(bb).getPath();
            }
        }
        return lst;
    }

    public void onEnable() {
        /* Get MC version */
        String mcver = server.getServerVersion();

        /* Load extra biomes */
        loadExtraBiomes(mcver);
        /* Set up player login/quit event handler */
        registerPlayerLoginListener();

        /* Initialize permissions handler */
        if (ModList.get().isLoaded("luckperms")) {
            Log.info("Using luckperms for access control");
            permissions = new LuckPermissions();
        } else {
            /* Initialize permissions handler */
            permissions = FilePermissions.create();
            if (permissions == null) {
                permissions = new OpPermissions(new String[]{"webchat", "marker.icons", "marker.list", "webregister", "stats", "hide.self", "show.self"});
            }
        }
        /* Get and initialize data folder */
        File dataDirectory = new File("dynmap");

        if (!dataDirectory.exists()) {
            dataDirectory.mkdirs();
        }

        /* Instantiate core */
        if (core == null) {
            core = new DynmapCore();
        }

        /* Inject dependencies */
        core.setPluginJarFile(DynmapMod.jarfile);
        core.setPluginVersion(DynmapMod.ver);
        core.setMinecraftVersion(mcver);
        core.setDataFolder(dataDirectory);
        core.setServer(fserver);
        core.setTriggerDefault(TRIGGER_DEFAULTS);
        core.setBiomeNames(getBiomeNames());

        if (!core.initConfiguration(null)) {
            return;
        }
        // Extract default permission example, if needed
        File filepermexample = new File(core.getDataFolder(), "permissions.yml.example");
        core.createDefaultFileFromResource("/permissions.yml.example", filepermexample);

        DynmapCommonAPIListener.apiInitialized(core);
    }

    private DynmapCommand dynmapCmd;
    private DmapCommand dmapCmd;
    private DmarkerCommand dmarkerCmd;
    private DynmapExpCommand dynmapexpCmd;

    public void registerCommands(CommandDispatcher<CommandSourceStack> cd) {
        dynmapCmd = new DynmapCommand(this);
        dmapCmd = new DmapCommand(this);
        dmarkerCmd = new DmarkerCommand(this);
        dynmapexpCmd = new DynmapExpCommand(this);
        dynmapCmd.register(cd);
        dmapCmd.register(cd);
        dmarkerCmd.register(cd);
        dynmapexpCmd.register(cd);

        Log.info("Register commands");
    }

    public void onStart() {
        initializeBlockStates();
        /* Enable core */
        if (!core.enableCore(null)) {
            return;
        }
        core_enabled = true;
        VersionCheck.runCheck(core);
        // Get per tick time limit
        perTickLimit = core.getMaxTickUseMS() * 1000000;
        // Prep TPS
        lasttick = System.nanoTime();
        tps = 20.0;

        /* Register tick handler (upstream: ServerTickEvents.END_SERVER_TICK) */
        if (!tickregistered) {
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> fserver.tickEvent(event.getServer()));
            tickregistered = true;
        }

        playerList = core.playerList;
        sscache = new GenericChunkCache(core.getSnapShotCacheSize(), core.useSoftRefInSnapShotCache());
        /* Get map manager from core */
        mapManager = core.getMapManager();

        /* Load saved world definitions */
        loadWorlds();

        for (NeoForgeWorld w : worlds.values()) {
            if (core.processWorldLoad(w)) {   /* Have core process load first - fire event listeners if good load after */
                if (w.isLoaded()) {
                    core.listenerManager.processWorldEvent(DynmapListenerManager.EventType.WORLD_LOAD, w);
                }
            }
        }
        core.updateConfigHashcode();

        /* Register our update trigger events */
        registerEvents();
        Log.info("Register events");

        Log.info("Enabled");
    }

    public void onDisable() {
        DynmapCommonAPIListener.apiTerminated();

        /* Save worlds */
        saveWorlds();

        /* Purge tick queue */
        fserver.clearTaskQueue();

        /* Disable core */
        core.disableCore();
        core_enabled = false;

        if (sscache != null) {
            sscache.cleanup();
            sscache = null;
        }

        Log.info("Disabled");
    }

    // TODO: Clean a bit
    public void handleCommand(CommandSourceStack commandSource, String cmd, String[] args) throws CommandSyntaxException {
        DynmapCommandSender dsender;
        ServerPlayer psender = null;

        // getPlayerOrException throws a CommandSyntaxException, so getEntity and instanceof for safety
        if (commandSource.getEntity() instanceof ServerPlayer) {
            psender = commandSource.getPlayerOrException();
        }

        if (psender != null) {
            // FIXME: New Player? Why not query the current player list.
            dsender = new NeoForgePlayer(this, psender);
        } else {
            dsender = new NeoForgeCommandSender(commandSource);
        }

        core.processCommand(dsender, cmd, cmd, args);
    }

    public class PlayerTracker {
        public void onPlayerLogin(ServerPlayer player) {
            if (!core_enabled) return;
            final DynmapPlayer dp = getOrAddPlayer(player);
            /* This event can be called from off server thread, so push processing there */
            core.getServer().scheduleServerTask(new Runnable() {
                public void run() {
                    core.listenerManager.processPlayerEvent(DynmapListenerManager.EventType.PLAYER_JOIN, dp);
                }
            }, 2);
        }

        public void onPlayerLogout(ServerPlayer player) {
            if (!core_enabled) return;
            final DynmapPlayer dp = getOrAddPlayer(player);
            final String name = player.getName().getString();
            /* This event can be called from off server thread, so push processing there */
            core.getServer().scheduleServerTask(new Runnable() {
                public void run() {
                    core.listenerManager.processPlayerEvent(DynmapListenerManager.EventType.PLAYER_QUIT, dp);
                    players.remove(name);
                }
            }, 0);
        }

        public void onPlayerChangedDimension(ServerPlayer player) {
            if (!core_enabled) return;
            getOrAddPlayer(player);    // Freshen player object reference
        }

        public void onPlayerRespawn(ServerPlayer player) {
            if (!core_enabled) return;
            getOrAddPlayer(player);    // Freshen player object reference
        }
    }

    private PlayerTracker playerTracker = null;

    private void registerPlayerLoginListener() {
        if (playerTracker == null) {
            playerTracker = new PlayerTracker();
            NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> playerTracker.onPlayerLogin((ServerPlayer) event.getEntity()));
            NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> playerTracker.onPlayerLogout((ServerPlayer) event.getEntity()));
            NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> playerTracker.onPlayerChangedDimension((ServerPlayer) event.getEntity()));
            NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> playerTracker.onPlayerRespawn((ServerPlayer) event.getEntity()));
        }
    }

    public class WorldTracker {
        public void handleWorldLoad(ServerLevel world) {
            if (!core_enabled) return;

            final NeoForgeWorld fw = getWorld(world);
            // This event can be called from off server thread, so push processing there
            core.getServer().scheduleServerTask(new Runnable() {
                public void run() {
                    if (core.processWorldLoad(fw))    // Have core process load first - fire event listeners if good load after
                        core.listenerManager.processWorldEvent(DynmapListenerManager.EventType.WORLD_LOAD, fw);
                }
            }, 0);
        }

        public void handleWorldUnload(ServerLevel world) {
            if (!core_enabled) return;

            final NeoForgeWorld fw = getWorld(world);
            if (fw != null) {
                // This event can be called from off server thread, so push processing there
                core.getServer().scheduleServerTask(new Runnable() {
                    public void run() {
                        core.listenerManager.processWorldEvent(DynmapListenerManager.EventType.WORLD_UNLOAD, fw);
                        core.processWorldUnload(fw);
                    }
                }, 0);
                // Set world unloaded (needs to be immediate, since it may be invalid after event)
                fw.setWorldUnloaded();
            }
        }

        public void handleChunkGenerate(ServerLevel world, LevelChunk chunk) {
            if (!onchunkgenerate) return;

            NeoForgeWorld fw = getWorld(world, false);
            ChunkPos chunkPos = chunk.getPos();

            int ymax = Integer.MIN_VALUE;
            int ymin = Integer.MAX_VALUE;
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                if ((sections[i] != null) && (!sections[i].hasOnlyAir())) {
                    int sy = chunk.getMinY() + i * SectionPos.SECTION_SIZE;
                    if (sy < ymin) ymin = sy;
                    if ((sy + 16) > ymax) ymax = sy + 16;
                }
            }
            if (ymax != Integer.MIN_VALUE) {
                mapManager.touchVolume(fw.getName(),
                        chunkPos.getMinBlockX(), ymin, chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX(), ymax, chunkPos.getMaxBlockZ(),
                        "chunkgenerate");
            }
        }

        public void handleBlockEvent(Level world, BlockPos pos) {
            if (!core_enabled) return;
            if (!onblockchange) return;
            if (!(world instanceof ServerLevel)) return;

            BlockUpdateRec r = new BlockUpdateRec();
            r.w = world;
            NeoForgeWorld fw = getWorld(world, false);
            if (fw == null) return;
            r.wid = fw.getName();
            r.x = pos.getX();
            r.y = pos.getY();
            r.z = pos.getZ();
            blockupdatequeue.add(r);
        }
    }

    private WorldTracker worldTracker = null;
    private boolean onblockchange = false;
    private boolean onchunkpopulate = false;
    private boolean onchunkgenerate = false;
    boolean onblockchange_with_id = false;

    private void registerEvents() {
        // To trigger rendering.
        onblockchange = core.isTrigger("blockupdate");
        onchunkpopulate = core.isTrigger("chunkpopulate");
        onchunkgenerate = core.isTrigger("chunkgenerate");
        onblockchange_with_id = core.isTrigger("blockupdate-with-id");
        if (onblockchange_with_id)
            onblockchange = true;
        if (worldTracker == null)
            worldTracker = new WorldTracker();
        if (onchunkpopulate || onchunkgenerate) {
            // Upstream hooked ChunkGenerating#generate (via mixin) and gated on
            // the chunk having had blocks written by worldgen; ChunkEvent.Load(isNewChunk)
            // fires at the same point for freshly generated chunks (server side).
            NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
                if (event.isNewChunk() && (event.getLevel() instanceof ServerLevel level) && !level.isClientSide()) {
                    worldTracker.handleChunkGenerate(level, event.getChunk());
                }
            });
        }
        if (onblockchange) {
            DynmapEvents.registerBlockChange((world, pos) -> worldTracker.handleBlockEvent(world, pos));
        }

        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level)
                worldTracker.handleWorldLoad(level);
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level)
                worldTracker.handleWorldUnload(level);
        });
    }

    NeoForgeWorld getWorldByName(String name) {
        return worlds.get(name);
    }

    NeoForgeWorld getWorld(Level w) {
        return getWorld(w, true);
    }

    private NeoForgeWorld getWorld(Level w, boolean add_if_not_found) {
        if (last_world == w) {
            return last_fworld;
        }
        String wname = NeoForgeWorld.getWorldName(this, w);

        for (NeoForgeWorld fw : worlds.values()) {
            if (fw.getRawName().equals(wname)) {
                last_world = w;
                last_fworld = fw;
                if (!fw.isLoaded()) {
                    fw.setWorldLoaded(w);
                }
                fw.updateWorld(w);
                return fw;
            }
        }
        NeoForgeWorld fw = null;
        if (add_if_not_found) {
            /* Add to list if not found */
            fw = new NeoForgeWorld(this, w);
            worlds.put(fw.getName(), fw);
        }
        last_world = w;
        last_fworld = fw;
        return fw;
    }

    private void saveWorlds() {
        File f = new File(core.getDataFolder(), NeoForgeWorld.SAVED_WORLDS_FILE);
        ConfigurationNode cn = new ConfigurationNode(f);
        ArrayList<HashMap<String, Object>> lst = new ArrayList<HashMap<String, Object>>();
        for (DynmapWorld fw : core.mapManager.getWorlds()) {
            HashMap<String, Object> vals = new HashMap<String, Object>();
            vals.put("name", fw.getRawName());
            vals.put("height", fw.worldheight);
            vals.put("miny", fw.minY);
            vals.put("sealevel", fw.sealevel);
            vals.put("nether", fw.isNether());
            vals.put("the_end", ((NeoForgeWorld) fw).isTheEnd());
            vals.put("title", fw.getTitle());
            lst.add(vals);
        }
        cn.put("worlds", lst);
        cn.put("useSaveFolderAsName", useSaveFolder);
        cn.put("maxWorldHeight", NeoForgeWorld.getMaxWorldHeight());

        cn.save();
    }

    private void loadWorlds() {
        File f = new File(core.getDataFolder(), NeoForgeWorld.SAVED_WORLDS_FILE);
        if (f.canRead() == false) {
            useSaveFolder = true;
            return;
        }
        ConfigurationNode cn = new ConfigurationNode(f);
        cn.load();
        // If defined, use maxWorldHeight
        NeoForgeWorld.setMaxWorldHeight(cn.getInteger("maxWorldHeight", 256));

        // If setting defined, use it
        if (cn.containsKey("useSaveFolderAsName")) {
            useSaveFolder = cn.getBoolean("useSaveFolderAsName", useSaveFolder);
        }
        List<Map<String, Object>> lst = cn.getMapList("worlds");
        if (lst == null) {
            Log.warning(String.format("Discarding bad %s", NeoForgeWorld.SAVED_WORLDS_FILE));
            return;
        }

        for (Map<String, Object> world : lst) {
            try {
                String name = (String) world.get("name");
                int height = (Integer) world.get("height");
                Integer miny = (Integer) world.get("miny");
                int sealevel = (Integer) world.get("sealevel");
                boolean nether = (Boolean) world.get("nether");
                boolean theend = (Boolean) world.get("the_end");
                String title = (String) world.get("title");
                if (name != null) {
                    NeoForgeWorld fw = new NeoForgeWorld(this, name, height, sealevel, nether, theend, title, (miny != null) ? miny : 0);
                    fw.setWorldUnloaded();
                    core.processWorldLoad(fw);
                    worlds.put(fw.getName(), fw);
                }
            } catch (Exception x) {
                Log.warning(String.format("Unable to load saved worlds from %s", NeoForgeWorld.SAVED_WORLDS_FILE));
                return;
            }
        }
    }
}
