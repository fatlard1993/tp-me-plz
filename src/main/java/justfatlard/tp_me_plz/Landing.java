package justfatlard.tp_me_plz;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a trip actually puts somebody down: near where they asked, on ground that holds them, and
 * not inside anybody.
 *
 * <p>The asked-for spot is tried first and kept whenever it will do, so a place saved in a mine
 * stays in the mine. Only when it will not - somebody already standing there, a wall grown over
 * it, lava where the floor was - does this look around, taking the nearest spot that works. It
 * searches rings outward rather than down from the sky the way {@code /spreadplayers} does,
 * because the point of a saved place is the place and not the surface above it.
 *
 * <p>The nearest-that-works rule is also what spreads a crowd. Each arrival is put down before the
 * next one looks, so the second person to a spot finds the first standing in it and takes the
 * block beside them, and the third takes the one past that.
 */
public final class Landing {
	private Landing() {}

	/** How far out a landing may be looked for, in blocks. Past this it is not the place any more. */
	private static final int REACH = 5;
	/** How far up or down, within that reach. */
	private static final int RISE = 3;
	/** A shade below the feet, so the floor is read rather than the block the feet are in. */
	private static final double FLOOR = 0.1;

	/**
	 * Where the trip ends. {@code found} is false when nothing nearby would do and {@code at} is
	 * the asked-for spot after all: somewhere bad beats stranded, and the trip says so.
	 */
	public record Spot(Vec3 at, boolean found) {}

	/** The asked-for spot when it works, else the nearest one that does. */
	public static Spot near(ServerPlayer player, ServerLevel level, double x, double y, double z) {
		// Everybody who could be in the way, asked for once. A search that finds nothing tries a
		// few hundred spots, and asking the world about each of them is what would be felt.
		List<LivingEntity> crowd = level.getEntitiesOfClass(LivingEntity.class,
			new AABB(x, y, z, x, y, z).inflate(REACH + 2, RISE + 2, REACH + 2),
			other -> other != player && other.isAlive());

		if (works(player, level, crowd, x, y, z)) return new Spot(new Vec3(x, y, z), true);

		// Rings outward, so the first ring holding anything holds the answer; inside a ring the
		// closest of them wins, and a tie goes to the lower one, which is the more likely floor.
		for (int ring = 1; ring <= REACH; ring++) {
			Vec3 best = null;
			double nearest = Double.MAX_VALUE;
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
					double cx = Math.floor(x + dx) + 0.5;
					double cz = Math.floor(z + dz) + 0.5;
					for (int dy = -RISE; dy <= RISE; dy++) {
						double cy = Math.floor(y) + dy;
						if (!works(player, level, crowd, cx, cy, cz)) continue;
						double away = new Vec3(cx, cy, cz).distanceToSqr(x, y, z);
						if (away >= nearest) continue;
						nearest = away;
						best = new Vec3(cx, cy, cz);
					}
				}
			}
			if (best != null) return new Spot(best, true);
		}
		return new Spot(new Vec3(x, y, z), false);
	}

	/**
	 * Room to stand, a floor under it, and nothing there that burns or drowns. Other players and
	 * mobs count the spot as taken: arriving inside somebody is the thing this is for.
	 */
	private static boolean works(ServerPlayer player, ServerLevel level, List<LivingEntity> crowd,
			double x, double y, double z) {
		BlockPos feet = BlockPos.containing(x, y, z);
		if (feet.getY() - 1 < level.getMinY() || feet.getY() + 1 > level.getMaxY()) return false;

		// Anything with a collision shape counts as a floor, not only a whole block: half the
		// places worth saving are stood on a slab, a stair or a patch of farmland.
		BlockPos floor = BlockPos.containing(x, y - FLOOR, z);
		if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) return false;
		if (burnsOrDrowns(level, feet) || burnsOrDrowns(level, feet.above())) return false;

		AABB box = player.getDimensions(Pose.STANDING).makeBoundingBox(x, y, z);
		// Blocks the player would be standing in. Players do not stop one another this way, which
		// is why the crowd is checked separately, pulled in a shade so that standing shoulder to
		// shoulder does not read as taken.
		if (!level.noCollision(player, box)) return false;
		AABB room = box.deflate(0.15);
		for (LivingEntity other : crowd) {
			if (other.getBoundingBox().intersects(room)) return false;
		}
		return true;
	}

	private static boolean burnsOrDrowns(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.getFluidState().isEmpty() || state.is(BlockTags.FIRE);
	}
}
