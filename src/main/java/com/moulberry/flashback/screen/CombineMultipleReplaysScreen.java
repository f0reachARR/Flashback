package com.moulberry.flashback.screen;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.RegistryMetaHelper;
import com.moulberry.flashback.exporting.AsyncFileDialogs;
import com.moulberry.flashback.io.ReplayCombiner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CombineMultipleReplaysScreen extends Screen {

    private static final int CONTROL_WIDTH = 300;
    private static final int ITEM_HEIGHT = 36;

    @Nullable
    private final Screen lastScreen;
    private final LinkedHashMap<String, LinkedHashSet<String>> currentNamespacesForRegistries;

    private String newReplayName = "Combined Replay";
    private final List<Path> sources = new ArrayList<>();
    @Nullable
    private Path output;
    private boolean dedupeChunkCaches = false;

    @Nullable
    private CombineSourceList list;
    @Nullable
    private Button moveUpButton;
    @Nullable
    private Button moveDownButton;
    @Nullable
    private Button removeButton;
    @Nullable
    private Button clearAllButton;
    @Nullable
    private Button outputButton;
    @Nullable
    private Button combineButton;

    public CombineMultipleReplaysScreen(@Nullable Screen lastScreen) {
        this(lastScreen, null);
    }

    public CombineMultipleReplaysScreen(@Nullable Screen lastScreen, @Nullable Path firstSource) {
        super(Component.translatable("flashback.combine_replay.multiple"));
        this.lastScreen = lastScreen;
        this.currentNamespacesForRegistries = RegistryMetaHelper.calculateNamespacesForRegistries();
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

        Path replayFolder = Flashback.getReplayFolder();

        int centerX = this.width / 2;
        int controlLeft = centerX - CONTROL_WIDTH / 2;

        int topY = 30;

        EditBox replayNameEditBox = new EditBox(this.font, controlLeft, topY, CONTROL_WIDTH, 20, Component.literal(this.newReplayName));
        replayNameEditBox.setMaxLength(128);
        replayNameEditBox.setValue(this.newReplayName);
        replayNameEditBox.setResponder(s -> this.newReplayName = s);
        replayNameEditBox.setHint(Component.translatable("flashback.combine_replay.new_replay_name"));
        this.addRenderableWidget(replayNameEditBox);

        Checkbox dedupeCheckbox = Checkbox.builder(Component.translatable("flashback.combine_replay.dedupe_chunk_caches"), this.font)
            .pos(controlLeft, topY + 24)
            .selected(this.dedupeChunkCaches)
            .onValueChange((c, value) -> this.dedupeChunkCaches = value)
            .build();
        this.addRenderableWidget(dedupeCheckbox);

        int listTop = topY + 24 + 24;
        int bottomPanelHeight = 24 * 4;
        int listHeight = Math.max(ITEM_HEIGHT, this.height - listTop - bottomPanelHeight - 8);

        Path previouslySelected = this.list != null && this.list.getSelected() != null
            ? this.list.getSelected().getPath()
            : null;

        this.list = new CombineSourceList(this, this.minecraft, this.width, listHeight, listTop, ITEM_HEIGHT, this.currentNamespacesForRegistries);
        this.list.refreshFromSources(this.sources);
        if (previouslySelected != null) {
            this.list.selectByPath(previouslySelected);
        }
        this.addRenderableWidget(this.list);

        int bottomY = this.height - bottomPanelHeight - 4;

        int actionButtonWidth = (CONTROL_WIDTH - 8) / 3;
        this.moveUpButton = Button.builder(Component.translatable("flashback.combine_replay.move_up"), b -> this.moveSelected(-1))
            .bounds(controlLeft, bottomY, actionButtonWidth, 20).build();
        this.moveDownButton = Button.builder(Component.translatable("flashback.combine_replay.move_down"), b -> this.moveSelected(1))
            .bounds(controlLeft + actionButtonWidth + 4, bottomY, actionButtonWidth, 20).build();
        this.removeButton = Button.builder(Component.translatable("flashback.combine_replay.remove"), b -> this.removeSelected())
            .bounds(controlLeft + 2 * (actionButtonWidth + 4), bottomY, actionButtonWidth, 20).build();
        this.addRenderableWidget(this.moveUpButton);
        this.addRenderableWidget(this.moveDownButton);
        this.addRenderableWidget(this.removeButton);

        int addButtonWidth = (CONTROL_WIDTH - 8) / 3;
        Button addSourceButton = Button.builder(Component.translatable("flashback.combine_replay.add_source"), b -> {
            CompletableFuture<String> future = AsyncFileDialogs.openFileDialog(replayFolder.toString(), "Replay Archive", "zip");
            future.thenAccept(pathStr -> {
                if (pathStr != null) {
                    this.minecraft.execute(() -> this.addSource(Path.of(pathStr)));
                }
            });
        }).bounds(controlLeft, bottomY + 24, addButtonWidth, 20).build();
        Button addDirectoryButton = Button.builder(Component.translatable("flashback.combine_replay.add_directory"), b -> {
            CompletableFuture<String> future = AsyncFileDialogs.openFolderDialog(replayFolder.toString());
            future.thenAccept(pathStr -> {
                if (pathStr != null) {
                    this.minecraft.execute(() -> this.addZipsFromDirectory(Path.of(pathStr)));
                }
            });
        }).bounds(controlLeft + addButtonWidth + 4, bottomY + 24, addButtonWidth, 20).build();
        this.clearAllButton = Button.builder(Component.translatable("flashback.combine_replay.clear_all"), b -> this.clearAllSources())
            .bounds(controlLeft + 2 * (addButtonWidth + 4), bottomY + 24, addButtonWidth, 20).build();
        this.addRenderableWidget(addSourceButton);
        this.addRenderableWidget(addDirectoryButton);
        this.addRenderableWidget(this.clearAllButton);

        Component outputLabelComponent = this.output == null
            ? Component.translatable("flashback.combine_replay.output")
            : Component.literal(this.output.toString());
        this.outputButton = Button.builder(outputLabelComponent, b -> {
            CompletableFuture<String> future = AsyncFileDialogs.saveFileDialog(replayFolder.toString(), "combined.zip", "Replay Archive", "zip");
            future.thenAccept(pathStr -> {
                if (pathStr != null) {
                    this.minecraft.execute(() -> {
                        this.output = Path.of(pathStr);
                        if (this.outputButton != null) {
                            this.outputButton.setMessage(Component.literal(this.output.toString()));
                        }
                        this.updateButtonStatus();
                    });
                }
            });
        }).bounds(controlLeft, bottomY + 48, CONTROL_WIDTH, 20).build();
        this.addRenderableWidget(this.outputButton);

        int finalButtonWidth = (CONTROL_WIDTH - 4) / 2;
        this.combineButton = Button.builder(Component.translatable("flashback.combine_replay.do_combine"), b -> this.runCombine())
            .bounds(controlLeft, bottomY + 72, finalButtonWidth, 20).build();
        Button cancelButton = Button.builder(CommonComponents.GUI_CANCEL, b -> Minecraft.getInstance().setScreen(this.lastScreen))
            .bounds(controlLeft + finalButtonWidth + 4, bottomY + 72, finalButtonWidth, 20).build();
        this.addRenderableWidget(this.combineButton);
        this.addRenderableWidget(cancelButton);

        this.updateButtonStatus();

        this.setInitialFocus(replayNameEditBox);
    }

    public void updateButtonStatus() {
        int selectedIndex = this.list == null ? -1 : this.list.indexOfSelected();
        boolean hasSelection = selectedIndex >= 0;
        int size = this.sources.size();

        if (this.moveUpButton != null) {
            this.moveUpButton.active = hasSelection && selectedIndex > 0;
        }
        if (this.moveDownButton != null) {
            this.moveDownButton.active = hasSelection && selectedIndex < size - 1;
        }
        if (this.removeButton != null) {
            this.removeButton.active = hasSelection;
        }
        if (this.clearAllButton != null) {
            this.clearAllButton.active = !this.sources.isEmpty();
        }
        if (this.combineButton != null) {
            this.combineButton.active = this.sources.size() >= 2 && this.output != null;
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
    }

    private void addSource(Path path) {
        this.sources.add(path);
        if (this.list != null) {
            this.list.refreshFromSources(this.sources);
            this.list.scrollToBottom();
            this.list.selectByPath(path);
        }
        this.updateButtonStatus();
    }

    private void clearAllSources() {
        this.sources.clear();
        if (this.list != null) {
            this.list.refreshFromSources(this.sources);
        }
        this.updateButtonStatus();
    }

    private void moveSelected(int delta) {
        if (this.list == null) {
            return;
        }
        int index = this.list.indexOfSelected();
        if (index < 0) {
            return;
        }
        int target = index + delta;
        if (target < 0 || target >= this.sources.size()) {
            return;
        }
        Path moved = this.sources.get(index);
        this.sources.set(index, this.sources.get(target));
        this.sources.set(target, moved);
        this.list.refreshFromSources(this.sources);
        this.list.selectByPath(moved);
        this.updateButtonStatus();
    }

    private void removeSelected() {
        if (this.list == null) {
            return;
        }
        int index = this.list.indexOfSelected();
        if (index < 0) {
            return;
        }
        this.sources.remove(index);
        this.list.refreshFromSources(this.sources);
        this.list.setSelected(null);
        this.updateButtonStatus();
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
        if (this.list != null) {
            this.list.refreshFromSources(this.sources);
            this.list.scrollToBottom();
        }
        this.updateButtonStatus();
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
    public void removed() {
        if (this.list != null) {
            this.list.children().forEach(CombineSourceEntry::close);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.lastScreen);
    }
}
