package de.grumanda;

import net.fabricmc.api.ModInitializer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import de.grumanda.database.Coords;
import de.grumanda.database.DatabaseManager;
import de.grumanda.database.User;
import de.grumanda.images.ImageMapCommand;
import de.grumanda.update.AutoUpdater;
import de.grumanda.ghast.HappyGhastCommand;

import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrumandaServerMod implements ModInitializer {
	public static final String MOD_ID = "grumanda-server-mod";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		AutoUpdater.initialize();
		HappyGhastCommand.initialize();

		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
				ImageMapCommand.register(dispatcher);
				AutoUpdater.register(dispatcher);
				HappyGhastCommand.register(dispatcher);

				dispatcher.register(Commands.literal("post")
						.then(Commands.argument("location", StringArgumentType.greedyString())
								.executes(context -> {
									var player = context.getSource().getPlayerOrException();
									String locationName = StringArgumentType.getString(context, "location").trim();
									if (locationName.isEmpty()) {
										context.getSource().sendFailure(Component.literal("Der Location-Name darf nicht leer sein."));
										return 0;
									}

									String uuid = player.getUUID().toString();
									User user = new User(uuid, player.getName().getString());
									Coords coords = new Coords(
											uuid,
											locationName,
											player.getBlockX(),
											player.getBlockY(),
											player.getBlockZ(),
											player.level().dimension().identifier().toString(),
											false,
											"PENDING"
									);
									try {
										DatabaseManager.getInstance().createCoordsEntry(user, coords);
									} catch (SQLException e) {
										LOGGER.error("Could not save location '{}' for player {}", locationName, user.getName(), e);
										context.getSource().sendFailure(Component.literal("Die Location konnte nicht gespeichert werden."));
										return 0;
									}
									context.getSource().sendSuccess(
											() -> Component.literal("Location '" + locationName + "' wurde gespeichert."),
											false
									);
									return 1;
								})));

				dispatcher.register(Commands.literal("lichtblock")
						.executes(context -> giveLightBlocks(context.getSource().getPlayerOrException(), 1, context.getSource()))
						.then(Commands.argument("anzahl", IntegerArgumentType.integer(1, 64))
								.executes(context -> giveLightBlocks(
										context.getSource().getPlayerOrException(),
										IntegerArgumentType.getInteger(context, "anzahl"),
										context.getSource()
								))));
		});
	}

	private static int giveLightBlocks(ServerPlayer player, int amount, CommandSourceStack source) {
		player.getInventory().add(new ItemStack(Items.LIGHT, amount));
		source.sendSuccess(() -> Component.literal(amount + " Lichtblock" + (amount == 1 ? "" : "e") + " erhalten."), false);
		return 1;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
