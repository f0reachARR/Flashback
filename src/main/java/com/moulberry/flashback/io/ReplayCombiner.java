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
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import java.nio.file.StandardCopyOption;
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
     * Minimum Jaccard similarity between the (chunkX, chunkZ) sets of two
     * adjacent input replays for them to be considered the same "recorded
     * chunk area" and therefore safe to combine into a single output zip.
     * Below this threshold the output is split at the boundary.
     */
    private static final double SAME_RANGE_JACCARD = 0.90;

    /**
     * Backwards-compatible 2-file API. {@code registryAccess} is ignored —
     * the combiner no longer needs any registry data. Throws if the inputs
     * disagree on chunk range and would have been split into multiple outputs;
     * use {@link #combine(String, List, Path, boolean)} to receive all paths.
     */
    @Deprecated
    public static void combine(RegistryAccess registryAccess, String replayName, Path first, Path second, Path output) throws Exception {
        List<Path> written = combine(replayName, List.of(first, second), output, false);
        if (written.size() > 1) {
            throw new IllegalStateException("Inputs have different chunk ranges; combine produced " + written.size() + " files. Use the List<Path> overload to receive split outputs.");
        }
    }

    /**
     * Combine N replay zips that were produced by Server Replay mod's auto-split
     * (same world, same position, guaranteed continuous). Streams bytes directly
     * without decoding packets, so no {@link RegistryAccess} is required.
     *
     * <p>If the recorded chunk area changes between consecutive inputs, the
     * output is split into multiple files (otherwise the combined playback
     * would silently freeze / desync). With a single output the original
     * {@code output} path is used; with N outputs the files are named
     * {@code <stem>_part1.zip}, {@code <stem>_part2.zip}, ...
     *
     * @param dedupeChunkCaches if true, identical level-chunk packets across the
     *                          inputs share a single output entry (smaller output,
     *                          slightly more CPU/memory for hashing).
     * @return the paths actually written, in order.
     */
    public static List<Path> combine(String replayName, List<Path> inputs, Path output, boolean dedupeChunkCaches) throws Exception {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("Need at least 1 input replay to combine");
        }

        // Pre-pass: compute the chunk-area signature of every input so we can
        // decide where to split before we start writing.
        List<LongSet> signatures = new ArrayList<>(inputs.size());
        for (Path input : inputs) {
            signatures.add(computeChunkSignature(input));
        }
        List<List<Path>> runs = groupByCompatibility(inputs, signatures);

        List<Path> written = new ArrayList<>(runs.size());
        for (int i = 0; i < runs.size(); i++) {
            Path runOutput = derivePartPath(output, i, runs.size());
            combineGroup(replayName, runs.get(i), runOutput, dedupeChunkCaches);
            written.add(runOutput);
        }

        if (runs.size() > 1) {
            Flashback.LOGGER.info("Split combiner output into {} parts due to chunk-area changes between inputs", runs.size());
        }
        return written;
    }

    private static void combineGroup(String replayName, List<Path> inputs, Path output, boolean dedupeChunkCaches) throws Exception {
        if (inputs.size() == 1) {
            // A single-input run is just a renamed copy. No remap needed.
            Files.copy(inputs.get(0), output, StandardCopyOption.REPLACE_EXISTING);
            return;
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

            CacheWriter cacheWriter = new CacheWriter(zipOut, dedupeChunkCaches);
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

    /**
     * Scan the level_chunk_caches of an input replay and collect the set of
     * (chunkX, chunkZ) positions, packed as {@code ((long)x << 32) | (z & 0xffffffffL)}.
     * Returns an empty set if the input has no cached chunks (e.g. very short replay);
     * see {@link #jaccard} which treats an empty signature as a wildcard.
     */
    private static LongSet computeChunkSignature(Path replayPath) throws IOException {
        LongOpenHashSet sig = new LongOpenHashSet();
        try (FileSystem fs = FileSystems.newFileSystem(replayPath)) {
            // We only need the first ~13 bytes (varint packet id up to 5 bytes + 2x int32 chunk coords).
            readEachCachePacketHeader(fs, 13, (localIndex, headerBytes, headerLen) -> {
                long packed = readChunkXZFromCachePacket(headerBytes, headerLen);
                if (packed != Long.MIN_VALUE) {
                    sig.add(packed);
                }
            });
        }
        return sig;
    }

    /**
     * Parse the (chunkX, chunkZ) header of a level_chunk_caches packet.
     * Format: varint packetId, int32 chunkX, int32 chunkZ, ... (see
     * {@code ClientboundLevelChunkWithLightPacket}). Returns {@link Long#MIN_VALUE}
     * if the packet is malformed / too short.
     */
    private static long readChunkXZFromCachePacket(byte[] packet, int len) {
        int p = 0;
        // Skip the varint packet id (max 5 bytes for a 32-bit varint).
        for (int i = 0; i < 5; i++) {
            if (p >= len) {
                return Long.MIN_VALUE;
            }
            byte b = packet[p++];
            if ((b & 0x80) == 0) {
                break;
            }
        }
        if (p + 8 > len) {
            return Long.MIN_VALUE;
        }
        int chunkX = (packet[p] & 0xff) << 24
            | (packet[p + 1] & 0xff) << 16
            | (packet[p + 2] & 0xff) << 8
            | packet[p + 3] & 0xff;
        int chunkZ = (packet[p + 4] & 0xff) << 24
            | (packet[p + 5] & 0xff) << 16
            | (packet[p + 6] & 0xff) << 8
            | packet[p + 7] & 0xff;
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    /**
     * Walk the inputs in order and split them into maximal consecutive sub-lists
     * whose adjacent chunk-area signatures meet {@link #SAME_RANGE_JACCARD}.
     */
    private static List<List<Path>> groupByCompatibility(List<Path> inputs, List<LongSet> signatures) {
        List<List<Path>> runs = new ArrayList<>();
        List<Path> current = new ArrayList<>();
        current.add(inputs.get(0));
        LongSet currentSig = signatures.get(0);
        for (int i = 1; i < inputs.size(); i++) {
            LongSet sig = signatures.get(i);
            double similarity = jaccard(currentSig, sig);
            if (similarity >= SAME_RANGE_JACCARD) {
                current.add(inputs.get(i));
                // Keep the first non-empty signature as the run's reference so that
                // an empty input mid-run doesn't reset the comparison baseline.
                if (currentSig.isEmpty() && !sig.isEmpty()) {
                    currentSig = sig;
                }
            } else {
                runs.add(current);
                current = new ArrayList<>();
                current.add(inputs.get(i));
                currentSig = sig;
            }
        }
        runs.add(current);
        return runs;
    }

    /**
     * Jaccard similarity |A∩B| / |A∪B|. Empty signature on either side is
     * treated as a wildcard (returns 1.0), so a tiny input with no cached
     * chunks doesn't force a spurious split.
     */
    private static double jaccard(LongSet a, LongSet b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 1.0;
        }
        LongSet smaller = a.size() <= b.size() ? a : b;
        LongSet larger = smaller == a ? b : a;
        int intersection = 0;
        LongIterator it = smaller.iterator();
        while (it.hasNext()) {
            if (larger.contains(it.nextLong())) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 1.0 : (double) intersection / (double) union;
    }

    /**
     * For a single-run combine, return the original output path unchanged.
     * For multi-run combines, append {@code _partN} before the {@code .zip}
     * extension (case-insensitive). If the path has no .zip suffix the
     * suffix is appended verbatim.
     */
    private static Path derivePartPath(Path output, int runIndex, int totalRuns) {
        if (totalRuns <= 1) {
            return output;
        }
        Path parent = output.getParent();
        String name = output.getFileName().toString();
        String stem;
        String ext;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.substring(dot).equalsIgnoreCase(".zip")) {
            stem = name.substring(0, dot);
            ext = name.substring(dot);
        } else {
            stem = name;
            ext = "";
        }
        String partName = stem + "_part" + (runIndex + 1) + ext;
        return parent == null ? Path.of(partName) : parent.resolve(partName);
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

    @FunctionalInterface
    private interface CachePacketHeaderConsumer {
        /** {@code headerBytes} contains at least the first {@code headerLen} bytes of the packet. */
        void accept(int localIndex, byte[] headerBytes, int headerLen) throws IOException;
    }

    /**
     * Walks the level_chunk_cache (legacy single-file) and level_chunk_caches/0,1,2,...
     * entries, yielding each packet as raw bytes along with its original local index.
     */
    private static void readEachCachePacket(FileSystem fs, CachePacketConsumer consumer) throws IOException {
        readEachCachePacket0(fs, consumer, null, 0);
    }

    /**
     * Walks the cache entries but only reads up to {@code headerLen} bytes of each
     * packet (the rest is skipped via {@link InputStream#skip}). Used by the
     * signature pre-pass so we don't materialize full chunk bytes just to read
     * the (chunkX, chunkZ) header.
     */
    private static void readEachCachePacketHeader(FileSystem fs, int headerLen, CachePacketHeaderConsumer consumer) throws IOException {
        readEachCachePacket0(fs, null, consumer, headerLen);
    }

    private static void readEachCachePacket0(FileSystem fs, CachePacketConsumer full, CachePacketHeaderConsumer header, int headerLen) throws IOException {
        int localIndex = 0;
        Path legacy = fs.getPath("/level_chunk_cache");
        if (Files.exists(legacy)) {
            localIndex = readPacketsFromFile(legacy, localIndex, full, header, headerLen);
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
            localIndex = readPacketsFromFile(path, localIndex, full, header, headerLen);
            bucket++;
        }
    }

    private static int readPacketsFromFile(Path path, int startIndex, CachePacketConsumer full, CachePacketHeaderConsumer header, int headerLen) throws IOException {
        int localIndex = startIndex;
        try (InputStream is = Files.newInputStream(path)) {
            byte[] headerBuf = header != null ? new byte[headerLen] : null;
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
                if (full != null) {
                    byte[] packet = is.readNBytes(size);
                    if (packet.length < size) {
                        Flashback.LOGGER.error("Ran out of bytes while reading {}, needed {} got {}", path, size, packet.length);
                        break;
                    }
                    full.accept(localIndex, packet);
                } else {
                    int toRead = Math.min(headerLen, size);
                    int got = is.readNBytes(headerBuf, 0, toRead);
                    if (got < toRead) {
                        Flashback.LOGGER.error("Ran out of bytes while reading header in {}, needed {} got {}", path, toRead, got);
                        break;
                    }
                    int remaining = size - toRead;
                    while (remaining > 0) {
                        long skipped = is.skip(remaining);
                        if (skipped <= 0) {
                            // Fallback for streams that don't honor skip; read & discard.
                            if (is.read() < 0) {
                                Flashback.LOGGER.error("Truncated payload while skipping in {}", path);
                                return localIndex;
                            }
                            remaining--;
                        } else {
                            remaining -= (int) skipped;
                        }
                    }
                    header.accept(localIndex, headerBuf, got);
                }
                localIndex++;
            }
        }
        return localIndex;
    }

    /**
     * Bucket-aware writer that streams level_chunk_caches/&lt;bucket&gt; entries
     * directly to the ZipOutputStream as packets are appended (no per-bucket
     * heap buffer). Also retains packet bytes so {@link #equalsAt} can confirm
     * hash matches when dedup is on.
     */
    private static final class CacheWriter {
        private final ZipOutputStream out;
        private final boolean retainForDedup;
        private final byte[] sizeHeader = new byte[4];
        private int totalCount = 0;
        private int currentBucket = -1;
        private boolean entryOpen = false;
        // For dedup byte-equality verification: stores all packet payloads we've ever appended,
        // keyed by global index. Only populated when dedup is on — otherwise the heap retention
        // (every chunk packet kept until the combine finishes) blows up on long replays.
        private final List<byte[]> packetsByIndex;

        CacheWriter(ZipOutputStream out, boolean retainForDedup) {
            this.out = out;
            this.retainForDedup = retainForDedup;
            this.packetsByIndex = retainForDedup ? new ArrayList<>() : null;
        }

        int appendAndReturnIndex(byte[] packet) throws IOException {
            int idx = totalCount;
            int bucket = idx / ReplayChunkCache.CHUNK_CACHE_SIZE;
            if (bucket != currentBucket) {
                if (entryOpen) {
                    out.closeEntry();
                    entryOpen = false;
                }
                currentBucket = bucket;
                out.putNextEntry(new ZipEntry("level_chunk_caches/" + currentBucket));
                entryOpen = true;
            }
            int len = packet.length;
            sizeHeader[0] = (byte) (len >>> 24);
            sizeHeader[1] = (byte) (len >>> 16);
            sizeHeader[2] = (byte) (len >>> 8);
            sizeHeader[3] = (byte) len;
            out.write(sizeHeader);
            out.write(packet);
            if (retainForDedup) {
                packetsByIndex.add(packet);
            }
            totalCount++;
            return idx;
        }

        boolean equalsAt(int globalIndex, byte[] candidate) {
            if (!retainForDedup || globalIndex < 0 || globalIndex >= packetsByIndex.size()) {
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
            if (entryOpen) {
                out.closeEntry();
                entryOpen = false;
            }
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
