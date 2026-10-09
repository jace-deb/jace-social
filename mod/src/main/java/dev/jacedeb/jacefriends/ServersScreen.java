package dev.jacedeb.jacefriends;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jace Social servers in game: your servers (and joining one with an invite), then a
 * server's channels by category. Text channels open in the chat screen; voice channels
 * show who's in them (talk in the Jace Social app or Jace Launcher).
 */
public class ServersScreen extends Screen {
	private static final int ROW = 22;
	private final Screen parent;
	private final String serverId;             // null: the list of servers
	private String serverName = "";
	private JsonArray servers = new JsonArray();
	private final List<Row> rows = new ArrayList<>();
	private final Map<String, String> names = new HashMap<>();
	private String status = "Loading…";
	private int page;
	private EditBox inviteBox;
	private String inviteText = "";

	/** kind: server / category / text / voice */
	private record Row(String kind, JsonObject o, String extra) {}

	public ServersScreen(Screen parent) {
		this(parent, null, "");
	}

	private ServersScreen(Screen parent, String serverId, String name) {
		super(Component.literal(serverId == null ? "Servers" : name));
		this.parent = parent;
		this.serverId = serverId;
		this.serverName = name;
		reload();
	}

	public boolean isServer(String id) {
		return serverId != null && serverId.equals(id);
	}

