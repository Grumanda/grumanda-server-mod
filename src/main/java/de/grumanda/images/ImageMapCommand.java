package de.grumanda.images;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.grumanda.GrumandaServerMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImageMapCommand {
    private static final int MAP_PIXELS = 128;
    private static final int MAX_MAPS = 1500;
    private static final int MAX_DIMENSION = 50;
    private static final int MAX_ANIMATED_MAPS = 25;
    private static final int MAX_GIF_FRAMES = 20;
    private static final int MAX_GIF_FRAME_PIXELS = 8_000_000;
    private static final int MIN_GIF_FRAME_TICKS = 5;
    private static final int MAX_ACTIVE_ANIMATIONS = 4;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_INPUT_PIXELS = 16_000_000;
    private static final long PLAYER_COOLDOWN_MILLIS = 15_000;
    private static final int MAX_CONCURRENT_JOBS = 2;
    private static final long MAX_DOWNLOAD_TIMEOUT_SECONDS = 12;
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^(.+)\\s+(\\d+)\\s+(\\d+)$");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final AtomicInteger ACTIVE_JOBS = new AtomicInteger();
    private static final Map<UUID, Long> LAST_REQUESTS = new HashMap<>();
    private static final List<RunningAnimation> ANIMATIONS = new ArrayList<>();
    private static final Set<UUID> PENDING_PLACEMENTS = new HashSet<>();
    private static final byte[] COLOR_LOOKUP = createColorLookup();
    private static boolean tickEventRegistered;

    private ImageMapCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bild")
                .then(Commands.argument("bild-und-groesse", StringArgumentType.greedyString())
                        .executes(context -> giveImageItem(
                                context.getSource(),
                                StringArgumentType.getString(context, "bild-und-groesse")
                        ))));
        dispatcher.register(Commands.literal("bildgroesse")
                .then(Commands.argument("link-und-kante", StringArgumentType.greedyString())
                        .executes(context -> suggestImageSize(
                                context.getSource(),
                                StringArgumentType.getString(context, "link-und-kante")
                        ))));
        if (!tickEventRegistered) {
            ServerTickEvents.END_SERVER_TICK.register(ImageMapCommand::tickAnimations);
            UseBlockCallback.EVENT.register(ImageMapCommand::useImageItem);
            tickEventRegistered = true;
        }
    }

    private static int giveImageItem(CommandSourceStack source, String input) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ParsedCommand parsed;
        try {
            parsed = parseCommand(input);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Verwendung: /bild <direkter Bild-Link> <breite> <höhe> — z. B. /bild https://example.org/bild.gif 8 4"));
            return 0;
        }

        String rawUrl = parsed.url();
        int width = parsed.width();
        int height = parsed.height();
        int mapCount = width * height;
        if (mapCount > MAX_MAPS) {
            source.sendFailure(Component.literal("Ein Bild darf höchstens " + MAX_MAPS + " Karten groß sein (Breite × Höhe)."));
            return 0;
        }

        URI imageUri;
        try {
            if (rawUrl.length() > MAX_URL_LENGTH) {
                throw new IllegalArgumentException("Image URL is too long.");
            }
            imageUri = validateUri(URI.create(rawUrl));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Ungültiger oder nicht unterstützter Bild-Link."));
            return 0;
        }

        ItemStack item = new ItemStack(Items.STICK);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Bild platzieren"));
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(new net.minecraft.nbt.CompoundTag()));
        CustomData.update(DataComponents.CUSTOM_DATA, item, tag -> {
            tag.putString("grumanda_image_url", imageUri.toString());
            tag.putInt("grumanda_image_width", width);
            tag.putInt("grumanda_image_height", height);
            tag.putString("grumanda_image_token", UUID.randomUUID().toString());
        });
        player.getInventory().add(item);
        source.sendSuccess(() -> Component.literal("Bild-Item erhalten. Rechtsklick damit auf die untere linke Wandfläche."), false);
        return 1;
    }

    private static int suggestImageSize(CommandSourceStack source, String input) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String[] parts = input.trim().split("\\s+");
        if (parts.length < 3) {
            source.sendFailure(Component.literal("Verwendung: /bildgroesse <direkter Bild-Link> <breite|hoehe> <Blöcke>"));
            return 0;
        }
        int blocks;
        try {
            blocks = Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("Die Blockanzahl muss eine Zahl zwischen 1 und " + MAX_DIMENSION + " sein."));
            return 0;
        }
        String edge = parts[parts.length - 2].toLowerCase(Locale.ROOT);
        if (blocks < 1 || blocks > MAX_DIMENSION
                || !(edge.equals("breite") || edge.equals("hoehe") || edge.equals("höhe"))) {
            source.sendFailure(Component.literal("Verwendung: /bildgroesse <direkter Bild-Link> <breite|hoehe> <Blöcke>"));
            return 0;
        }
        String url = input.substring(0, input.lastIndexOf(parts[parts.length - 2])).trim();
        if (url.isEmpty()) {
            source.sendFailure(Component.literal("Bitte gib einen direkten Bild-Link an."));
            return 0;
        }
        URI uri;
        try {
            if (url.length() > MAX_URL_LENGTH) {
                throw new IllegalArgumentException("Image URL is too long.");
            }
            uri = validateUri(URI.create(url));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Ungültiger oder nicht unterstützter Bild-Link."));
            return 0;
        }
        if (!reserveJob()) {
            source.sendFailure(Component.literal("Der Bild-Download ist gerade ausgelastet. Bitte versuche es später erneut."));
            return 0;
        }

        boolean widthGiven = edge.equals("breite");
        UUID playerId = player.getUUID();
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Bildabmessungen werden ermittelt …"), false);
        CompletableFuture.supplyAsync(() -> {
            try {
                return readImageDimensions(uri);
            } catch (IOException | InterruptedException e) {
                throw new ImageLoadException(e);
            } finally {
                ACTIVE_JOBS.decrementAndGet();
            }
        }).whenComplete((dimensions, failure) -> server.execute(() -> {
            ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
            if (onlinePlayer == null) {
                return;
            }
            if (failure != null) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                GrumandaServerMod.LOGGER.warn("Could not read image dimensions requested by {}", onlinePlayer.getGameProfile().name(), cause);
                onlinePlayer.sendSystemMessage(Component.literal("Bildgröße konnte nicht ermittelt werden. Verwende einen direkten Bild-Link; Details stehen im Server-Log."));
                return;
            }
            double aspectRatio = (double) dimensions.width() / dimensions.height();
            int width = widthGiven ? blocks : Math.max(1, (int) Math.round(blocks * aspectRatio));
            int height = widthGiven ? Math.max(1, (int) Math.round(blocks / aspectRatio)) : blocks;
            if (width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_MAPS) {
                onlinePlayer.sendSystemMessage(Component.literal("Das passende Seitenverhältnis wäre " + width + "×" + height
                        + " Blöcke und überschreitet das Größenlimit. Nimm weniger Blöcke für die angegebene Kante."));
                return;
            }
            String imageCommand = "/bild " + url + " " + width + " " + height;
            Component message = Component.literal("Passende Größe: " + width + "×" + height
                            + " Blöcke. Klicke zum Kopieren: ")
                    .append(Component.literal(imageCommand)
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.GREEN)
                                    .withUnderlined(true)
                                    .withClickEvent(new ClickEvent.CopyToClipboard(imageCommand))));
            onlinePlayer.sendSystemMessage(message);
        }));
        return 1;
    }

    private static InteractionResult useImageItem(net.minecraft.world.entity.player.Player player,
                                                  net.minecraft.world.level.Level world,
                                                  InteractionHand hand,
                                                  BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!held.is(Items.STICK)) {
            return InteractionResult.PASS;
        }
        CustomData customData = held.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.copyTag().contains("grumanda_image_token")) {
            return InteractionResult.PASS;
        }
        if (world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        Direction face = hit.getDirection();
        if (face.getAxis().isVertical()) {
            serverPlayer.sendSystemMessage(Component.literal("Benutze das Bild-Item auf einer senkrechten Wandfläche."));
            return InteractionResult.FAIL;
        }

        net.minecraft.nbt.CompoundTag tag = customData.copyTag();
        String url = tag.getStringOr("grumanda_image_url", "");
        String token = tag.getStringOr("grumanda_image_token", "");
        int width = tag.getIntOr("grumanda_image_width", 0);
        int height = tag.getIntOr("grumanda_image_height", 0);
        try {
            if (url.length() > MAX_URL_LENGTH) {
                throw new IllegalArgumentException("Image URL is too long.");
            }
            validateUri(URI.create(url));
        } catch (IllegalArgumentException e) {
            serverPlayer.sendSystemMessage(Component.literal("Das Bild-Item enthält keinen gültigen Bild-Link."));
            return InteractionResult.FAIL;
        }
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_MAPS) {
            serverPlayer.sendSystemMessage(Component.literal("Das Bild-Item enthält eine ungültige Bildgröße."));
            return InteractionResult.FAIL;
        }

        UUID playerId = serverPlayer.getUUID();
        if (!PENDING_PLACEMENTS.add(playerId)) {
            serverPlayer.sendSystemMessage(Component.literal("Dein Bild wird bereits verarbeitet. Bitte warte kurz."));
            return InteractionResult.FAIL;
        }
        long now = System.currentTimeMillis();
        Long previousRequest = LAST_REQUESTS.get(playerId);
        if (previousRequest != null && now - previousRequest < PLAYER_COOLDOWN_MILLIS) {
            PENDING_PLACEMENTS.remove(playerId);
            serverPlayer.sendSystemMessage(Component.literal("Bitte warte kurz, bevor du das nächste Bild platzierst."));
            return InteractionResult.FAIL;
        }
        if (!reserveJob()) {
            PENDING_PLACEMENTS.remove(playerId);
            serverPlayer.sendSystemMessage(Component.literal("Der Bild-Download ist gerade ausgelastet. Bitte versuche es später erneut."));
            return InteractionResult.FAIL;
        }
        LAST_REQUESTS.put(playerId, now);

        BlockPos anchor = hit.getBlockPos();
        ResourceKey<Level> targetDimension = serverPlayer.level().dimension();
        MinecraftServer server = serverPlayer.level().getServer();
        serverPlayer.sendSystemMessage(Component.literal("Bild wird geladen …"));
        CompletableFuture.supplyAsync(() -> {
            try {
                return loadAndScaleImage(URI.create(url), width, height);
            } catch (IOException | InterruptedException e) {
                throw new ImageLoadException(e);
            } finally {
                ACTIVE_JOBS.decrementAndGet();
            }
        }).whenComplete((image, failure) -> server.execute(() -> {
            PENDING_PLACEMENTS.remove(playerId);
            ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
            if (onlinePlayer == null) {
                return;
            }
            if (failure != null) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                GrumandaServerMod.LOGGER.warn("Could not load image requested by {}", onlinePlayer.getGameProfile().name(), cause);
                onlinePlayer.sendSystemMessage(Component.literal("Bild konnte nicht geladen werden. Details stehen im Server-Log."));
                return;
            }
            if (!onlinePlayer.level().dimension().equals(targetDimension)) {
                onlinePlayer.sendSystemMessage(Component.literal("Du bist in eine andere Dimension gewechselt; das Bild wurde nicht platziert."));
                return;
            }
            if (image.frames().size() > 1 && width * height > MAX_ANIMATED_MAPS) {
                onlinePlayer.sendSystemMessage(Component.literal("GIFs sind auf " + MAX_ANIMATED_MAPS + " Karten begrenzt."));
                return;
            }
            if (image.frames().size() > 1 && ANIMATIONS.size() >= MAX_ACTIVE_ANIMATIONS) {
                onlinePlayer.sendSystemMessage(Component.literal("Es laufen bereits zu viele GIF-Animationen. Bitte versuche es später erneut."));
                return;
            }
            ItemStack current = onlinePlayer.getItemInHand(hand);
            CustomData currentData = current.get(DataComponents.CUSTOM_DATA);
            if (!current.is(Items.STICK) || currentData == null
                    || !currentData.copyTag().getStringOr("grumanda_image_token", "").equals(token)) {
                onlinePlayer.sendSystemMessage(Component.literal("Das Bild-Item ist nicht mehr in der Hand; das Bild wurde nicht platziert."));
                return;
            }
            try {
                List<MapItemSavedData> maps = placeImage(
                        onlinePlayer, image.frames().getFirst().tiles(), width, height, anchor, face
                );
                current.shrink(1);
                if (image.frames().size() > 1) {
                    startAnimation(server, image, maps);
                }
                String animated = image.frames().size() > 1 ? " (GIF-Animation läuft)" : "";
                onlinePlayer.sendSystemMessage(Component.literal("Bild " + width + "×" + height + " platziert." + animated));
            } catch (IllegalArgumentException e) {
                onlinePlayer.sendSystemMessage(Component.literal(e.getMessage()));
            } catch (RuntimeException e) {
                GrumandaServerMod.LOGGER.error("Could not place image for {}", onlinePlayer.getGameProfile().name(), e);
                onlinePlayer.sendSystemMessage(Component.literal("Bild konnte nicht platziert werden. Details stehen im Server-Log."));
            }
        }));
        return InteractionResult.SUCCESS;
    }

    private static ImageDimensions readImageDimensions(URI uri) throws IOException, InterruptedException {
        byte[] bytes = downloadImage(uri);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("Could not read image data.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int frameWidth = reader.getWidth(0);
                int frameHeight = reader.getHeight(0);
                IIOMetadata streamMetadata = reader.getStreamMetadata();
                int width = parseMetadataInt(
                        getMetadataAttribute(streamMetadata, "LogicalScreenDescriptor", "logicalScreenWidth",
                                Integer.toString(frameWidth)), frameWidth
                );
                int height = parseMetadataInt(
                        getMetadataAttribute(streamMetadata, "LogicalScreenDescriptor", "logicalScreenHeight",
                                Integer.toString(frameHeight)), frameHeight
                );
                if (width < 1 || height < 1 || (long) width * height > MAX_INPUT_PIXELS) {
                    throw new IOException("Image dimensions exceed the supported limit.");
                }
                return new ImageDimensions(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private static ParsedCommand parseCommand(String input) {
        Matcher matcher = COMMAND_PATTERN.matcher(input.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Missing image URL or size.");
        }
        int width;
        int height;
        try {
            width = Integer.parseInt(matcher.group(2));
            height = Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid image dimensions.", e);
        }
        if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Image dimensions must be between 1 and " + MAX_DIMENSION + ".");
        }
        String url = matcher.group(1).trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Missing image URL.");
        }
        return new ParsedCommand(url, width, height);
    }

    private static void tickAnimations(MinecraftServer server) {
        for (int i = ANIMATIONS.size() - 1; i >= 0; i--) {
            RunningAnimation animation = ANIMATIONS.get(i);
            if (animation.server() != server) {
                continue;
            }
            animation.ticksUntilNextFrame--;
            if (animation.ticksUntilNextFrame <= 0) {
                animation.frameIndex = (animation.frameIndex + 1) % animation.image().frames().size();
                applyAnimationFrame(animation);
                animation.ticksUntilNextFrame = animation.image().frames().get(animation.frameIndex).durationTicks();
            }
        }
    }

    private static void applyAnimationFrame(RunningAnimation animation) {
        AnimationFrame frame = animation.image().frames().get(animation.frameIndex);
        for (int i = 0; i < animation.maps().size(); i++) {
            byte[] colors = frame.tiles().get(i);
            var mapData = animation.maps().get(i);
            System.arraycopy(colors, 0, mapData.colors, 0, colors.length);
            mapData.setColor(0, 0, (byte) (colors[0] == 0 ? 4 : 0));
            mapData.setColor(0, 0, colors[0]);
            mapData.setColor(MAP_PIXELS - 1, MAP_PIXELS - 1,
                    (byte) (colors[colors.length - 1] == 0 ? 4 : 0));
            mapData.setColor(MAP_PIXELS - 1, MAP_PIXELS - 1, colors[colors.length - 1]);
        }
    }

    private static boolean reserveJob() {
        while (true) {
            int active = ACTIVE_JOBS.get();
            if (active >= MAX_CONCURRENT_JOBS) {
                return false;
            }
            if (ACTIVE_JOBS.compareAndSet(active, active + 1)) {
                return true;
            }
        }
    }

    private static URI validateUri(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("Only public HTTP(S) image URLs are supported.");
        }
        return uri;
    }

    private static void validatePublicAddress(URI uri) throws IOException {
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || isReservedAddress(address)) {
                throw new IllegalArgumentException("Private and local hosts are not allowed.");
            }
        }
    }

    private static boolean isReservedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || first == 100 && second >= 64 && second <= 127
                    || first == 192 && second == 0
                    || first == 192 && second == 0 && third == 2
                    || first == 198 && (second == 18 || second == 19)
                    || first == 198 && second == 51 && third == 100
                    || first == 203 && second == 0 && third == 113
                    || first >= 240;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xFE) == 0xFC
                    || first == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0D && Byte.toUnsignedInt(bytes[3]) == 0xB8;
        }
        return false;
    }

    private static LoadedImage loadAndScaleImage(URI uri, int width, int height) throws IOException, InterruptedException {
        byte[] imageBytes = downloadImage(uri);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (input == null) {
                throw new IOException("Could not read image data.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                if (reader.getFormatName().equalsIgnoreCase("gif")) {
                    return loadGif(reader, width, height);
                }
                BufferedImage source = readBoundedFrame(reader, 0);
                try {
                    return new LoadedImage(List.of(new AnimationFrame(scaleToTiles(source, width, height), Integer.MAX_VALUE)));
                } finally {
                    source.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static LoadedImage loadGif(ImageReader reader, int width, int height) throws IOException {
        int frameCount = reader.getNumImages(true);
        if (frameCount < 1 || frameCount > MAX_GIF_FRAMES) {
            throw new IOException("GIF frame count exceeds the maximum of " + MAX_GIF_FRAMES + ".");
        }
        if ((long) width * height * MAP_PIXELS * MAP_PIXELS * frameCount > MAX_GIF_FRAME_PIXELS) {
            throw new IOException("GIF animation is too large for the requested map size.");
        }
        IIOMetadata streamMetadata = reader.getStreamMetadata();
        int canvasWidth = parseMetadataInt(
                getMetadataAttribute(streamMetadata, "LogicalScreenDescriptor", "logicalScreenWidth",
                        Integer.toString(reader.getWidth(0))), reader.getWidth(0)
        );
        int canvasHeight = parseMetadataInt(
                getMetadataAttribute(streamMetadata, "LogicalScreenDescriptor", "logicalScreenHeight",
                        Integer.toString(reader.getHeight(0))), reader.getHeight(0)
        );
        if (canvasWidth < 1 || canvasHeight < 1
                || (long) canvasWidth * canvasHeight > MAX_INPUT_PIXELS
                || (long) canvasWidth * canvasHeight * frameCount > MAX_GIF_FRAME_PIXELS) {
            throw new IOException("GIF dimensions or total animation size exceed the supported limit.");
        }

        BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D initialGraphics = canvas.createGraphics();
        initialGraphics.setColor(Color.WHITE);
        initialGraphics.fillRect(0, 0, canvasWidth, canvasHeight);
        initialGraphics.dispose();

        List<AnimationFrame> frames = new ArrayList<>(frameCount);
        BufferedImage priorCanvas = null;
        String priorDisposal = "none";
        FrameRectangle priorRectangle = new FrameRectangle(0, 0, 0, 0);
        for (int index = 0; index < frameCount; index++) {
            if (priorDisposal.equals("restoreToBackgroundColor")) {
                Graphics2D graphics = canvas.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(priorRectangle.left(), priorRectangle.top(),
                        priorRectangle.width(), priorRectangle.height());
                graphics.dispose();
            } else if (priorDisposal.equals("restoreToPrevious") && priorCanvas != null) {
                canvas.flush();
                canvas = priorCanvas;
                priorCanvas = null;
            }
            if (priorCanvas != null) {
                priorCanvas.flush();
                priorCanvas = null;
            }

            IIOMetadata metadata = reader.getImageMetadata(index);
            FrameRectangle rectangle = getFrameRectangle(metadata, reader.getWidth(index), reader.getHeight(index));
            if (rectangle.left() < 0 || rectangle.top() < 0 || rectangle.width() < 1 || rectangle.height() < 1
                    || rectangle.left() + rectangle.width() > canvasWidth
                    || rectangle.top() + rectangle.height() > canvasHeight) {
                throw new IOException("GIF frame is outside the logical image bounds.");
            }
            String disposal = getMetadataAttribute(metadata, "GraphicControlExtension", "disposalMethod", "none");
            int delay = parseMetadataInt(
                    getMetadataAttribute(metadata, "GraphicControlExtension", "delayTime", "10"), 10
            );
            BufferedImage beforeFrame = disposal.equals("restoreToPrevious") ? copyImage(canvas) : null;

            BufferedImage frame = readBoundedFrame(reader, index);
            Graphics2D graphics = canvas.createGraphics();
            graphics.setComposite(java.awt.AlphaComposite.SrcOver);
            graphics.drawImage(frame, rectangle.left(), rectangle.top(), null);
            graphics.dispose();
            frame.flush();

            BufferedImage snapshot = copyImage(canvas);
            frames.add(new AnimationFrame(
                    scaleToTiles(snapshot, width, height),
                    (int) Math.min(1200, Math.max(MIN_GIF_FRAME_TICKS, (delay + 4L) / 5))
            ));
            snapshot.flush();
            priorCanvas = beforeFrame;
            priorDisposal = disposal;
            priorRectangle = rectangle;
        }
        canvas.flush();
        if (priorCanvas != null) {
            priorCanvas.flush();
        }
        return new LoadedImage(frames);
    }

    private static BufferedImage readBoundedFrame(ImageReader reader, int index) throws IOException {
        int width = reader.getWidth(index);
        int height = reader.getHeight(index);
        if (width < 1 || height < 1 || (long) width * height > MAX_INPUT_PIXELS) {
            throw new IOException("Image dimensions exceed the supported limit.");
        }
        BufferedImage image = reader.read(index);
        if (image == null) {
            throw new IOException("Could not decode image data.");
        }
        return image;
    }

    private static BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private static FrameRectangle getFrameRectangle(IIOMetadata metadata, int width, int height) throws IOException {
        String left = getMetadataAttribute(metadata, "ImageDescriptor", "imageLeftPosition", "0");
        String top = getMetadataAttribute(metadata, "ImageDescriptor", "imageTopPosition", "0");
        width = parseMetadataInt(
                getMetadataAttribute(metadata, "ImageDescriptor", "imageWidth", Integer.toString(width)), width
        );
        height = parseMetadataInt(
                getMetadataAttribute(metadata, "ImageDescriptor", "imageHeight", Integer.toString(height)), height
        );
        int x = parseMetadataInt(left, 0);
        int y = parseMetadataInt(top, 0);
        return new FrameRectangle(x, y, width, height);
    }

    private static String getMetadataAttribute(IIOMetadata metadata, String targetNode, String attribute, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        for (String format : metadata.getMetadataFormatNames()) {
            Node found = findNode(metadata.getAsTree(format), targetNode);
            if (found != null) {
                NamedNodeMap attributes = found.getAttributes();
                Node value = attributes == null ? null : attributes.getNamedItem(attribute);
                if (value != null) {
                    return value.getNodeValue();
                }
            }
        }
        return fallback;
    }

    private static Node findNode(Node node, String targetName) {
        if (node.getNodeName().equals(targetName)) {
            return node;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node found = findNode(child, targetName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static int parseMetadataInt(String value, int fallback) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid image metadata.", e);
        }
    }

    private static List<byte[]> scaleToTiles(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width * MAP_PIXELS, height * MAP_PIXELS, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, scaled.getWidth(), scaled.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        } finally {
            graphics.dispose();
            source.flush();
        }

        List<byte[]> tiles = new ArrayList<>(width * height);
        for (int tileY = 0; tileY < height; tileY++) {
            for (int tileX = 0; tileX < width; tileX++) {
                byte[] colors = new byte[MAP_PIXELS * MAP_PIXELS];
                for (int y = 0; y < MAP_PIXELS; y++) {
                    for (int x = 0; x < MAP_PIXELS; x++) {
                        int rgb = scaled.getRGB(tileX * MAP_PIXELS + x, tileY * MAP_PIXELS + y);
                        int lookupIndex = ((rgb >>> 9) & 0x7C00) | ((rgb >>> 6) & 0x03E0) | ((rgb >>> 3) & 0x001F);
                        colors[y * MAP_PIXELS + x] = COLOR_LOOKUP[lookupIndex];
                    }
                }
                tiles.add(colors);
            }
        }
        scaled.flush();
        return tiles;
    }

    private static byte[] downloadImage(URI initialUri) throws IOException, InterruptedException {
        URI uri = initialUri;
        for (int redirects = 0; redirects <= 3; redirects++) {
            validatePublicAddress(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(MAX_DOWNLOAD_TIMEOUT_SECONDS))
                    .header("User-Agent", "GrumandaServerMod/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                try (InputStream body = response.body()) {
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new IOException("Image host returned a redirect without a destination."));
                    if (redirects == 3) {
                        throw new IOException("Image host returned too many redirects.");
                    }
                    uri = validateUri(uri.resolve(location));
                }
                continue;
            }

            try (InputStream body = response.body()) {
                if (status != 200) {
                    throw new IOException("Image host returned HTTP " + status + ".");
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("")
                        .toLowerCase(Locale.ROOT);
                if (!contentType.startsWith("image/")) {
                    throw new IOException("URL did not return an image.");
                }
                byte[] bytes = body.readNBytes(MAX_IMAGE_BYTES + 1);
                if (bytes.length > MAX_IMAGE_BYTES) {
                    throw new IOException("Image exceeds the 10 MB limit.");
                }
                return bytes;
            }
        }
        throw new IOException("Image host returned too many redirects.");
    }

    private static List<MapItemSavedData> placeImage(ServerPlayer player, List<byte[]> tiles, int width, int height,
                                                     BlockPos anchor, Direction face) {
        ServerLevel level = player.level();
        Direction right = face.getCounterClockWise();
        List<BlockPos> backingBlocks = new ArrayList<>(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                BlockPos pos = anchor.relative(right, x).above(y);
                if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)
                        || !player.mayInteract(level, pos)
                        || !level.getBlockState(pos).isFaceSturdy(level, pos, face)) {
                    throw new IllegalArgumentException("Die Wand ist zu klein oder du darfst dort keine Bilder platzieren. Benötigt werden " + width + "×" + height + " freie, feste Blockseiten.");
                }
                backingBlocks.add(pos);
            }
        }

        List<ItemFrame> frames = new ArrayList<>(backingBlocks.size());
        for (BlockPos pos : backingBlocks) {
            BlockPos framePos = pos.relative(face);
            if (!level.isInWorldBounds(framePos) || !level.hasChunkAt(framePos)) {
                throw new IllegalArgumentException("Die Wandfläche liegt außerhalb des geladenen Bereichs.");
            }
            if (hasFrameAt(level, framePos, face)) {
                throw new IllegalArgumentException("Auf der benötigten Wandfläche ist bereits ein Rahmen.");
            }
            ItemFrame frame = new ItemFrame(level, framePos, face);
            if (!frame.survives()) {
                throw new IllegalArgumentException("Die Wand eignet sich nicht für alle benötigten Bilderrahmen.");
            }
            frames.add(frame);
        }

        List<ItemFrame> placedFrames = new ArrayList<>(frames.size());
        List<MapItemSavedData> mapDataEntries = new ArrayList<>(frames.size());
        try {
            for (int i = 0; i < frames.size(); i++) {
                ItemFrame frame = frames.get(i);
                ItemStack map = MapItem.create(level, 0, 0, (byte) 0, false, false);
                var mapData = MapItem.getSavedData(map, level);
                if (mapData == null) {
                    throw new IllegalStateException("Newly created map has no saved data.");
                }
                int tileX = i % width;
                int tileYFromBottom = i / width;
                byte[] colors = tiles.get((height - 1 - tileYFromBottom) * width + tileX);
                System.arraycopy(colors, 0, mapData.colors, 0, colors.length);
                mapData.setDirty();
                frame.setItem(map, false);
                if (!level.addFreshEntity(frame)) {
                    throw new IllegalStateException("Minecraft rejected an image frame entity.");
                }
                placedFrames.add(frame);
                mapDataEntries.add(mapData);
            }
        } catch (RuntimeException e) {
            for (ItemFrame frame : placedFrames) {
                frame.discard();
            }
            throw e;
        }
        return mapDataEntries;
    }

    private static void startAnimation(MinecraftServer server, LoadedImage image, List<MapItemSavedData> maps) {
        int initialDelay = image.frames().getFirst().durationTicks();
        ANIMATIONS.add(new RunningAnimation(server, image, maps, initialDelay));
    }

    private static boolean hasFrameAt(ServerLevel level, BlockPos pos, Direction face) {
        AABB searchArea = new AABB(pos).inflate(0.25);
        for (Entity entity : level.getEntities((Entity) null, searchArea, candidate -> candidate instanceof ItemFrame)) {
            if (entity instanceof ItemFrame frame
                    && frame.blockPosition().equals(pos)
                    && frame.getDirection() == face) {
                return true;
            }
        }
        return false;
    }

    private static byte[] createColorLookup() {
        List<Integer> palette = new ArrayList<>();
        for (int packedColor = 4; packedColor <= 255; packedColor++) {
            int argb = MapColor.getColorFromPackedId(packedColor);
            if ((argb >>> 24) != 0) {
                palette.add(packedColor);
            }
        }

        byte[] lookup = new byte[32 * 32 * 32];
        for (int red = 0; red < 32; red++) {
            for (int green = 0; green < 32; green++) {
                for (int blue = 0; blue < 32; blue++) {
                    int r = (red << 3) + 4;
                    int g = (green << 3) + 4;
                    int b = (blue << 3) + 4;
                    long nearestDistance = Long.MAX_VALUE;
                    int nearestColor = 4;
                    for (int packedColor : palette) {
                        int argb = MapColor.getColorFromPackedId(packedColor);
                        int deltaRed = r - ((argb >>> 16) & 0xFF);
                        int deltaGreen = g - ((argb >>> 8) & 0xFF);
                        int deltaBlue = b - (argb & 0xFF);
                        long distance = 2L * deltaRed * deltaRed
                                + 4L * deltaGreen * deltaGreen
                                + 3L * deltaBlue * deltaBlue;
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            nearestColor = packedColor;
                        }
                    }
                    int index = (red << 10) | (green << 5) | blue;
                    lookup[index] = (byte) nearestColor;
                }
            }
        }
        return lookup;
    }

    private static final class ImageLoadException extends RuntimeException {
        private ImageLoadException(Exception cause) {
            super(cause);
        }
    }

    private record ParsedCommand(String url, int width, int height) {
    }

    private record ImageDimensions(int width, int height) {
    }

    private record FrameRectangle(int left, int top, int width, int height) {
    }

    private record AnimationFrame(List<byte[]> tiles, int durationTicks) {
    }

    private record LoadedImage(List<AnimationFrame> frames) {
    }

    private static final class RunningAnimation {
        private final MinecraftServer server;
        private final LoadedImage image;
        private final List<MapItemSavedData> maps;
        private int frameIndex;
        private int ticksUntilNextFrame;

        private RunningAnimation(MinecraftServer server, LoadedImage image,
                                 List<MapItemSavedData> maps, int initialDelay) {
            this.server = server;
            this.image = image;
            this.maps = maps;
            ticksUntilNextFrame = initialDelay;
        }

        private MinecraftServer server() {
            return server;
        }

        private LoadedImage image() {
            return image;
        }

        private List<MapItemSavedData> maps() {
            return maps;
        }
    }
}
