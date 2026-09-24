package justfatlard.tp_me_plz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.NoticeApi;
import justfatlard.pandorical.api.PandoricalApi;

/**
 * Asking about a trip between two players, which goes both ways: "let me come to you", and "come
 * to me". Either way the one who did not ask is the one who answers, within a minute or so, in
 * chat or in their own menu.
 *
 * <p>That is the rule the whole thing turns on: <b>you never move yourself by asking</b>. Asking
 * to go somewhere waits on the person who is there; asking somebody to come waits on them. Nobody
 * is moved, or has somebody land on them, without having said yes to it.
 *
 * <p>One ask at a time from each player: asking again, either way, takes back the first.
 */
public final class Requests {
	private Requests() {}

	/** Which of the two a yes would move. */
	public enum Way {
		/** The asker goes to the one asked: "let me come to you". */
		THERE,
		/** The one asked comes to the asker: "come to me". */
		HERE
	}

	record Ask(UUID asker, UUID asked, long until, Way way) {}

	/** By asker. */
	private static final Map<UUID, Ask> asks = new ConcurrentHashMap<>();

	/** What the tray files these under; the id of each one is the asker. */
	private static final String NOTICE_KIND = "tp-me-plz:ask";

	/**
	 * Wired once, from mod init: an answer given in the tray is the same answer as the one given
	 * in chat or in the menu, and lands in the same place.
	 */
	public static void register() {
		PandoricalApi.notices().onChoice(NOTICE_KIND, (asked, noticeId, choiceId) -> {
			ServerPlayer asker = asked.level().getServer().getPlayerList()
				.getPlayer(UUID.fromString(noticeId));
			if (asker == null) {
				Trips.say(asked, "They are not on any more");
				return;
			}
			if ("accept".equals(choiceId)) accept(asked, asker); else deny(asked, asker);
		});
	}

	/** Take the question out of the tray, however it came to be answered. */
	private static void withdraw(ServerPlayer asked, UUID asker) {
		if (asked != null) PandoricalApi.notices().withdraw(asked, NOTICE_KIND, asker.toString());
	}

	/** "Let me come to you", for the asker to be brought over if they say yes. */
	public static void ask(ServerPlayer asker, ServerPlayer asked) {
		send(asker, asked, Way.THERE);
	}

	/**
	 * "Come to me", for the one asked to be brought over if they say yes.
	 *
	 * <p>The traveller here is the one being asked, so it is their teleporting that is being used
	 * and {@link Access} is asked about them rather than about the asker. Otherwise anybody given
	 * the menu could ferry somebody who was not, and the setting would mean nothing.
	 */
	public static void bring(ServerPlayer asker, ServerPlayer asked) {
		if (!Access.allowed(asked)) {
			Trips.say(asker, asked.getGameProfile().name() + " has not been given teleporting");
			return;
		}
		send(asker, asked, Way.HERE);
	}