	public void reload() {
		if (serverId == null) {
			Social.async(Social::servers).whenComplete((d, err) -> Minecraft.getInstance().execute(() -> {
				if (err != null) { status = FriendsScreen.cause(err); rebuildWidgets(); return; }
				servers = d.getAsJsonArray("servers");
				rows.clear();
				for (JsonElement e : servers) rows.add(new Row("server", e.getAsJsonObject(), ""));
				status = servers.isEmpty() ? "You're not in any servers yet" : servers.size() + " servers";
				rebuildWidgets();
			}));
			return;
		}
		Social.async(() -> Social.server(serverId)).whenComplete((d, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) { status = FriendsScreen.cause(err); rebuildWidgets(); return; }
			serverName = Social.str(d.getAsJsonObject("server"), "name");
			names.clear();
			for (JsonElement m : d.getAsJsonArray("members")) {
				JsonObject p = m.getAsJsonObject();
				String nick = Social.str(p, "nickname");
				names.put(Social.str(p, "uuid"), nick.isEmpty() ? Social.str(p, "name") : nick);
			}
			// who's in each voice channel
			Map<String, List<String>> voice = new HashMap<>();
			if (d.has("voice")) for (JsonElement v : d.getAsJsonArray("voice")) {
				JsonObject o = v.getAsJsonObject();
				voice.computeIfAbsent(Social.str(o, "channel_id"), k -> new ArrayList<>()).add(names.getOrDefault(Social.str(o, "uuid"), "someone"));
			}
			List<JsonObject> chans = new ArrayList<>();
			for (JsonElement c : d.getAsJsonArray("channels")) chans.add(c.getAsJsonObject());
			chans.sort((a, b) -> Integer.compare(a.has("position") ? a.get("position").getAsInt() : 0, b.has("position") ? b.get("position").getAsInt() : 0));
			rows.clear();
			// channels without a category first, then each category with its channels
			for (JsonObject c : chans) if (!kind(c).equals("category") && Social.str(c, "parent_id").isEmpty()) rows.add(channelRow(c, voice));
			for (JsonObject cat : chans) {
				if (!kind(cat).equals("category")) continue;
				List<Row> inside = new ArrayList<>();
				for (JsonObject c : chans) if (Social.str(c, "parent_id").equals(Social.str(cat, "id"))) inside.add(channelRow(c, voice));
				if (inside.isEmpty()) continue;
				rows.add(new Row("category", cat, ""));
				rows.addAll(inside);
			}
			status = names.size() + " members";
			rebuildWidgets();
		}));
	}

	private static String kind(JsonObject c) {
		String k = Social.str(c, "kind");
		return k.isEmpty() ? "text" : k;
	}

	private static Row channelRow(JsonObject c, Map<String, List<String>> voice) {
		String k = kind(c);
		if (k.equals("voice")) {
			List<String> who = voice.getOrDefault(Social.str(c, "id"), List.of());
			return new Row("voice", c, who.isEmpty() ? "" : String.join(", ", who));
		}
		return new Row("text", c, "");
	}

	private int perPage() {
		return Math.max(1, (height - 64 - 36) / ROW);
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int right = cx + 154;
		if (serverId == null) {
			if (inviteBox != null) inviteText = inviteBox.getValue();
			inviteBox = new EditBox(font, cx - 150, 34, 236, 20, Component.literal("Invite"));
			inviteBox.setHint(Component.literal("Invite code or link"));
			inviteBox.setMaxLength(120);
			inviteBox.setValue(inviteText);
			addRenderableWidget(inviteBox);
			addRenderableWidget(Button.builder(Component.literal("Join"), b -> join()).bounds(cx + 90, 34, 64, 20).build());
		}
		int per = perPage();
		page = Math.min(page, Math.max(0, (rows.size() - 1) / per));
		int y = 64;
		for (int i = page * per; i < Math.min(rows.size(), (page + 1) * per); i++) {
			Row r = rows.get(i);
			if (r.kind.equals("server")) {
				int unread = r.o.has("unread") ? r.o.get("unread").getAsInt() : 0;
				addRenderableWidget(Button.builder(Component.literal(unread > 0 ? "Open (" + unread + ")" : "Open"),
						b -> Compat.setScreen(new ServersScreen(this, Social.str(r.o, "id"), Social.str(r.o, "name")))).bounds(right - 70, y, 70, 20).build());
			} else if (r.kind.equals("text")) {
				int unread = r.o.has("unread") ? r.o.get("unread").getAsInt() : 0;
				JsonObject target = r.o.deepCopy();
				target.addProperty("name", "#" + Social.str(r.o, "name") + " · " + serverName);
				addRenderableWidget(Button.builder(Component.literal(unread > 0 ? "Chat (" + unread + ")" : "Chat"),
						b -> Compat.setScreen(ChatScreen.channel(this, target, names))).bounds(right - 70, y, 70, 20).build());
			}
			y += ROW;
		}
		int by = height - 28;
		if (rows.size() > per) {
			addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(cx + 2, by, 20, 20).build()).active = page > 0;
			addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(cx + 24, by, 20, 20).build()).active = (page + 1) * per < rows.size();
		}
		addRenderableWidget(Button.builder(Component.literal(serverId == null ? "Done" : "< Servers"), b -> onClose()).bounds(cx + 74, by, 80, 20).build());
	}

	private void join() {
		String code = inviteBox.getValue().trim();
		if (code.isEmpty()) return;
		status = "Joining…";
		Social.async(() -> Social.joinServer(code)).whenComplete((r, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) { status = FriendsScreen.cause(err); rebuildWidgets(); return; }
			inviteBox.setValue("");
			inviteText = "";
			JsonObject s = r.getAsJsonObject("server");
			status = (r.has("already") ? "You're already in " : "Joined ") + Social.str(s, "name");
			if (r.has("onboarding") && r.get("onboarding").getAsBoolean()) {
				status += " - open it in the Jace Social app to finish its rules and questions";
			}
			reload();
		}));
	}

	//? if >=26.1 {
	@Override
	public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawContent(new Draw(g));
	}
	//?} else {
	/*@Override
	public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float delta) {
		//? if <1.20.2
		/^renderBackground(g);^/
		super.render(g, mouseX, mouseY, delta);
		drawContent(new Draw(g));
	}
	*///?}

	private void drawContent(Draw g) {
		int cx = width / 2;
		String t = serverId == null ? title.getString() : serverName;
		g.text(font, t, cx - font.width(t) / 2, 10, 0xFFFFFFFF);
		String st = font.plainSubstrByWidth(status, 320);
		g.text(font, st, cx - font.width(st) / 2, 22, 0xFFA0A6B0);
		int per = perPage();
		int y = 64;
		int left = cx - 154;
		for (int i = page * per; i < Math.min(rows.size(), (page + 1) * per); i++) {
			Row r = rows.get(i);
			String name = Social.str(r.o, "name");
			switch (r.kind) {
				case "server" -> {
					g.fill(left, y + 5, left + 6, y + 11, 0xFF3DDC84);
					g.text(font, font.plainSubstrByWidth(name, 200), left + 10, y + 6, 0xFFFFFFFF);
				}
				case "category" -> g.text(font, font.plainSubstrByWidth(name.toUpperCase(), 230), left, y + 8, 0xFF8B919C);
				case "voice" -> {
					g.text(font, "♪ " + font.plainSubstrByWidth(name, 120), left + 8, y + 1, 0xFFE6E8EB);
					g.text(font, font.plainSubstrByWidth(r.extra.isEmpty() ? "Voice - join in the Jace Social app" : "In voice: " + r.extra, 290),
							left + 8, y + 11, r.extra.isEmpty() ? 0xFF6B717C : 0xFF3DDC84);
				}
				default -> {
					int unread = r.o.has("unread") ? r.o.get("unread").getAsInt() : 0;
					g.text(font, "# " + font.plainSubstrByWidth(name, 200), left + 8, y + 6, unread > 0 ? 0xFFFFFFFF : 0xFFB5BAC1);
				}
			}
			y += ROW;
		}
		if (rows.isEmpty() && !status.equals("Loading…") && serverId == null) {
			String hint = "Paste an invite above, or make a server in the Jace Social app";
			g.text(font, hint, cx - font.width(hint) / 2, 80, 0xFF8B919C);
		}
	}

	@Override
	public void onClose() {
		Compat.setScreen(parent);
		if (parent instanceof ServersScreen s) s.reload();
		if (parent instanceof FriendsScreen f) f.reload();
	}
}
