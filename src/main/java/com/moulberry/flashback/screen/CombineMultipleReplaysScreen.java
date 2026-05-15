package com.moulberry.flashback.screen;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.AsyncFileDialogs;
import com.moulberry.flashback.io.ReplayCombiner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CombineMultipleReplaysScreen extends Screen {

    private static final int TOTAL_WIDTH = 300;
    private static final int VISIBLE_SOURCES = 6;

    @Nullable
    private final Screen lastScreen;

    private String newReplayName = "Combined Replay";
    private final List<Path> sources = new ArrayList<>();
    private Path output;
    private boolean dedupeChunkCaches = false;
    private int sourceScrollOffset = 0;

    public CombineMultipleReplaysScreen(@Nullable Screen lastScreen) {
        this(lastScreen, null);
    }

    public CombineMultipleReplaysScreen(@Nullable Screen lastScreen, @Nullable Path firstSource) {
        super(Component.translatable("flashback.combine_replay.multiple"));
        this.lastScreen = lastScreen;
        if (firstSource != null) {
            this.sources.add(firstSource);
        }
    }

    @Override
    protected void setInitialFocus() {
    }

    @Override
    protected void init() {
        super.init();

        clampScrollOffset();

        GridLayout gridLayout = new GridLayout();
        gridLayout.defaultCellSetting().padding(4, 4, 4, 0);
        GridLayout.RowHelper rowHelper = gridLayout.createRowHelper(6);

        rowHelper.addChild(new StringWidget(TOTAL_WIDTH, 20, Component.translatable("flashback.combine_replay.multiple"), this.font), 6);

        rowHelper.addChild(new BottomTextWidget(TOTAL_WIDTH, 10, Component.translatable("flashback.combine_replay.new_replay_name"), this.font).alignLeft(), 6);

        EditBox replayNameEditBox = new EditBox(this.font, 0, 0, TOTAL_WIDTH, 20, Component.literal(this.newReplayName));
        replayNameEditBox.setMaxLength(128);
        replayNameEditBox.setValue(this.newReplayName);
        replayNameEditBox.setResponder(s -> this.newReplayName = s);
        rowHelper.addChild(replayNameEditBox, 6);

        int total = this.sources.size();
        Component sourcesLabel;
        if (total <= VISIBLE_SOURCES) {
            sourcesLabel = Component.translatable("flashback.combine_replay.sources");
        } else {
            int from = this.sourceScrollOffset + 1;
            int to = Math.min(this.sourceScrollOffset + VISIBLE_SOURCES, total);
            sourcesLabel = Component.translatable("flashback.combine_replay.sources_window", from, to, total);
        }
        rowHelper.addChild(new BottomTextWidget(TOTAL_WIDTH, 10, sourcesLabel, this.font).alignLeft(), 6);

        Path replayFolder = Flashback.getReplayFolder();

        int windowStart = this.sourceScrollOffset;
        int windowEnd = Math.min(windowStart + VISIBLE_SOURCES, total);

        for (int i = windowStart; i < windowEnd; i++) {
            final int index = i;
            Path src = this.sources.get(i);

            rowHelper.addChild(new StringWidget(20, 20, Component.literal(Integer.toString(i + 1)), this.font), 1);

            String label = src.getFileName() == null ? src.toString() : src.getFileName().toString();
            rowHelper.addChild(Button.builder(Component.literal(label), b -> {
                CompletableFuture<String> future = AsyncFileDialogs.openFileDialog(replayFolder.toString(), "Replay Archive", "zip");
                future.thenAccept(pathStr -> {
                    if (pathStr != null) {
                        this.sources.set(index, Path.of(pathStr));
                        this.rebuild();
                    }
                });
            }).width(150).build(), 3);

            Button upButton = Button.builder(Component.literal("▲"), b -> {
                if (index > 0) {
                    Path tmp = this.sources.get(index - 1);
                    this.sources.set(index - 1, this.sources.get(index));
                    this.sources.set(index, tmp);
                    this.rebuild();
                }
            }).width(20).build();
            upButton.active = (i > 0);
            rowHelper.addChild(upButton, 1);

            Button downButton = Button.builder(Component.literal("▼"), b -> {
                if (index < this.sources.size() - 1) {
                    Path tmp = this.sources.get(index + 1);
                    this.sources.set(index + 1, this.sources.get(index));
                    this.sources.set(index, tmp);
                    this.rebuild();
                }
            }).width(20).build();
            downButton.active = (i < this.sources.size() - 1);
            rowHelper.addChild(downButton, 1);

            Button removeButton = Button.builder(Component.literal("×"), b -> {
                this.sources.remove(index);
                this.rebuild();
            }).width(20).build();
            rowHelper.addChild(removeButton, 1);
        }

        // Pad empty source slots so the layout below stays put when the list is short.
        int emptySlots = VISIBLE_SOURCES - (windowEnd - windowStart);
        for (int i = 0; i < emptySlots; i++) {
            rowHelper.addChild(new StringWidget(TOTAL_WIDTH, 20, Component.literal(""), this.font), 6);
        }

        rowHelper.addChild(Button.builder(Component.translatable("flashback.combine_replay.add_source"), b -> {
            CompletableFuture<String> future = AsyncFileDialogs.openFileDialog(replayFolder.toString(), "Replay Archive", "zip");
            future.thenAccept(pathStr -> {
                if (pathStr != null) {
                    this.sources.add(Path.of(pathStr));
                    this.sourceScrollOffset = Math.max(0, this.sources.size() - VISIBLE_SOURCES);
                    this.rebuild();
                }
            });
        }).width(145).build(), 3);
        rowHelper.addChild(Button.builder(Component.translatable("flashback.combine_replay.add_directory"), b -> {
            CompletableFuture<String> future = AsyncFileDialogs.openFolderDialog(replayFolder.toString());
            future.thenAccept(pathStr -> {
                if (pathStr != null) {
                    this.addZipsFromDirectory(Path.of(pathStr));
                }
            });
        }).width(145).build(), 3);

        Button clearAll = Button.builder(Component.translatable("flashback.combine_replay.clear_all"), b -> {
            this.sources.clear();
            this.sourceScrollOffset = 0;
            this.rebuild();
        }).width(TOTAL_WIDTH).build();
        clearAll.active = !this.sources.isEmpty();
        rowHelper.addChild(clearAll, 6);

        rowHelper.addChild(new BottomTextWidget(TOTAL_WIDTH, 10, Component.literal(""), this.font), 6);

        Checkbox dedupeCheckbox = Checkbox.builder(Component.translatable("flashback.combine_replay.dedupe_chunk_caches"), this.font)
            .selected(this.dedupeChunkCaches)
            .onValueChange((c, value) -> this.dedupeChunkCaches = value)
            .build();
        rowHelper.addChild(dedupeCheckbox, 6);

        rowHelper.addChild(new BottomTextWidget(TOTAL_WIDTH, 10, Component.translatable("flashback.combine_replay.output"), this.font).alignLeft(), 6);

        String outputLabel = this.output == null ? "" : this.output.toString();
        Button outputButton = Button.builder(Component.literal(outputLabel), b -> {
            CompletableFuture<String> future = AsyncFileDialogs.saveFileDialog(replayFolder.toString(), "combined.zip", "Replay Archive", "zip");
            future.thenAccept(pathStr -> {
                if (pathStr != null) {
                    this.output = Path.of(pathStr);
                    this.rebuild();
                }
            });
        }).width(TOTAL_WIDTH).build();
        rowHelper.addChild(outputButton, 6);

        rowHelper.addChild(new BottomTextWidget(TOTAL_WIDTH, 10, Component.literal(""), this.font), 6);

        Button combineButton = Button.builder(Component.translatable("flashback.combine_replay.do_combine"), b -> this.runCombine())
            .width(145).build();
        combineButton.active = this.sources.size() >= 2 && this.output != null;
        rowHelper.addChild(combineButton, 3);
        rowHelper.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> Minecraft.getInstance().setScreen(this.lastScreen))
            .width(145).build(), 3);

        gridLayout.arrangeElements();
        FrameLayout.alignInRectangle(gridLayout, 0, 0, this.width, this.height, 0.5f, 0.5f);
        gridLayout.visitWidgets(this::addRenderableWidget);

        this.setInitialFocus(replayNameEditBox);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.sources.size() > VISIBLE_SOURCES && scrollY != 0) {
            int delta = scrollY > 0 ? -1 : 1;
            int newOffset = Mth.clamp(this.sourceScrollOffset + delta, 0, this.sources.size() - VISIBLE_SOURCES);
            if (newOffset != this.sourceScrollOffset) {
                this.sourceScrollOffset = newOffset;
                this.rebuild();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void clampScrollOffset() {
        int max = Math.max(0, this.sources.size() - VISIBLE_SOURCES);
        if (this.sourceScrollOffset > max) {
            this.sourceScrollOffset = max;
        }
        if (this.sourceScrollOffset < 0) {
            this.sourceScrollOffset = 0;
        }
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private void addZipsFromDirectory(Path directory) {
        List<Path> zips;
        try (Stream<Path> stream = Files.list(directory)) {
            zips = stream
                .filter(p -> {
                    String name = p.getFileName() == null ? "" : p.getFileName().toString();
                    return name.toLowerCase().endsWith(".zip") && !Files.isDirectory(p);
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .collect(Collectors.toList());
        } catch (IOException e) {
            Flashback.LOGGER.error("Failed to list directory {}", directory, e);
            Minecraft.getInstance().setScreen(new AlertScreen(
                () -> Minecraft.getInstance().setScreen(this),
                Component.translatable("flashback.combine_replay.error"),
                Component.literal(e.getMessage() == null ? e.toString() : e.getMessage())));
            return;
        }
        if (zips.isEmpty()) {
            Minecraft.getInstance().setScreen(new AlertScreen(
                () -> Minecraft.getInstance().setScreen(this),
                Component.translatable("flashback.combine_replay.error"),
                Component.translatable("flashback.combine_replay.no_zips_in_directory")));
            return;
        }
        this.sources.addAll(zips);
        this.sourceScrollOffset = Math.max(0, this.sources.size() - VISIBLE_SOURCES);
        this.rebuild();
    }

    private void runCombine() {
        if (this.sources.size() < 2 || this.output == null) {
            return;
        }
        try {
            List<Path> outputs = ReplayCombiner.combine(this.newReplayName, List.copyOf(this.sources), this.output, this.dedupeChunkCaches);
            CombineReplayResults.afterCombine(outputs, this.lastScreen);
        } catch (Exception e) {
            Flashback.LOGGER.error("Error combining replays", e);
            Minecraft.getInstance().setScreen(new AlertScreen(
                () -> Minecraft.getInstance().setScreen(this.lastScreen),
                Component.translatable("flashback.combine_replay.error"),
                Component.literal(e.getMessage() == null ? e.toString() : e.getMessage())));
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.lastScreen);
    }
}
