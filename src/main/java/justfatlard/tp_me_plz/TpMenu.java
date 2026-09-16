package justfatlard.tp_me_plz;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentDef;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The menu, and the picture picker a new place is saved from.
 *
 * <p>Both are dialogs: a panel the colour of the inventory behind everything, dark words on it, the
 * way the game's own screens are, so nothing is read off the world behind.
 *
 * <p>Pictures first, for somebody still learning to read. Every place is a tile with a big item on
 * it and its name underneath - a bed for home, a compass for spawn, a skull for the last death, an
 * amethyst cluster for the geode, and whatever picture a player chose for each place of their own -
 * and every player is their face. Yes to a request is a green button with the asker's face on it,
 * no a barrier beside it, and a player already asked shows a clock.
 *
 * <p>Kept current while it is open: a request arriving, lapsing or being answered, or a player
 * coming or going, builds it again, so a list on screen is never somebody who has left.
 */
public final class TpMenu {
	private TpMenu() {}

	public static final String TYPE = "tp-me-plz:menu";
	public static final String PICKER = "tp-me-plz:picture";

	private static final boolean DEAD_HEADS = FabricLoader.getInstance().isModLoaded("dead-heads");
	private static final boolean GEODES = FabricLoader.getInstance().isModLoaded("amethyst-door-justfatlard");

	private static final int WIDTH = 188;
	private static final int PAD = 8;
	private static final int TOP = 20;
	private static final int TILE = 40;
	private static final int COLUMNS = 3;
	private static final int BUTTON = 24;
	private static final int ROW = 26;
	private static final int GAP = 4;
	private static final int FACE = 16;
	private static final int ICON = 16;
	/** How much bigger a tile's item is drawn than an item in a slot. */
	private static final float TILE_ICON_SCALE = 1.5F;
	/** Rows of players shown before the list scrolls. */
	private static final int PLAYER_ROWS = 5;
	/** The game's own colour for words on a panel. */
	private static final String INK = "#FF404040";
	/** Letters that fit under a tile's picture. */
	private static final int TILE_LETTERS = 8;

	/**
	 * Pictures for a new place, chosen to be told apart at a glance and to stand for the places
	 * people make: a door for a house, a sapling for a tree farm, a pickaxe for a mine, a boat for
	 * the water, a bell for a village. Whatever is in the player's hand comes first, when it is not
	 * already here, for the place that is its own thing.
	 */
	private static final List<String> PICTURES = List.of(
		"minecraft:oak_door", "minecraft:oak_sapling", "minecraft:poppy", "minecraft:wheat",
		"minecraft:iron_pickaxe", "minecraft:diamond", "minecraft:chest", "minecraft:crafting_table",
		"minecraft:cod", "minecraft:oak_boat", "minecraft:water_bucket", "minecraft:lava_bucket",
		"minecraft:snowball", "minecraft:cactus", "minecraft:sunflower", "minecraft:pumpkin",
		"minecraft:cake", "minecraft:apple", "minecraft:emerald", "minecraft:bell",
		"minecraft:rail", "minecraft:torch", "minecraft:gold_ingot", "minecraft:nether_star");
	private static final int PICTURE_COLUMNS = 6;
	private static final int PICTURE = 24;

	/** Whose menu is open, by the screen showing it, so a change can rebuild it for them. */
	private static final Map<UUID, String> open = new ConcurrentHashMap<>();
	/** A place waiting on "are you sure", by player. */
	private static final Map<UUID, Integer> deleting = new ConcurrentHashMap<>();
	/** Where a player stood when they asked for a new place, until they pick its picture. */
	private static final Map<UUID, Places.Spot> newPlace = new ConcurrentHashMap<>();
	/** What they have typed in the picker's name box, which the box sends as it changes. */
	private static final Map<UUID, String> typed = new ConcurrentHashMap<>();

