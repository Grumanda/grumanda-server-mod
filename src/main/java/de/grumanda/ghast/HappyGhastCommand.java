package de.grumanda.ghast;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import de.grumanda.GrumandaServerMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class HappyGhastCommand {
	private static final double MIN_SPEED_FACTOR = 0.1;
	private static final double MAX_SPEED_FACTOR = 10.0;
	private static final Identifier SPEED_MODIFIER_ID =
			Identifier.fromNamespaceAndPath(GrumandaServerMod.MOD_ID, "happy_ghast_speed");
	private static final Identifier HAPPY_GHAST_ID = Identifier.withDefaultNamespace("happy_ghast");

	private static volatile double speedFactor = 1.0;
	private static volatile MinecraftServer server;
	private static Path settingsFile;

	private HappyGhastCommand() {
	}

	public static void initialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(HappyGhastCommand::onServerStarted);
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> applySpeedFactor(entity));
	}

	public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("ghast")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("speed")
						.executes(context -> showSpeed(context.getSource()))
						.then(Commands.argument("faktor",
										DoubleArgumentType.doubleArg(MIN_SPEED_FACTOR, MAX_SPEED_FACTOR))
								.executes(context -> setSpeed(
										context.getSource(),
										DoubleArgumentType.getDouble(context, "faktor"))))));
	}

	private static void onServerStarted(MinecraftServer startedServer) {
		server = startedServer;
		settingsFile = startedServer.getServerDirectory()
				.resolve("config")
				.resolve(GrumandaServerMod.MOD_ID)
				.resolve("happy-ghast-speed.json");
		speedFactor = readSpeedFactor();
		applyToLoadedGhasts(startedServer);
	}

	private static int showSpeed(CommandSourceStack source) {
		source.sendSuccess(
				() -> Component.literal("Happy-Ghast-Geschwindigkeit: " + format(speedFactor) + "× Vanilla."),
				false);
		return 1;
	}

	private static int setSpeed(CommandSourceStack source, double newSpeedFactor) {
		Path file = settingsFile;
		if (file == null) {
			source.sendFailure(Component.literal("Die Happy-Ghast-Einstellungen sind noch nicht geladen."));
			return 0;
		}
		try {
			writeSpeedFactor(file, newSpeedFactor);
		} catch (IOException exception) {
			GrumandaServerMod.LOGGER.error("Could not save Happy Ghast speed setting.", exception);
			source.sendFailure(Component.literal("Die Geschwindigkeit konnte nicht gespeichert werden."));
			return 0;
		}

		speedFactor = newSpeedFactor;
		MinecraftServer activeServer = server;
		if (activeServer != null) {
			applyToLoadedGhasts(activeServer);
		}
		source.sendSuccess(
				() -> Component.literal("Happy-Ghast-Geschwindigkeit auf "
						+ format(newSpeedFactor) + "× Vanilla gesetzt (wirkt auch nach Neustarts)."),
				true);
		return 1;
	}

	private static void applySpeedFactor(Entity entity) {
		if (!EntityType.getKey(entity.getType()).equals(HAPPY_GHAST_ID)
				|| !(entity instanceof LivingEntity livingEntity)) {
			return;
		}
		var flyingSpeed = livingEntity.getAttribute(Attributes.FLYING_SPEED);
		if (flyingSpeed != null) {
			flyingSpeed.addOrUpdateTransientModifier(new AttributeModifier(
					SPEED_MODIFIER_ID,
					speedFactor - 1.0,
					AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
		}
	}

	private static void applyToLoadedGhasts(MinecraftServer activeServer) {
		for (ServerLevel level : activeServer.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				applySpeedFactor(entity);
			}
		}
	}

	private static double readSpeedFactor() {
		try {
			if (settingsFile == null || !Files.exists(settingsFile)) {
				return 1.0;
			}
			JsonObject settings = JsonParser.parseString(
					Files.readString(settingsFile, StandardCharsets.UTF_8)).getAsJsonObject();
			double loadedFactor = settings.get("speedFactor").getAsDouble();
			if (Double.isFinite(loadedFactor)
					&& loadedFactor >= MIN_SPEED_FACTOR && loadedFactor <= MAX_SPEED_FACTOR) {
				return loadedFactor;
			}
			throw new IOException("Saved Happy Ghast speed factor is outside the allowed range.");
		} catch (Exception exception) {
			GrumandaServerMod.LOGGER.error("Could not load Happy Ghast speed setting; using vanilla speed.", exception);
			return 1.0;
		}
	}

	private static void writeSpeedFactor(Path file, double newSpeedFactor) throws IOException {
		Files.createDirectories(file.getParent());
		Path temporaryFile = Files.createTempFile(file.getParent(), "happy-ghast-speed-", ".tmp");
		try {
			JsonObject settings = new JsonObject();
			settings.addProperty("speedFactor", newSpeedFactor);
			Files.writeString(temporaryFile, settings.toString(), StandardCharsets.UTF_8);
			try {
				Files.move(temporaryFile, file,
						StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
				Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporaryFile);
		}
	}

	private static String format(double factor) {
		return factor == Math.rint(factor)
				? Long.toString((long) factor)
				: Double.toString(factor);
	}
}
