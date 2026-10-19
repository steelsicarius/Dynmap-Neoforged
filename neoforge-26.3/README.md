# Dynmap (NeoForge, Minecraft 26.3)

Port of [Dynmap](https://github.com/webbukkit/dynmap) (v3.0 branch, `3.9-SNAPSHOT`) to
**NeoForge 26.3.0.69-beta** for Minecraft 26.3 (Java 25).

- Port base: the upstream `fabric-1.21.11` platform module (Yarn mappings), converted to
  Mojang mappings and NeoForge APIs.
- Mojang-mapped API reference: the upstream `forge-1.21.11` platform module of the same repo.
- Shared core (`DynmapCore`/`DynmapCoreAPI`) is compiled **in place** from the dynmap repo
  this module lives in (`..` is the repo root, like every other platform module folder),
  so the core stays byte-identical across platforms.

## Build

```bash
# Integrated (from the dynmap repo root; forks this module's own Gradle 9 wrapper
# with a Java 25 toolchain):
./neoforge-build.sh neoforge-26.3
# Standalone (from this folder, with its own Gradle 9 wrapper):
JAVA_HOME=/usr/java/jdk-25-oracle-aarch64 ./gradlew build
```

Output: `../target/Dynmap-3.9-SNAPSHOT-neoforge-26.3.jar` — a self-contained fat jar
with DynmapCore + its third-party libraries shaded and relocated (`org.dynmap.jetty`,
`org.dynmap.snakeyaml`, `org.dynmap.json.simple`, `org.dynmap.org.postgresql`,
`org.dynmap.s3lite`, `org.dynmap.javax.servlet`, `org.dynmap.org.owasp.html`),
mirroring the upstream `DynmapCore` shadow jar relocations. Guava is **not** bundled
(Minecraft ships its own copy, as in the upstream builds).


## Runtime validation (dev server)

Verified with `runServer` on port 25599: mod + both mixins apply cleanly; DynmapCore
enables; Jetty serves on `0.0.0.0:8123` (`/`, `/up/configuration`, `/up/world/world/0`);
the `chunkgenerate` trigger queues the freshly generated spawn chunks (108 pending tiles);
flat + HD maps render them to `web/tiles/world/...` (202 tiles, 198 unique contents) and the
tiles are served over HTTP.

## Porting notes (Fabric → NeoForge)

- **Mod id** stays `dynmap`; entry point `@Mod("dynmap")` (`DynmapMod`), game-bus listeners
  registered on `NeoForge.EVENT_BUS`.
- **Server lifecycle**: Fabric's `SERVER_STARTING` (core configuration) and its
  `loadWorld`-HEAD mixin (`SERVER_STARTED_PRE_WORLD_LOAD`: core enable + `serverStarted()`)
  both happen before worlds load on NeoForge through a single `ServerAboutToStartEvent`
  (fired immediately before `DedicatedServer#loadLevel()`), invoked in the same order.
  Shutdown uses `ServerStoppingEvent`.
- **Commands**: Fabric `CommandRegistrationCallback` → `RegisterCommandsEvent`.
- **Players**: Fabric mixins (`PlayerManager`, `ServerPlayerEntity`) → NeoForge
  `PlayerLoggedInEvent` / `PlayerLoggedOutEvent` / `PlayerRespawnEvent` / `PlayerChangedDimensionEvent`.
- **Chat**: Fabric mixin on `handleDecoratedMessage` → NeoForge `ServerChatEvent` (`getRawText()`).
- **Chunk generation**: Fabric mixin on `ChunkGenerating` + `ProtoChunk#touchedByWorldGen`
  tracking → NeoForge `ChunkEvent.Load` with `isNewChunk()` (server side only), which fires
  at the same point for freshly generated chunks. The two upstream mixins are not needed.
- **Worlds load/unload**: Fabric `ServerWorldEvents` → NeoForge `LevelEvent.Load` / `Unload`
  (guarded to `ServerLevel`).
- **Ticks**: Fabric `ServerTickEvents.END_SERVER_TICK` → `ServerTickEvent.Post`.
- **Block changes**: kept as a mixin (`LevelChunkMixin` on `LevelChunk#setBlockState`),
  because NeoForge has no generic block-change event; fired through
  `event/DynmapEvents` (replaces the Fabric `EventFactory` indirection).
- **Sign changes**: kept as a mixin (`ServerGamePacketListenerImplMixin`), replicating the
  vanilla `updateSignText` body with the raw lines routed through the callback first.
- **Biome colors**: no access widener / accessor mixin needed — `BiomeSpecialEffects` is a
  record in 26.3 with public accessors (`waterColor()`, `foliageColorOverride()`,
  `grassColorOverride()`, `grassColorModifier()`).
- **Access Transformer**: `ChunkMap#readChunk(ChunkPos)` made public (same purpose as the
  upstream forge module's AT), used by the renderer to read chunks that are not loaded.
- **Permissions**: `luckperms` detected via `ModList.get().isLoaded("luckperms")` using the
  LuckPerms API directly (same as upstream); otherwise file/ops based. The fabric-only
  `fabric-permissions-api` adapter has no NeoForge counterpart (NeoForge's own permission
  API is node-registration based) and is dropped.

## Deltas vs the neoforge-26.1.2 module

- `LevelChunkSection.SECTION_HEIGHT` was removed in 26.2; the section height is now read
  from `SectionPos.SECTION_SIZE` (same constant lives in `net.minecraft.core.SectionPos`
  in all three versions).
- 26.3 changed the sign-update flow: `ServerboundSignUpdatePacket` is now
  `record(BlockPos pos, List<String> lines, SignTextSlot slot)` — the old
  `getPos()`/`isFrontText()` became record accessors and the front/back flag became the
  `SignTextSlot` enum (`FRONT`/`BACK`); `FilteredText` moved from
  `net.minecraft.network.chat` to `net.minecraft.server.network`; and
  `SignBlockEntity.updateSignText` now takes the slot instead of a boolean. The mixin
  replicates the same vanilla body with the new shapes.

## Deviations from upstream packaging

- The `io.github.linktosriram.s3lite:0.0.2-SNAPSHOT` artifacts used by the `aws_s3`
  storage backend are no longer resolvable anywhere (OSSRH snapshot sunset, project gone
  from GitHub). The five small jars in `libs/` were rebuilt from the original MIT-licensed
  source (see `libs/s3lite-LICENSE`) with the same `0.0.2-SNAPSHOT` coordinates, and are
  shaded+relocated like upstream.
- `jakarta.xml.bind`/`jaxb-impl` were upstream `DynmapCore` shadow-jar dependencies but are
  not referenced by any DynmapCore source; they are not bundled in this port.

### Never bundle libraries the game already ships

Transitives of the shaded set must not leak into the mod jar. The first production test
(2026-10-14) crashed every dedicated server at `Bootstrap.bootStrap()` with
`NoSuchMethodError: ImmutableMap$Builder.buildOrThrow()` — owasp-java-html-sanitizer
pulled an old Guava into the fat jar, and the game's `BlockStateProperties` resolved that
bundled copy instead of Minecraft's own Guava 33.5 (the infamous `Couldn't find Minecraft
server thread` `FatalStartupException` was only the symptom; the real error was swallowed
by `Main.main`'s `catch (Throwable)`). The `shadowJar` block now excludes
`com/google/common`, `com/google/thirdparty`, `com/google/errorprone`, `org/checkerframework`
and `org/hamcrest` (plus `META-INF/maven`) by real package path — mirroring upstream's
DynmapCore include-allowlist and the fabric module's guava filter.

## Origin checkout

`../dynmap` is the dynmap repo root (the upstream v3.0 checkout) (group `us.dynmap`,
version `3.9-SNAPSHOT`). Only `DynmapCore/` and `DynmapCoreAPI/` sources/resources are
consumed from it.
