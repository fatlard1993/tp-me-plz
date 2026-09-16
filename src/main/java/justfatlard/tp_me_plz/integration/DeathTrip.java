package justfatlard.tp_me_plz.integration;

import justfatlard.dead_heads.DeadHeadCommands;
import net.minecraft.server.level.ServerPlayer;

/**
 * Back to where the player last died, the way dead-heads takes them. Names dead-heads' types, so
 * it is only ever reached behind the isModLoaded guard in TpMenu.
 */
public final class DeathTrip {
	private DeathTrip() {}

	public static boolean has(ServerPlayer player) {
		return DeadHeadCommands.hasLastDeath(player);
	}

	public static void go(ServerPlayer player) {
		DeadHeadCommands.goToLastDeath(player);
	}
}
