package com.moulberry.flashback.screen;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.SneakyThrow;
import com.moulberry.flashback.record.FlashbackMeta;
import net.minecraft.Util;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;

public final class ReplaySummaryLoader {

    private ReplaySummaryLoader() {
    }

    public static CompletableFuture<@Nullable ReplaySummary> loadAsync(Path zipPath,
            LinkedHashMap<String, LinkedHashSet<String>> currentNamespacesForRegistries) {
        return CompletableFuture.supplyAsync(() -> loadBlocking(zipPath, currentNamespacesForRegistries), Util.backgroundExecutor());
    }

    @Nullable
    public static ReplaySummary loadBlocking(Path zipPath,
            LinkedHashMap<String, LinkedHashSet<String>> currentNamespacesForRegistries) {
        try {
            if (zipPath.getFileName() == null) {
                return null;
            }
            String fileName = zipPath.getFileName().toString();

            BasicFileAttributeView attributeView = Files.getFileAttributeView(zipPath, BasicFileAttributeView.class);
            BasicFileAttributes basicFileAttributes = attributeView.readAttributes();

            long lastModified = Math.max(basicFileAttributes.creationTime().toMillis(), basicFileAttributes.lastModifiedTime().toMillis());
            long filesize = basicFileAttributes.size();

            byte[] iconBytes = null;
            String metadataString = null;

            try (FileSystem fs = FileSystems.newFileSystem(zipPath)) {
                Path iconPath = fs.getPath("/icon.png");
                if (Files.exists(iconPath)) {
                    iconBytes = Files.readAllBytes(iconPath);
                }

                Path metadataPath = fs.getPath("/metadata.json");
                if (Files.exists(metadataPath)) {
                    metadataString = Files.readString(metadataPath);
                }
            } catch (IOException e) {
                SneakyThrow.sneakyThrow(e);
            }

            if (metadataString == null) {
                return null;
            }

            JsonObject metadataJson = new Gson().fromJson(metadataString, JsonObject.class);
            FlashbackMeta metadata = FlashbackMeta.fromJson(metadataJson);
            if (metadata == null) {
                return null;
            }

            return new ReplaySummary(zipPath, metadata, currentNamespacesForRegistries, fileName, lastModified, filesize, iconBytes);
        } catch (IOException e) {
            Flashback.LOGGER.error("Failed to load replay summary for {}", zipPath, e);
            return null;
        }
    }
}
