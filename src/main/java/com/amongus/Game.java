package com.amongus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** All Among Us game logic. Everything runs on the server thread. */
public final class Game {
	public enum Phase { LOBBY, PLAYING, DISCUSS, VOTE, EJECT }

	// ---- settings (change with /among set <key> <value>) ----
	static int impostors = 0;        // 0 = automatic   (/impostor <n>)
	static int crewmates = 0;        // 0 = not set     (/crewmate <n>) -> lobby size and auto-start
	static int shapeshifters = 1;
	static int engineers = 1;
	static int scientists = 1;
	static int easyTasks = 3;
	static int mediumTasks = 3;
	static int hardTasks = 2;
	static int killCd = 25;          // seconds
	static int discuss = 20;         // seconds
	static int voteTime = 30;        // seconds
	static int meetings = 1;         // emergency meetings per player
	static int shiftDuration = 30;   // seconds
	static int ventDuration = 7;     // engineer: seconds allowed inside the vents
	static int ventCooldown = 30;    // engineer: seconds to wait before venting again
	static int buttonCd = 15;        // emergency button cooldown (seconds)
	static boolean o2Easy = false;   // true = the O2 code is also shown at the O2 keypad
	static boolean redSword = true;
	static int taskTime = 200;       // task time limits in percent (100 = normal, 200 = double time)

	// ---- state ----
	static MinecraftServer server;
	static MapData map = MapData.load();
	static Phase phase = Phase.LOBBY;
	static final Map<UUID, PState> players = new LinkedHashMap<>();
	static final Set<String> dirtyNames = new HashSet<>();
	static final List<Body> bodies = new ArrayList<>();
	static final List<int[]> bloodSpots = new ArrayList<>();
	static final ArrayDeque<Object> work = new ArrayDeque<>(); // build queue: String command | Integer wait | Runnable
	static UUID host;
	static boolean created;          // is there a game right now?
	static String gameName = "";
	static boolean isPublic = true;
	static String gameCode = "";
	static boolean testMode;
	static boolean lightsOut;
	static boolean[] lightsState = new boolean[0];
	static int reactorTicks;
	static int reactorHold;
	static int o2Ticks;
	static int o2CodeVal;
	static int buttonLeft;
	static int phaseTicks;
	static int autoCount = -1;
	static int waitTicks;
	static long tickN;
	static boolean hudDirty;
	private static final Random RNG = new Random();

	static final class TaskRef {
		final int idx;
		int step;
		boolean done;
		long waitUntil;
		int stage;

		TaskRef(int idx) {
			this.idx = idx;
		}
	}

	static final class Body {
		final int color;
		final double x;
		final double y;
		final double z;

		Body(int color, double x, double y, double z) {
			this.color = color;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	static final class PState {
		final UUID id;
		final String name;
		int slot;
		int color = -1;
		Role role = Role.CREWMATE;
		boolean alive = true;
		int killCd;
		int sabCd;
		int shiftLeft;
		int shiftCd;
		int shiftColor = -1;
		int ventLeft;
		int ventCd;
		int hopCd;
		int meetingsLeft;
		int vitalsLeft;
		int votedFor = -2; // -2 = not voted, -1 = skip, else color index
		final List<TaskRef> tasks = new ArrayList<>();
		Tasks.Run run;
		List<String> hudLines;
		String loc = "";
		String locSent = "";

		PState(UUID id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	// =====================================================================
	// helpers
	// =====================================================================

	static void cmd(String c) {
		if (server == null) return;
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), c);
		} catch (Exception e) {
			AmongUsMod.LOGGER.warn("Command failed: {}", c, e);
		}
	}

	static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	static String txt(String s, String color) {
		return "{\"text\":\"" + esc(s) + "\",\"color\":\"" + color + "\"}";
	}

	static String btn(String label, String color, String command) {
		return "{\"text\":\"[" + esc(label) + "] \",\"color\":\"" + color
			+ "\",\"click_event\":{\"action\":\"run_command\",\"command\":\"" + esc(command) + "\"}}";
	}

	// ---- SNBT helpers for the 1.21.6+ dialog screens ----
	static String sn(String s) {
		return "\"" + esc(s) + "\"";
	}

	static String comp(String text, String color) {
		return "{text:" + sn(text) + ",color:" + sn(color) + "}";
	}

	static String line(String text, String color) {
		return "{type:\"minecraft:plain_message\",contents:" + comp(text, color) + ",width:260}";
	}

	/** A click-to-suggest button for chat (fills the chat box instead of running). */
	static String sbtn(String label, String color, String command) {
		return "{\"text\":\"[" + esc(label) + "] \",\"color\":\"" + color
			+ "\",\"click_event\":{\"action\":\"suggest_command\",\"command\":\"" + esc(command) + "\"}}";
	}

	/** Info screen with one OK button. */
	static void notice(String name, String title, String titleColor, List<String> bodyLines) {
		StringBuilder sb = new StringBuilder("{type:\"minecraft:notice\",title:" + comp(title, titleColor) + ",body:[");
		for (int i = 0; i < bodyLines.size(); i++) {
			if (i > 0) sb.append(",");
			sb.append(bodyLines.get(i));
		}
		sb.append("]}");
		cmd("dialog show " + name + " " + sb);
	}

	static void raw(String name, String json) {
		cmd("tellraw " + name + " " + json);
	}

	static void tell(String name, String s, String color) {
		raw(name, txt(s, color));
	}

	static void tellAll(String s, String color) {
		for (PState p : players.values()) tell(p.name, s, color);
	}

	static void bar(String name, String s, String color) {
		cmd("title " + name + " actionbar " + txt(s, color));
	}

	static void title(String name, String main, String sub, String color) {
		cmd("title " + name + " times 10 70 20");
		cmd("title " + name + " subtitle " + txt(sub, "white"));
		cmd("title " + name + " title " + txt(main, color));
	}

	static String f(double d) {
		return String.format(Locale.ROOT, "%.2f", d);
	}

	static void tp(String name, double x, double y, double z) {
		cmd("tp " + name + " " + f(x) + " " + f(y) + " " + f(z));
	}

	static String label(PState p) {
		return p.color >= 0 ? AmongColor.label(p.color) : p.name;
	}

	static ServerPlayer find(UUID id) {
		return server == null ? null : server.getPlayerList().getPlayer(id);
	}

	static boolean hasArmor(ServerPlayer sp) {
		return !sp.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
			|| !sp.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
			|| !sp.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
			|| !sp.getItemBySlot(EquipmentSlot.FEET).isEmpty();
	}

	static double dist2(ServerPlayer sp, MapData.P q) {
		double dx = sp.getX() - q.cx();
		double dy = sp.getY() - q.y;
		double dz = sp.getZ() - q.cz();
		return dx * dx + dy * dy + dz * dz;
	}

	static List<PState> alive() {
		List<PState> l = new ArrayList<>();
		for (PState p : players.values()) if (p.alive) l.add(p);
		return l;
	}

	static PState byColor(int c) {
		for (PState p : players.values()) if (p.color == c) return p;
		return null;
	}

	static int lobbyTarget() {
		return crewmates > 0 ? Math.max(impostors, 1) + crewmates : 0;
	}

	static String targetSuffix() {
		int t = lobbyTarget();
		return t > 0 ? "/" + t : "";
	}

	static void dress(String name, int rgb) {
		String c = "[dyed_color=" + rgb + ",unbreakable={},enchantments={\"minecraft:binding_curse\":1}]";
		cmd("item replace entity " + name + " armor.head with minecraft:leather_helmet" + c);
		cmd("item replace entity " + name + " armor.chest with minecraft:leather_chestplate" + c);
		cmd("item replace entity " + name + " armor.legs with minecraft:leather_leggings" + c);
		cmd("item replace entity " + name + " armor.feet with minecraft:leather_boots" + c);
	}

	static int shownColor(PState p) {
		return p.shiftLeft > 0 && p.shiftColor >= 0 ? p.shiftColor : p.color;
	}

	static void cleanupPlayer(String name, boolean adventure) {
		cmd("effect clear " + name);
		cmd("clear " + name + " minecraft:netherite_sword");
		cmd("item replace entity " + name + " armor.head with minecraft:air");
		cmd("item replace entity " + name + " armor.chest with minecraft:air");
		cmd("item replace entity " + name + " armor.legs with minecraft:air");
		cmd("item replace entity " + name + " armor.feet with minecraft:air");
		cmd("gamemode " + (adventure ? "adventure " : "survival ") + name);
		if (!adventure) cmd("tag " + name + " remove amongus_in");
	}

	static void resetWorldState() {
		bodies.clear();
		clearBodies();
		cmd("bossbar remove amongus:tasks");
		lightsOut = false;
		reactorTicks = 0;
		reactorHold = 0;
		o2Ticks = 0;
		phase = Phase.LOBBY;
		testMode = false;
		autoCount = -1;
		hudDirty = true;
	}


