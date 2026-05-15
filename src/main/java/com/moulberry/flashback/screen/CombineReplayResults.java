package com.moulberry.flashback.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

final class CombineReplayResults {

    private CombineReplayResults() {}

    static void afterCombine(List<Path> outputs, @Nullable Screen lastScreen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (outputs.size() <= 1) {
            minecraft.setScreen(new TitleScreen());
            return;
        }
        String fileList = outputs.stream()
            .map(p -> p.getFileName().toString())
            .collect(Collectors.joining("\n"));
        minecraft.setScreen(new AlertScreen(
            () -> minecraft.setScreen(lastScreen),
            Component.translatable("flashback.combine_replay.split_outputs"),
            Component.translatable("flashback.combine_replay.split_outputs_body", outputs.size(), fileList)));
    }
}
