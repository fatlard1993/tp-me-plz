package justfatlard.tp_me_plz;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * Home, which is the player's bed, and the places a player has saved, each with a picture.
 *
 * <p>Home is not something the menu keeps. It is wherever the game will respawn the player - a
 * bed, or a charged respawn anchor - read fresh on every trip, so sleeping somewhere new moves it
 * and there is nothing to keep in step. Everything else a player wants to get back to is a place:
 * where they stood when they saved it, named, and shown by an item of their choosing, which is how
 * somebody who cannot read the name finds it again.
 *
 * <p>A place is private until its maker shares it, when it moves to {@link SharedPlaces}: everyone
 * can go there and see it on the map, and only its maker and ops can delete it or make it private.
 * A player's own places are the private ones first, then the shared, and both count toward the most
 * they may have.
 */
public final class Places {
	private Places() {}

	public record Place(String name, String icon, ResourceKey<Level> dimension, double x, double y, double z,
			float yaw, float pitch) {
		static final Codec<Place> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name").forGetter(Place::name),
			Codec.STRING.fieldOf("icon").forGetter(Place::icon),
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Place::dimension),
			Codec.DOUBLE.fieldOf("x").forGetter(Place::x),
			Codec.DOUBLE.fieldOf("y").forGetter(Place::y),
			Codec.DOUBLE.fieldOf("z").forGetter(Place::z),
			Codec.FLOAT.fieldOf("yaw").forGetter(Place::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(Place::pitch)
		).apply(instance, Place::new));
	}

	/** Where a player stood when they asked for a new place, until they pick its picture. */
	public record Spot(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
		static Spot of(ServerPlayer player) {
			return new Spot(player.level().dimension(), player.getX(), player.getY(), player.getZ(),
				player.getYRot(), player.getXRot());
		}
	}

	public static final AttachmentType<List<Place>> PLACES = AttachmentRegistry.<List<Place>>builder()
		.persistent(Place.CODEC.listOf())
		.copyOnDeath()
		.buildAndRegister(Identifier.fromNamespaceAndPath(Main.MOD_ID, "places"));

	/**
	 * The one home the first version kept, set by hand. Still read so nobody who set one loses it:
	 * it comes back as a place the first time they open the menu, and is gone after that.
	 */
	private record OldHome(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
		static final Codec<OldHome> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(OldHome::dimension),
			Codec.DOUBLE.fieldOf("x").forGetter(OldHome::x),
			Codec.DOUBLE.fieldOf("y").forGetter(OldHome::y),
			Codec.DOUBLE.fieldOf("z").forGetter(OldHome::z),
			Codec.FLOAT.fieldOf("yaw").forGetter(OldHome::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(OldHome::pitch)
		).apply(instance, OldHome::new));
	}

	private static final AttachmentType<OldHome> OLD_HOME = AttachmentRegistry.<OldHome>builder()
		.persistent(OldHome.CODEC)
		.copyOnDeath()
		.buildAndRegister(Identifier.fromNamespaceAndPath(Main.MOD_ID, "home"));

	/** Loads the class, which is what registers the attachments; it must happen at init. */
	static void init() {}

	public static List<Place> of(ServerPlayer player) {
		OldHome old = player.getAttached(OLD_HOME);
		if (old != null) {
			player.removeAttached(OLD_HOME);
			List<Place> places = new ArrayList<>(listOf(player));
			places.add(0, new Place("Old home", "minecraft:white_banner", old.dimension(), old.x(), old.y(), old.z(),
				old.yaw(), old.pitch()));
			player.setAttached(PLACES, List.copyOf(places));
		}
		return listOf(player);
	}

	private static List<Place> listOf(ServerPlayer player) {
		List<Place> places = player.getAttached(PLACES);
		return places == null ? List.of() : places;
	}

	/** One of a player's own places, and whether they have shared it. */
	public record Own(Place place, boolean shared) {}

	/** A player's own places: the private ones, then those they have shared. */
	public static List<Own> mine(ServerPlayer player) {
		reclaim(player);
		List<Own> mine = new ArrayList<>();
		for (Place place : of(player)) mine.add(new Own(place, false));
		for (SharedPlaces.Shared shared : SharedPlaces.get(player.level().getServer()).of(player.getUUID())) mine.add(new Own(shared.place(), true));
		return mine;
	}

	/** The places other players have shared, which this one may go to. */
	public static List<SharedPlaces.Shared> sharedByOthers(ServerPlayer player) {
		return SharedPlaces.listed(player.level().getServer()).stream().filter(shared -> !shared.owner().equals(player.getUUID())).toList();
	}

	/** Places an op made private while their maker was away come back to them. */
	static void reclaim(ServerPlayer player) {
		List<SharedPlaces.Shared> back = SharedPlaces.get(player.level().getServer()).takeReturning(player.getUUID());
		if (back.isEmpty()) return;
		List<Place> places = new ArrayList<>(of(player));
		for (SharedPlaces.Shared shared : back) places.add(new Place(unique(places, shared.place().name()), shared.place().icon(),
			shared.place().dimension(), shared.place().x(), shared.place().y(), shared.place().z(), shared.place().yaw(), shared.place().pitch()));
		player.setAttached(PLACES, List.copyOf(places));
		Trips.say(player, back.size() == 1 ? back.get(0).place().name() + " is private again" : back.size() + " of your places are private again");
	}

	public static boolean full(ServerPlayer player) {
		return mine(player).size() >= TpConfig.maxPlaces();
	}

	/** Shares a private place, or makes a shared one private again, by its place among the player's own. */
	public static void toggleShared(ServerPlayer player, int index) {
		List<Own> mine = mine(player);
		if (index < 0 || index >= mine.size()) return;
		Own own = mine.get(index);
		SharedPlaces store = SharedPlaces.get(player.level().getServer());
		if (own.shared()) {
			SharedPlaces.Shared shared = store.of(player.getUUID()).stream().filter(s -> s.place().equals(own.place())).findFirst().orElse(null);
			if (shared == null) return;
			store.remove(shared);
			List<Place> places = new ArrayList<>(of(player));
			places.add(own.place());
			player.setAttached(PLACES, List.copyOf(places));
			Trips.say(player, own.place().name() + " is private: only you can see it");
		} else {
			List<Place> places = new ArrayList<>(of(player));
			places.remove(own.place());
			player.setAttached(PLACES, List.copyOf(places));
			// A shared name is unique among this player's shared places, which are what others tell apart by name
			List<Place> sharedNames = store.of(player.getUUID()).stream().map(SharedPlaces.Shared::place).toList();
			Place place = own.place();
			String name = unique(sharedNames, place.name());
			if (!name.equals(place.name())) place = new Place(name, place.icon(), place.dimension(), place.x(), place.y(), place.z(), place.yaw(), place.pitch());
			store.add(new SharedPlaces.Shared(player.getUUID(), player.getGameProfile().name(), place, false));
			Trips.say(player, place.name() + " is shared: everyone can go there and see it on the map");
		}
	}

	/** Whether this player may delete or make private a place shared by someone: its maker, or an op. */
	public static boolean mayChange(ServerPlayer player, SharedPlaces.Shared shared) {
		return shared.owner().equals(player.getUUID()) || Access.isOp(player);
	}

	/** An op's change to someone else's shared place: deleted, or made private and given back to its maker. */
	public static void changeShared(ServerPlayer player, SharedPlaces.Shared shared, boolean delete) {
		if (!mayChange(player, shared)) {
			Trips.say(player, "Only " + shared.ownerName() + " and ops can change " + shared.place().name());
			return;
		}
		MinecraftServer server = player.level().getServer();
		SharedPlaces store = SharedPlaces.get(server);
		if (delete) {
			store.remove(shared);
			Trips.say(player, "Deleted " + shared.ownerName() + "'s " + shared.place().name());
			return;
		}
		store.returnToOwner(shared);
		ServerPlayer owner = server.getPlayerList().getPlayer(shared.owner());
		if (owner != null) reclaim(owner);
		Trips.say(player, shared.ownerName() + "'s " + shared.place().name() + " is private again");
	}

	public static void goShared(ServerPlayer player, SharedPlaces.Shared shared) {
		Place place = shared.place();
		ServerLevel level = player.level().getServer().getLevel(place.dimension());
		if (level == null) {
			Trips.say(player, place.name() + " is somewhere this world no longer has");
			return;
		}
		Trips.go(player, level, place.x(), place.y(), place.z(), place.yaw(), place.pitch(), place.name());
	}

	/** A place shared by someone else, by name, or null. */
	public static SharedPlaces.Shared sharedNamed(ServerPlayer player, String name) {
		for (SharedPlaces.Shared shared : sharedByOthers(player)) if (shared.place().name().equalsIgnoreCase(name)) return shared;
		return null;
	}

	/** A place shared by someone else, by the key its menu button carries, or null. */
	public static SharedPlaces.Shared sharedKeyed(ServerPlayer player, String key) {
		for (SharedPlaces.Shared shared : SharedPlaces.listed(player.level().getServer())) if (shared.key().equals(key)) return shared;
		return null;
	}

	/** A new place where the player stood, with this picture, named as typed or else after the picture. */
	public static void add(ServerPlayer player, Spot spot, String icon, String typed) {
		List<Place> places = new ArrayList<>(of(player));
		int count = mine(player).size();
		if (count >= TpConfig.maxPlaces()) {
			Trips.say(player, "You have " + count + " places already. Delete one to make room.");
			return;
		}
		String name = typed == null ? "" : typed.strip();
		if (name.isEmpty()) name = itemName(icon);
		// Unique among all the player's own places, shared ones too, so a name always picks out one
		name = unique(mine(player).stream().map(Own::place).toList(), name.length() > 32 ? name.substring(0, 32) : name);
		places.add(new Place(name, icon, spot.dimension(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch()));
		player.setAttached(PLACES, List.copyOf(places));
		Trips.say(player, "Saved " + name);
	}

	/** Deletes one of the player's own places, private or shared, by its place among them. */
	public static void delete(ServerPlayer player, int index) {
		List<Own> mine = mine(player);
		if (index < 0 || index >= mine.size()) return;
		Own gone = mine.get(index);
		if (gone.shared()) {
			SharedPlaces store = SharedPlaces.get(player.level().getServer());
			store.of(player.getUUID()).stream().filter(s -> s.place().equals(gone.place())).findFirst().ifPresent(store::remove);
		} else {
			List<Place> places = new ArrayList<>(of(player));
			places.remove(gone.place());
			player.setAttached(PLACES, List.copyOf(places));
		}
		Trips.say(player, "Deleted " + gone.place().name());
	}

	public static int indexOf(ServerPlayer player, String name) {
		List<Own> mine = mine(player);
		for (int i = 0; i < mine.size(); i++) {
			if (mine.get(i).place().name().equalsIgnoreCase(name)) return i;
		}
		return -1;
	}

	public static void go(ServerPlayer player, int index) {
		List<Own> mine = mine(player);
		if (index < 0 || index >= mine.size()) return;
		Place place = mine.get(index).place();
		ServerLevel level = player.level().getServer().getLevel(place.dimension());
		if (level == null) {
			Trips.say(player, place.name() + " is somewhere this world no longer has");
			return;
		}
		Trips.go(player, level, place.x(), place.y(), place.z(), place.yaw(), place.pitch(), place.name());
	}

	/** Whether the player has a bed, or an anchor, to go home to. */
	public static boolean hasHome(ServerPlayer player) {
		return player.getRespawnConfig() != null;
	}

	/**
	 * To the bed, standing beside it the way waking up does. An anchor's charge is not spent: this
	 * is a walk home, not a death. A bed that is gone is said so, rather than the trip quietly
	 * turning into one to spawn.
	 */
	public static void home(ServerPlayer player) {
		if (!hasHome(player)) {
			Trips.say(player, "Sleep in a bed and it becomes your home");
			return;
		}
		TeleportTransition bed = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
		if (bed.missingRespawnBlock() || bed.newLevel() == null) {
			Trips.say(player, "Your bed is gone. Sleep in a bed to make a new home");
			return;
		}
		Trips.go(player, bed.newLevel(), bed.position().x, bed.position().y, bed.position().z,
			bed.yRot(), bed.xRot(), "Home");
	}

	/** The item's own name, for a place nobody named: a diamond picture is "Diamond". */
	public static String itemName(String icon) {
		Identifier id = Identifier.tryParse(icon);
		Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
		return item == Items.AIR ? "Place" : new ItemStack(item).getHoverName().getString();
	}

	private static String unique(List<Place> places, String name) {
		String candidate = name;
		for (int n = 2; ; n++) {
			String tried = candidate;
			if (places.stream().noneMatch(place -> place.name().equalsIgnoreCase(tried))) return candidate;
			candidate = name + " " + n;
		}
	}

	/** Short enough to sit under a tile's picture: about eight letters. */
	public static String clip(String name, int letters) {
		return name.length() <= letters ? name : name.substring(0, Math.max(1, letters - 1)) + "…";
	}

	static BlockPos blockOf(Place place) {
		return BlockPos.containing(place.x(), place.y(), place.z());
	}
}
