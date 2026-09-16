package justfatlard.tp_me_plz;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.loader.api.FabricLoader;

/** The file's knobs, and the same knobs on the mod's page of the mod menu for ops. */
public final class TpConfig {
	private TpConfig() {}

	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(Main.MOD_ID + ".properties");

	public enum Mode {
		/** Only players an op has given it to, with /tpme grant. */
		GRANTED,
		/** Every player. */
		EVERYONE
	}

	private static Mode access = Mode.GRANTED;
	private static int requestSeconds = 60;
	private static int maxPlaces = 9;

	private static final String DEFAULT_CONFIG = """
			# TP Me Plz Configuration
			# Delete this file to regenerate with defaults.

			# Who has the teleport menu:
			#   granted  - only players an op has given it to with /tpme grant <player>. (default)
			#              Ops always have it.
			#   everyone - every player on the server.
			access=granted

			# How long a request to go to another player stands before it lapses, in seconds.
			request_seconds=60

			# How many places of their own each player can save, besides home and spawn.
			max_places=9
			""";

	public static void load() {
		try {
			if (!Files.exists(CONFIG_PATH)) {
				Files.createDirectories(CONFIG_PATH.getParent());
				Files.writeString(CONFIG_PATH, DEFAULT_CONFIG);
			}
			Properties props = new Properties();
			try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
				props.load(reader);
			}
			String mode = props.getProperty("access", "granted").trim().toUpperCase(java.util.Locale.ROOT);
			try {
				access = Mode.valueOf(mode);
			} catch (IllegalArgumentException e) {
				Main.LOGGER.warn("[{}] access '{}' is not granted or everyone; keeping it to granted", Main.MOD_ID, mode);
				access = Mode.GRANTED;
			}
			try {
				maxPlaces = Math.clamp(Integer.parseInt(props.getProperty("max_places", "9").trim()), 0, 30);
			} catch (NumberFormatException e) {
				Main.LOGGER.warn("[{}] max_places is not a number, using 9", Main.MOD_ID);
				maxPlaces = 9;
			}
			try {
				requestSeconds = Math.clamp(Integer.parseInt(props.getProperty("request_seconds", "60").trim()), 10, 600);
			} catch (NumberFormatException e) {
				Main.LOGGER.warn("[{}] request_seconds is not a number, using 60", Main.MOD_ID);
				requestSeconds = 60;
			}
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not read config, using defaults: {}", Main.MOD_ID, e.toString());
		}
	}

	public static boolean everyone() {
		return access == Mode.EVERYONE;
	}

	public static int requestSeconds() {
		return requestSeconds;
	}

	public static int maxPlaces() {
		return maxPlaces;
	}

	public static void setMaxPlaces(int places) {
		maxPlaces = places;
		store("max_places", String.valueOf(places));
	}

	public static void setAccess(Mode mode) {
		access = mode;
		store("access", mode.name().toLowerCase(java.util.Locale.ROOT));
	}

	public static void setRequestSeconds(int seconds) {
		requestSeconds = seconds;
		store("request_seconds", String.valueOf(seconds));
	}

	/** One value back into the file, keeping its comments and order. */
	private static void store(String key, String value) {
		try {
			List<String> lines = Files.exists(CONFIG_PATH) ? new ArrayList<>(Files.readAllLines(CONFIG_PATH)) : new ArrayList<>();
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).trim();
				if (line.startsWith(key + "=") || line.startsWith(key + " =")) {
					lines.set(i, key + "=" + value);
					found = true;
				}
			}
			if (!found) lines.add(key + "=" + value);
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.write(CONFIG_PATH, lines);
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not write config: {}", Main.MOD_ID, e.toString());
		}
	}

	public static void menu() {
		var group = PandoricalApi.settings().serverGroup(Main.MOD_ID, "TP Me Plz");
		Map<String, String> modes = new LinkedHashMap<>();
		modes.put("granted", "Given by command");
		modes.put("everyone", "Everyone");
		group.choice("access", "Who has it", modes, "granted")
			.describe("Given by command: only players an op gives it to with /tpme grant")
			.backedBy(player -> access.name().toLowerCase(java.util.Locale.ROOT),
				(player, value) -> {
					setAccess(Mode.valueOf(value.toUpperCase(java.util.Locale.ROOT)));
					TpMenu.refreshAll(player.level().getServer());
				});
		group.number("requestSeconds", "Requests last, seconds", 10, 600, 10, 60)
			.backedBy(player -> requestSeconds, (player, value) -> setRequestSeconds(value));
		group.number("maxPlaces", "Places each player can save", 0, 30, 1, 9)
			.backedBy(player -> maxPlaces, (player, value) -> setMaxPlaces(value));
	}
}
