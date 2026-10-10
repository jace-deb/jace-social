package dev.jacedeb.jacefriends;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The bar at the bottom of the Friends and Servers screens while you're in a call or a voice
 * room: what's going on (one line), and its buttons (the line below).
 */
final class CallBar {
	static final int HEIGHT = 36;                 // text line + buttons

	private CallBar() {}

	/** The buttons, left to right from x, on line y. */
	static void addButtons(Font font, int x, int y, Consumer<Button> add, Consumer<String> status) {
		List<Object[]> b = new ArrayList<>();             // {label, action}
		if (Calls.voice() != null) {
			b.add(new Object[]{Calls.voiceFlag("muted") ? "Unmute" : "Mute", (FriendsScreen.ThrowingRunnable) LauncherLink::voiceMute});
			b.add(new Object[]{Calls.voiceFlag("deafened") ? "Undeafen" : "Deafen", (FriendsScreen.ThrowingRunnable) LauncherLink::voiceDeafen});
			if (Calls.voiceFlag("can_video")) {
				b.add(new Object[]{Calls.voiceFlag("camera") ? "Cam off" : "Camera", (FriendsScreen.ThrowingRunnable) LauncherLink::voiceCamera});
				b.add(new Object[]{Calls.voiceFlag("sharing") ? "Stop share" : "Share", (FriendsScreen.ThrowingRunnable) LauncherLink::voiceScreen});
			}
			if (!Calls.voiceVideo().isEmpty()) b.add(new Object[]{"Watch", null});
			b.add(new Object[]{"Leave", (FriendsScreen.ThrowingRunnable) LauncherLink::voiceLeave});
		} else {
			switch (Calls.state()) {
				case "ringing" -> {
					b.add(new Object[]{"Answer", (FriendsScreen.ThrowingRunnable) LauncherLink::answer});
					b.add(new Object[]{"Decline", (FriendsScreen.ThrowingRunnable) LauncherLink::hangUp});
				}
				case "calling" -> b.add(new Object[]{"Cancel", (FriendsScreen.ThrowingRunnable) LauncherLink::hangUp});
				default -> {
					b.add(new Object[]{Calls.muted() ? "Unmute" : "Mute", (FriendsScreen.ThrowingRunnable) LauncherLink::toggleMute});
					b.add(new Object[]{Calls.camera() ? "Cam off" : "Camera", (FriendsScreen.ThrowingRunnable) LauncherLink::camera});
					b.add(new Object[]{Calls.sharing() ? "Stop share" : "Share", (FriendsScreen.ThrowingRunnable) LauncherLink::screen});
					if (Calls.peerVideo()) b.add(new Object[]{"Watch", null});
					b.add(new Object[]{"Hang up", (FriendsScreen.ThrowingRunnable) LauncherLink::hangUp});
				}
			}
		}
		for (Object[] o : b) {
			String label = (String) o[0];
			FriendsScreen.ThrowingRunnable work = (FriendsScreen.ThrowingRunnable) o[1];
			int w = font.width(label) + 12;
			add.accept(Button.builder(Component.literal(label), btn -> {
				if (work == null) Calls.watch(status);
				else Calls.act(work, status);
			}).bounds(x, y, w, 20).build());
			x += w + 2;
		}
	}

	/** What's going on, e.g. "♪ General · 3 here · Alex: camera". */
	static String text() {
		JsonObject v = Calls.voice();
		if (v != null) {
			if (Calls.voiceFlag("connecting")) return "Joining " + Social.str(v, "channel_name") + "…";
			int n = Calls.voicePeople().size();
			List<String> video = Calls.voiceVideo();
			return "♪ " + Social.str(v, "channel_name") + " · " + (n == 0 ? "just you" : n + " here")
					+ (Calls.voiceFlag("muted") ? " (muted)" : "") + (Calls.voiceFlag("deafened") ? " (deafened)" : "")
					+ (video.isEmpty() ? "" : " · video: " + String.join(", ", video));
		}
		return switch (Calls.state()) {
			case "calling" -> "Calling " + Calls.peerName() + "…";
			case "ringing" -> Calls.peerName() + " is calling you";
			default -> "In a call with " + Calls.peerName() + (Calls.muted() ? " (muted)" : "")
					+ (Calls.peerVideo() ? " · their " + Calls.videoWhat() + " is on" : "");
		};
	}

	static void draw(Draw g, Font font, int left, int y) {
		g.fill(left, y + 1, left + 6, y + 7, 0xFF3DDC84);
		g.text(font, font.plainSubstrByWidth(text(), 300), left + 10, y, 0xFFFFFFFF);
	}
}