	public static void register() {
		ScreenApi screens = PandoricalApi.screens();
		screens.onActionFallback(TYPE, TpMenu::pressed);
		screens.onClose(TYPE, player -> {
			open.remove(player.getUUID());
			deleting.remove(player.getUUID());
		});
		screens.onActionFallback(PICKER, TpMenu::picked);
		screens.onClose(PICKER, player -> {
			newPlace.remove(player.getUUID());
			typed.remove(player.getUUID());
		});
	}

	/** The key, or /tpme: the menu on a Pandorical client, the same choices in chat on any other. */
	public static void openFor(ServerPlayer player) {
		if (!Access.allowed(player)) {
			refuse(player);
			return;
		}
		if (!PandoricalApi.isAvailable(player)) {
			inChat(player);
			return;
		}
		show(player);
	}

	public static void refuse(ServerPlayer player) {
		Trips.say(player, "Teleporting has not been given to you. An op can, with /tpme grant");
	}

	/** Build it again for this player, if it is what they have open. */
	public static void refresh(ServerPlayer player) {
		String screenId = open.get(player.getUUID());
		if (screenId == null || !screenId.equals(PandoricalApi.getOpenScreenId(player.getUUID()))) {
			open.remove(player.getUUID());
			return;
		}
		if (!Access.allowed(player)) {
			PandoricalApi.screens().close(player, screenId);
			open.remove(player.getUUID());
			return;
		}
		show(player);
	}

	public static void refreshAll(MinecraftServer server) {
		for (UUID uuid : List.copyOf(open.keySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) refresh(player);
		}
	}

	public static void forget(UUID player) {
		open.remove(player);
		deleting.remove(player);
		newPlace.remove(player);
		typed.remove(player);
	}

	// --- The menu ---

	private record Tile(String id, String item, String word, boolean deletable) {}

