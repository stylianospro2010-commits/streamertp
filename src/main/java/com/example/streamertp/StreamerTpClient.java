package com.example.streamertp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * StreamerTP for Minecraft 1.21.11 (Fabric, client side).
 * Keeps a list of names and sends "/tpa <name>" on request.
 * - Open GUI key (default [) opens the name list screen.
 * - Send next key (default ]) sends /tpa to the next name in the list.
 * Both keys can be changed in Options > Controls > Key Binds.
 */
public class StreamerTpClient implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("streamertp.json");
    private static final Pattern VALID_NAME = Pattern.compile("[.A-Za-z0-9_]{1,17}");

    public static final double MIN_COOLDOWN = 0.1;
    public static final double MAX_COOLDOWN = 10.0;

    /** Saved to config/streamertp.json */
    private static class Config {
        List<String> names = new ArrayList<>();
        double cooldownSeconds = 3.0;
        boolean hereMode = false;
    }

    private static Config config = new Config();
    private static int index = 0;
    private static long lastSend = 0;

    /** Last status message, shown inside the GUI. */
    static String lastStatus = "";

    private static KeyBinding openGuiKey;
    private static KeyBinding sendNextKey;
    private static KeyBinding toggleAutoKey;

    /** Auto mode never sends faster than this, whatever the cooldown slider says. */
    private static final long AUTO_MIN_MS = 1000;
    private static boolean autoEnabled = false;

    @Override
    public void onInitializeClient() {
        load();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("streamertp", "main"));
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.streamertp.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                category));
        sendNextKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.streamertp.send_next",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                category));
        toggleAutoKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.streamertp.toggle_auto",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSLASH,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new StreamerTpScreen());
                }
            }
            while (sendNextKey.wasPressed()) {
                sendNext(client);
            }
            while (toggleAutoKey.wasPressed()) {
                setAuto(client, !autoEnabled);
            }
            if (autoEnabled) {
                if (client.player == null) {
                    autoEnabled = false;
                } else if (config.names.isEmpty()) {
                    setAuto(client, false);
                } else {
                    long waitMs = Math.max((long) (config.cooldownSeconds * 1000), AUTO_MIN_MS);
                    if (System.currentTimeMillis() - lastSend >= waitMs) {
                        sendNext(client);
                    }
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("streamtp")
                        .then(ClientCommandManager.literal("add")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    addName(StringArgumentType.getString(ctx, "name"))));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .suggests((ctx, b) -> CommandSource.suggestMatching(config.names, b))
                                        .executes(ctx -> {
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    removeName(StringArgumentType.getString(ctx, "name"))));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("list")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(Text.literal(
                                            config.names.isEmpty() ? "List is empty" : "Names: " + String.join(", ", config.names)));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("cooldown")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(Text.literal(
                                            "Cooldown is " + getCooldown() + "s (0.1 to 10, set with /streamtp cooldown <seconds>)"));
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(MIN_COOLDOWN, MAX_COOLDOWN))
                                        .executes(ctx -> {
                                            setCooldown(DoubleArgumentType.getDouble(ctx, "seconds"));
                                            saveConfig();
                                            ctx.getSource().sendFeedback(Text.literal("Cooldown set to " + getCooldown() + "s"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("send")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .suggests((ctx, b) -> CommandSource.suggestMatching(config.names, b))
                                        .executes(ctx -> {
                                            trySend(MinecraftClient.getInstance(), StringArgumentType.getString(ctx, "name"), false);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("sendhere")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .suggests((ctx, b) -> CommandSource.suggestMatching(config.names, b))
                                        .executes(ctx -> {
                                            trySend(MinecraftClient.getInstance(), StringArgumentType.getString(ctx, "name"), true);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("auto")
                                .executes(ctx -> {
                                    setAuto(MinecraftClient.getInstance(), !autoEnabled);
                                    ctx.getSource().sendFeedback(Text.literal("Auto send is " + (autoEnabled ? "ON" : "OFF")));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("mode")
                                .executes(ctx -> {
                                    config.hereMode = !config.hereMode;
                                    saveConfig();
                                    ctx.getSource().sendFeedback(Text.literal(
                                            "The send-next key now uses " + (config.hereMode ? "/tpahere" : "/tpa")));
                                    return 1;
                                }))));
    }

    // ---- shared state used by the GUI and commands ----

    static List<String> names() {
        return config.names;
    }

    static double getCooldown() {
        return config.cooldownSeconds;
    }

    static boolean isAutoEnabled() {
        return autoEnabled;
    }

    static void setAuto(MinecraftClient client, boolean on) {
        autoEnabled = on;
        if (on) index = 0;
        say(client, "Auto send " + (on ? "ON (every " + Math.max(config.cooldownSeconds, AUTO_MIN_MS / 1000.0) + "s)" : "OFF"));
    }

    static boolean isHereMode() {
        return config.hereMode;
    }

    static void toggleHereMode() {
        config.hereMode = !config.hereMode;
        saveConfig();
    }

    static void setCooldown(double seconds) {
        double rounded = Math.round(seconds * 10.0) / 10.0;
        config.cooldownSeconds = Math.max(MIN_COOLDOWN, Math.min(MAX_COOLDOWN, rounded));
    }

    static String addName(String name) {
        if (!VALID_NAME.matcher(name).matches()) return "Invalid name";
        if (config.names.stream().anyMatch(x -> x.equalsIgnoreCase(name))) return name + " is already in the list";
        config.names.add(name);
        saveConfig();
        return "Added " + name;
    }

    static String removeName(String name) {
        boolean removed = config.names.removeIf(x -> x.equalsIgnoreCase(name));
        if (removed) {
            index = 0;
            saveConfig();
        }
        return removed ? "Removed " + name : name + " not found";
    }

    // ---- sending ----

    private static void sendNext(MinecraftClient client) {
        if (config.names.isEmpty()) {
            say(client, "Name list is empty. Open the GUI to add names.");
            return;
        }
        if (index >= config.names.size()) index = 0;
        String name = config.names.get(index);
        if (trySend(client, name, config.hereMode)) {
            index = (index + 1) % config.names.size();
        }
    }

    /** Sends /tpa <name> unless the cooldown is still running. Returns true if sent. */
    static boolean trySend(MinecraftClient client, String name, boolean here) {
        if (client.player == null || client.player.networkHandler == null) {
            lastStatus = "Not in a world";
            return false;
        }
        long now = System.currentTimeMillis();
        long cooldownMs = (long) (config.cooldownSeconds * 1000);
        long left = cooldownMs - (now - lastSend);
        if (left > 0) {
            lastStatus = String.format("Cooldown: wait %.1fs", left / 1000.0);
            say(client, lastStatus);
            return false;
        }
        lastSend = now;
        String cmd = here ? "tpahere" : "tpa";
        client.player.networkHandler.sendChatCommand(cmd + " " + name);
        lastStatus = "Sent /" + cmd + " " + name;
        say(client, lastStatus);
        return true;
    }

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal("[StreamerTP] " + msg), true);
    }

    // ---- config ----

    private static void load() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE)) {
            Config loaded = GSON.fromJson(r, Config.class);
            if (loaded != null) {
                if (loaded.names == null) loaded.names = new ArrayList<>();
                config = loaded;
                setCooldown(config.cooldownSeconds);
            }
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
        }
    }

    static void saveConfig() {
        try (Writer w = Files.newBufferedWriter(FILE)) {
            GSON.toJson(config, w);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
