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
import java.util.List;

/** Start a group chat: pick up to 9 friends and (optionally) a name. */
public class NewGroupScreen extends Screen {
	private static final int ROW = 24;
	private final FriendsScreen parent;
	private final List<JsonObject> friends = new ArrayList<>();
	private final List<String> picked = new ArrayList<>();
	private EditBox nameBox;
	private String name = "";
	private String status = "Pick friends for the group (up to 9)";
	private int page;

	public NewGroupScreen(FriendsScreen parent, JsonArray friends) {
		super(Component.literal("New group chat"));
		this.parent = parent;
		for (JsonElement e : friends) this.friends.add(e.getAsJsonObject());
		this.friends.sort((a, b) -> Social.str(a, "name").compareToIgnoreCase(Social.str(b, "name")));
	}

	private int perPage() {
		return Math.max(1, (height - 64 - 40) / ROW);
	}

	@Override
	protected void init() {
		int cx = width / 2;
		if (nameBox != null) name = nameBox.getValue();
		nameBox = new EditBox(font, cx - 150, 34, 300, 20, Component.literal("Group name"));
		nameBox.setHint(Component.literal("Group name (optional)"));
		nameBox.setMaxLength(48);
		nameBox.setValue(name);
		addRenderableWidget(nameBox);
		int per = perPage();
		int y = 64;
		for (int i = page * per; i < Math.min(friends.size(), (page + 1) * per); i++) {
			String uuid = Social.str(friends.get(i), "uuid");
			boolean on = picked.contains(uuid);
			addRenderableWidget(Button.builder(Component.literal(on ? "✔ Added" : "Add"), b -> {
				if (picked.contains(uuid)) picked.remove(uuid);
				else if (picked.size() < 9) picked.add(uuid);
				rebuildWidgets();
			}).bounds(cx + 80, y, 70, 20).build());
			y += ROW;
		}
		int by = height - 28;
		if (friends.size() > per) {
			addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(cx - 22, by, 20, 20).build()).active = page > 0;
			addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(cx + 2, by, 20, 20).build()).active = (page + 1) * per < friends.size();
		}
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(cx - 150, by, 70, 20).build());
		Button create = addRenderableWidget(Button.builder(Component.literal("Create"), b -> create()).bounds(cx + 80, by, 70, 20).build());
		create.active = !picked.isEmpty();
	}

	private void create() {
		String n = nameBox.getValue().trim();
		List<String> members = new ArrayList<>(picked);
		status = "Creating…";
		Social.async(() -> Social.createGroup(n, members)).whenComplete((r, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				status = FriendsScreen.cause(err);
				return;
			}
			parent.reload();
			Compat.setScreen(parent);
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
		g.text(font, title.getString(), cx - font.width(title.getString()) / 2, 10, 0xFFFFFFFF);
		g.text(font, status, cx - font.width(status) / 2, 22, 0xFFA0A6B0);
		int per = perPage();
		int y = 64;
		for (int i = page * per; i < Math.min(friends.size(), (page + 1) * per); i++) {
			g.text(font, Social.str(friends.get(i), "name"), cx - 150, y + 6, 0xFFFFFFFF);
			y += ROW;
		}
	}

	@Override
	public void onClose() {
		Compat.setScreen(parent);
	}
}