	private static void show(ServerPlayer player) {
		List<ServerPlayer> others = new ArrayList<>(player.level().getServer().getPlayerList().getPlayers());
		others.remove(player);
		others.sort(java.util.Comparator.comparing(p -> p.getGameProfile().name().toLowerCase(java.util.Locale.ROOT)));
		List<ServerPlayer> askers = Requests.askingOf(player);
		others.removeAll(askers);
		List<Places.Place> places = Places.of(player);

		// Drawn in the order added: the dialog, then buttons and words, then the pictures laid on
		// the buttons. The dialog's height is only known at the end, so the rest waits in these.
		List<ComponentBuilder> under = new ArrayList<>();
		List<ComponentBuilder> over = new ArrayList<>();
		int inner = WIDTH - PAD * 2;
		int y = TOP;

		// The tiles: the fixed places, then the player's own, then the one that adds another.
		List<Tile> tiles = new ArrayList<>();
		tiles.add(new Tile("home", "minecraft:red_bed", "Home", false));
		tiles.add(new Tile("spawn", "minecraft:compass", "Spawn", false));
		if (DEAD_HEADS && justfatlard.tp_me_plz.integration.DeathTrip.has(player)) {
			tiles.add(new Tile("death", "minecraft:skeleton_skull", "Death", false));
		}
		if (GEODES) tiles.add(new Tile("geode", "minecraft:amethyst_cluster", "Geode", false));
		for (int i = 0; i < places.size(); i++) {
			tiles.add(new Tile("place:" + i, places.get(i).icon(), places.get(i).name(), true));
		}
		if (!Places.full(player)) tiles.add(new Tile("new", "minecraft:writable_book", "New", false));

		int tileW = (inner - GAP * (COLUMNS - 1)) / COLUMNS;
		for (int i = 0; i < tiles.size(); i++) {
			Tile tile = tiles.get(i);
			int x = PAD + (i % COLUMNS) * (tileW + GAP);
			int ty = y + (i / COLUMNS) * (TILE + GAP);
			tile(under, over, tile.id(), x, ty, tileW, tile.item(), tile.word());
			if (tile.deletable()) {
				// Small, and in a corner, on purpose: deleting is the one thing here that loses
				// something, and it asks first.
				String index = tile.id().substring("place:".length());
				under.add(button("delete:" + index, x + tileW - 12, ty + 1, 11, 11,
					Map.of(ComponentType.PROP_LABEL, "✕", ComponentType.PROP_TOOLTIP, "Delete " + tile.word())));
			}
		}
		y += ((tiles.size() + COLUMNS - 1) / COLUMNS) * (TILE + GAP) + 2;

		// Are you sure?
		Integer doomed = deleting.get(player.getUUID());
		if (doomed != null && doomed < places.size()) {
			Places.Place place = places.get(doomed);
			over.add(icon("doomed_icon", place.icon(), PAD, y + 4, 1F));
			under.add(text("doomed_text", PAD + ICON + 4, y + 8, "Delete " + Places.clip(place.name(), 16) + "?"));
			y += BUTTON + 2;
			int half = (inner - GAP) / 2;
			under.add(button("delete_yes", PAD, y, half, BUTTON, Map.of(ComponentType.PROP_LABEL, "Delete",
				ComponentType.PROP_TOOLTIP, "Delete " + place.name())));
			over.add(icon("delete_yes_icon", "minecraft:barrier", PAD + 4, y + (BUTTON - ICON) / 2, 1F));
			under.add(button("delete_no", PAD + half + GAP, y, inner - half - GAP, BUTTON, Map.of(ComponentType.PROP_LABEL, "Keep",
				ComponentType.PROP_STYLE, "accepted")));
			y += ROW + 2;
		} else {
			deleting.remove(player.getUUID());
		}

		// Whoever is asking to come here, to say yes or no to.
		if (!askers.isEmpty()) {
			heading(under, over, "asking", y, "minecraft:bell", "Wants to come to you");
			y += 20;
			for (ServerPlayer asker : askers) {
				String id = asker.getUUID().toString();
				String name = asker.getGameProfile().name();
				under.add(button("accept:" + id, PAD, y, inner - BUTTON - GAP, BUTTON, Map.of(
					ComponentType.PROP_LABEL, name,
					ComponentType.PROP_TOOLTIP, "Bring " + name + " here",
					ComponentType.PROP_STYLE, "accepted")));
				over.add(face("accept_face:" + id, asker, PAD + 4, y + (BUTTON - FACE) / 2));
				under.add(button("deny:" + id, PAD + inner - BUTTON, y, BUTTON, BUTTON,
					Map.of(ComponentType.PROP_TOOLTIP, "Say no")));
				over.add(icon("deny_icon:" + id, "minecraft:barrier", PAD + inner - BUTTON + (BUTTON - ICON) / 2,
					y + (BUTTON - ICON) / 2, 1F));
				y += ROW;
			}
			y += 2;
		}

		// Everyone else: a press asks to go to them.
		heading(under, over, "players", y, "minecraft:ender_pearl", "Go to a player");
		y += 20;
		List<ComponentDef> rows = new ArrayList<>();
		if (others.isEmpty()) {
			under.add(text("nobody", PAD, y + 4, "Nobody else is on right now"));
			y += ROW;
		} else {
			int rowY = 0;
			for (ServerPlayer other : others) {
				String id = other.getUUID().toString();
				String name = other.getGameProfile().name();
				boolean asked = Requests.asking(player, other.getUUID());
				rows.add(new ComponentBuilder("ask:" + id, ComponentType.BUTTON)
					.bounds(0, rowY, inner - 6, BUTTON)
					.prop(ComponentType.PROP_LABEL, name)
					.prop(ComponentType.PROP_TOOLTIP, asked ? "Waiting for " + name + " to say yes" : "Ask to go to " + name)
					.prop(ComponentType.PROP_ENABLED, String.valueOf(!asked))
					.build());
				rows.add(face("ask_face:" + id, other, 4, rowY + (BUTTON - FACE) / 2).build());
				// A clock on a player already asked: waiting, in a picture.
				if (asked) rows.add(icon("ask_wait:" + id, "minecraft:clock", inner - 6 - ICON - 4, rowY + (BUTTON - ICON) / 2, 1F).build());
				rowY += ROW;
			}
		}
		int shown = Math.min(others.size(), PLAYER_ROWS);
		int listY = y;
		if (shown > 0) y += shown * ROW;

		int height = y + PAD - 2;
		ScreenBuilder screen = new ScreenBuilder(TYPE).title("Teleport").pauseGame(false).size(WIDTH, height);
		screen.panel("dialog", 0, 0, WIDTH, height, Map.of(ComponentType.PROP_BORDER, "beveled"));
		screen.component(text("title", PAD, 7, "Teleport"));
		for (ComponentBuilder component : under) screen.component(component);
		for (ComponentBuilder component : over) screen.component(component);
		if (shown > 0) {
			screen.scrollPanel("players", PAD, listY, inner, shown * ROW, Map.of(
				"item_height", String.valueOf(ROW),
				"visible_items", String.valueOf(shown),
				"total_items", String.valueOf(others.size()),
				"show_scrollbar", String.valueOf(others.size() > PLAYER_ROWS),
				ComponentType.PROP_BACKGROUND, "#00000000"), rows);
		}

		PandoricalApi.screens().open(player, screen.build());
		String screenId = PandoricalApi.getOpenScreenId(player.getUUID());
		if (screenId != null) open.put(player.getUUID(), screenId);
	}

