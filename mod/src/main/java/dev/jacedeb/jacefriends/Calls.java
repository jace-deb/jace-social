package dev.jacedeb.jacefriends;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;

/**
 * Voice calls. The audio runs in Jace Launcher; the game only shows the call and
 * sends Call / Answer / Mute / Hang up to the launcher (see LauncherLink).
 */
public final class Calls {
	private static volatile JsonObject state = new JsonObject();
	private static String lastState = "idle";
	private static boolean polling;
	private static boolean moving;          // Watch was clicked: the call is moving to Jace Social
	private static int ticks;

	private Calls() {}

	/** idle, calling, ringing or in-call */
	public static String state() {
		String s = Social.str(state, "state");
		return s.isEmpty() ? "idle" : s;
	}

	public static String peerName() {
		return Social.str(state, "peer_name");
	}

	public static boolean muted() {
		return state.has("muted") && state.get("muted").getAsBoolean();
	}

	/** They turned on their camera or are sharing their screen (only Jace Social can show it). */
	public static boolean peerVideo() {
		return flag("peer_camera") || flag("peer_screen");
	}

	/** "camera", "screen" or "camera and screen" */
	public static String videoWhat() {
		return flag("peer_camera") && flag("peer_screen") ? "camera and screen" : flag("peer_camera") ? "camera" : "screen";
	}

	private static boolean flag(String key) {
		return state.has(key) && state.get(key).getAsBoolean();
	}

	/** Ask the launcher about calls once a second. */
	static void tick() {
		if (!LauncherLink.available() || polling || ++ticks % 20 != 0) return;
		polling = true;
		Social.async(LauncherLink::status).whenComplete((s, err) -> Minecraft.getInstance().execute(() -> {
			polling = false;
			boolean hadVideo = peerVideo();
			state = err == null ? s : new JsonObject();
			String now = state();
			if (now.equals("in-call") && peerVideo() && !hadVideo) {
				Compat.toast(peerName() + " turned on their " + videoWhat(), "Press J, then Watch to see it in Jace Social");
				if (Compat.currentScreen() instanceof FriendsScreen f) f.callChanged();
			} else if (hadVideo && !peerVideo() && Compat.currentScreen() instanceof FriendsScreen f) {
				f.callChanged();
			}
			if (now.equals(lastState)) return;
			if (now.equals("ringing")) Compat.toast("Incoming call", peerName() + " is calling - press J to answer");
			if (now.equals("idle") && lastState.equals("in-call"))
				Compat.toast(moving ? "Call moved to Jace Social" : "Call ended", moving ? "Keep talking there" : "");
			if (now.equals("idle")) moving = false;
			lastState = now;
			if (Compat.currentScreen() instanceof FriendsScreen f) f.callChanged();
		}));
	}

	/** Run a call action; errors (like "Jace Launcher required!") go to {@code status}. */
	public static void act(FriendsScreen.ThrowingRunnable work, Consumer<String> status) {
		if (!LauncherLink.available()) {
			status.accept(LauncherLink.REQUIRED);
			return;
		}
		Social.async(() -> {
			work.run();
			return LauncherLink.status();
		}).whenComplete((s, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				status.accept(FriendsScreen.cause(err));
				return;
			}
			state = s;
			lastState = state();
			if (Compat.currentScreen() instanceof FriendsScreen f) f.callChanged();
		}));
	}

	/** Open the call in Jace Social to see their camera / screen (it takes the call over there). */
	public static void watch(Consumer<String> status) {
		moving = true;
		status.accept("Opening Jace Social - click Move call here");
		act(LauncherLink::watch, status);
	}

	public static void call(JsonObject friend, Consumer<String> status) {
		String uuid = Social.str(friend, "uuid");
		String name = Social.str(friend, "name");
		act(() -> LauncherLink.start(uuid, name), status);
	}
}
