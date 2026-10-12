package de.grumanda.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.grumanda.GrumandaServerMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutoUpdater {
	private static final URI RELEASE_API = URI.create(
			"https://api.github.com/repos/Grumanda/grumanda-server-mod/releases/latest");
	private static final long CHECK_INTERVAL_HOURS = 6;
	private static final int MAX_JAR_BYTES = 100 * 1024 * 1024;
	private static final int MAX_CHANGELOG_LENGTH = 20_000;
	private static final Pattern VERSION_PATTERN = Pattern.compile(
			"^v?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?$");
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final ScheduledExecutorService EXECUTOR =
			Executors.newSingleThreadScheduledExecutor(task -> {
				Thread thread = new Thread(task, "grumanda-release-updater");
				thread.setDaemon(true);
				return thread;
			});
	private static final AtomicBoolean UPDATE_IN_PROGRESS = new AtomicBoolean();

	private static volatile MinecraftServer server;
	private static volatile Release latestRelease;
	private static Notice pendingNotice;
	private static Path stateFile;

	private AutoUpdater() {
	}

	public static void initialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(AutoUpdater::onServerStarted);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, joinedServer) -> {
			ServerPlayer player = handler.player;
			joinedServer.execute(() -> {
				sendAvailableUpdate(player);
				sendPendingNotice(player);
			});
		});
	}

	public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("grumanda")
				.then(Commands.literal("check")
						.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
						.executes(context -> checkForUpdate(context.getSource())))
				.then(Commands.literal("update")
						.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
						.executes(context -> startUpdate(context.getSource()))));
	}

	private static void onServerStarted(MinecraftServer startedServer) {
		server = startedServer;
		stateFile = startedServer.getServerDirectory()
				.resolve("config")
				.resolve(GrumandaServerMod.MOD_ID)
				.resolve("update-notice.json");
		pendingNotice = readNotice();
		EXECUTOR.execute(AutoUpdater::checkForUpdate);
		EXECUTOR.scheduleAtFixedRate(
				AutoUpdater::checkForUpdate,
				CHECK_INTERVAL_HOURS,
				CHECK_INTERVAL_HOURS,
				TimeUnit.HOURS);
	}

	private static void checkForUpdate() {
		checkForUpdate(null);
	}

	private static int checkForUpdate(CommandSourceStack source) {
		if (source == null) {
			return 1;
		}
		source.sendSuccess(() -> Component.literal("Suche nach einem neuen Grumanda-Mod-Release ..."), false);
		EXECUTOR.execute(() -> {
			CheckResult result = fetchLatestRelease();
			MinecraftServer activeServer = server;
			if (activeServer == null) {
				return;
			}
			activeServer.execute(() -> {
				if (result.error() != null) {
					source.sendFailure(Component.literal("Release-Prüfung fehlgeschlagen: " + result.error()));
				} else if (result.release() == null) {
					source.sendSuccess(() -> Component.literal("Kein neueres Release gefunden."), false);
				} else {
					source.sendSuccess(() -> Component.literal("Neues Release " + result.release().tag()
							+ " gefunden. Verwende /grumanda update oder klicke auf den Update-Button."), false);
				}
			});
		});
		return 1;
	}

	private static CheckResult fetchLatestRelease() {
		try {
			HttpRequest request = HttpRequest.newBuilder(RELEASE_API)
					.timeout(Duration.ofSeconds(20))
					.header("Accept", "application/vnd.github+json")
					.header("User-Agent", GrumandaServerMod.MOD_ID)
					.GET()
					.build();
			HttpResponse<String> response = HTTP.send(
					request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() == 404) {
				GrumandaServerMod.LOGGER.info("No published GitHub release exists yet; auto-updates are idle.");
				latestRelease = null;
				return new CheckResult(null, null);
			}
			if (response.statusCode() != 200) {
				throw new IOException("GitHub release check returned HTTP " + response.statusCode());
			}

			Release release = parseRelease(JsonParser.parseString(response.body()).getAsJsonObject());
			String currentVersion = currentVersion();
			if (compareVersions(release.version(), currentVersion) <= 0) {
				latestRelease = null;
				return new CheckResult(null, null);
			}

			Release previous = latestRelease;
			latestRelease = release;
			if (previous == null || !previous.version().equals(release.version())) {
				MinecraftServer activeServer = server;
				if (activeServer != null) {
					activeServer.execute(() -> notifyOperatorsOfAvailableUpdate(release));
				}
			}
			return new CheckResult(release, null);
		} catch (Exception exception) {
			GrumandaServerMod.LOGGER.error("Could not check for a Grumanda mod release.", exception);
			return new CheckResult(null, safeMessage(exception));
		}
	}

	private static Release parseRelease(JsonObject json) throws IOException {
		String tag = requiredString(json, "tag_name");
		String version = normalizeVersion(tag);

		JsonArray assets = json.getAsJsonArray("assets");
		if (assets == null) {
			throw new IOException("The latest GitHub release has no assets.");
		}
		String expectedName = "grumanda-server-mod-" + version + ".jar";
		JsonObject matchingAsset = null;
		for (JsonElement element : assets) {
			JsonObject asset = element.getAsJsonObject();
			if (expectedName.equals(asset.get("name").getAsString())) {
				matchingAsset = asset;
				break;
			}
		}
		if (matchingAsset == null) {
			throw new IOException("Release " + tag + " is missing " + expectedName + ".");
		}
		URI downloadUri = URI.create(requiredString(matchingAsset, "browser_download_url"));
		if (!"https".equalsIgnoreCase(downloadUri.getScheme())
				|| !"github.com".equalsIgnoreCase(downloadUri.getHost())) {
			throw new IOException("Release asset URL is not a trusted GitHub HTTPS URL.");
		}

		String changelog = json.has("body") && !json.get("body").isJsonNull()
				? json.get("body").getAsString()
				: "Kein Changelog veröffentlicht.";
		if (changelog.length() > MAX_CHANGELOG_LENGTH) {
			changelog = changelog.substring(0, MAX_CHANGELOG_LENGTH);
		}
		String digest = matchingAsset.has("digest") && !matchingAsset.get("digest").isJsonNull()
				? matchingAsset.get("digest").getAsString()
				: "";
		return new Release(version, tag, downloadUri, changelog, digest);
	}

	private static int startUpdate(CommandSourceStack source) {
		Release release = latestRelease;
		if (release == null) {
			source.sendFailure(Component.literal("Es ist kein neueres Release verfügbar."));
			return 0;
		}
		if (!UPDATE_IN_PROGRESS.compareAndSet(false, true)) {
			source.sendFailure(Component.literal("Ein Update läuft bereits."));
			return 0;
		}
		ServerPlayer initiator = source.getPlayer();
		source.sendSuccess(() -> Component.literal("Update auf " + release.tag()
				+ " wird vorbereitet. Der Server startet anschließend neu."), false);
		EXECUTOR.execute(() -> downloadAndInstall(release, initiator));
		return 1;
	}

	private static void downloadAndInstall(Release release, ServerPlayer initiator) {
		Path temporaryJar = null;
		try {
			MinecraftServer activeServer = server;
			if (activeServer == null) {
				throw new IOException("The Minecraft server is not available.");
			}
			Path activeJar = FabricLoader.getInstance().getModContainer(GrumandaServerMod.MOD_ID)
					.orElseThrow(() -> new IOException("The mod container could not be located."))
					.getOrigin().getPaths().stream()
					.filter(Files::isRegularFile)
					.findFirst()
					.orElseThrow(() -> new IOException("The loaded mod JAR could not be located."));
			activeJar = activeJar.toAbsolutePath().normalize();
			Path expectedModsDirectory = activeServer.getServerDirectory()
					.resolve("mods").toAbsolutePath().normalize();
			if (!activeJar.getParent().equals(expectedModsDirectory)
					|| !activeJar.getFileName().toString().endsWith(".jar")) {
				throw new IOException("The loaded mod is not a JAR directly inside the server's mods directory.");
			}

			byte[] jarBytes = downloadJar(release);
			temporaryJar = Files.createTempFile(expectedModsDirectory, "grumanda-update-", ".jar.tmp");
			Files.write(temporaryJar, jarBytes);
			validateJar(temporaryJar, release.version());

			Notice newNotice = new Notice(release.version(), release.tag(), release.changelog(), new HashSet<>());
			writeNotice(newNotice);
			replaceAtomically(temporaryJar, activeJar);
			temporaryJar = null;
			pendingNotice = newNotice;

			MinecraftServer runningServer = server;
			if (runningServer != null) {
				runningServer.execute(() -> notifyOperators(Component.literal(
						"Update heruntergeladen. Der Server wird jetzt über systemctl neu gestartet.")));
			}
			Process restart = new ProcessBuilder("systemctl", "restart", "minecraft")
					.inheritIO()
					.start();
			if (restart.waitFor(15, TimeUnit.SECONDS) && restart.exitValue() != 0) {
				throw new IOException("systemctl restart minecraft exited with code " + restart.exitValue());
			}
		} catch (Exception exception) {
			GrumandaServerMod.LOGGER.error("Could not complete the Grumanda mod update.", exception);
			MinecraftServer activeServer = server;
			if (activeServer != null) {
				String message = "Update fehlgeschlagen: " + safeMessage(exception);
				activeServer.execute(() -> {
					if (initiator != null && initiator.isAlive()) {
						initiator.sendSystemMessage(Component.literal(message));
					}
					notifyOperators(Component.literal(message));
				});
			}
		} finally {
			if (temporaryJar != null) {
				try {
					Files.deleteIfExists(temporaryJar);
				} catch (IOException exception) {
					GrumandaServerMod.LOGGER.warn("Could not remove temporary updater file {}.", temporaryJar, exception);
				}
			}
			UPDATE_IN_PROGRESS.set(false);
		}
	}

	private static byte[] downloadJar(Release release) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(release.downloadUri())
				.timeout(Duration.ofSeconds(90))
				.header("User-Agent", GrumandaServerMod.MOD_ID)
				.GET()
				.build();
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) {
				throw new IOException("GitHub asset download returned HTTP " + response.statusCode());
			}
			byte[] data = body.readNBytes(MAX_JAR_BYTES + 1);
			if (data.length > MAX_JAR_BYTES) {
				throw new IOException("The release JAR exceeds the 100 MB download limit.");
			}
			if (release.digest().startsWith("sha256:")) {
				String actualDigest = "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(data));
				if (!actualDigest.equalsIgnoreCase(release.digest())) {
					throw new IOException("The release asset SHA-256 digest does not match GitHub's metadata.");
				}
			}
			return data;
		}
	}

	private static void validateJar(Path jar, String expectedVersion) throws IOException {
		try (JarFile archive = new JarFile(jar.toFile())) {
			var metadataEntry = archive.getJarEntry("fabric.mod.json");
			if (metadataEntry == null) {
				throw new IOException("The downloaded JAR does not contain fabric.mod.json.");
			}
			try (InputStream input = archive.getInputStream(metadataEntry)) {
				JsonObject metadata = JsonParser.parseString(
						new String(input.readNBytes(64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
				if (!GrumandaServerMod.MOD_ID.equals(requiredString(metadata, "id"))
						|| !expectedVersion.equals(normalizeVersion(requiredString(metadata, "version")))) {
					throw new IOException("The downloaded JAR does not match this mod and release version.");
				}
			}
		} catch (IllegalStateException exception) {
			throw new IOException("The downloaded file is not a valid Fabric mod JAR.", exception);
		}
	}

	private static void replaceAtomically(Path temporaryJar, Path activeJar) throws IOException {
		try {
			Files.move(temporaryJar, activeJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(temporaryJar, activeJar, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void notifyOperatorsOfAvailableUpdate(Release release) {
		notifyOperators(Component.literal("Neues Grumanda-Server-Mod-Release " + release.tag()
				+ " verfügbar. "));
		Component action = Component.literal("[Jetzt aktualisieren]")
				.withStyle(style -> style
						.withColor(ChatFormatting.GREEN)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.RunCommand("/grumanda update")));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isOperator(player)) {
				player.sendSystemMessage(action);
			}
		}
	}

	private static void sendAvailableUpdate(ServerPlayer player) {
		Release release = latestRelease;
		if (release == null || !isOperator(player)) {
			return;
		}
		player.sendSystemMessage(Component.literal("Neues Grumanda-Server-Mod-Release "
				+ release.tag() + " verfügbar."));
		player.sendSystemMessage(Component.literal("[Jetzt aktualisieren]")
				.withStyle(style -> style
						.withColor(ChatFormatting.GREEN)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.RunCommand("/grumanda update"))));
	}

	private static void sendPendingNotice(ServerPlayer player) {
		Notice notice = pendingNotice;
		if (notice == null || !notice.version().equals(currentVersion())
				|| !isOperator(player) || notice.notified().contains(player.getUUID())) {
			return;
		}
		player.sendSystemMessage(Component.literal("Grumanda-Server-Mod wurde auf "
				+ notice.tag() + " aktualisiert."));
		player.sendSystemMessage(Component.literal("Changelog:"));
		for (String line : changelogLines(notice.changelog())) {
			player.sendSystemMessage(Component.literal(line));
		}
		notice.notified().add(player.getUUID());
		try {
			writeNotice(notice);
		} catch (IOException exception) {
			GrumandaServerMod.LOGGER.error("Could not persist the update-notice state.", exception);
		}
	}

	private static List<String> changelogLines(String changelog) {
		List<String> lines = new ArrayList<>();
		for (String rawLine : changelog.split("\\R")) {
			String line = rawLine.trim();
			if (!line.isEmpty()) {
				lines.add(line.length() > 240 ? line.substring(0, 237) + "..." : line);
			}
			if (lines.size() == 20) {
				lines.add("…");
				break;
			}
		}
		if (lines.isEmpty()) {
			lines.add("Kein Changelog veröffentlicht.");
		}
		return lines;
	}

	private static void notifyOperators(Component message) {
		MinecraftServer activeServer = server;
		if (activeServer == null) {
			return;
		}
		for (ServerPlayer player : activeServer.getPlayerList().getPlayers()) {
			if (isOperator(player)) {
				player.sendSystemMessage(message);
			}
		}
	}

	private static boolean isOperator(ServerPlayer player) {
		return Commands.LEVEL_ADMINS.check(player.permissions());
	}

	private static Notice readNotice() {
		try {
			if (stateFile == null || !Files.exists(stateFile)) {
				return null;
			}
			JsonObject json = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
			Set<UUID> notified = new HashSet<>();
			JsonArray ids = json.getAsJsonArray("notified");
			if (ids != null) {
				for (JsonElement id : ids) {
					notified.add(UUID.fromString(id.getAsString()));
				}
			}
			return new Notice(
					requiredString(json, "version"),
					requiredString(json, "tag"),
					json.has("changelog") ? json.get("changelog").getAsString() : "",
					notified);
		} catch (Exception exception) {
			GrumandaServerMod.LOGGER.error("Could not read the pending update notification.", exception);
			return null;
		}
	}

	private static synchronized void writeNotice(Notice notice) throws IOException {
		Files.createDirectories(stateFile.getParent());
		Path temporary = Files.createTempFile(stateFile.getParent(), "update-notice-", ".tmp");
		try {
			JsonObject json = new JsonObject();
			json.addProperty("version", notice.version());
			json.addProperty("tag", notice.tag());
			json.addProperty("changelog", notice.changelog());
			JsonArray notified = new JsonArray();
			for (UUID id : notice.notified()) {
				notified.add(id.toString());
			}
			json.add("notified", notified);
			Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
			replaceAtomically(temporary, stateFile);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String currentVersion() {
		return FabricLoader.getInstance().getModContainer(GrumandaServerMod.MOD_ID)
				.orElseThrow(() -> new IllegalStateException("Mod metadata is unavailable."))
				.getMetadata().getVersion().getFriendlyString();
	}

	private static String normalizeVersion(String version) throws IOException {
		Matcher matcher = VERSION_PATTERN.matcher(version);
		if (!matcher.matches()) {
			throw new IOException("Unsupported release version: " + version);
		}
		return version.startsWith("v") ? version.substring(1) : version;
	}

	private static int compareVersions(String first, String second) throws IOException {
		Matcher firstMatcher = VERSION_PATTERN.matcher(first);
		Matcher secondMatcher = VERSION_PATTERN.matcher(second);
		if (!firstMatcher.matches() || !secondMatcher.matches()) {
			throw new IOException("Release and installed versions must use numeric dotted version tags.");
		}
		String[] firstParts = firstMatcher.group(1).split("\\.");
		String[] secondParts = secondMatcher.group(1).split("\\.");
		int partCount = Math.max(firstParts.length, secondParts.length);
		for (int i = 0; i < partCount; i++) {
			long firstPart = i < firstParts.length ? Long.parseLong(firstParts[i]) : 0;
			long secondPart = i < secondParts.length ? Long.parseLong(secondParts[i]) : 0;
			int result = Long.compare(firstPart, secondPart);
			if (result != 0) {
				return result;
			}
		}
		String firstPreRelease = firstMatcher.group(2);
		String secondPreRelease = secondMatcher.group(2);
		if (firstPreRelease == null) {
			return secondPreRelease == null ? 0 : 1;
		}
		return secondPreRelease == null ? -1
				: firstPreRelease.toLowerCase(Locale.ROOT).compareTo(secondPreRelease.toLowerCase(Locale.ROOT));
	}

	private static String requiredString(JsonObject json, String key) throws IOException {
		if (!json.has(key) || json.get(key).isJsonNull()) {
			throw new IOException("GitHub release metadata is missing '" + key + "'.");
		}
		return json.get(key).getAsString();
	}

	private static String hex(byte[] bytes) {
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			result.append(String.format("%02x", value));
		}
		return result.toString();
	}

	private static String safeMessage(Exception exception) {
		String message = exception.getMessage();
		return message == null ? exception.getClass().getSimpleName() : message;
	}

	private record Release(String version, String tag, URI downloadUri, String changelog, String digest) {
	}

	private record CheckResult(Release release, String error) {
	}

	private record Notice(String version, String tag, String changelog, Set<UUID> notified) {
	}
}