	/**
	 * A place: a button with a big item on it and its name underneath. The name is on the tooltip
	 * too, whole, since a tile only has room for the start of it. The button goes on the screen
	 * now and the picture and name in {@code over}, which is laid on after the dialog, so they are
	 * drawn over the button rather than under it.
	 */
	private static void tile(List<ComponentBuilder> under, List<ComponentBuilder> over, String id, int x, int y, int w,
			String item, String word) {
		under.add(button(id, x, y, w, TILE, Map.of(ComponentType.PROP_TOOLTIP, word)));
		over.add(icon(id + "_icon", item, x + (w - ICON) / 2, y + 7, TILE_ICON_SCALE));
		over.add(new ComponentBuilder(id + "_word", ComponentType.TEXT)
			.bounds(x, y + TILE - 11, w, 10)
			.prop(ComponentType.PROP_TEXT, Places.clip(word, TILE_LETTERS))
			.prop(ComponentType.PROP_ALIGN, "center")
			.prop(ComponentType.PROP_SHADOW, "true"));
	}

	/** A section's name, after a picture of what the section is for. */
	private static void heading(List<ComponentBuilder> under, List<ComponentBuilder> over, String id, int y, String item, String words) {
		over.add(icon(id + "_heading_icon", item, PAD, y, 1F));
		under.add(text(id + "_heading", PAD + ICON + 4, y + 4, words));
	}

	private static ComponentBuilder button(String id, int x, int y, int w, int h, Map<String, String> props) {
		return new ComponentBuilder(id, ComponentType.BUTTON).bounds(x, y, w, h).props(props);
	}

	/** Words on the dialog, in the game's own dark grey. */
	private static ComponentBuilder text(String id, int x, int y, String words) {
		return new ComponentBuilder(id, ComponentType.TEXT).pos(x, y)
			.prop(ComponentType.PROP_TEXT, words)
			.prop(ComponentType.PROP_COLOR, INK);
	}

	private static ComponentBuilder icon(String id, String item, int x, int y, float scale) {
		return new ComponentBuilder(id, ComponentType.ITEM_ICON)
			.bounds(x, y, ICON, ICON)
			.scale(scale)
			.prop(ComponentType.PROP_ITEM_ID, item);
	}

	private static ComponentBuilder face(String id, ServerPlayer who, int x, int y) {
		return new ComponentBuilder(id, ComponentType.PLAYER_FACE)
			.bounds(x, y, FACE, FACE)
			.prop(ComponentType.PROP_PLAYER, who.getUUID().toString());
	}

