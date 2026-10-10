package dev.jacedeb.jacefriends;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Voice calls with a friend, and voice rooms (server voice channels and group calls), with
 * camera and screen sharing. It all runs in Jace Launcher; the game shows it and sends the
 * buttons to the launcher (see LauncherLink). Other people's video shows in Jace Social:
 * Watch moves the call there.
 */
public final class Calls {
	private static volatile JsonObject state = new JsonObject();
	private static String lastState = "idle";
	private static String lastSeen = "";
	private static String lastRoom = "";
	private static boolean polling;
	private static boolean moving;          // Watch was clicked: the call is moving to Jace Social
	private static int ticks;

	private Calls() {}

	// -- a call with a friend
	/** idle, calling, ringing or in-call */
	public static String state() {
		String s = Social.str(state, "state");
		return s.isEmpty() ? "idle" : s;
	}

	public static String peerName() {
		return Social.str(state, "peer_name");
	}

	public static boolean muted() {
		return flag(state, "muted");
	}

	/** Your camera / screen sharing in the call. */
	public static boolean camera() {
		return flag(state, "camera");
	}

	public static boolean sharing() {
		return flag(state, "sharing");
	}

	/** They turned on their camera or are sharing their screen (only Jace Social can show it). */
	public static boolean peerVideo() {
		return flag(state, "peer_camera") || flag(state, "peer_screen");
	}

	/** "camera", "screen" or "camera and screen" */
	public static String videoWhat() {
		return flag(state, "peer_camera") && flag(state, "peer_screen") ? "camera and screen" : flag(state, "peer_camera") ? "camera" : "screen";
	}

	// -- a voice room
	/** The voice channel / group call you're in, or null. */
	public static JsonObject voice() {
		return state.has("voice") && state.get("voice").isJsonObject() ? state.getAsJsonObject("voice") : null;
	}

	public static boolean inVoice(String channelId) {
		JsonObject v = voice();
		return v != null && Social.str(v, "channel_id").equals(channelId);
	}

	public static boolean voiceFlag(String key) {
		JsonObject v = voice();
		return v != null && flag(v, key);
	}

	/** The others in the room. */
	public static List<JsonObject> voicePeople() {
		List<JsonObject> out = new ArrayList<>();
		JsonObject v = voice();
		if (v == null || !v.has("participants")) return out;
		for (JsonElement e : v.getAsJsonArray("participants")) {
			JsonObject p = e.getAsJsonObject();
			if (!flag(p, "me")) out.add(p);
		}
		return out;
	}

	/** Names of the others in the room with their camera on or sharing their screen. */
	public static List<String> voiceVideo() {
		List<String> out = new ArrayList<>();
		for (JsonObject p : voicePeople()) if (flag(p, "camera") || flag(p, "screen")) out.add(Social.str(p, "name"));
		return out;
	}

	/** In a call or a voice room (the call bar shows). */
	public static boolean busy() {
		return !state().equals("idle") || voice() != null;
	}

	private static boolean flag(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
	}

	/** Ask the launcher about calls once a second. */
	static void tick() {
		if (!LauncherLink.available() || polling || ++ticks % 20 != 0) return;
		polling = true;
		Social.async(LauncherLink::status).whenComplete((s, err) -> Minecraft.getInstance().execute(() -> {
			polling = false;
			boolean hadVideo = peerVideo();
			List<String> hadRoomVideo = voiceVideo();
			state = err == null ? s : new JsonObject();
			String now = state();
			if (now.equals("in-call") && peerVideo() && !hadVideo)
				Compat.toast(peerName() + " turned on their " + videoWhat(), "Press J, then Watch to see it in Jace Social");
			for (String name : voiceVideo())
				if (!hadRoomVideo.contains(name)) Compat.toast(name + " is sharing video", "Press J, then Watch to see it in Jace Social");
			JsonObject v = voice();
			String room = v == null ? "" : Social.str(v, "channel_name");
			if (!room.equals(lastRoom) && room.isEmpty() && !moving) Compat.toast("Left voice", lastRoom);
			lastRoom = room;
			if (!now.equals(lastState)) {
				if (now.equals("ringing")) Compat.toast("Incoming call", peerName() + " is calling - press J to answer");
				if (now.equals("idle") && lastState.equals("in-call"))
					Compat.toast(moving ? "Call moved to Jace Social" : "Call ended", moving ? "Keep talking there" : "");
				lastState = now;
			}
			if (!busy()) moving = false;
			changed();
		}));
	}

	/** Rebuild the screen showing the call bar, if anything about calls changed. */
	private static void changed() {
		String seen = state.toString();
		if (seen.equals(lastSeen)) return;
		lastSeen = seen;
		if (Compat.currentScreen() instanceof FriendsScreen f) f.callChanged();
		if (Compat.currentScreen() instanceof ServersScreen s) s.callChanged();
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
			JsonObject v = voice();
			lastRoom = v == null ? "" : Social.str(v, "channel_name");
			changed();
		}));
	}

	/** Open the call (or room) in Jace Social to see their camera / screen; it moves there. */
	public static void watch(Consumer<String> status) {
		moving = true;
		boolean room = voice() != null;
		status.accept(room ? "Opening Jace Social - click Join voice" : "Opening Jace Social - click Move call here");
		act(room ? LauncherLink::voiceWatch : LauncherLink::watch, status);
	}

	public static void call(JsonObject friend, Consumer<String> status) {
		String uuid = Social.str(friend, "uuid");
		String name = Social.str(friend, "name");
		act(() -> LauncherLink.start(uuid, name), status);
	}

	/** Join a server voice channel (serverId set) or a group chat's call (channelId = the group). */
	public static void joinVoice(String channelId, String name, String serverId, Consumer<String> status) {
		status.accept("Joining " + name + "…");
		act(() -> LauncherLink.voiceJoin(channelId, name, serverId), status);
	}
}
