package com.example.streamertp;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

/** The StreamerTP window: add/remove names, click /tpa or /tpahere, adjust the cooldown. */
public class StreamerTpScreen extends Screen {
    private static final int ROW_H = 24;
    private static final int LIST_TOP = 60;

    private TextFieldWidget nameField;
    private String draft = "";
    private String status = "";
    private int page = 0;
    private int pages = 1;
    private int navY = 0;

    public StreamerTpScreen() {
        super(Text.literal("StreamerTP"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int doneY = this.height - 32;
        int sliderY = this.height - 56;
        int modeY = this.height - 80;
        int autoY = this.height - 104;
        navY = this.height - 128;

        nameField = new TextFieldWidget(this.textRenderer, cx - 115, 22, 170, 20, Text.literal("Name"));
        nameField.setMaxLength(17);
        nameField.setPlaceholder(Text.literal("Player name"));
        nameField.setText(draft);
        this.addDrawableChild(nameField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> {
            status = StreamerTpClient.addName(nameField.getText().trim());
            draft = status.startsWith("Added") ? "" : nameField.getText();
            this.clearAndInit();
        }).dimensions(cx + 60, 22, 55, 20).build());

        List<String> names = StreamerTpClient.names();
        int perPage = Math.max(1, (navY - 6 - LIST_TOP) / ROW_H);
        pages = Math.max(1, (names.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        int start = page * perPage;

        for (int i = 0; i < perPage && start + i < names.size(); i++) {
            String name = names.get(start + i);
            int y = LIST_TOP + i * ROW_H;

            this.addDrawableChild(ButtonWidget.builder(Text.literal("/tpa  " + name), b -> {
                StreamerTpClient.trySend(this.client, name, false);
                status = StreamerTpClient.lastStatus;
            }).dimensions(cx - 115, y, 130, 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal("/tpahere"), b -> {
                StreamerTpClient.trySend(this.client, name, true);
                status = StreamerTpClient.lastStatus;
            }).dimensions(cx + 20, y, 65, 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal("X"), b -> {
                status = StreamerTpClient.removeName(name);
                draft = nameField.getText();
                this.clearAndInit();
            }).dimensions(cx + 90, y, 25, 20).build());
        }

        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> {
            page--;
            draft = nameField.getText();
            this.clearAndInit();
        }).dimensions(cx - 115, navY, 40, 20).build();
        prev.active = page > 0;
        this.addDrawableChild(prev);

        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> {
            page++;
            draft = nameField.getText();
            this.clearAndInit();
        }).dimensions(cx + 75, navY, 40, 20).build();
        next.active = page < pages - 1;
        this.addDrawableChild(next);

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Send-next key uses: " + (StreamerTpClient.isHereMode() ? "/tpahere" : "/tpa")), b -> {
                    StreamerTpClient.toggleHereMode();
                    draft = nameField.getText();
                    this.clearAndInit();
                }).dimensions(cx - 115, modeY, 230, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Auto send: " + (StreamerTpClient.isAutoEnabled() ? "ON" : "OFF")), b -> {
                    StreamerTpClient.setAuto(this.client, !StreamerTpClient.isAutoEnabled());
                    draft = nameField.getText();
                    this.clearAndInit();
                }).dimensions(cx - 115, autoY, 230, 20).build());

        this.addDrawableChild(new CooldownSlider(cx - 115, sliderY, 230, 20));

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx - 115, doneY, 230, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 8, 0xFFFFFFFF);
        if (!status.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status), cx, 47, 0xFFFFFF55);
        }
        if (StreamerTpClient.names().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("No names yet. Type one above and press Add."), cx, LIST_TOP + 6, 0xFFAAAAAA);
        }
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Page " + (page + 1) + "/" + pages), cx, navY + 6, 0xFFAAAAAA);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Created by samouros samouridiou"), cx, this.height - 10, 0xFF888888);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        StreamerTpClient.saveConfig();
        super.removed();
    }

    /** Slider from 0.1s to 10s in 0.1s steps. */
    private static class CooldownSlider extends SliderWidget {
        CooldownSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Text.empty(),
                    (StreamerTpClient.getCooldown() - StreamerTpClient.MIN_COOLDOWN)
                            / (StreamerTpClient.MAX_COOLDOWN - StreamerTpClient.MIN_COOLDOWN));
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal(String.format("Cooldown: %.1fs", StreamerTpClient.getCooldown())));
        }

        @Override
        protected void applyValue() {
            double range = StreamerTpClient.MAX_COOLDOWN - StreamerTpClient.MIN_COOLDOWN;
            StreamerTpClient.setCooldown(StreamerTpClient.MIN_COOLDOWN + this.value * range);
            this.value = (StreamerTpClient.getCooldown() - StreamerTpClient.MIN_COOLDOWN) / range;
        }
    }
}
