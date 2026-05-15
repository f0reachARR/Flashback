package com.moulberry.flashback.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public class CombineSourceList extends ObjectSelectionList<CombineSourceEntry> {

    private final CombineMultipleReplaysScreen screen;
    private final LinkedHashMap<String, LinkedHashSet<String>> currentNamespacesForRegistries;

    public CombineSourceList(CombineMultipleReplaysScreen screen, Minecraft minecraft, int width, int height, int y, int itemHeight,
            LinkedHashMap<String, LinkedHashSet<String>> currentNamespacesForRegistries) {
        super(minecraft, width, height, y, itemHeight);
        this.screen = screen;
        this.currentNamespacesForRegistries = currentNamespacesForRegistries;
    }

    public void refreshFromSources(List<Path> sources) {
        Path previouslySelected = this.getSelected() != null ? this.getSelected().getPath() : null;

        List<CombineSourceEntry> previousEntries = new ArrayList<>(this.children());
        this.clearEntries();

        for (int i = 0; i < sources.size(); i++) {
            Path path = sources.get(i);
            CombineSourceEntry reused = null;
            for (CombineSourceEntry candidate : previousEntries) {
                if (candidate != null && candidate.getPath().equals(path)) {
                    reused = candidate;
                    break;
                }
            }
            if (reused != null) {
                previousEntries.remove(reused);
                reused.setDisplayIndex(i + 1);
                this.addEntry(reused);
            } else {
                this.addEntry(new CombineSourceEntry(this, this.minecraft, path, i + 1, this.currentNamespacesForRegistries));
            }
        }

        for (CombineSourceEntry leftover : previousEntries) {
            if (leftover != null) {
                leftover.close();
            }
        }

        if (previouslySelected != null) {
            for (CombineSourceEntry entry : this.children()) {
                if (entry.getPath().equals(previouslySelected)) {
                    super.setSelected(entry);
                    break;
                }
            }
        }

        this.refreshScrollAmount();
        this.screen.updateButtonStatus();
    }

    public void selectByPath(@Nullable Path path) {
        if (path == null) {
            this.setSelected(null);
            return;
        }
        for (CombineSourceEntry entry : this.children()) {
            if (entry.getPath().equals(path)) {
                this.setSelected(entry);
                return;
            }
        }
        this.setSelected(null);
    }

    public int indexOfSelected() {
        CombineSourceEntry selected = this.getSelected();
        if (selected == null) {
            return -1;
        }
        return this.children().indexOf(selected);
    }

    @Override
    public void setSelected(@Nullable CombineSourceEntry entry) {
        super.setSelected(entry);
        this.screen.updateButtonStatus();
    }

    @Override
    protected void clearEntries() {
        for (CombineSourceEntry entry : this.children()) {
            entry.close();
        }
        super.clearEntries();
    }

    @Override
    public int getRowWidth() {
        return 300;
    }

    public void scrollToBottom() {
        this.setScrollAmount(this.maxScrollAmount());
    }
}
