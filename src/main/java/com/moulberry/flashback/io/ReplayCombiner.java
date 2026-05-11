package com.moulberry.flashback.io;

import com.google.gson.JsonObject;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FlashbackGson;
import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.action.ActionLevelChunkCached;
import com.moulberry.flashback.action.ActionRegistry;
import com.moulberry.flashback.playback.ReplayChunkCache;
import com.moulberry.flashback.record.FlashbackChunkMeta;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.ReplayMarker;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32C;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ReplayCombiner {

    /**
     * Backwards-compatible 2-file API. {@code registryAccess} is ignored —
     * the combiner no longer needs any registry data.
     */
    public static void combine(RegistryAccess registryAccess, String replayName, Path first, Path second, Path output) throws Exception {
        combine(replayName, List.of(first, second), output, false);
    }

    /**
     * Combine N replay zips that were produced by Server Replay mod's auto-split
     * (same world, same position, guaranteed continuous). Streams bytes directly
     * without decoding packets, so no {@link RegistryAccess} is required.
     *
     * @param dedupeChunkCaches if true, identical level-chunk packets across the
     *                          inputs share a single output entry (smaller output,
     *                          slightly more CPU/memory for hashing).
     */
    public static void combine(String replayName, List<Path> inputs, Path output, boolean dedupeChunkCaches) throws Exception {
        if (inputs == null || inputs.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 input replays to combine");
        }

        FlashbackMeta combinedMeta = null;
        Path iconSource = null;
        int tickOffset = 0;

        // Build state in-memory first, write once at the end (except .flashback chunks
        // and level_chunk_caches/* which we stream as we go).
        List<Int2IntMap> cacheRemapPerReplay = new ArrayList<>(inputs.size());
        List<List<String>> outputChunkNamesPerReplay = new ArrayList<>(inputs.size());

        try (FileOutputStream fos = new FileOutputStream(output.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zipOut = new ZipOutputStream(bos)) {
            zipOut.setLevel(Deflater.BEST_SPEED);

            CacheWriter cacheWriter = new CacheWriter(zipOut);
            // Only populated when dedupeChunkCaches is true.
            HashMap<Long, List<int[]>> seenHashToIndex = dedupeChunkCaches ? new HashMap<>() : null;
            // Buffer reused for hashing if dedup is on.
            CRC32C crc = dedupeChunkCaches ? new CRC32C() : null;

            int flashbackChunkSeq = 0;

            // ---- Pass 1: metadata + level_chunk_caches streaming ----
            for (int i = 0; i < inputs.size(); i++) {
                Path replayPath = inputs.get(i);
                try (FileSystem fs = FileSystems.newFileSystem(replayPath)) {
                    FlashbackMeta meta = readMetadata(fs);

                    if (i == 0) {
                        combinedMeta = baseMetaFrom(meta, replayName);
                        if (Files.exists(fs.getPath("/icon.png"))) {
                            // Read into memory here; the FileSystem closes at end of this try-block,
                            // so defer the actual write until after the loop iteration but copy bytes now.
                            // Simpler: just remember the input path and re-open later. Since FileSystem is
                            // per-iteration, store as a marker and re-open below.
                            iconSource = replayPath;
                        }
                    } else {
                        if (combinedMeta.dataVersion != 0 && meta.dataVersion != 0 && combinedMeta.dataVersion != meta.dataVersion) {
                            throw new RuntimeException("Replays were created on different versions of the game, unable to combine");
                        }
                        if (combinedMeta.protocolVersion != meta.protocolVersion) {
                            Flashback.LOGGER.warn("Combining replays with different protocolVersion ({} vs {}); proceeding because Server Replay splits are assumed compatible.",
                                combinedMeta.protocolVersion, meta.protocolVersion);
                        }
                    }

                    // markers shifted by accumulated ticks
                    for (Map.Entry<Integer, ReplayMarker> entry : meta.replayMarkers.entrySet()) {
                        combinedMeta.replayMarkers.put(entry.getKey() + tickOffset, entry.getValue());
                    }

                    // chunks renumber; mark forcePlaySnapshot on every secondary replay's first chunk
                    boolean isFirstChunkOfReplay = (i > 0);
                    List<String> outputNamesThisReplay = new ArrayList<>(meta.chunks.size());
                    for (Map.Entry<String, FlashbackChunkMeta> entry : meta.chunks.entrySet()) {
                        FlashbackChunkMeta chunkMeta = entry.getValue();
                        if (isFirstChunkOfReplay) {
                            chunkMeta.forcePlaySnapshot = true;
                            isFirstChunkOfReplay = false;
                        }
                        String newName = "c" + flashbackChunkSeq + ".flashback";
                        flashbackChunkSeq++;
                        combinedMeta.chunks.put(newName, chunkMeta);
                        outputNamesThisReplay.add(newName);
                    }
                    outputChunkNamesPerReplay.add(outputNamesThisReplay);

                    // level_chunk_caches: byte-stream copy with optional dedup
                    Int2IntMap remap = new Int2IntOpenHashMap();
                    remap.defaultReturnValue(-1);
                    readEachCachePacket(fs, (localIndex, packetBytes) -> {
                        int globalIndex;
                        if (dedupeChunkCaches) {
                            crc.reset();
                            crc.update(packetBytes, 0, packetBytes.length);
                            long h = crc.getValue();
                            List<int[]> bucket = seenHashToIndex.get(h);
                            int existing = -1;
                            if (bucket != null) {
                                // Linear scan within hash bucket to confirm true byte equality.
                                for (int[] cand : bucket) {
                                    if (cand[0] == packetBytes.length && cacheWriter.equalsAt(cand[1], packetBytes)) {
                                        existing = cand[1];
                                        break;
                                    }
                                }
                            }
                            if (existing >= 0) {
                                globalIndex = existing;
                            } else {
                                globalIndex = cacheWriter.appendAndReturnIndex(packetBytes);
                                seenHashToIndex.computeIfAbsent(h, k -> new ArrayList<>())
                                    .add(new int[] { packetBytes.length, globalIndex });
                            }
                        } else {
                            globalIndex = cacheWriter.appendAndReturnIndex(packetBytes);
                        }
                        remap.put(localIndex, globalIndex);
                    });
                    cacheRemapPerReplay.add(remap);

                    tickOffset += meta.totalTicks;
                }
            }

            cacheWriter.flushFinalBucket();

            combinedMeta.totalTicks = tickOffset;

            // ---- Write metadata.json ----
            ZipEntry zipEntry = new ZipEntry("metadata.json");
            zipOut.putNextEntry(zipEntry);
            zipOut.write(FlashbackGson.COMPRESSED.toJson(combinedMeta.toJson()).getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // ---- Write icon.png from first replay (if present) ----
            if (iconSource != null) {
                try (FileSystem fs = FileSystems.newFileSystem(iconSource)) {
                    Path iconPath = fs.getPath("/icon.png");
                    if (Files.exists(iconPath)) {
                        zipEntry = new ZipEntry("icon.png");
                        zipOut.putNextEntry(zipEntry);
                        Files.copy(iconPath, zipOut);
                        zipOut.closeEntry();
                    }
                }
            }

            // ---- Pass 2: stream .flashback chunks, rewriting ActionLevelChunkCached ids ----
            for (int i = 0; i < inputs.size(); i++) {
                Path replayPath = inputs.get(i);
                Int2IntMap remap = cacheRemapPerReplay.get(i);
                List<String> outputNames = outputChunkNamesPerReplay.get(i);
                boolean isIdentityRemap = !dedupeChunkCaches && i == 0 && isIdentityMap(remap);
                try (FileSystem fs = FileSystems.newFileSystem(replayPath)) {
                    FlashbackMeta meta = readMetadata(fs);
                    int chunkIdx = 0;
                    for (String oldName : meta.chunks.keySet()) {
                        String newName = outputNames.get(chunkIdx++);
                        Path src = fs.getPath("/" + oldName);
                        zipOut.putNextEntry(new ZipEntry(newName));
                        if (isIdentityRemap) {
                            Files.copy(src, zipOut);
                        } else {
                            rewriteFlashbackChunk(src, zipOut, remap);
                        }
                        zipOut.closeEntry();
                    }
                }
            }
        }
    }

    private static boolean isIdentityMap(Int2IntMap remap) {
        for (Int2IntMap.Entry e : remap.int2IntEntrySet()) {
            if (e.getIntKey() != e.getIntValue()) {
                return false;
            }
        }
        return true;
    }

    private static FlashbackMeta readMetadata(FileSystem fs) throws IOException {
        Path metadataPath = fs.getPath("/metadata.json");
        String metadataJson = Files.readString(metadataPath);
        FlashbackMeta meta = FlashbackMeta.fromJson(FlashbackGson.COMPRESSED.fromJson(metadataJson, JsonObject.class));
        if (meta == null) {
            throw new RuntimeException("Unable to load /metadata.json from " + fs);
        }
        return meta;
    }

    private static FlashbackMeta baseMetaFrom(FlashbackMeta source, String replayName) {
        FlashbackMeta meta = new FlashbackMeta();
        meta.replayIdentifier = UUID.randomUUID();
        meta.name = replayName;
        meta.versionString = source.versionString;
        meta.worldName = source.worldName;
        meta.bobbyWorldName = source.bobbyWorldName;
        meta.dataVersion = source.dataVersion;
        meta.protocolVersion = source.protocolVersion;
        meta.namespacesForRegistries = source.namespacesForRegistries;
        meta.distantHorizonPaths = new HashMap<>(source.distantHorizonPaths);
        meta.chunks = new LinkedHashMap<>();
        meta.totalTicks = 0;
        return meta;
    }

    @FunctionalInterface
    private interface CachePacketConsumer {
        void accept(int localIndex, byte[] packetBytes) throws IOException;
    }

    /**
     * Walks the level_chunk_cache (legacy single-file) and level_chunk_caches/0,1,2,...
     * entries, yielding each packet as raw bytes along with its original local index.
     */
    private static void readEachCachePacket(FileSystem fs, CachePacketConsumer consumer) throws IOException {
        int localIndex = 0;
        Path legacy = fs.getPath("/level_chunk_cache");
        if (Files.exists(legacy)) {
            localIndex = readPacketsFromFile(legacy, localIndex, consumer);
        }
        int bucket = 0;
        while (true) {
            Path path = fs.getPath("/level_chunk_caches/" + bucket);
            if (!Files.exists(path)) {
                break;
            }
            // Packets here logically start at bucket * CHUNK_CACHE_SIZE in the source replay.
            // We always feed `localIndex` matching the source-side global index so the remap is correct.
            int expectedStart = bucket * ReplayChunkCache.CHUNK_CACHE_SIZE;
            if (localIndex != expectedStart) {
                // Sparse bucket numbering shouldn't happen in practice, but if it does,
                // fast-forward so the remap keys still match in-stream packet ids.
                localIndex = expectedStart;
            }
            localIndex = readPacketsFromFile(path, localIndex, consumer);
            bucket++;
        }
    }

    private static int readPacketsFromFile(Path path, int startIndex, CachePacketConsumer consumer) throws IOException {
        int localIndex = startIndex;
        try (InputStream is = Files.newInputStream(path)) {
            while (true) {
                byte[] sizeBuffer = is.readNBytes(4);
                if (sizeBuffer.length == 0) {
                    break;
                }
                if (sizeBuffer.length < 4) {
                    Flashback.LOGGER.error("Truncated size header in {}", path);
                    break;
                }
                int size = (sizeBuffer[0] & 0xff) << 24
                    | (sizeBuffer[1] & 0xff) << 16
                    | (sizeBuffer[2] & 0xff) << 8
                    | sizeBuffer[3] & 0xff;
                byte[] packet = is.readNBytes(size);
                if (packet.length < size) {
                    Flashback.LOGGER.error("Ran out of bytes while reading {}, needed {} got {}", path, size, packet.length);
                    break;
                }
                consumer.accept(localIndex, packet);
                localIndex++;
            }
        }
        return localIndex;
    }

    /**
     * Bucket-aware writer that emits level_chunk_caches/&lt;bucket&gt; entries as
     * packets are appended. Also retains all written bytes so {@link #equalsAt}
     * can confirm hash matches when dedup is on.
     */
    private static final class CacheWriter {
        private final ZipOutputStream out;
        private int totalCount = 0;
        private int currentBucket = -1;
        private ByteBuf currentBucketBuf = null;
        // For dedup byte-equality verification: stores all packet payloads we've ever appended,
        // keyed by global index. Only populated when needed (we always populate, kept simple).
        private final List<byte[]> packetsByIndex = new ArrayList<>();

        CacheWriter(ZipOutputStream out) {
            this.out = out;
        }

        int appendAndReturnIndex(byte[] packet) throws IOException {
            int idx = totalCount;
            int bucket = idx / ReplayChunkCache.CHUNK_CACHE_SIZE;
            if (bucket != currentBucket) {
                if (currentBucketBuf != null) {
                    writeBucketToZip();
                }
                currentBucket = bucket;
                currentBucketBuf = Unpooled.buffer(Math.min(1 << 20, packet.length + 8));
            }
            currentBucketBuf.writeInt(packet.length);
            currentBucketBuf.writeBytes(packet);
            packetsByIndex.add(packet);
            totalCount++;
            return idx;
        }

        boolean equalsAt(int globalIndex, byte[] candidate) {
            if (globalIndex < 0 || globalIndex >= packetsByIndex.size()) {
                return false;
            }
            byte[] stored = packetsByIndex.get(globalIndex);
            if (stored.length != candidate.length) {
                return false;
            }
            for (int i = 0; i < stored.length; i++) {
                if (stored[i] != candidate[i]) {
                    return false;
                }
            }
            return true;
        }

        void flushFinalBucket() throws IOException {
            if (currentBucketBuf != null) {
                writeBucketToZip();
                currentBucketBuf = null;
            }
        }

        private void writeBucketToZip() throws IOException {
            int len = currentBucketBuf.writerIndex();
            byte[] bytes = new byte[len];
            currentBucketBuf.getBytes(0, bytes);
            out.putNextEntry(new ZipEntry("level_chunk_caches/" + currentBucket));
            out.write(bytes);
            out.closeEntry();
        }
    }

    /**
     * Re-emits a .flashback chunk file, rewriting only the cached-chunk id inside any
     * {@link ActionLevelChunkCached} action via {@code remap}. Other bytes are passed
     * through. If the chunk uses no ActionLevelChunkCached action, the file is copied
     * wholesale.
     */
    private static void rewriteFlashbackChunk(Path source, ZipOutputStream zipOut, Int2IntMap remap) throws IOException {
        byte[] replayChunk = Files.readAllBytes(source);
        FriendlyByteBuf inputBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(replayChunk));
        FriendlyByteBuf outputBuf = new FriendlyByteBuf(Unpooled.buffer(replayChunk.length));

        int magic = inputBuf.readInt();
        if (magic != Flashback.MAGIC) {
            throw new RuntimeException("Invalid magic in " + source);
        }
        outputBuf.writeInt(magic);

        int levelChunkCachedActionId = -1;
        int actionCount = inputBuf.readVarInt();
        outputBuf.writeVarInt(actionCount);
        for (int i = 0; i < actionCount; i++) {
            ResourceLocation actionName = inputBuf.readResourceLocation();
            outputBuf.writeResourceLocation(actionName);
            Action action = ActionRegistry.getAction(actionName);
            if (action instanceof ActionLevelChunkCached) {
                levelChunkCachedActionId = i;
            }
        }

        if (levelChunkCachedActionId == -1) {
            // No cached-chunk references — pass the rest through unchanged.
            zipOut.write(replayChunk);
            return;
        }

        int snapshotSize = inputBuf.readInt();
        if (snapshotSize < 0) {
            throw new RuntimeException("Invalid snapshot size: " + snapshotSize + " (0x" + Integer.toHexString(snapshotSize) + ")");
        }
        int snapshotInputEnd = inputBuf.readerIndex() + snapshotSize;

        int snapshotOutputSizeIndex = outputBuf.writerIndex();
        outputBuf.writeInt(0xDEADBEEF);
        int snapshotStartWriterIndex = outputBuf.writerIndex();
        int snapshotEndWriterIndex = snapshotStartWriterIndex;

        while (inputBuf.readerIndex() < inputBuf.writerIndex()) {
            boolean inSnapshot = inputBuf.readerIndex() < snapshotInputEnd;

            int id = inputBuf.readVarInt();
            int size = inputBuf.readInt();
            if (id == levelChunkCachedActionId) {
                int cachedChunkId = inputBuf.readVarInt();
                int newCachedChunkId = remap.get(cachedChunkId);
                if (newCachedChunkId < 0) {
                    throw new RuntimeException("Missing cached chunk id " + cachedChunkId + " in remap for " + source);
                }
                // ActionLevelChunkCached payload is exactly one varint, so the action's
                // declared size should equal the bytes used by that varint. Recompute.
                outputBuf.writeVarInt(id);
                int sizeWriterIndex = outputBuf.writerIndex();
                outputBuf.writeInt(0);
                int cachedIdWriterIndex = outputBuf.writerIndex();
                outputBuf.writeVarInt(newCachedChunkId);
                int endWriterIndex = outputBuf.writerIndex();
                outputBuf.writerIndex(sizeWriterIndex);
                outputBuf.writeInt(endWriterIndex - cachedIdWriterIndex);
                outputBuf.writerIndex(endWriterIndex);

                int leftover = size - varIntLength(cachedChunkId);
                if (leftover > 0) {
                    inputBuf.skipBytes(leftover);
                }
            } else {
                outputBuf.writeVarInt(id);
                outputBuf.writeInt(size);
                outputBuf.writeBytes(inputBuf, size);
            }

            if (inSnapshot) {
                snapshotEndWriterIndex = outputBuf.writerIndex();
            }
        }

        int endWriterIndex = outputBuf.writerIndex();
        outputBuf.writerIndex(snapshotOutputSizeIndex);
        outputBuf.writeInt(snapshotEndWriterIndex - snapshotStartWriterIndex);
        outputBuf.writerIndex(endWriterIndex);

        byte[] bytes = new byte[outputBuf.writerIndex()];
        outputBuf.getBytes(0, bytes);
        zipOut.write(bytes);
    }

    private static int varIntLength(int value) {
        int len = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            len++;
        }
        return len;
    }
}
