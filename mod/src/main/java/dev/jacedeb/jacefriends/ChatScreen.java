package dev.jacedeb.jacefriends;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Chat with one friend, or in a group chat or server channel (groupId set: the channel). */
public class ChatScreen extends Screen {
	private final Screen parent;
	private final JsonObject friend;          // direct message: the friend; group chat: the group
	private final String uuid;                // direct message: friend's id
	private final String groupId;             // group chat: its id, else null
	private final Map<String, String> names = new HashMap<>();
	private final List<JsonObject> messages = new ArrayList<>();
	private EditBox input;
	private String draft = "";
	private String status = "Loading…";

	public ChatScreen(Screen parent, JsonObject friend) {
		this(parent, friend, null);
	}

	private ChatScreen(Screen parent, JsonObject target, String groupId) {
		super(Component.literal(groupId == null ? "Chat with " + Social.str(target, "name") : Social.str(target, "name")));
		this.parent = parent;
		this.friend = target;
		this.groupId = groupId;
		this.uuid = groupId == null ? Social.str(target, "uuid") : "";
		if (groupId != null && target.has("members")) {
			for (JsonElement m : target.getAsJsonArray("members")) {
				names.put(Social.str(m.getAsJsonObject(), "uuid"), Social.str(m.getAsJsonObject(), "name"));
			}
		}
		reload();
	}

	public static ChatScreen group(Screen parent, JsonObject group) {
		return new ChatScreen(parent, group, Social.str(group, "id"));
	}

	/** A server channel; names = the server's members (for who said what and @mentions). */
	public static ChatScreen channel(Screen parent, JsonObject channel, Map<String, String> names) {
		ChatScreen s = new ChatScreen(parent, channel, Social.str(channel, "id"));
		s.names.putAll(names);
		return s;
	}

	/** Message text as shown in game: @names for mentions, and files listed. */
	private String shown(JsonObject m) {
		String body = Social.str(m, "body");
		java.util.regex.Matcher mm = java.util.regex.Pattern.compile("<@([0-9a-f]{32})>").matcher(body);
		StringBuilder out = new StringBuilder();
		while (mm.find()) mm.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("@" + names.getOrDefault(mm.group(1), "someone")));
		mm.appendTail(out);
		String text = out.toString().replaceAll("<@&[0-9a-f-]{36}>", "@role");
		if (m.has("attachments") && m.get("attachments").isJsonArray()) {
			for (JsonElement a : m.getAsJsonArray("attachments")) {
				text += (text.isEmpty() ? "" : "\n") + "[file: " + Social.str(a.getAsJsonObject(), "name") + "]";
			}
		}
		return text;
	}

	public boolean isWith(String other) {
		return groupId == null && uuid.equals(other);
	}

	public boolean isGroup(String id) {
		return groupId != null && groupId.equals(id);
	}

	public void reload() {
		Social.async(() -> {
			JsonObject r;
			if (groupId == null) {
				r = Social.messages(uuid);
				Social.markRead(uuid);
			} else {
				r = Social.groupMessages(groupId);
				Social.readGroup(groupId);
			}
			return r;
		}).whenComplete((r, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				status = FriendsScreen.cause(err);
				return;
			}
			messages.clear();
			for (JsonElement e : r.getAsJsonArray("messages")) messages.add(e.getAsJsonObject());
			if (r.has("people") && r.get("people").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("people").entrySet()) {
					names.put(e.getKey(), Social.str(e.getValue().getAsJsonObject(), "name"));
				}
			}
			status = groupId == null ? Social.describe(friend) : names.size() + " people";
		}));
	}

	@Override
	protected void init() {
		int cx = width / 2;
		if (input != null) draft = input.getValue();
		input = new EditBox(font, cx - 160, height - 30, 256, 20, Component.literal("Message"));
		input.setMaxLength(groupId == null ? 500 : 2000);
		input.setHint(Component.literal("Message " + Social.str(friend, "name") + "…"));
		input.setValue(draft);
		addRenderableWidget(input);
		setInitialFocus(input);
		addRenderableWidget(Button.builder(Component.literal("Send"), b -> send()).bounds(cx + 100, height - 30, 60, 20).build());
		addRenderableWidget(Button.builder(Component.literal("< Back"), b -> onClose()).bounds(6, 6, 60, 20).build());
		if (groupId == null) {
			addRenderableWidget(Button.builder(Component.literal("Call"), b -> Calls.call(friend, s -> status = s)).bounds(width - 66, 6, 60, 20).build());
		}
	}

	private void send() {
		String text = input.getValue().trim();
		if (text.isEmpty()) return;
		input.setValue("");
		Social.async(() -> {
			if (groupId == null) Social.send(uuid, text);
			else Social.sendGroup(groupId, text);
			return null;
		}).whenComplete((v, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				status = FriendsScreen.cause(err);
				input.setValue(text);
			} else {
				reload();
			}
		}));
	}

	//? if >=1.21.9 {
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (Compat.isEnter(event.input()) && input.isFocused()) {
			send();
			return true;
		}
		return super.keyPressed(event);
	}
	//?} else {
	/*@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (Compat.isEnter(key) && input.isFocused()) {
			send();
			return true;
		}
		return super.keyPressed(key, scanCode, modifiers);
	}
	*///?}

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
		g.text(font, title.getString(), cx - font.width(title.getString()) / 2, 10, 0xFFFFFFFF);
		g.text(font, status, cx - font.width(status) / 2, 22, 0xFF8B919C);
		int left = cx - 160;
		int wrap = 320;
		int top = 38;
		int y = height - 40;
		// draw newest at the bottom, going up until we run out of room
		for (int i = messages.size() - 1; i >= 0 && y > top; i--) {
			JsonObject m = messages.get(i);
			String sender = Social.str(m, "sender");
			boolean system = Social.str(m, "kind").equals("system");
			boolean mine = sender.equals(Social.myUuid());
			List<FormattedCharSequence> lines = font.split(Component.literal(shown(m)), wrap);
			if (system) {                                     // "Alex joined the group"
				y -= lines.size() * 10 + 4;
				if (y < top) break;
				int ly = y;
				for (FormattedCharSequence line : lines) {
					g.text(font, line, left, ly, 0xFF8B919C);
					ly += 10;
				}
				continue;
			}
			y -= lines.size() * 10 + 12;
			if (y < top) break;
			String who = mine ? "You" : groupId == null ? Social.str(friend, "name") : names.getOrDefault(sender, "Someone");
			g.text(font, who, left, y, mine ? 0xFF3DDC84 : 0xFF4F8FD6);
			int ly = y + 10;
			for (FormattedCharSequence line : lines) {
				g.text(font, line, left, ly, 0xFFE6E8EB);
				ly += 10;
			}
		}
		if (messages.isEmpty() && !status.equals("Loading…")) {
			String hint = "No messages yet - say hi!";
			g.text(font, hint, cx - font.width(hint) / 2, height / 2, 0xFF8B919C);
		}
	}

	@Override
	public void onClose() {
		Compat.setScreen(parent);
		if (parent instanceof FriendsScreen f) f.reload();
	}
}