	/** Dead body: a coloured chestplate + legs + bone lying flat on the floor, an interaction box to click, and blood. */
	static void spawnBody(int color, double x, double y, double z) {
		int rgb = AmongColor.RGB[color];
		String dye = "components:{\"minecraft:dyed_color\":" + rgb + "}";
		String rot = "left_rotation:[-0.7071f,0f,0f,0.7071f],right_rotation:[0f,0f,0f,1f]";
		cmd("summon minecraft:item_display " + f(x) + " " + f(y + 0.04) + " " + f(z)
			+ " {Tags:[\"amongus_body\"],item:{id:\"minecraft:leather_chestplate\",count:1," + dye + "},item_display:\"fixed\","
			+ "transformation:{" + rot + ",translation:[0f,0f,-0.2f],scale:[1.6f,1.6f,1.6f]}}");
		cmd("summon minecraft:item_display " + f(x) + " " + f(y + 0.04) + " " + f(z)
			+ " {Tags:[\"amongus_body\"],item:{id:\"minecraft:leather_leggings\",count:1," + dye + "},item_display:\"fixed\","
			+ "transformation:{" + rot + ",translation:[0f,0f,0.45f],scale:[1.6f,1.6f,1.6f]}}");
		cmd("summon minecraft:item_display " + f(x) + " " + f(y + 0.06) + " " + f(z)
			+ " {Tags:[\"amongus_body\"],item:{id:\"minecraft:bone\",count:1},item_display:\"fixed\","
			+ "transformation:{" + rot + ",translation:[0.5f,0f,-0.5f],scale:[0.8f,0.8f,0.8f]}}");
		cmd("summon minecraft:interaction " + f(x) + " " + f(y) + " " + f(z)
			+ " {Tags:[\"amongus_body\"],width:1.4f,height:0.6f,response:1b}");
		// blood: red carpets around the body (only on empty floor) + a red splash
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		int by = (int) Math.floor(y);
		int[][] spots = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}};
		for (int[] sp : spots) {
			if (sp[0] != 0 && sp[1] != 0 && RNG.nextBoolean()) continue;
			int px = bx + sp[0];
			int pz = bz + sp[1];
			cmd("fill " + px + " " + by + " " + pz + " " + px + " " + by + " " + pz + " minecraft:red_carpet replace minecraft:air");
			bloodSpots.add(new int[] {px, by, pz});
		}
		cmd("particle minecraft:block{block_state:\"minecraft:redstone_block\"} " + f(x) + " " + f(y + 0.3) + " " + f(z) + " 0.4 0.3 0.4 0.05 60");
	}

	static void clearBodies() {
		cmd("kill @e[tag=amongus_body]");
		for (int[] b : bloodSpots) {
			cmd("fill " + b[0] + " " + b[1] + " " + b[2] + " " + b[0] + " " + b[1] + " " + b[2] + " minecraft:air replace minecraft:red_carpet");
		}
		bloodSpots.clear();
	}

	static void reward(PState p, int coins) {
		Stats.get(p.id).coins += coins;
		hudDirty = true;
	}

	// =====================================================================
	// lobby: join / leave / menu / settings
	// =====================================================================

	static void menu(ServerPlayer sp) {
		String n = sp.getName().getString();
		raw(n, txt("=== AMONG US ===", "gold"));
		raw(n, "[" + btn("CREATE", "green", "/among create") + "," + btn("GAMES", "aqua", "/among games") + ","
			+ btn("JOIN", "green", "/among join") + "," + btn("LEAVE", "red", "/among leave") + ","
			+ btn("START", "yellow", "/among start") + "," + btn("STATUS", "aqua", "/among status") + ","
			+ btn("STATS", "gold", "/among stats") + "," + btn("HELP", "white", "/among help") + "]");
	}

	static void help(ServerPlayer sp) {
		String n = sp.getName().getString();
		String[] lines = {
			"/among create [name] - make a game (name = your name by default) | rename <name> | public | private [code]",
			"/among games - list games | /among join [name] [code] | leave | start [force] | stop | status | stats | tasks",
			"/among minimap right|top|off - where the mini map and room name show up",
			"/impostor <n>  /crewmate <n>  - how many impostors / crewmates (lobby starts itself when full)",
			"/among report - report a nearby body (or right-click it)",
			"/among vote <color|skip> - during voting (a voting screen opens; /among vote alone reopens it)",
			"/among code <number> - for 'code' tasks;  /among o2 <code> - fix O2",
			"/among sabotage lights|o2|reactor - impostors",
			"/among shift <color> - shapeshifter | /among vitals - scientist",
			"/among set <key> <value> - settings (see /among status)",
			"/among map buildmap - builds the big Skeld map where you stand",
			"Emergency meeting = the red button in the middle of the Cafeteria.",
			"Tasks = lecterns (green=easy, yellow=medium, red=very hard). Vents = iron trapdoors (click)."
		};
		for (String l : lines) tell(n, l, "gray");
	}

	static void status(ServerPlayer sp) {
		String n = sp.getName().getString();
		tell(n, "Game: " + (created ? gameName + (isPublic ? " (public)" : " (private" + (sp.getUUID().equals(host) ? ", code " + gameCode : "") + ")") : "none")
			+ " | Phase: " + phase + " | Players: " + players.size() + "/" + AmongColor.NAMES.length
			+ " | Map ready: " + map.ready() + " | Lobby target: " + (lobbyTarget() > 0 ? lobbyTarget() : "none"), "yellow");
		tell(n, "impostors=" + impostors + " (0=auto) crewmates=" + crewmates + " shapeshifters=" + shapeshifters
			+ " engineers=" + engineers + " scientists=" + scientists, "gray");
		tell(n, "easytasks=" + easyTasks + " mediumtasks=" + mediumTasks + " hardtasks=" + hardTasks
			+ " killcd=" + killCd + " discuss=" + discuss + " vote=" + voteTime + " meetings=" + meetings, "gray");
		tell(n, "shiftduration=" + shiftDuration + " ventduration=" + ventDuration + " ventcooldown=" + ventCooldown
			+ " buttoncd=" + buttonCd + " o2easy=" + (o2Easy ? 1 : 0) + " redsword=" + (redSword ? 1 : 0)
			+ " tasktime=" + taskTime + "%", "gray");
	}

	static void minimapInfo(ServerPlayer sp) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null) {
			tell(n, "Join a game first (/among join).", "red");
			return;
		}
		int m = Hud.mode(p);
		tell(n, "Mini map: " + (m == Hud.MODE_RIGHT ? "RIGHT" : m == Hud.MODE_TOP ? "TOP" : "OFF")
			+ "  -  change it with /among minimap right | top | off", "yellow");
	}

	static void setMinimap(ServerPlayer sp, int mode) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null) {
			tell(n, "Join a game first (/among join).", "red");
			return;
		}
		if (p.slot >= Hud.SIDEBAR_SLOTS) {
			tell(n, "Players 17-20 only get the top location bar (Minecraft has 16 sidebar colours).", "yellow");
			return;
		}
		Hud.setMode(p, mode);
		tell(n, mode == Hud.MODE_RIGHT ? "Mini map on the RIGHT (with your room name)."
			: mode == Hud.MODE_TOP ? "Room name on TOP only; task list stays on the right."
			: "Mini map and room name OFF.", "green");
	}

	static void stats(ServerPlayer sp) {
		String n = sp.getName().getString();
		Stats.S s = Stats.get(sp.getUUID());
		tell(n, "--- Your stats ---", "gold");
		tell(n, "Coins: " + s.coins + " | Wins: " + s.wins + " | Games: " + s.games, "yellow");
		tell(n, "Kills: " + s.kills + " | Tasks completed: " + s.tasks, "yellow");
	}

	static void setImpostors(ServerPlayer sp, Integer n) {
		String name = sp.getName().getString();
		if (n == null) {
			tell(name, "Impostors: " + (impostors == 0 ? "auto" : String.valueOf(impostors)) + " (use /impostor <number>, 0 = auto)", "yellow");
			return;
		}
		if (phase != Phase.LOBBY) {
			tell(name, "Can't change this during a game.", "red");
			return;
		}
		impostors = Math.min(n, 3);
		tellAll("Impostors set to " + (impostors == 0 ? "auto" : String.valueOf(impostors)) + "." + lobbyHint(), "green");
		hudDirty = true;
	}

	static void setCrewmates(ServerPlayer sp, Integer n) {
		String name = sp.getName().getString();
		if (n == null) {
			tell(name, "Crewmates: " + (crewmates == 0 ? "not set" : String.valueOf(crewmates)) + " (use /crewmate <number>)", "yellow");
			return;
		}
		if (phase != Phase.LOBBY) {
			tell(name, "Can't change this during a game.", "red");
			return;
		}
		crewmates = Math.min(n, AmongColor.NAMES.length - 1);
		if (lobbyTarget() > AmongColor.NAMES.length) crewmates = AmongColor.NAMES.length - Math.max(impostors, 1);
		tellAll("Crewmates set to " + (crewmates == 0 ? "not set" : String.valueOf(crewmates)) + "." + lobbyHint(), "green");
		hudDirty = true;
	}

	private static String lobbyHint() {
		int t = lobbyTarget();
		return t > 0 ? " The game starts by itself when " + t + " players have joined." : "";
	}

	// ---------------------------------------------------------------------
	// creating / listing / joining games (one game at a time)
	// ---------------------------------------------------------------------

	private static String cleanName(String raw, String fallback) {
		StringBuilder sb = new StringBuilder();
		if (raw != null) {
			for (char ch : raw.toCharArray()) {
				if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') sb.append(ch);
			}
		}
		String r = sb.toString();
		if (r.length() > 16) r = r.substring(0, 16);
		return r.isEmpty() ? fallback : r;
	}

	private static String hostName() {
		PState h = host == null ? null : players.get(host);
		return h == null ? "?" : h.name;
	}

	static void closeGame() {
		created = false;
		gameName = "";
		gameCode = "";
		isPublic = true;
		host = null;
	}

	static void create(ServerPlayer sp, String nameArg) {
		String n = sp.getName().getString();
		if (created) {
			tell(n, "A game already exists: \"" + gameName + "\" (host " + hostName() + "). Only one game at a time.", "red");
			return;
		}
		if (phase != Phase.LOBBY) {
			tell(n, "Wait for the current round to finish.", "red");
			return;
		}
		if (!map.ready()) {
			tell(n, "No map yet. Stand in a big flat area and run /among map buildmap", "red");
			return;
		}
		if (!work.isEmpty()) {
			tell(n, "The map is still being built. Wait for it to finish.", "red");
			return;
		}
		if (hasArmor(sp)) {
			tell(n, "Take off your armor first (it would be replaced during the game).", "red");
			return;
		}
		created = true;
		gameName = cleanName(nameArg, n);
		isPublic = true;
		gameCode = "";
		host = null;
		joinNow(sp);
		tell(n, "Game \"" + gameName + "\" created and PUBLIC. You are the host.", "gold");
		tell(n, "/among rename <name> | /among private [code] | /among public | /among start", "gray");
		announce();
	}

	/** Tell everybody on the server that a game is open. */
	private static void announce() {
		if (server == null || !created) return;
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			if (players.containsKey(o.getUUID())) continue;
			String on = o.getName().getString();
			if (isPublic) {
				raw(on, "[" + txt("Among Us game \"" + gameName + "\" is open!  ", "gold") + "," + btn("CLICK TO JOIN", "green", "/among join " + gameName) + "]");
			} else {
				raw(on, "[" + txt("A PRIVATE Among Us game \"" + gameName + "\" was created. Ask the host for the code: ", "gold")
					+ "," + sbtn("JOIN", "green", "/among join " + gameName + " ") + "]");
			}
		}
	}

	static void rename(ServerPlayer sp, String nameArg) {
		String n = sp.getName().getString();
		if (!created || !sp.getUUID().equals(host)) {
			tell(n, "Only the host of a game can rename it.", "red");
			return;
		}
		gameName = cleanName(nameArg, gameName);
		tellAll("The game is now called \"" + gameName + "\".", "gold");
		hudDirty = true;
	}

	static void setPublic(ServerPlayer sp) {
		String n = sp.getName().getString();
		if (!created || !sp.getUUID().equals(host)) {
			tell(n, "Only the host of a game can do that.", "red");
			return;
		}
		isPublic = true;
		gameCode = "";
		tellAll("The game is now PUBLIC. Anyone can join.", "green");
		if (phase == Phase.LOBBY) announce();
	}

	static void setPrivate(ServerPlayer sp, String code) {
		String n = sp.getName().getString();
		if (!created || !sp.getUUID().equals(host)) {
			tell(n, "Only the host of a game can do that.", "red");
			return;
		}
		isPublic = false;
		gameCode = code != null && !code.isEmpty() ? code : String.valueOf(1000 + RNG.nextInt(9000));
		tellAll("The game is now PRIVATE.", "yellow");
		tell(n, "Join code: " + gameCode + "  (friends join with /among join " + gameName + " " + gameCode + ")", "gold");
	}

	static void games(ServerPlayer sp) {
		String n = sp.getName().getString();
		if (!created) {
			tell(n, "No games right now. Make one with /among create [name]", "yellow");
			return;
		}
		tell(n, "=== GAMES ===", "gold");
		tell(n, gameName + "  |  host: " + hostName() + "  |  players: " + players.size() + "/" + AmongColor.NAMES.length
			+ "  |  " + (isPublic ? "PUBLIC" : "PRIVATE") + "  |  " + (phase == Phase.LOBBY ? "in lobby" : "round running"),
			isPublic ? "green" : "yellow");
		if (phase == Phase.LOBBY && !players.containsKey(sp.getUUID())) {
			raw(n, "[" + (isPublic ? btn("JOIN " + gameName, "green", "/among join " + gameName)
				: sbtn("JOIN " + gameName + " (needs code)", "yellow", "/among join " + gameName + " ")) + "]");
		}
	}

	/** /among join [name] [code] */
	static void join(ServerPlayer sp, String wanted, String code) {
		String name = sp.getName().getString();
		if (!created) {
			tell(name, "There is no game yet. Create one with /among create [name]", "red");
			return;
		}
		if (wanted != null && !wanted.equalsIgnoreCase(gameName)) {
			tell(name, "No game called \"" + wanted + "\". See /among games", "red");
			return;
		}
		if (!isPublic && !players.containsKey(sp.getUUID())) {
			if (code == null) {
				tell(name, "This game is PRIVATE. Use /among join " + gameName + " <code>", "red");
				return;
			}
			if (!code.equals(gameCode)) {
				tell(name, "Wrong code.", "red");
				return;
			}
		}
		joinNow(sp);
	}

	private static void joinNow(ServerPlayer sp) {
		String name = sp.getName().getString();
		if (phase != Phase.LOBBY) {
			tell(name, "A game is running. Wait for it to end.", "red");
			return;
		}
		if (players.containsKey(sp.getUUID())) {
			tell(name, "You already joined.", "yellow");
			return;
		}
		if (players.size() >= AmongColor.NAMES.length) {
			tell(name, "The game is full (" + AmongColor.NAMES.length + ").", "red");
			return;
		}
		if (map.lobby == null) {
			tell(name, "No map yet. Stand in a big flat area and run /among map buildmap", "red");
			return;
		}
		if (hasArmor(sp)) {
			tell(name, "Take off your armor first (it would be replaced during the game).", "red");
			return;
		}
		PState p = new PState(sp.getUUID(), name);
		boolean[] used = new boolean[AmongColor.NAMES.length];
		for (PState o : players.values()) used[o.slot] = true;
		for (int i = 0; i < used.length; i++) {
			if (!used[i]) {
				p.slot = i;
				break;
			}
		}
		players.put(sp.getUUID(), p);
		if (host == null) host = p.id;
		cmd("tag " + name + " add amongus_in");
		cmd("gamemode adventure " + name);
		tp(name, map.lobby.cx(), map.lobby.y, map.lobby.cz());
		Hud.setup(p);
		tellAll(name + " joined \"" + gameName + "\" (" + players.size() + targetSuffix() + (lobbyTarget() > 0 ? "" : "/" + AmongColor.NAMES.length) + ")", "green");
		hudDirty = true;
		if (p.id.equals(host)) {
			tell(name, "You are the host. Start with /among start, or set /impostor and /crewmate and the game starts when full.", "gold");
		}
	}

	static void leave(UUID id, boolean disconnected) {
		PState p = players.get(id);
		if (p == null) return;
		if (phase != Phase.LOBBY && p.alive) {
			p.alive = false;
			tellAll(label(p) + " left the game.", "gray");
		}
		players.remove(id);
		Hud.teardown(p);
		if (disconnected) dirtyNames.add(p.name);
		else cleanupPlayer(p.name, false);
		if (p.id.equals(host)) {
			host = players.isEmpty() ? null : players.keySet().iterator().next();
			if (host != null) tellAll(players.get(host).name + " is now the host.", "gold");
		}
		if (players.isEmpty()) {
			resetWorldState();
			closeGame();
			return;
		}
		hudDirty = true;
		if (phase != Phase.LOBBY) {
			updateBar();
			checkWin();
		}
	}

	/** Called when any player joins the server. */
	static void onServerJoin(ServerPlayer sp) {
		String name = sp.getName().getString();
		if (dirtyNames.remove(name)) {
			cleanupPlayer(name, false);
		}
		if (created && phase == Phase.LOBBY) {
			if (isPublic) {
				raw(name, "[" + txt("Among Us game \"" + gameName + "\" is open!  ", "gold") + "," + btn("CLICK TO JOIN", "green", "/among join " + gameName) + "]");
			} else {
				raw(name, "[" + txt("A private Among Us game \"" + gameName + "\" exists. Ask the host for the code.  ", "gold")
					+ "," + sbtn("JOIN", "green", "/among join " + gameName + " ") + "]");
			}
		} else if (map.ready() && !created) {
			raw(name, "[" + txt("Among Us is on this server!  ", "gold") + "," + btn("CREATE A GAME", "green", "/among create") + "]");
		}
	}

	// =====================================================================
	// start / stop
	// =====================================================================

	static void startCmd(ServerPlayer sp, boolean force) {
		String name = sp.getName().getString();
		if (host != null && !sp.getUUID().equals(host)) {
			tell(name, "Only the host (" + players.get(host).name + ") can start the game.", "red");
			return;
		}
		String err = start(force);
		if (err != null) tell(name, err, "red");
	}

	static void assignTasks(PState p) {
		p.tasks.clear();
		for (int diff = 1; diff <= 3; diff++) {
			int want = diff == 1 ? easyTasks : diff == 2 ? mediumTasks : hardTasks;
			List<Integer> pool = new ArrayList<>();
			for (int i = 0; i < map.tasks.size(); i++) if (map.tasks.get(i).diff == diff) pool.add(i);
			Collections.shuffle(pool, RNG);
			for (int i = 0; i < Math.min(want, pool.size()); i++) p.tasks.add(new TaskRef(pool.get(i)));
		}
	}

	static String start(boolean force) {
		if (phase != Phase.LOBBY) return "A game is already running.";
		if (!map.ready()) return "No map yet. Run /among map buildmap first.";
		if (!work.isEmpty()) return "The map is still being built. Wait for it to finish.";
		int n = players.size();
		if (n < 1) return "Nobody joined. Use /among join.";
		if (n < 3 && !force) return "Need at least 3 players. (/among start force = solo test mode)";
		for (PState p : players.values()) {
			ServerPlayer sp = find(p.id);
			if (sp == null) return p.name + " is offline.";
			if (hasArmor(sp)) return p.name + " must take off their armor first.";
		}

		testMode = n < 3;
		List<PState> list = new ArrayList<>(players.values());
		Collections.shuffle(list, RNG);

		List<Integer> cols = new ArrayList<>();
		for (int i = 0; i < AmongColor.NAMES.length; i++) cols.add(i);
		Collections.shuffle(cols, RNG);

		for (int i = 0; i < n; i++) {
			PState p = list.get(i);
			p.color = cols.get(i);
			p.role = Role.CREWMATE;
			p.alive = true;
			p.run = null;
			p.killCd = 10 * 20;
			p.sabCd = 15 * 20;
			p.shiftLeft = 0;
			p.shiftCd = 0;
			p.shiftColor = -1;
			p.ventLeft = 0;
			p.ventCd = 0;
			p.hopCd = 0;
			p.meetingsLeft = meetings;
			p.vitalsLeft = 3;
			p.votedFor = -2;
		}

		int nImp = impostors > 0 ? impostors : (n >= 14 ? 3 : n >= 7 ? 2 : 1);
		if (n < 3) nImp = n == 2 ? 1 : 0;
		else nImp = Math.min(nImp, (n - 1) / 2);
		int nShape = Math.min(shapeshifters, nImp);
		int idx = 0;
		for (int i = 0; i < nImp; i++) {
			list.get(idx++).role = i < nShape ? Role.SHAPESHIFTER : Role.IMPOSTOR;
		}
		int nEng = Math.min(engineers, n - idx);
		for (int i = 0; i < nEng; i++) list.get(idx++).role = Role.ENGINEER;
		int nSci = Math.min(scientists, n - idx);
		for (int i = 0; i < nSci; i++) list.get(idx++).role = Role.SCIENTIST;

		// tasks: everyone gets a list; impostors' list is fake (they can't finish them)
		for (PState p : list) assignTasks(p);

		bodies.clear();
		clearBodies();
		lightsOut = false;
		reactorTicks = 0;
		reactorHold = 0;
		o2Ticks = 0;
		for (int k = 0; k < map.switches.size(); k++) setLever(k, true);

		for (PState p : list) {
			cmd("effect clear " + p.name);
			Hud.hideNames(p, true);
			cmd("gamemode adventure " + p.name);
			cmd("effect give " + p.name + " minecraft:invisibility infinite 0 true");
			cmd("effect give " + p.name + " minecraft:saturation infinite 0 true");
			cmd("effect give " + p.name + " minecraft:resistance infinite 4 true");
			dress(p.name, AmongColor.RGB[p.color]);
			if (p.role.impostor) giveSword(p.name);
		}
		gather(5.0);

		cmd("bossbar remove amongus:tasks");
		cmd("bossbar add amongus:tasks " + txt("Total Tasks Completed", "green"));
		cmd("bossbar set amongus:tasks color green");
		cmd("bossbar set amongus:tasks style notched_10");
		cmd("bossbar set amongus:tasks max 100");
		cmd("bossbar set amongus:tasks value 0");
		cmd("bossbar set amongus:tasks players @a[tag=amongus_in]");

		phase = Phase.PLAYING;
		buttonLeft = buttonCd * 20;
		autoCount = -1;

		for (PState p : list) {
			title(p.name, p.role.display.toUpperCase(Locale.ROOT), "You are " + AmongColor.label(p.color), p.role.color);
			tell(p.name, "You are the " + p.role.display + " (" + AmongColor.label(p.color) + "). " + p.role.desc, p.role.color);
			if (p.role.impostor) {
				StringBuilder sb = new StringBuilder();
				for (PState o : list) {
					if (o != p && o.role.impostor) {
						if (sb.length() > 0) sb.append(", ");
						sb.append(AmongColor.label(o.color)).append(" (").append(o.name).append(")");
					}
				}
				tell(p.name, sb.length() == 0 ? "You are the only impostor." : "Fellow impostors: " + sb, "red");
				tell(p.name, "Your task list on screen is FAKE - pretend to do tasks to blend in.", "gray");
			} else {
				tell(p.name, "Your tasks are in the box on the right. More info: /among tasks", "gray");
			}
		}
		Hud.refreshAll(true);
		if (testMode) tellAll("TEST MODE: fewer than 3 players, the game will not end by itself. Use /among stop.", "yellow");
		return null;
	}

	static void giveSword(String name) {
		String model = redSword ? ",item_model=\"amongus:red_sword\"" : "";
		cmd("give " + name + " minecraft:netherite_sword[item_name={text:\"Impostor Blade\",color:\"red\"},"
			+ "unbreakable={},enchantment_glint_override=true" + model + "]");
	}

	static void stopCmd(ServerPlayer sp) {
		String name = sp.getName().getString();
		if (host != null && !sp.getUUID().equals(host)) {
			tell(name, "Only the host can stop the game.", "red");
			return;
		}
		if (phase == Phase.LOBBY) {
			tell(name, "No game is running.", "yellow");
			return;
		}
		endGame(null, "The host stopped the game.");
	}

	/** Teleport all alive players in a circle around the emergency button. */
	static void gather(double radius) {
		List<PState> al = alive();
		int n = al.size();
		MapData.P c = map.button;
		double floorY = map.lobby != null ? map.lobby.y : c.y;
		for (int i = 0; i < n; i++) {
			double a = 2 * Math.PI * i / Math.max(n, 1);
			double x = c.cx() + radius * Math.cos(a);
			double z = c.cz() + radius * Math.sin(a);
			cmd("tp " + al.get(i).name + " " + f(x) + " " + f(floorY) + " " + f(z)
				+ " facing " + f(c.cx()) + " " + f(c.y) + " " + f(c.cz()));
		}
	}

	// =====================================================================
	// main tick
	// =====================================================================

	static void pump() {
		if (waitTicks > 0) {
			waitTicks--;
			return;
		}
		int n = 0;
		while (!work.isEmpty() && n < 40) {
			Object o = work.poll();
			if (o instanceof String s) {
				cmd(s);
				n++;
			} else if (o instanceof Integer w) {
				waitTicks = w;
				return;
			} else if (o instanceof Runnable r) {
				r.run();
			}
		}
	}

	static void tick(MinecraftServer s) {
		server = s;
		pump();
		if (players.isEmpty()) return;
		tickN++;

		if (hudDirty || tickN % 10 == 0) {
			hudDirty = false;
			Hud.refreshAll(false);
		}
		if (phase != Phase.LOBBY && tickN % 20 == 0) {
			// no night vision cheating
			for (PState p : players.values()) cmd("effect clear " + p.name + " minecraft:night_vision");
		}

		if (phase == Phase.LOBBY) {
			lobbyTick();
			return;
		}
		if (phase == Phase.EJECT) {
			if (--phaseTicks <= 0) endMeeting();
			return;
		}
		if (phase == Phase.DISCUSS) {
			if (--phaseTicks <= 0) beginVoting();
			else if (tickN % 20 == 0) {
				for (PState p : alive()) bar(p.name, "Discussion: " + (phaseTicks / 20) + "s - talk in chat!", "yellow");
			}
			return;
		}
		if (phase == Phase.VOTE) {
			phaseTicks--;
			boolean all = true;
			for (PState p : alive()) if (p.votedFor == -2) all = false;
			if (all || phaseTicks <= 0) {
				tally();
			} else if (tickN % 20 == 0) {
				for (PState p : alive()) {
					if (p.votedFor == -2) bar(p.name, "Vote now! " + (phaseTicks / 20) + "s left", "gold");
				}
			}
			return;
		}

		// PLAYING
		if (buttonLeft > 0) buttonLeft--;
		for (PState p : new ArrayList<>(players.values())) {
			if (phase != Phase.PLAYING) break;
			if (!p.alive) continue;
			ServerPlayer sp = find(p.id);
			if (sp == null) continue;

			if (p.killCd > 0) p.killCd--;
			if (p.sabCd > 0) p.sabCd--;
			if (p.hopCd > 0) p.hopCd--;
			if (p.ventLeft > 0) {
				p.ventLeft--;
				if (p.ventLeft == 0) endVent(p);
				else if (tickN % 10 == 0) bar(p.name, "In vent: " + (p.ventLeft / 20 + 1) + "s left (click a vent to hop)", "gray");
			} else if (p.ventCd > 0) {
				p.ventCd--;
			}
			if (p.shiftLeft > 0) {
				p.shiftLeft--;
				if (p.shiftLeft == 0) unshift(p);
			} else if (p.shiftCd > 0) {
				p.shiftCd--;
			}

			if (p.run != null) Tasks.tick(p, sp);

			if (tickN % 40 == 0) {
				if (sp.getItemBySlot(EquipmentSlot.HEAD).isEmpty() || sp.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
					|| sp.getItemBySlot(EquipmentSlot.LEGS).isEmpty() || sp.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
					dress(p.name, AmongColor.RGB[shownColor(p)]);
				}
			}
			if (lightsOut && tickN % 100 == 0 && !p.role.impostor) {
				cmd("effect give " + p.name + " minecraft:blindness infinite 0 true");
			}
			if (tickN % 10 == 0 && p.run == null && p.ventLeft == 0 && !sabotageActive()) hud(p);
		}
		if (phase == Phase.PLAYING) sabotageTick();
	}

	static boolean sabotageActive() {
		return reactorTicks > 0 || o2Ticks > 0;
	}

	static void lobbyTick() {
		if (tickN % 100 == 0 && host != null) {
			PState h = players.get(host);
			for (PState p : players.values()) {
				bar(p.name, "Lobby: " + players.size() + targetSuffix() + " player(s). Host (" + (h == null ? "?" : h.name)
					+ ") starts with /among start", "yellow");
			}
		}
		int target = lobbyTarget();
		if (target > 0 && players.size() >= target && map.ready() && work.isEmpty()) {
			if (autoCount < 0) {
				autoCount = 200;
				tellAll("The lobby is full! The game starts in 10 seconds...", "gold");
			}
			if (--autoCount == 0) {
				String err = start(target < 3);
				if (err != null) {
					tellAll("Can't start: " + err, "red");
					autoCount = -1;
				}
			}
		} else {
			autoCount = -1;
		}
	}

	static void hud(PState p) {
		if (p.role.impostor) {
			String s = "Kill: " + (p.killCd > 0 ? (p.killCd / 20 + 1) + "s" : "READY")
				+ "  |  Sabotage: " + (p.sabCd > 0 ? (p.sabCd / 20 + 1) + "s" : "READY");
			if (p.role == Role.SHAPESHIFTER) {
				s += "  |  Shift: " + (p.shiftLeft > 0 ? "ACTIVE " + (p.shiftLeft / 20) + "s"
					: p.shiftCd > 0 ? (p.shiftCd / 20 + 1) + "s" : "READY");
			}
			bar(p.name, s, "red");
		} else {
			int done = 0;
			for (TaskRef t : p.tasks) if (t.done) done++;
			String s = "Tasks: " + done + "/" + p.tasks.size();
			if (p.role == Role.ENGINEER) s += "  |  Vent: " + (p.ventCd > 0 ? (p.ventCd / 20 + 1) + "s" : "READY");
			if (p.role == Role.SCIENTIST) s += "  |  Vitals left: " + p.vitalsLeft;
			bar(p.name, s, "green");
		}
	}

	// =====================================================================
	// tasks
	// =====================================================================

	static void completeTask(PState p, TaskRef ref, MapData.TaskDef def) {
		ref.done = true;
		p.run = null;
		int coins = def.diff == 1 ? 10 : def.diff == 2 ? 20 : 40;
		Stats.S st = Stats.get(p.id);
		st.tasks++;
		st.coins += coins;
		Stats.save();
		tell(p.name, "Task complete: " + def.name + " [" + MapData.diffName(def.diff) + "]  +" + coins + " coins", "green");
		cmd("playsound minecraft:entity.experience_orb.pickup master " + p.name);
		hudDirty = true;
		updateBar();
		checkWin();
	}

	static void enterCode(ServerPlayer sp, int code) {
		Tasks.code(sp, code);
	}

	static void showTasks(ServerPlayer sp) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase == Phase.LOBBY) {
			tell(n, "No game running.", "gray");
			return;
		}
		tell(n, p.role.impostor ? "Your FAKE tasks (blend in!):" : "Your tasks:", p.role.impostor ? "red" : "gold");
		for (TaskRef t : p.tasks) {
			MapData.TaskDef d = map.tasks.get(t.idx);
			String where;
			if (t.done) {
				where = "done";
			} else {
				MapData.Step s = d.steps.get(Math.min(t.step, d.steps.size() - 1));
				where = s.room + " (" + s.pos.x + ", " + s.pos.y + ", " + s.pos.z + ")"
					+ (d.steps.size() > 1 ? " step " + (t.step + 1) + "/" + d.steps.size() : "");
			}
			tell(n, (t.done ? "[done] " : "[todo] ") + d.name + " [" + MapData.diffName(d.diff) + "] - " + where,
				t.done ? "dark_gray" : MapData.diffColor(d.diff));
		}
	}

	static void updateBar() {
		int total = 0;
		int done = 0;
		for (PState p : players.values()) {
			if (p.role.impostor || !p.alive) continue;
			for (TaskRef t : p.tasks) {
				total++;
				if (t.done) done++;
			}
		}
		int v = total == 0 ? 0 : done * 100 / total;
		cmd("bossbar set amongus:tasks value " + v);
	}

	// =====================================================================
	// kill / bodies / report
	// =====================================================================

	static InteractionResult onAttack(Player player, Level world, InteractionHand hand, Entity target) {
		if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
		PState a = players.get(sp.getUUID());
		if (a == null) return InteractionResult.PASS;
		if (phase == Phase.PLAYING && a.alive && a.role.impostor && target instanceof ServerPlayer tp
			&& sp.getMainHandItem().is(Items.NETHERITE_SWORD)) {
			PState v = players.get(tp.getUUID());
			if (v != null && v.alive && !v.role.impostor) {
				if (a.killCd > 0) {
					bar(a.name, "Kill cooldown: " + (a.killCd / 20 + 1) + "s", "red");
				} else if (sp.distanceToSqr(tp) > 16.0) {
					bar(a.name, "Too far away.", "red");
				} else {
					kill(a, v, tp);
				}
			}
		}
		return InteractionResult.FAIL;
	}

	static void kill(PState killer, PState victim, ServerPlayer victimPlayer) {
		double x = victimPlayer.getX();
		double y = victimPlayer.getY();
		double z = victimPlayer.getZ();
		victim.alive = false;
		victim.run = null;
		if (victim.ventLeft > 0) victim.ventLeft = 0;
		killer.killCd = killCd * 20;
		Stats.S ks = Stats.get(killer.id);
		ks.kills++;
		ks.coins += 25;
		Stats.save();

		double fy = map.lobby != null ? map.lobby.y : y;
		bodies.add(new Body(victim.color, x, fy, z));
		spawnBody(victim.color, x, fy, z);

		cmd("gamemode spectator " + victim.name);
		title(victim.name, "YOU DIED", "You were killed. You are a ghost now.", "red");
		tp(killer.name, x, y, z);
		hudDirty = true;
		updateBar();
		checkWin();
	}

	static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
		if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.PASS;
		}
		PState p = players.get(sp.getUUID());
		if (p == null) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();

		// electrical levers: never toggled by vanilla, only by our lights puzzle
		for (int k = 0; k < map.switches.size(); k++) {
			if (map.switches.get(k).is(pos)) {
				if (phase == Phase.PLAYING && p.alive) leverClick(p, k);
				return InteractionResult.SUCCESS;
			}
		}
		if (phase != Phase.PLAYING || !p.alive) return InteractionResult.PASS;

		if (map.button != null && map.button.is(pos)) {
			emergency(p);
			return InteractionResult.PASS;
		}
		if (map.o2Code != null && map.o2Code.is(pos)) {
			o2CodeClick(p);
			return InteractionResult.SUCCESS;
		}
		if (map.o2Panel != null && map.o2Panel.is(pos)) {
			o2PanelClick(p);
			return InteractionResult.SUCCESS;
		}
		for (int k = 0; k < map.reactors.size(); k++) {
			if (map.reactors.get(k).is(pos)) {
				reactorClick(p, map.reactors.size() == 1);
				return InteractionResult.SUCCESS;
			}
		}
		for (int i = 0; i < map.vents.size(); i++) {
			if (map.vents.get(i).pos.is(pos)) {
				ventUse(p, sp, i);
				return InteractionResult.SUCCESS;
			}
		}
		if (Tasks.click(p, sp, pos)) return InteractionResult.SUCCESS;
		return InteractionResult.PASS;
	}

	static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand, Entity entity) {
		if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.PASS;
		}
		PState p = players.get(sp.getUUID());
		if (p == null) return InteractionResult.PASS;
		if (entity.getTags().contains("amongus_body")) {
			if (phase == Phase.PLAYING && p.alive) reportNear(p, sp);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	static void reportCmd(ServerPlayer sp) {
		PState p = players.get(sp.getUUID());
		if (p == null || phase != Phase.PLAYING || !p.alive) {
			tell(sp.getName().getString(), "You can't report right now.", "red");
			return;
		}
		reportNear(p, sp);
	}

	static void reportNear(PState p, ServerPlayer sp) {
		Body best = null;
		double bd = 64.0; // 8 blocks squared
		for (Body b : bodies) {
			double dx = sp.getX() - b.x;
			double dy = sp.getY() - b.y;
			double dz = sp.getZ() - b.z;
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bd) {
				bd = d;
				best = b;
			}
		}
		if (best == null) {
			bar(p.name, "No body nearby.", "gray");
			return;
		}
		startMeeting(p, best.color);
	}

	// =====================================================================
	// vents
	// =====================================================================

	static void ventUse(PState p, ServerPlayer sp, int i) {
		boolean imp = p.role.impostor;
		if (!imp && p.role != Role.ENGINEER) {
			bar(p.name, "Only impostors and engineers can use vents.", "red");
			return;
		}
		if (p.hopCd > 0) return;
		if (!imp && p.ventLeft <= 0) {
			if (p.ventCd > 0) {
				bar(p.name, "Vent cooldown: " + (p.ventCd / 20 + 1) + "s", "red");
				return;
			}
			p.ventLeft = ventDuration * 20; // start the vent session
			tell(p.name, "You entered the vents. You have " + ventDuration + " seconds - click vents to hop.", "gray");
		}
		// travel only inside the vent's own group
		int group = map.vents.get(i).group;
		List<Integer> members = new ArrayList<>();
		for (int k = 0; k < map.vents.size(); k++) if (map.vents.get(k).group == group) members.add(k);
		if (members.size() < 2) {
			bar(p.name, "This vent is not connected to anything.", "gray");
			return;
		}
		int at = members.indexOf(i);
		int n = members.size();
		int next = members.get(sp.isShiftKeyDown() ? (at - 1 + n) % n : (at + 1) % n);
		MapData.P dest = map.vents.get(next).pos;
		p.hopCd = 20;
		tp(p.name, dest.cx(), dest.y + 0.1, dest.cz());
		if (imp) bar(p.name, "Vent (click = next, sneak+click = previous)", "gray");
	}

	/** Engineer leaves the vents (time ran out or a meeting started): cooldown begins. */
	static void endVent(PState p) {
		p.ventLeft = 0;
		p.ventCd = ventCooldown * 20;
		bar(p.name, "You left the vents. Vent cooldown: " + ventCooldown + "s", "gray");
	}

	// =====================================================================
	// sabotage: lights (switches), O2 (code), reactor (two panels)
	// =====================================================================

	static void sabotageMenu(ServerPlayer sp) {
		String n = sp.getName().getString();
		raw(n, "[" + btn("LIGHTS", "yellow", "/among sabotage lights") + "," + btn("O2", "aqua", "/among sabotage o2") + ","
			+ btn("REACTOR", "red", "/among sabotage reactor") + "]");
	}

	static void sabotage(ServerPlayer sp, String kind) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.PLAYING || !p.alive || !p.role.impostor) {
			tell(n, "Only living impostors can sabotage during the game.", "red");
			return;
		}
		if (p.sabCd > 0) {
			tell(n, "Sabotage cooldown: " + (p.sabCd / 20 + 1) + "s", "red");
			return;
		}
		if (lightsOut || sabotageActive()) {
			tell(n, "A sabotage is already active.", "red");
			return;
		}
		switch (kind) {
			case "lights" -> {
				if (map.switches.isEmpty()) {
					tell(n, "This map has no electrical switches.", "red");
					return;
				}
				lightsOut = true;
				int cnt = map.switches.size();
				lightsState = new boolean[cnt];
				boolean allOn = true;
				for (int k = 0; k < cnt; k++) {
					lightsState[k] = RNG.nextBoolean();
					if (!lightsState[k]) allOn = false;
				}
				if (allOn) lightsState[RNG.nextInt(cnt)] = false;
				for (int k = 0; k < cnt; k++) setLever(k, lightsState[k]);
				for (PState c : players.values()) {
					if (!c.role.impostor) {
						if (c.alive) cmd("effect give " + c.name + " minecraft:blindness infinite 0 true");
						title(c.name, "LIGHTS OUT", "Flip ALL switches ON in Electrical!", "red");
					}
				}
			}
			case "o2" -> {
				if (map.o2Panel == null) {
					tell(n, "This map has no O2 panel.", "red");
					return;
				}
				o2Ticks = 45 * 20;
				o2CodeVal = 10000 + RNG.nextInt(90000);
				for (PState c : players.values()) {
					if (!c.role.impostor) title(c.name, "O2 DEPLETED", "Read the code at the Admin panel, enter it in O2!", "red");
				}
			}
			default -> {
				if (map.reactors.isEmpty()) {
					tell(n, "This map has no reactor panels.", "red");
					return;
				}
				reactorTicks = 45 * 20;
				reactorHold = 0;
				for (PState c : players.values()) {
					if (!c.role.impostor) title(c.name, "REACTOR MELTDOWN", "Two players: HOLD SNEAK at both Reactor panels!", "red");
				}
			}
		}
		p.sabCd = 30 * 20;
	}

	static void sabotageTick() {
		if (o2Ticks > 0) {
			o2Ticks--;
			if (tickN % 10 == 0) {
				for (PState p : alive()) {
					if (!p.role.impostor) bar(p.name, "O2 DEPLETED in " + (o2Ticks / 20) + "s! Code is at Admin, enter it at the O2 keypad: /among o2 <code>", "red");
				}
			}
			if (o2Ticks == 0) {
				if (!testMode) endGame(false, "Everyone suffocated - O2 was not restored!");
				else tellAll("O2 ran out (test mode, game continues).", "red");
				return;
			}
		}
		if (reactorTicks > 0) {
			reactorTicks--;
			if (map.reactors.size() >= 2) {
				PState occ0 = null;
				PState occ1 = null;
				for (PState c : alive()) {
					if (c.role.impostor) continue;
					ServerPlayer s = find(c.id);
					if (s == null || !s.isShiftKeyDown()) continue;
					if (occ0 == null && dist2(s, map.reactors.get(0)) <= 9.0) occ0 = c;
					else if (occ1 == null && dist2(s, map.reactors.get(1)) <= 9.0) occ1 = c;
				}
				if (occ0 != null && occ1 != null && occ0 != occ1) {
					reactorHold++;
					if (reactorHold % 10 == 0) {
						bar(occ0.name, "Hold! " + (reactorHold * 100 / 60) + "%", "green");
						bar(occ1.name, "Hold! " + (reactorHold * 100 / 60) + "%", "green");
					}
					if (reactorHold >= 60) {
						reactorTicks = 0;
						reactorHold = 0;
						tellAll("Reactor stabilised by " + label(occ0) + " and " + label(occ1) + ".", "green");
						return;
					}
				} else {
					reactorHold = 0;
				}
			}
			if (tickN % 10 == 0) {
				for (PState p : alive()) {
					if (!p.role.impostor) bar(p.name, "REACTOR MELTDOWN in " + (reactorTicks / 20) + "s! Two players HOLD SNEAK at both Reactor panels", "red");
				}
			}
			if (reactorTicks == 0) {
				if (!testMode) endGame(false, "The reactor melted down!");
				else tellAll("Reactor meltdown (test mode, game continues).", "red");
			}
		}
	}

	static void setLever(int k, boolean on) {
		MapData.P q = map.switches.get(k);
		cmd("setblock " + q.x + " " + q.y + " " + q.z + " minecraft:lever[face=floor,facing=north,powered=" + on + "]");
	}

	static void leverClick(PState p, int k) {
		if (!lightsOut) {
			bar(p.name, "The lights are fine - nothing to fix.", "gray");
			return;
		}
		if (p.role.impostor) {
			bar(p.name, "Impostors can't fix sabotage.", "red");
			return;
		}
		if (k >= lightsState.length) return;
		lightsState[k] = !lightsState[k];
		setLever(k, lightsState[k]);
		int on = 0;
		for (boolean b : lightsState) if (b) on++;
		bar(p.name, "Switches ON: " + on + "/" + lightsState.length, "yellow");
		if (on == lightsState.length) {
			lightsOut = false;
			clearBlind();
			tellAll("The lights are back! Fixed by " + label(p) + ".", "green");
		}
	}

	static void clearBlind() {
		for (PState c : players.values()) cmd("effect clear " + c.name + " minecraft:blindness");
	}

	static void o2CodeClick(PState p) {
		if (o2Ticks <= 0) {
			bar(p.name, "The O2 system is fine.", "gray");
			return;
		}
		if (p.role.impostor) {
			bar(p.name, "Impostors can't fix sabotage.", "red");
			return;
		}
		title(p.name, "O2 CODE: " + o2CodeVal, "Go to O2 and type /among o2 " + o2CodeVal, "aqua");
		tell(p.name, "O2 code: " + o2CodeVal + "  -> go to the O2 keypad and type /among o2 " + o2CodeVal, "aqua");
	}

	static void o2PanelClick(PState p) {
		if (o2Ticks <= 0) {
			bar(p.name, "The O2 system is fine.", "gray");
			return;
		}
		if (p.role.impostor) {
			bar(p.name, "Impostors can't fix sabotage.", "red");
			return;
		}
		tell(p.name, "O2 keypad: type /among o2 <code>. The code is shown on the Admin panel.", "aqua");
		if (o2Easy) tell(p.name, "(easy mode) The code is " + o2CodeVal, "aqua");
	}

	static void o2Enter(ServerPlayer sp, int code) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.PLAYING || !p.alive) return;
		if (o2Ticks <= 0) {
			tell(n, "The O2 system is fine.", "gray");
			return;
		}
		if (p.role.impostor) {
			tell(n, "Impostors can't fix sabotage.", "red");
			return;
		}
		if (map.o2Panel != null && dist2(sp, map.o2Panel) > 64.0) {
			tell(n, "You must be next to the O2 keypad to enter the code.", "red");
			return;
		}
		if (code != o2CodeVal) {
			tell(n, "Wrong code!", "red");
			return;
		}
		o2Ticks = 0;
		tellAll("O2 restored by " + label(p) + ".", "green");
	}

	static void reactorClick(PState p, boolean single) {
		if (reactorTicks <= 0) {
			bar(p.name, "The reactor is stable.", "gray");
			return;
		}
		if (p.role.impostor) {
			bar(p.name, "Impostors can't fix sabotage.", "red");
			return;
		}
		if (single) {
			reactorTicks = 0;
			tellAll("Reactor stabilised by " + label(p) + ".", "green");
		} else {
			bar(p.name, "Two players must HOLD SNEAK next to the two panels at the same time.", "yellow");
		}
	}

	// =====================================================================
	// abilities
	// =====================================================================

	static void shift(ServerPlayer sp, String colorName) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.PLAYING || !p.alive || p.role != Role.SHAPESHIFTER) {
			tell(n, "Only a living Shapeshifter can do that during the game.", "red");
			return;
		}
		if (colorName == null) {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for (PState o : alive()) {
				if (o == p) continue;
				if (!first) sb.append(",");
				first = false;
				sb.append(btn(AmongColor.label(o.color), AmongColor.hex(o.color), "/among shift " + AmongColor.NAMES[o.color]));
			}
			sb.append("]");
			if (first) tell(n, "Nobody to copy.", "gray");
			else raw(n, sb.toString());
			return;
		}
		if (p.shiftLeft > 0) {
			tell(n, "You are already shifted.", "red");
			return;
		}
		if (p.shiftCd > 0) {
			tell(n, "Shift cooldown: " + (p.shiftCd / 20 + 1) + "s", "red");
			return;
		}
		int ci = AmongColor.indexOf(colorName);
		PState t = ci < 0 ? null : byColor(ci);
		if (t == null || !t.alive || t == p) {
			tell(n, "Pick a living player's color.", "red");
			return;
		}
		p.shiftColor = ci;
		p.shiftLeft = shiftDuration * 20;
		dress(p.name, AmongColor.RGB[ci]);
		tell(n, "You now look like " + AmongColor.label(ci) + " for " + shiftDuration + "s.", "gold");
	}

	static void unshift(PState p) {
		p.shiftLeft = 0;
		p.shiftCd = 20 * 20;
		p.shiftColor = -1;
		if (p.alive) {
			dress(p.name, AmongColor.RGB[p.color]);
			tell(p.name, "You returned to your own color.", "gray");
		}
	}

	static void vitals(ServerPlayer sp) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.PLAYING || !p.alive || p.role != Role.SCIENTIST) {
			tell(n, "Only a living Scientist can do that during the game.", "red");
			return;
		}
		if (p.vitalsLeft <= 0) {
			tell(n, "No vitals uses left.", "red");
			return;
		}
		p.vitalsLeft--;
		tell(n, "--- VITALS (" + p.vitalsLeft + " uses left) ---", "aqua");
		for (PState o : players.values()) {
			tell(n, AmongColor.label(o.color) + ": " + (o.alive ? "ALIVE" : "DEAD"), o.alive ? "green" : "red");
		}
	}

	// =====================================================================
	// meetings and voting
	// =====================================================================

	static void emergency(PState p) {
		if (sabotageActive()) {
			bar(p.name, "You can't call a meeting during a sabotage!", "red");
			return;
		}
		if (buttonLeft > 0) {
			bar(p.name, "The emergency button is recharging: " + (buttonLeft / 20 + 1) + "s", "red");
			return;
		}
		if (p.meetingsLeft <= 0) {
			bar(p.name, "You have no emergency meetings left.", "red");
			return;
		}
		p.meetingsLeft--;
		startMeeting(p, -1);
	}

	static void startMeeting(PState caller, int bodyColor) {
		phase = Phase.DISCUSS;
		phaseTicks = discuss * 20;
		lightsOut = false;
		reactorTicks = 0;
		reactorHold = 0;
		o2Ticks = 0;
		clearBlind();
		for (int k = 0; k < map.switches.size(); k++) setLever(k, true);
		bodies.clear();
		clearBodies();
		for (PState p : players.values()) {
			p.run = null;
			p.votedFor = -2;
			if (p.shiftLeft > 0) unshift(p);
			if (p.ventLeft > 0) endVent(p);
		}
		gather(4.0);
		for (PState p : alive()) {
			cmd("effect give " + p.name + " minecraft:slowness infinite 10 true");
		}
		String head = bodyColor >= 0 ? "BODY REPORTED" : "EMERGENCY MEETING";
		String sub = bodyColor >= 0 ? label(caller) + " found the body of " + AmongColor.label(bodyColor)
			: label(caller) + " pressed the button";
		for (PState p : players.values()) {
			title(p.name, head, sub, "red");
			tell(p.name, head + ": " + sub + ". Discuss in chat! Voting starts in " + discuss + "s.", "gold");
		}
		meetingScreen(caller, bodyColor);
		hudDirty = true;
	}

	/** "Color (Name)" of a player, from the color. */
	static String who(int color) {
		PState o = byColor(color);
		return AmongColor.label(color) + (o != null ? " (" + o.name + ")" : "");
	}

	/** The Among Us style screen every player sees when a body is reported / the button is pressed. */
	static void meetingScreen(PState caller, int bodyColor) {
		boolean body = bodyColor >= 0;
		List<String> lines = new ArrayList<>();
		lines.add(line(who(caller.color) + (body ? " reported a dead body!" : " pressed the emergency button!"), "gold"));
		if (body) lines.add(line("DEAD: " + who(bodyColor), "red"));
		lines.add(line(" ", "white"));
		lines.add(line("Players alive:", "green"));
		for (PState o : alive()) lines.add(line("- " + who(o.color), AmongColor.hex(o.color)));
		for (PState o : players.values()) {
			if (!o.alive && o.color != bodyColor && o.color >= 0) lines.add(line("- " + who(o.color) + "  (dead)", "gray"));
		}
		lines.add(line(" ", "white"));
		lines.add(line("Discuss in chat. Voting opens in " + discuss + " seconds.", "yellow"));
		for (PState p : players.values()) notice(p.name, body ? "BODY REPORTED" : "EMERGENCY MEETING", "red", lines);
	}

	/** Voting screen: one button per living player ("Color - Name") plus Skip. */
	static void voteScreen(PState p) {
		StringBuilder sb = new StringBuilder("{type:\"minecraft:multi_action\",title:" + comp("WHO IS THE IMPOSTOR?", "red")
			+ ",columns:2,body:[" + line("Click a player to vote, or Skip. You can also type /among vote <color>", "yellow") + "],actions:[");
		boolean first = true;
		for (PState o : alive()) {
			if (!first) sb.append(",");
			first = false;
			sb.append("{label:").append(comp(AmongColor.label(o.color) + " - " + o.name + (o == p ? " (you)" : ""), AmongColor.hex(o.color)))
				.append(",action:{type:\"minecraft:run_command\",command:").append(sn("among vote " + AmongColor.NAMES[o.color])).append("}}");
		}
		sb.append(",{label:").append(comp("Skip Vote", "gray"))
			.append(",action:{type:\"minecraft:run_command\",command:\"among vote skip\"}}]}");
		cmd("dialog show " + p.name + " " + sb);
	}

	static void beginVoting() {
		phase = Phase.VOTE;
		phaseTicks = voteTime * 20;
		StringBuilder sb = new StringBuilder("[");
		for (PState o : alive()) {
			sb.append(btn(AmongColor.label(o.color), AmongColor.hex(o.color), "/among vote " + AmongColor.NAMES[o.color])).append(",");
		}
		sb.append(btn("SKIP", "gray", "/among vote skip")).append("]");
		for (PState p : players.values()) {
			tell(p.name, "=== VOTE === click a color (or SKIP). Type /among vote <color> if clicking doesn't work.", "gold");
			if (p.alive) {
				raw(p.name, sb.toString());
				voteScreen(p);
			}
		}
	}

	/** /among vote (no argument): reopen the voting screen. */
	static void voteReopen(ServerPlayer sp) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.VOTE || !p.alive) {
			tell(n, "You can't vote right now.", "red");
			return;
		}
		if (p.votedFor != -2) {
			tell(n, "You already voted.", "red");
			return;
		}
		voteScreen(p);
	}

	static void vote(ServerPlayer sp, String target) {
		PState p = players.get(sp.getUUID());
		String n = sp.getName().getString();
		if (p == null || phase != Phase.VOTE || !p.alive) {
			tell(n, "You can't vote right now.", "red");
			return;
		}
		if (p.votedFor != -2) {
			tell(n, "You already voted.", "red");
			return;
		}
		int v;
		if ("skip".equalsIgnoreCase(target)) {
			v = -1;
		} else {
			int ci = AmongColor.indexOf(target);
			PState t = ci < 0 ? null : byColor(ci);
			if (t == null || !t.alive) {
				tell(n, "Vote for the color of a living player, or skip.", "red");
				return;
			}
			v = ci;
		}
		p.votedFor = v;
		tell(n, "Vote registered.", "green");
		tellAll(label(p) + " voted.", "gray");
	}

	static void tally() {
		int[] counts = new int[AmongColor.NAMES.length];
		int skips = 0;
		for (PState p : alive()) {
			if (p.votedFor == -1 || p.votedFor == -2) skips++;
			else counts[p.votedFor]++;
		}
		StringBuilder res = new StringBuilder("Votes: ");
		int best = -1;
		int bestN = 0;
		boolean tie = false;
		for (int i = 0; i < counts.length; i++) {
			if (counts[i] == 0) continue;
			res.append(AmongColor.label(i)).append("=").append(counts[i]).append(" ");
			if (counts[i] > bestN) {
				best = i;
				bestN = counts[i];
				tie = false;
			} else if (counts[i] == bestN) {
				tie = true;
			}
		}
		res.append("Skip=").append(skips);
		tellAll(res.toString(), "yellow");

		List<String> lines = new ArrayList<>();
		if (best < 0 || tie || skips >= bestN) {
			tellAll("Nobody was ejected.", "gray");
			lines.add(line("No one was ejected.", "gray"));
			lines.add(line(tie && best >= 0 ? "(Tie)" : "(Skipped)", "gray"));
		} else {
			PState e = byColor(best);
			if (e != null) {
				e.alive = false;
				cmd("gamemode spectator " + e.name);
				cmd("effect clear " + e.name + " minecraft:slowness");
				tellAll(AmongColor.label(best) + " was ejected. " + AmongColor.label(best) + " was "
					+ (e.role.impostor ? "An Impostor." : "not An Impostor."), e.role.impostor ? "green" : "red");
				lines.add(line(who(best) + " was " + (e.role.impostor ? "An Impostor." : "not An Impostor."), e.role.impostor ? "green" : "red"));
			}
		}
		lines.add(line(" ", "white"));
		lines.add(line(res.toString(), "yellow"));
		for (PState p : players.values()) notice(p.name, "EJECTION", "gold", lines);
		phase = Phase.EJECT;
		phaseTicks = 120; // 6 seconds to read the result
		hudDirty = true;
	}

	static void endMeeting() {
		phase = Phase.PLAYING;
		buttonLeft = buttonCd * 20;
		for (PState p : alive()) {
			cmd("effect clear " + p.name + " minecraft:slowness");
			p.killCd = Math.max(p.killCd, killCd * 20);
		}
		gather(5.0);
		hudDirty = true;
		updateBar();
		checkWin();
	}

	// =====================================================================
	// win conditions and end of game
	// =====================================================================

	static void checkWin() {
		if (phase == Phase.LOBBY || testMode) return;
		int imp = 0;
		int crew = 0;
		for (PState p : players.values()) {
			if (!p.alive) continue;
			if (p.role.impostor) imp++;
			else crew++;
		}
		if (imp == 0) {
			endGame(true, "All impostors are gone!");
			return;
		}
		if (imp >= crew) {
			endGame(false, "The impostors outnumber the crew!");
			return;
		}
		int total = 0;
		int done = 0;
		for (PState p : players.values()) {
			if (!p.alive || p.role.impostor) continue;
			for (TaskRef t : p.tasks) {
				total++;
				if (t.done) done++;
			}
		}
		if (total > 0 && done == total) endGame(true, "All tasks were completed!");
	}

	/** crewWin: true = crew, false = impostors, null = stopped without winner. */
	static void endGame(Boolean crewWin, String reason) {
		String head = crewWin == null ? "GAME STOPPED" : crewWin ? "CREWMATES WIN" : "IMPOSTORS WIN";
		String color = crewWin == null ? "gray" : crewWin ? "green" : "red";
		for (PState p : players.values()) {
			title(p.name, head, reason, color);
			tell(p.name, head + " - " + reason, color);
		}
		if (crewWin != null) {
			tellAll("--- Roles ---", "gold");
			for (PState p : players.values()) {
				if (p.color < 0) continue;
				tellAll(AmongColor.label(p.color) + " = " + p.name + " (" + p.role.display + ")", p.role.color);
			}
			for (PState p : players.values()) {
				if (p.color < 0) continue;
				Stats.S st = Stats.get(p.id);
				st.games++;
				st.coins += 10;
				boolean won = crewWin ? !p.role.impostor : p.role.impostor;
				if (won) {
					st.wins++;
					st.coins += 100;
					tell(p.name, "You won! +100 coins (+10 for playing)", "gold");
				} else {
					tell(p.name, "+10 coins for playing", "gray");
				}
			}
			Stats.save();
		}
		for (PState p : players.values()) {
			cleanupPlayer(p.name, true);
			Hud.hideNames(p, false);
			if (map.lobby != null) tp(p.name, map.lobby.cx(), map.lobby.y, map.lobby.cz());
			p.alive = true;
			p.color = -1;
			p.role = Role.CREWMATE;
			p.tasks.clear();
			p.run = null;
		}
		for (int k = 0; k < map.switches.size(); k++) setLever(k, true);
		resetWorldState();
		Hud.refreshAll(true);
		tellAll("Back in the lobby. Host: /among start for another round.", "yellow");
	}
}