	private static void pressed(ServerPlayer player, Map<String, String> data) {
		String id = data.get(ScreenApi.FALLBACK_COMPONENT_ID_KEY);
		if (id == null || !Access.allowed(player) && !id.startsWith("accept:") && !id.startsWith("deny:")) return;

		switch (id) {
			case "home" -> leave(player, Places::home);
			case "spawn" -> leave(player, Trips::spawn);
			case "death" -> leave(player, TpMenu::lastDeath);
			case "geode" -> leave(player, TpMenu::geode);
			case "new" -> pickPicture(player);
			case "delete_yes" -> {
				Integer index = deleting.remove(player.getUUID());
				if (index != null) Places.delete(player, index);
				show(player);
			}
			case "delete_no" -> {
				deleting.remove(player.getUUID());
				show(player);
			}
			default -> {
				int colon = id.indexOf(':');
				if (colon < 0) return;
				String kind = id.substring(0, colon);
				String rest = id.substring(colon + 1);
				switch (kind) {
					case "place" -> {
						int index = parse(rest);
						leave(player, p -> Places.go(p, index));
					}
					case "delete" -> {
						deleting.put(player.getUUID(), parse(rest));
						show(player);
					}
					case "ask", "accept", "deny" -> {
						ServerPlayer other = playerOf(player, rest);
						if (other == null) {
							show(player);
							return;
						}
						switch (kind) {
							// Asking closes the menu: the answer is the other player's to give, and
							// "asked" said in a menu left standing read as a button that did nothing.
							case "ask" -> leave(player, p -> Requests.ask(p, other));
							case "accept" -> Requests.accept(player, other);
							default -> Requests.deny(player, other);
						}
					}
					default -> { }
				}
			}
		}
	}

