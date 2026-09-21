package justfatlard.tp_me_plz;

import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/** Moving a player, and telling them about it. */
public final class Trips {
	private Trips() {}

	/**
	 * Off whatever they are riding, across dimensions if need be, with the sound at both ends.
	 *
	 * <p>Every trip in the mod comes through here, so every trip is put down by {@link Landing}
	 * rather than at the asked-for numbers: near where they meant, on a floor, and not inside
	 * whoever is already standing there.
	 */
	public static void go(ServerPlayer player, ServerLevel level, double x, double y, double z,
			float yaw, float pitch, String arrived) {
		Landing.Spot spot = Landing.near(player, level, x, y, z);
		Vec3 at = spot.at();
		ServerLevel from = player.level();
		from.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_TELEPORT,
			SoundSource.PLAYERS, 0.6F, 1.0F);
		player.stopRiding();
		player.teleportTo(level, at.x, at.y, at.z, Set.<Relative>of(), yaw, pitch, true);
		player.resetFallDistance();
		level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.2F);
		// Said rather than swallowed: the trip happened, but not onto anything that holds, and
		// somebody dropped into lava deserves to know it was not a near miss.
		if (!spot.found()) say(player, "Nothing safe to stand on there - mind your step");
		else if (arrived != null) say(player, arrived);
	}

	/** To the world's spawn, wherever the world keeps it. */
	public static void spawn(ServerPlayer player) {
		ServerLevel overworld = player.level().getServer().overworld();
		LevelData.RespawnData respawn = overworld.getRespawnData();
		ServerLevel level = player.level().getServer().getLevel(respawn.dimension());
		if (level == null) level = overworld;
		BlockPos pos = respawn.pos();
		go(player, level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, respawn.yaw(), respawn.pitch(), "Spawn");
	}

	/** On the action bar: a trip is its own confirmation, and chat is for the conversation. */
	public static void say(ServerPlayer player, String what) {
		player.sendSystemMessage(Component.literal(what).withStyle(ChatFormatting.LIGHT_PURPLE), true);
	}
}
