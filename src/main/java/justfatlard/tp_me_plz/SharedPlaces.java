package justfatlard.tp_me_plz;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The places players have shared, which everyone can go to and see on the map, kept by the world
 * rather than on the player who made them, so they stay while that player is away.
 *
 * <p>A shared place is still its maker's: only they, and ops, can delete it or make it private
 * again. One made private by an op while its maker is away waits here, shown to nobody, and goes back
 * to its maker's own places when they next join, since a player's own places can only be written
 * while they are on.
 */
public final class SharedPlaces extends SavedData {
	private static final String STORAGE_KEY = Main.MOD_ID + ":shared_places";

	/**
	 * @param returning made private while its maker was away, and waiting to go back to them
	 */
	public record Shared(UUID owner, String ownerName, Places.Place place, boolean returning) {
		static final Codec<Shared> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("owner").forGetter(Shared::owner),
			Codec.STRING.fieldOf("owner_name").forGetter(Shared::ownerName),
			Places.Place.CODEC.fieldOf("place").forGetter(Shared::place),
			Codec.BOOL.optionalFieldOf("returning", false).forGetter(Shared::returning)
		).apply(instance, Shared::new));

		/** What a menu button names this place by: stable while the list around it changes. */
		public String key() {
			return Integer.toHexString((owner + "/" + place.name().toLowerCase(java.util.Locale.ROOT)).hashCode());
		}
	}

	private static final Codec<SharedPlaces> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Shared.CODEC.listOf().optionalFieldOf("places", List.of()).forGetter(shared -> shared.places)
	).apply(instance, SharedPlaces::new));

	private static final SavedDataType<SharedPlaces> TYPE = new SavedDataType<>(
		Identifier.parse(STORAGE_KEY), SharedPlaces::new, CODEC, DataFixTypes.LEVEL);

	private final List<Shared> places = new ArrayList<>();

	private SharedPlaces() {}

	private SharedPlaces(List<Shared> saved) {
		places.addAll(saved);
	}

	public static SharedPlaces get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	/** Every shared place anyone may see and go to, in the order they were shared. */
	public static List<Shared> listed(MinecraftServer server) {
		return get(server).places.stream().filter(shared -> !shared.returning()).toList();
	}

	/** One player's places still shared, in the order they were shared. */
	List<Shared> of(UUID owner) {
		return places.stream().filter(shared -> shared.owner().equals(owner) && !shared.returning()).toList();
	}

	void add(Shared shared) {
		places.add(shared);
		setDirty();
	}

	void remove(Shared shared) {
		if (places.remove(shared)) setDirty();
	}

	/** Hidden from everyone until its maker next joins and takes it back. */
	void returnToOwner(Shared shared) {
		int at = places.indexOf(shared);
		if (at < 0) return;
		places.set(at, new Shared(shared.owner(), shared.ownerName(), shared.place(), true));
		setDirty();
	}

	/** The places made private while their maker was away, which they take back now. */
	List<Shared> takeReturning(UUID owner) {
		List<Shared> back = places.stream().filter(shared -> shared.owner().equals(owner) && shared.returning()).toList();
		if (!back.isEmpty()) {
			places.removeAll(back);
			setDirty();
		}
		return back;
	}
}
