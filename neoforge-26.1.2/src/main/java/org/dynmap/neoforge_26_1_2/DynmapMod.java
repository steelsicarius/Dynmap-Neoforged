package org.dynmap.neoforge_26_1_2;

import java.io.File;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.dynmap.Log;

/**
 * NeoForge port of the upstream Fabric entry point (org.dynmap.fabric_1_21_11.DynmapMod).
 */
@Mod("dynmap")
public class DynmapMod {
    // The instance of your mod that NeoForge uses.
    public static DynmapMod instance;

    public static DynmapPlugin plugin;
    public static File jarfile;
    public static String ver;
    public static boolean useforcedchunks;

    public DynmapMod(IEventBus modBus, ModContainer container) {
        instance = this;

        try {
            java.nio.file.Path p = container.getModInfo().getOwningFile().getFile().getFilePath();
            File f = p.toFile();
            if (f.isFile() && f.getName().endsWith(".jar")) {
                jarfile = f;
            } else {
                // In a ModDevGradle dev run the mod "file" is the exploded
                // classes directory, but DynmapCore reads its bundled
                // resources (extracted/web, colorschemes, renderdata...) from
                // the plugin jar as a zip - so point it at the built fat jar
                // (requires a prior './gradlew build'). The jar lands in the
                // repo root target/ dir (../target from this module); also
                // probe the module-local build/libs for older layouts.
                File projectDir = null;
                for (File dir = f; dir != null; dir = dir.getParentFile()) {
                    if (new File(dir, "build").isDirectory() && new File(dir, "src").isDirectory()) {
                        projectDir = dir;
                        break;
                    }
                }
                File[] searchDirs = (projectDir != null)
                        ? new File[]{ new File(projectDir.getParentFile(), "target"),
                                       new File(projectDir, "build/libs") }
                        : new File[]{};
                File found = null;
                for (File dir : searchDirs) {
                    File[] jars = dir.isDirectory()
                            ? dir.listFiles((d, name) -> name.startsWith("Dynmap-") && name.endsWith(".jar"))
                            : null;
                    if (jars != null && jars.length > 0) {
                        found = jars[0];
                        break;
                    }
                }
                if (found != null) {
                    jarfile = found;
                } else {
                    Log.warning("Dynmap mod is not running from a jar and no built Dynmap jar was found in "
                            + String.join(", ", java.util.Arrays.stream(searchDirs).map(File::getPath).toArray(String[]::new))
                            + " - run 'gradlew build' to enable bundled resource extraction");
                }
            }
        } catch (Exception e) {
            Log.severe("Unable to get Dynmap mod jar path", e);
        }

        ver = container.getModInfo().getVersion().toString();

        Log.setLogger(new NeoForgeLogger());
        org.dynmap.modsupport.ModSupportImpl.init();

        // Initialize the plugin; it will be fully enabled when the server starts.
        plugin = new DynmapPlugin();

        // Game bus events (the upstream Fabric module registered equivalents
        // through its own event indirections and mixins).
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::onServerAboutToStart);
        gameBus.addListener(this::onRegisterCommands);
        gameBus.addListener(this::onServerStopping);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        // The upstream Fabric build splits this into SERVER_STARTING (core
        // configuration: onEnable) and a loadWorld-HEAD hook (core enable:
        // onStart + core.serverStarted). Both run before any world is created
        // on Fabric, so on NeoForge they collapse into ServerAboutToStartEvent,
        // which fires before DedicatedServer#loadLevel().
        plugin.serverStart(server);
        plugin.serverStarted(server);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        plugin.registerCommands(event.getDispatcher());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        plugin.serverStop(event.getServer());
    }
}