	private static void send(ServerPlayer asker, ServerPlayer asked, Way way) {
		if (asker == asked) return;
		String who = asker.getGameProfile().name();
		String them = asked.getGameProfile().name();
		long until = asker.level().getGameTime() + 20L * TpConfig.requestSeconds();
		Ask previous = asks.put(asker.getUUID(), new Ask(asker.getUUID(), asked.getUUID(), until, way));
		if (previous != null && !previous.asked().equals(asked.getUUID())) {
			withdraw(asker.level().getServer().getPlayerList().getPlayer(previous.asked()),
				asker.getUUID());
			refreshAsked(asker.level().getServer(), previous.asked());
		}

		Trips.say(asker, way == Way.THERE
			? "Asked " + them + " to let you come to them"
			: "Asked " + them + " to come to you");

		String yes = way == Way.THERE ? "Bring " + who + " to you" : "Go to " + who;
		Component accept = Component.literal(way == Way.THERE ? "[Accept]" : "[Go]").withStyle(style -> style
			.withColor(ChatFormatting.GREEN).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand("/tpme accept " + who))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(yes))));
		Component deny = Component.literal(way == Way.THERE ? "[Deny]" : "[Stay]").withStyle(style -> style
			.withColor(ChatFormatting.RED).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand("/tpme deny " + who))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(way == Way.THERE
				? "Leave " + who + " where they are"
				: "Stay where you are"))));
		String said = way == Way.THERE
			? "%s asks to teleport to you. "
			: "%s asks you to teleport to them. ";
		Component asking = Component.translatableWithFallback(
			way == Way.THERE ? "tp-me-plz.ask" : "tp-me-plz.ask-here", said, who);

		// Chat always, tray as well where it can be drawn.
		//
		// The tray alone was a mistake. It keeps the question instead of letting it scroll away,
		// which is the right thing for a question to do, but a notice arriving is a quiet bell and
		// a small badge in the corner, and the tray behind a keybind the player was never told
		// about and may not have bound. The request was arriving perfectly and being missed
		// entirely, which from the asker's side looks exactly like the mod being broken.
		//
		// So the line that was always there stays there, buttons and all, and the tray is the
		// copy that waits. Answering either one withdraws the other.
		asked.sendSystemMessage(asking.copy()
			.withStyle(ChatFormatting.LIGHT_PURPLE)
			.append(accept).append(Component.literal("  ")).append(deny));
		if (PandoricalApi.hasCapability(asked, Capabilities.SCREENS)) {
			PandoricalApi.notices().offer(asked, new NoticeApi.Notice(
				asker.getUUID().toString(), NOTICE_KIND,
				way == Way.THERE ? "minecraft:ender_pearl" : "minecraft:compass",
				asking.getString(),
				List.of(new NoticeApi.Choice("accept", "minecraft:lime_dye",
						way == Way.THERE ? "Bring them" : "Go to them"),
					new NoticeApi.Choice("deny", "minecraft:barrier",
						way == Way.THERE ? "Leave them" : "Stay")),
				TpConfig.requestSeconds()));
		}
		TpMenu.refresh(asked);
	}

	/** Which way this player is waiting on an answer from that one, or null if they are not. */
	public static Way asking(ServerPlayer asker, UUID asked) {
		Ask ask = asks.get(asker.getUUID());
		return ask != null && ask.asked().equals(asked) ? ask.way() : null;
	}

	/** The asks waiting on this player to answer, oldest first. */
	public static List<Ask> askingOf(ServerPlayer asked) {
		List<Ask> waiting = new ArrayList<>();
		asks.values().stream()
			.filter(ask -> ask.asked().equals(asked.getUUID()))
			.sorted(java.util.Comparator.comparingLong(Ask::until))
			.forEach(ask -> {
				if (asked.level().getServer().getPlayerList().getPlayer(ask.asker()) != null) waiting.add(ask);
			});
		return waiting;
	}

	/** The player who sent this ask, if they are still on. */
	public static ServerPlayer askerOf(MinecraftServer server, Ask ask) {
		return server.getPlayerList().getPlayer(ask.asker());
	}

	public static void accept(ServerPlayer asked, ServerPlayer asker) {
		Ask ask = asks.get(asker.getUUID());
		if (ask == null || !ask.asked().equals(asked.getUUID())) {
			Trips.say(asked, asker.getGameProfile().name() + " is not asking you anything");
			return;
		}
		asks.remove(asker.getUUID());
		withdraw(asked, asker.getUUID());
		String who = asker.getGameProfile().name();
		String them = asked.getGameProfile().name();
		if (ask.way() == Way.THERE) {
			Trips.go(asker, asked.level(), asked.getX(), asked.getY(), asked.getZ(),
				asked.getYRot(), asker.getXRot(), "With " + them);
			Trips.say(asked, who + " is here");
		} else if (!Access.allowed(asked)) {
			// Between the asking and the yes, an op took it away.
			TpMenu.refuse(asked);
			Trips.say(asker, them + " cannot teleport any more");
		} else {
			Trips.go(asked, asker.level(), asker.getX(), asker.getY(), asker.getZ(),
				asker.getYRot(), asked.getXRot(), "With " + who);
			Trips.say(asker, them + " is here");
		}
		TpMenu.refresh(asked);
		TpMenu.refresh(asker);
	}

	public static void deny(ServerPlayer asked, ServerPlayer asker) {
		Ask ask = asks.get(asker.getUUID());
		if (ask == null || !ask.asked().equals(asked.getUUID())) return;
		asks.remove(asker.getUUID());
		withdraw(asked, asker.getUUID());
		Trips.say(asker, asked.getGameProfile().name() + " said no");
		Trips.say(asked, "Told " + asker.getGameProfile().name() + " no");
		TpMenu.refresh(asked);
		TpMenu.refresh(asker);
	}

	/** Once a tick: asks past their minute lapse, and both ends hear so. */
	public static void tick(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		for (Ask ask : List.copyOf(asks.values())) {
			if (now < ask.until()) continue;
			asks.remove(ask.asker(), ask);
			ServerPlayer asker = server.getPlayerList().getPlayer(ask.asker());
			ServerPlayer asked = server.getPlayerList().getPlayer(ask.asked());
			if (asker != null) {
				Trips.say(asker, "Your request" + (asked != null ? " to " + asked.getGameProfile().name() : "") + " lapsed");
				TpMenu.refresh(asker);
			}
			if (asked != null) {
				withdraw(asked, ask.asker());
				TpMenu.refresh(asked);
			}
		}
	}

	/** A player who left: nothing they asked stands, and nobody waits on them to answer. */
	public static void forget(MinecraftServer server, UUID player) {
		Ask theirs = asks.remove(player);
		// They asked somebody and then logged off. The question cannot be answered now, so it
		// goes rather than sitting in a tray waiting for a name that is not on any more.
		if (theirs != null && server != null) {
			withdraw(server.getPlayerList().getPlayer(theirs.asked()), player);
		}
		asks.values().removeIf(ask -> ask.asked().equals(player));
	}

	private static void refreshAsked(MinecraftServer server, UUID asked) {
		ServerPlayer player = server.getPlayerList().getPlayer(asked);
		if (player != null) TpMenu.refresh(player);
	}
}
