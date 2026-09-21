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
import justfatlard.pandorical.api.Viewport;
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
 * way the game's own screens are, so nothing is read off the world behind. The menu is cut to the
 * window it is opened in - two columns given the room, one when not - and both of its lists sit in
 * wells that scroll, so a full shelf of places and a full server of players fill it rather than
 * running off the bottom of the screen.
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

	private static final int PAD = 8;
	private static final int TOP = 20;
	private static final int TILE = 40;
	private static final int BUTTON = 24;
	private static final int ROW = 26;
	private static final int GAP = 4;
	private static final int FACE = 16;
	private static final int ICON = 16;
	/** A heading and the gap under it. */
	private static final int HEADING = 20;
	/** How far a well's border insets what sits in it, on every side. */
	private static final int WELL = 4;
	/** What a scroll panel keeps down its right for its scrollbar. */
	private static final int BAR = 6;
	/** Narrowest a tile may be drawn and still have a name under it worth reading. */
	private static final int LEAST_TILE = 50;
	/** How much bigger a tile's item is drawn than an item in a slot. */
	private static final float TILE_ICON_SCALE = 1.5F;
	/** The game's own colour for words on a panel. */
	private static final String INK = "#FF404040";

	/** A list sits in a well: the dialog's own bevel, lit from the other side so it reads sunken. */
	private static final Map<String, String> WELL_STYLE = Map.of(
		ComponentType.PROP_BORDER, "beveled",
		ComponentType.PROP_BORDER_LIGHT, "#FF373737",
		ComponentType.PROP_BORDER_DARK, "#FFFFFFFF",
		ComponentType.PROP_BORDER_MID_LIGHT, "#FF8B8B8B",
		ComponentType.PROP_BORDER_MID_DARK, "#FFC6C6C6",
		ComponentType.PROP_BACKGROUND, "#FF8B8B8B");

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

	/**
	 * The dialog, sized to what is in it and then cut to the window it will be shown in, which the
	 * client reports and Pandorical keeps.
	 *
	 * <p>Content first, window second: a dialog as tall as the window with one row of tiles in it
	 * is a screen of empty grey, which is the same waste as one that runs off the bottom. So each
	 * list asks for the room its rows need, the dialog is the sum of that, and only where the sum
	 * will not fit does anything scroll - the two lists then splitting what there is between them
	 * in proportion to what they wanted.
	 *
	 * <p>Given the width it stands the places beside the people; given a narrow window, or nobody
	 * else on the server to stand there, it puts them one above the other.
	 */
	private record Layout(int width, int height,
			int placesX, int placesY, int placesW, int placesH, int columns, int tileW,
			int peopleX, int peopleW,
			int askersY, int askersH, int playersY, int playersH) {

		/** Clear of the window's edge, so the dialog never looks wedged into it. */
		private static final int MARGIN = 10;
		private static final int LEAST_W = 188;
		private static final int MOST_W = 420;
		private static final int LEAST_H = 120;
		private static final int MOST_H = 330;
		/** Wide enough to stand the two lists side by side and still hold three tiles across. */
		private static final int SIDE_BY_SIDE = 310;
		private static final int GUTTER = 10;
		/** The width a column of names wants, where there is room to give it. */
		private static final int NAMES_W = 150;
		/** Tiles past this many are another row: a row of seven reads as a strip, not a grid. */
		private static final int MOST_COLUMNS = 5;
		/** The width a tile is drawn at where nothing squeezes it. */
		private static final int TILE_W = 54;
		/** A well with one name in it: the least a list of people is ever cut to. */
		private static final int ONE_ROW = ROW + WELL * 2;
		/** The same for the grid, whose rows carry the gap under them as well as between. */
		private static final int ONE_TILE_ROW = TILE + GAP + WELL * 2;

		static Layout fit(Viewport view, int tiles, int askers, int others) {
			// What it would like to be: a grid of tiles at their proper width, and beside it a
			// column wide enough for a name, where there is anybody to name.
			boolean people = askers + others > 0;
			int columns = Math.clamp(tiles, 2, MOST_COLUMNS);
			int wants = PAD * 2 + columns * TILE_W + (columns - 1) * GAP + WELL * 2 + BAR
				+ (people ? GUTTER + NAMES_W : 0);
			int width = Math.clamp(wants, LEAST_W, Math.clamp(view.width() - 2 * MARGIN, LEAST_W, MOST_W));
			int inner = width - PAD * 2;

			// And what it was granted, shared out. A tile narrower than LEAST_TILE has no room for
			// a name, so past that point the grid drops a column rather than shrinking further.
			boolean twoPane = people && width >= SIDE_BY_SIDE;
			int placesW = inner;
			int peopleW = inner;
			int peopleX = PAD;
			if (twoPane) {
				int leastPlaces = 2 * LEAST_TILE + GAP + WELL * 2 + BAR;
				peopleW = Math.clamp(NAMES_W, 120, inner - GUTTER - leastPlaces);
				placesW = inner - GUTTER - peopleW;
				peopleX = PAD + placesW + GUTTER;
			}
			int grid = placesW - WELL * 2 - BAR;
			columns = Math.max(2, Math.min(columns, (grid + GAP) / (LEAST_TILE + GAP)));
			int tileW = (grid - GAP * (columns - 1)) / columns;
			int rows = (tiles + columns - 1) / columns;

			// The room each list would take uncut. A list with nobody in it still wants one row,
			// for the line that says so. A grid asks for the gap under its last row as well as
			// between them: the well scrolls by whole rows, and a well four short of a whole one
			// shows a scrollbar and clips the bottom row rather than dropping the padding.
			int placesWant = rows * (TILE + GAP) + WELL * 2;
			int playersWant = Math.max(others, 1) * ROW + WELL * 2;
			int askersWant = askers == 0 ? 0 : askers * ROW + WELL * 2;
			int overhead = HEADING + (askers == 0 ? 0 : HEADING + GAP) + (twoPane ? 0 : HEADING + GAP);

			int body = twoPane
				? Math.max(HEADING + placesWant, overhead + askersWant + playersWant)
				: overhead + placesWant + askersWant + playersWant;
			int roof = Math.min(view.height() - 2 * MARGIN, MOST_H);
			int height = Math.clamp(TOP + body + PAD, LEAST_H, Math.max(LEAST_H, roof));
			int bodyH = height - TOP - PAD;

			int askersH = 0;
			int placesH;
			int playersH;
			if (twoPane) {
				// Side by side, neither list is taking room from the other.
				placesH = Math.min(placesWant, bodyH - HEADING);
				if (askers > 0) askersH = Math.min(askersWant, bodyH - HEADING * 2 - ONE_ROW - GAP);
				playersH = Math.min(playersWant, bodyH - overhead - askersH);
			} else {
				int room = bodyH - overhead;
				if (askers > 0) {
					askersH = Math.min(askersWant, room - ONE_TILE_ROW - ONE_ROW);
					room -= askersH;
				}
				if (placesWant + playersWant <= room) {
					placesH = placesWant;
					playersH = playersWant;
				} else {
					// Neither fits: split what there is the way they asked for it, so a shelf of
					// thirty places does not squeeze the player list down to nothing, nor the other
					// way about.
					placesH = Math.clamp(room * placesWant / (placesWant + playersWant),
						ONE_TILE_ROW, room - ONE_ROW);
					playersH = room - placesH;
				}
			}

			int placesY = TOP + HEADING;
			int top = twoPane ? TOP : placesY + placesH + GAP;
			int askersY = askers == 0 ? 0 : top + HEADING;
			if (askers > 0) top = askersY + askersH + GAP;
			return new Layout(width, height,
				PAD, placesY, placesW, placesH, columns, tileW,
				peopleX, peopleW,
				askersY, askersH, top + HEADING, playersH);
		}
	}

	private static void show(ServerPlayer player) {
		List<ServerPlayer> others = new ArrayList<>(player.level().getServer().getPlayerList().getPlayers());
		others.remove(player);
		others.sort(java.util.Comparator.comparing(p -> p.getGameProfile().name().toLowerCase(java.util.Locale.ROOT)));
		List<Requests.Ask> askers = Requests.askingOf(player);
		for (Requests.Ask ask : askers) others.remove(Requests.askerOf(player.level().getServer(), ask));
		List<Places.Place> places = Places.of(player);

		Integer doomed = deleting.get(player.getUUID());
		if (doomed == null || doomed >= places.size()) {
			deleting.remove(player.getUUID());
			doomed = null;
		}

		List<Tile> tiles = tilesFor(player, places);
		Layout at = Layout.fit(PandoricalApi.screens().viewport(player), tiles.size(), askers.size(), others.size());
		ScreenBuilder screen = new ScreenBuilder(TYPE).title("Teleport").pauseGame(false).size(at.width(), at.height());
		screen.panel("dialog", 0, 0, at.width(), at.height(), Map.of(ComponentType.PROP_BORDER, "beveled"));
		screen.component(text("title", PAD, 7, "Teleport"));

		placesPane(screen, at, tiles, places, doomed);
		peoplePane(screen, at, player, askers, others);

		PandoricalApi.screens().open(player, screen.build());
		String screenId = PandoricalApi.getOpenScreenId(player.getUUID());
		if (screenId != null) open.put(player.getUUID(), screenId);
	}

	/**
	 * The tiles, as a grid in a well that scrolls a row at a time.
	 *
	 * <p>Over the well is either its name or, when a place is waiting on "are you sure", the
	 * question: the same row, because the question is about what is under it and because a dialog
	 * that grows a strip while you are reading it moves everything else out from under the mouse.
	 */
	/** The fixed places, then the player's own, then the one that adds another. */
	private static List<Tile> tilesFor(ServerPlayer player, List<Places.Place> places) {
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
		return tiles;
	}

	private static void placesPane(ScreenBuilder screen, Layout at, List<Tile> tiles,
			List<Places.Place> places, Integer doomed) {
		screen.panel("places_well", at.placesX(), at.placesY(), at.placesW(), at.placesH(), WELL_STYLE);
		if (doomed == null) heading(screen, "places", at.placesX(), TOP, at.placesW(), "minecraft:filled_map", "Places");
		else confirm(screen, at, places.get(doomed));

		int letters = Math.max(4, at.tileW() / 6 - 1);
		List<ComponentDef> grid = new ArrayList<>();
		for (int i = 0; i < tiles.size(); i++) {
			Tile tile = tiles.get(i);
			int x = (i % at.columns()) * (at.tileW() + GAP);
			int y = (i / at.columns()) * (TILE + GAP);
			grid.add(button(tile.id(), x, y, at.tileW(), TILE,
				Map.of(ComponentType.PROP_TOOLTIP, tile.word())).build());
			grid.add(icon(tile.id() + "_icon", tile.item(), x + (at.tileW() - ICON) / 2, y + 7, TILE_ICON_SCALE).build());
			grid.add(new ComponentBuilder(tile.id() + "_word", ComponentType.TEXT)
				.bounds(x, y + TILE - 11, at.tileW(), 10)
				.prop(ComponentType.PROP_TEXT, Places.clip(tile.word(), letters))
				.prop(ComponentType.PROP_ALIGN, "center")
				.prop(ComponentType.PROP_SHADOW, "true")
				.build());
			if (!tile.deletable()) continue;
			// Small, and in a corner, on purpose: deleting is the one thing here that loses
			// something, and it asks first.
			grid.add(button("delete:" + tile.id().substring("place:".length()), x + at.tileW() - 12, y + 1, 11, 11,
				Map.of(ComponentType.PROP_LABEL, "✕", ComponentType.PROP_TOOLTIP, "Delete " + tile.word())).build());
		}
		int rows = (tiles.size() + at.columns() - 1) / at.columns();
		scroller(screen, "places", at.placesX(), at.placesY(), at.placesW(), at.placesH(),
			TILE + GAP, rows, grid);
	}

	/**
	 * Whoever is asking, to answer, and then everyone else to ask.
	 *
	 * <p>Two pictures carry the whole of which way a trip goes, in both lists: a pearl is you
	 * travelling, a lead is them. So a row asking you to come over wears a pearl and a row asking
	 * to come to you wears a lead, and the same two are the two buttons on every player.
	 */
	private static void peoplePane(ScreenBuilder screen, Layout at, ServerPlayer player,
			List<Requests.Ask> askers, List<ServerPlayer> others) {
		int inner = at.peopleW() - WELL * 2 - BAR;
		if (!askers.isEmpty()) {
			heading(screen, "asking", at.peopleX(), at.askersY() - HEADING, at.peopleW(),
				"minecraft:bell", "Asking you");
			screen.panel("askers_well", at.peopleX(), at.askersY(), at.peopleW(), at.askersH(), WELL_STYLE);
			List<ComponentDef> rows = new ArrayList<>();
			int y = 0;
			for (Requests.Ask ask : askers) {
				ServerPlayer asker = Requests.askerOf(player.level().getServer(), ask);
				if (asker == null) continue;
				boolean here = ask.way() == Requests.Way.HERE;
				String id = asker.getUUID().toString();
				String name = asker.getGameProfile().name();
				int wide = inner - BUTTON - GAP;
				rows.add(button("accept:" + id, 0, y, wide, BUTTON, Map.of(
					ComponentType.PROP_LABEL, name,
					ComponentType.PROP_TOOLTIP, here ? "Go to " + name : "Bring " + name + " here",
					ComponentType.PROP_STYLE, "accepted")).build());
				rows.add(face("accept_face:" + id, asker, 4, y + (BUTTON - FACE) / 2).build());
				rows.add(icon("accept_way:" + id, here ? "minecraft:ender_pearl" : "minecraft:lead",
					wide - ICON - 4, y + (BUTTON - ICON) / 2, 1F).build());
				rows.add(button("deny:" + id, inner - BUTTON, y, BUTTON, BUTTON,
					Map.of(ComponentType.PROP_TOOLTIP, here ? "Stay where you are" : "Say no")).build());
				rows.add(icon("deny_icon:" + id, "minecraft:barrier",
					inner - BUTTON + (BUTTON - ICON) / 2, y + (BUTTON - ICON) / 2, 1F).build());
				y += ROW;
			}
			scroller(screen, "askers", at.peopleX(), at.askersY(), at.peopleW(), at.askersH(),
				ROW, askers.size(), rows);
		}

		heading(screen, "players", at.peopleX(), at.playersY() - HEADING, at.peopleW(),
			"minecraft:ender_pearl", "Go to, or bring here");
		screen.panel("players_well", at.peopleX(), at.playersY(), at.peopleW(), at.playersH(), WELL_STYLE);
		if (others.isEmpty()) {
			screen.component(text("nobody", at.peopleX() + WELL + 4, at.playersY() + WELL + 6,
				"Nobody else is on right now"));
			return;
		}
		List<ComponentDef> rows = new ArrayList<>();
		int y = 0;
		for (ServerPlayer other : others) {
			String id = other.getUUID().toString();
			String name = other.getGameProfile().name();
			Requests.Way asked = Requests.asking(player, other.getUUID());
			boolean waitingThere = asked == Requests.Way.THERE;
			boolean waitingHere = asked == Requests.Way.HERE;
			// Their name is the trip you take; the lead beside it is the one they take.
			int wide = inner - BUTTON - GAP;
			rows.add(new ComponentBuilder("ask:" + id, ComponentType.BUTTON)
				.bounds(0, y, wide, BUTTON)
				.prop(ComponentType.PROP_LABEL, name)
				.prop(ComponentType.PROP_TOOLTIP, waitingThere
					? "Waiting for " + name + " to say yes" : "Ask to go to " + name)
				.prop(ComponentType.PROP_ENABLED, String.valueOf(!waitingThere))
				.build());
			rows.add(face("ask_face:" + id, other, 4, y + (BUTTON - FACE) / 2).build());
			// A clock on a player already asked: waiting, in a picture.
			if (waitingThere) rows.add(icon("ask_wait:" + id, "minecraft:clock", wide - ICON - 4, y + (BUTTON - ICON) / 2, 1F).build());
			rows.add(new ComponentBuilder("bring:" + id, ComponentType.BUTTON)
				.bounds(inner - BUTTON, y, BUTTON, BUTTON)
				.prop(ComponentType.PROP_TOOLTIP, waitingHere
					? "Waiting for " + name + " to say yes" : "Ask " + name + " to come to you")
				.prop(ComponentType.PROP_ENABLED, String.valueOf(!waitingHere))
				.build());
			rows.add(icon("bring_icon:" + id, waitingHere ? "minecraft:clock" : "minecraft:lead",
				inner - BUTTON + (BUTTON - ICON) / 2, y + (BUTTON - ICON) / 2, 1F).build());
			y += ROW;
		}
		scroller(screen, "players", at.peopleX(), at.playersY(), at.peopleW(), at.playersH(),
			ROW, others.size(), rows);
	}

	/**
	 * Are you sure, on the places heading's row: the place, then Delete and Keep.
	 *
	 * <p>The picture and the name say which place, and the two buttons say what is being asked, so
	 * nothing here spells out the question. At the width this has to survive - a narrow window,
	 * with the name of somebody's longest-named place in it - a sentence would not have fit.
	 */
	private static void confirm(ScreenBuilder screen, Layout at, Places.Place place) {
		int wide = Math.min(46, (at.placesW() - GAP * 2) / 3);
		int asking = at.placesW() - wide * 2 - GAP * 2;
		int words = asking - ICON - 4;
		screen.component(icon("doomed_icon", place.icon(), at.placesX(), TOP, 1F));
		screen.component(new ComponentBuilder("doomed_text", ComponentType.TEXT)
			.bounds(at.placesX() + ICON + 4, TOP + 4, words, 10)
			.prop(ComponentType.PROP_TEXT, Places.clip(place.name(), Math.max(4, words / 6)))
			.prop(ComponentType.PROP_COLOR, INK));
		screen.component(button("delete_yes", at.placesX() + asking, TOP - 2, wide, BUTTON - 6,
			Map.of(ComponentType.PROP_LABEL, "Delete", ComponentType.PROP_TOOLTIP, "Delete " + place.name())));
		screen.component(button("delete_no", at.placesX() + asking + wide + GAP, TOP - 2, wide, BUTTON - 6,
			Map.of(ComponentType.PROP_LABEL, "Keep", ComponentType.PROP_TOOLTIP, "Keep " + place.name(),
				ComponentType.PROP_STYLE, "accepted")));
	}

	/**
	 * The rows inside a well, clipped to it and scrolled a row at a time. The well's border is
	 * what the panel is inset by, and its scrollbar sits inside that.
	 */
	private static void scroller(ScreenBuilder screen, String id, int x, int y, int w, int h,
			int rowHeight, int rows, List<ComponentDef> content) {
		int visible = Math.max(1, (h - WELL * 2) / rowHeight);
		screen.scrollPanel(id, x + WELL, y + WELL, w - WELL * 2, h - WELL * 2, Map.of(
			"item_height", String.valueOf(rowHeight),
			"visible_items", String.valueOf(visible),
			"total_items", String.valueOf(rows),
			"show_scrollbar", String.valueOf(rows > visible),
			ComponentType.PROP_BACKGROUND, "#00000000"), content);
	}

	/** A section's name, after a picture of what the section is for. */
	private static void heading(ScreenBuilder screen, String id, int x, int y, int w, String item, String words) {
		screen.component(icon(id + "_heading_icon", item, x, y, 1F));
		screen.component(new ComponentBuilder(id + "_heading", ComponentType.TEXT)
			.bounds(x + ICON + 4, y + 4, w - ICON - 4, 10)
			.prop(ComponentType.PROP_TEXT, words)
			.prop(ComponentType.PROP_COLOR, INK));
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
		// Answering is not a use of the menu: whoever was asked may say yes or no either way,
		// and where a yes would move them it is their own teleporting that was checked to ask.
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
					case "ask", "bring", "accept", "deny" -> {
						ServerPlayer other = playerOf(player, rest);
						if (other == null) {
							show(player);
							return;
						}
						switch (kind) {
							// Asking to go closes the menu: the answer is the other player's to
							// give, and "asked" said in a menu left standing read as a button that
							// did nothing.
							case "ask" -> leave(player, p -> Requests.ask(p, other));
							// Asking them to come does not: you are staying put, and the menu is
							// where the answer will show up.
							case "bring" -> {
								Requests.bring(player, other);
								show(player);
							}
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

		MutableComponent bring = Component.literal("Bring here: ").withStyle(ChatFormatting.LIGHT_PURPLE);
		for (int i = 0; i < others.size(); i++) {
			if (i > 0) bring.append(" ");
			String name = others.get(i).getGameProfile().name();
			bring.append(choice(name, "/tpme bring " + name));
		}
		player.sendSystemMessage(bring);
	}

	private static Component choice(String label, String command) {
		return Component.literal("[" + label + "]").withStyle(style -> style
			.withColor(ChatFormatting.AQUA)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}
}
