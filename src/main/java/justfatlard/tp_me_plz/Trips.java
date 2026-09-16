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

/** Moving a player, and telling them about it. */
public final class Trips {
	private Trips() {}

	/** Off whatever they are riding, across dimensions if need be, with the sound at both ends. */
	public static void go(ServerPlayer player, ServerLevel level, double x, double y, double z,
			float yaw, float pitch, String arrived) {
		ServerLevel from = player.level();
		from.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_TELEPORT,
			SoundSource.PLAYERS, 0.6F, 1.0F);
		player.stopRiding();
		player.teleportTo(level, x, y, z, Set.<Relative>of(), yaw, pitch, true);
		player.resetFallDistance();
		level.playSound(null, x, y, z, SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.2F);
		if (arrived != null) say(player, arrived);
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
