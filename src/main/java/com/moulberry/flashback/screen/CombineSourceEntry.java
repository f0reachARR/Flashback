package com.moulberry.flashback.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class CombineSourceEntry extends ObjectSelectionList.Entry<CombineSourceEntry> implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());
    private static final int ICON_X_OFFSET = 22;
    private static final int ICON_SIZE = 32;

    private final Minecraft minecraft;
    private final CombineSourceList list;
    private final Path path;
    private final String fileName;
    private int displayIndex;

    private final CompletableFuture<@Nullable ReplaySummary> summaryFuture;
    @Nullable
    private ReplaySummary summary;
    private boolean loaded;
    private FaviconTexture icon;

    public CombineSourceEntry(CombineSourceList list, Minecraft minecraft, Path path, int displayIndex,
            LinkedHashMap<String, LinkedHashSet<String>> currentNamespacesForRegistries) {
        this.list = list;
        this.minecraft = minecraft;
        this.path = path;
        this.displayIndex = displayIndex;

        Path pathFileName = path.getFileName();
        this.fileName = pathFileName == null ? path.toString() : pathFileName.toString();

        this.icon = FaviconTexture.forWorld(minecraft.getTextureManager(), "combine-source-" + System.identityHashCode(this));
        this.icon.clear();

        this.summaryFuture = ReplaySummaryLoader.loadAsync(path, currentNamespacesForRegistries);
    }

    public Path getPath() {
        return this.path;
    }

    public void setDisplayIndex(int displayIndex) {
        this.displayIndex = displayIndex;
    }

    private void pollSummary() {
        if (this.loaded) {
            return;
        }
        try {
            if (!this.summaryFuture.isDone()) {
                return;
            }
            this.summary = this.summaryFuture.getNow(null);
        } catch (CancellationException | CompletionException ignored) {
            this.summary = null;
        }
        this.loaded = true;
        if (this.summary != null) {
            this.loadIcon();
        }
    }

    private void loadIcon() {
        byte[] iconBytes = this.summary == null ? null : this.summary.getIconBytes();
        if (iconBytes != null) {
            try {
                this.icon.upload(NativeImage.read(iconBytes));
                return;
            } catch (Throwable t) {
                LOGGER.error("Invalid icon for replay {}", this.path, t);
            }
        }
        this.icon.clear();
    }

    @Override
    public Component getNarration() {
        if (this.summary != null) {
            return Component.translatable("narrator.select", Component.literal(this.summary.getReplayName()));
        }
        return Component.translatable("narrator.select", Component.literal(this.fileName));
    }

    @Override
    public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
        this.pollSummary();

        int x = this.getContentX();
        int y = this.getContentY();
        int height = this.getContentHeight();

        String indexLabel = this.displayIndex + ".";
        int indexY = y + (height - this.minecraft.font.lineHeight) / 2;
        guiGraphics.drawString(this.minecraft.font, indexLabel, x, indexY, 0xFFFFFFFF, false);

        int iconX = x + ICON_X_OFFSET;
        int textX = iconX + ICON_SIZE + 3;

        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, this.icon.textureLocation(), iconX, y, 0.0f, 0.0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

        if (!this.loaded) {
            int loadingY = y + (height - this.minecraft.font.lineHeight) / 2;
            guiGraphics.drawString(this.minecraft.font, Component.translatable("flashback.select_replay.loading_replays"), textX, loadingY, 0xFFAAAAAA, false);
            return;
        }

        if (this.summary == null) {
            int titleColour = 0xFFFF5555;
            guiGraphics.drawString(this.minecraft.font, this.fileName, textX, y + 1, titleColour, false);
            guiGraphics.drawString(this.minecraft.font, Component.translatable("flashback.combine_replay.missing_metadata"), textX,
                y + this.minecraft.font.lineHeight + 3, 0xFF808080, false);
            return;
        }

        String title = this.summary.getReplayName();
        if (StringUtils.isEmpty(title)) {
            title = this.fileName;
        }

        int titleColour = 0xFFFFFFFF;
        if (!this.summary.canOpen()) {
            titleColour = 0xFFFF5555;
        } else if (this.summary.hasWarning()) {
            titleColour = 0xFFFFAA55;
        }

        guiGraphics.drawString(this.minecraft.font, title, textX, y + 1, titleColour, false);
        int titleEnd = textX + this.minecraft.font.width(title);

        String worldName = this.summary.getWorldName();
        if (worldName != null) {
            guiGraphics.drawString(this.minecraft.font, "(" + worldName + ")", titleEnd + 4, y + 1, 0xFF808080, false);
        }

        String fileAndTime = this.fileName;
        long lastModified = this.summary.getLastModified();
        if (lastModified != -1L) {
            fileAndTime = fileAndTime + " (" + DATE_FORMAT.format(Instant.ofEpochMilli(lastModified)) + ")";
        }
        guiGraphics.drawString(this.minecraft.font, fileAndTime, textX, y + this.minecraft.font.lineHeight + 3, 0xFF808080, false);
        guiGraphics.drawString(this.minecraft.font, this.summary.getInfo(), textX, y + this.minecraft.font.lineHeight + this.minecraft.font.lineHeight + 3, 0xFF808080, false);

        if (hovered && this.summary.getHoverInfo() != null) {
            guiGraphics.setTooltipForNextFrame(this.minecraft.font, this.minecraft.font.split(this.summary.getHoverInfo(), 240), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClicked) {
        this.list.setSelected(this);
        return true;
    }

    @Override
    public void close() {
        if (this.icon != null) {
            this.icon.close();
            this.icon = null;
        }
    }
}