	private static int parse(String number) {
		try {
			return Integer.parseInt(number);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** A trip closes the menu first: it is done with, and a screen over an arrival hides it. */
	private static void leave(ServerPlayer player, java.util.function.Consumer<ServerPlayer> trip) {
		String screenId = open.remove(player.getUUID());
		if (screenId != null) PandoricalApi.screens().close(player, screenId);
		trip.accept(player);
	}

	public static void lastDeath(ServerPlayer player) {
		if (!DEAD_HEADS) {
			Trips.say(player, "Last death needs Dead Heads on the server");
			return;
		}
		justfatlard.tp_me_plz.integration.DeathTrip.go(player);
	}

	public static void geode(ServerPlayer player) {
		if (!GEODES) {
			Trips.say(player, "Geodes need Amethyst Door on the server");
			return;
		}
		justfatlard.tp_me_plz.integration.GeodeTrip.go(player);
	}

	private static ServerPlayer playerOf(ServerPlayer player, String uuid) {
		try {
			return player.level().getServer().getPlayerList().getPlayer(UUID.fromString(uuid));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	// --- The picture picker ---

	/**
	 * Where the new place is, fixed now: the spot they pressed New place on, not wherever they
	 * have wandered by the time they choose a picture. Then a grid of pictures, the held item
	 * first, and a name box for whoever wants one; tapping a picture saves the place.
	 */
	private static void pickPicture(ServerPlayer player) {
		if (Places.full(player)) {
			show(player);
			return;
		}
		open.remove(player.getUUID());
		newPlace.put(player.getUUID(), Places.Spot.of(player));
		typed.remove(player.getUUID());

		Set<String> pictures = new LinkedHashSet<>();
		ItemStack held = player.getMainHandItem();
		if (!held.isEmpty()) pictures.add(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
		pictures.addAll(PICTURES);
		List<String> ordered = new ArrayList<>(pictures);
		if (ordered.size() > PICTURES.size()) ordered = ordered.subList(0, PICTURES.size());

		int inner = PICTURE_COLUMNS * PICTURE + (PICTURE_COLUMNS - 1) * GAP;
		int width = inner + PAD * 2;
		int y = TOP;
		List<ComponentBuilder> under = new ArrayList<>();
		List<ComponentBuilder> over = new ArrayList<>();

		over.add(icon("hint_icon", "minecraft:writable_book", PAD, y, 1F));
		under.add(text("hint", PAD + ICON + 4, y + 4, "Pick a picture for this place"));
		y += 20;
		under.add(new ComponentBuilder("name", ComponentType.TEXT_INPUT)
			.bounds(PAD, y, inner, 16)
			.prop(ComponentType.PROP_PLACEHOLDER, "Name it (or skip this)")
			.prop(ComponentType.PROP_MAX_LENGTH, "32"));
		y += 22;

		for (int i = 0; i < ordered.size(); i++) {
			String item = ordered.get(i);
			int x = PAD + (i % PICTURE_COLUMNS) * (PICTURE + GAP);
			int py = y + (i / PICTURE_COLUMNS) * (PICTURE + GAP);
			under.add(button("pic:" + i, x, py, PICTURE, PICTURE, Map.of(ComponentType.PROP_TOOLTIP, Places.itemName(item))));
			over.add(icon("pic_icon:" + i, item, x + (PICTURE - ICON) / 2, py + (PICTURE - ICON) / 2, 1F));
		}
		y += ((ordered.size() + PICTURE_COLUMNS - 1) / PICTURE_COLUMNS) * (PICTURE + GAP) + 2;

		int height = y + PAD - 4;
		ScreenBuilder screen = new ScreenBuilder(PICKER).title("New place").pauseGame(false).size(width, height);
		screen.panel("dialog", 0, 0, width, height, Map.of(ComponentType.PROP_BORDER, "beveled"));
		screen.component(text("title", PAD, 7, "New place"));
		for (ComponentBuilder component : under) screen.component(component);
		for (ComponentBuilder component : over) screen.component(component);
		pickerPictures.put(player.getUUID(), List.copyOf(ordered));
		PandoricalApi.screens().open(player, screen.build());
	}

	/** The pictures the picker showed each player, in order, so a press can be read back to one. */
	private static final Map<UUID, List<String>> pickerPictures = new ConcurrentHashMap<>();

	private static void picked(ServerPlayer player, Map<String, String> data) {
		String id = data.get(ScreenApi.FALLBACK_COMPONENT_ID_KEY);
		if (id == null) return;
		if (id.equals("name")) {
			String text = data.getOrDefault("text", "");
			typed.put(player.getUUID(), text.length() > 32 ? text.substring(0, 32) : text);
			return;
		}
		if (!id.startsWith("pic:") || !Access.allowed(player)) return;
		List<String> pictures = pickerPictures.get(player.getUUID());
		Places.Spot spot = newPlace.remove(player.getUUID());
		int index = parse(id.substring(4));
		if (pictures == null || spot == null || index < 0 || index >= pictures.size()) return;
		Places.add(player, spot, pictures.get(index), typed.remove(player.getUUID()));
		pickerPictures.remove(player.getUUID());
		show(player);
	}

	// --- Without Pandorical ---

	/**
	 * The same choices as clickable words, for a player on a client without Pandorical, where
	 * there is no menu to open.
	 */
	private static void inChat(ServerPlayer player) {
		MutableComponent line = Component.literal("Teleport: ").withStyle(ChatFormatting.LIGHT_PURPLE);
		line.append(choice("Home", "/tpme home")).append(" ").append(choice("Spawn", "/tpme spawn"));
		if (DEAD_HEADS && justfatlard.tp_me_plz.integration.DeathTrip.has(player)) line.append(" ").append(choice("Last death", "/tpme death"));
		if (GEODES) line.append(" ").append(choice("Geode", "/tpme geode"));
		for (Places.Place place : Places.of(player)) line.append(" ").append(choice(place.name(), "/tpme place " + place.name()));
		player.sendSystemMessage(line);

		List<ServerPlayer> others = new ArrayList<>(player.level().getServer().getPlayerList().getPlayers());
		others.remove(player);
		if (others.isEmpty()) return;
		MutableComponent people = Component.literal("Go to: ").withStyle(ChatFormatting.LIGHT_PURPLE);
		for (int i = 0; i < others.size(); i++) {
			if (i > 0) people.append(" ");
			String name = others.get(i).getGameProfile().name();
			people.append(choice(name, "/tpme ask " + name));
		}
		player.sendSystemMessage(people);
	}

	private static Component choice(String label, String command) {
		return Component.literal("[" + label + "]").withStyle(style -> style
			.withColor(ChatFormatting.AQUA)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}
}
