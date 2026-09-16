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

	public static boolean full(ServerPlayer player) {
		return of(player).size() >= TpConfig.maxPlaces();
	}

	/** A new place where the player stood, with this picture, named as typed or else after the picture. */
	public static void add(ServerPlayer player, Spot spot, String icon, String typed) {
		List<Place> places = new ArrayList<>(of(player));
		if (places.size() >= TpConfig.maxPlaces()) {
			Trips.say(player, "You have " + places.size() + " places already. Delete one to make room.");
			return;
		}
		String name = typed == null ? "" : typed.strip();
		if (name.isEmpty()) name = itemName(icon);
		name = unique(places, name.length() > 32 ? name.substring(0, 32) : name);
		places.add(new Place(name, icon, spot.dimension(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch()));
		player.setAttached(PLACES, List.copyOf(places));
		Trips.say(player, "Saved " + name);
	}

	public static void delete(ServerPlayer player, int index) {
		List<Place> places = new ArrayList<>(of(player));
		if (index < 0 || index >= places.size()) return;
		Place gone = places.remove(index);
		player.setAttached(PLACES, List.copyOf(places));
		Trips.say(player, "Deleted " + gone.name());
	}

	public static int indexOf(ServerPlayer player, String name) {
		List<Place> places = of(player);
		for (int i = 0; i < places.size(); i++) {
			if (places.get(i).name().equalsIgnoreCase(name)) return i;
		}
		return -1;
	}

	public static void go(ServerPlayer player, int index) {
		List<Place> places = of(player);
		if (index < 0 || index >= places.size()) return;
		Place place = places.get(index);
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
