package com.amongus;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The on-screen boxes. Every player has their own team with a unique colour and their own scoreboard
 * objective, shown only to them through "sidebar.team.<colour>" (vanilla has 16 team colours, so players
 * 17-20 only get the top bar). Mini map modes:  right = mini map in the sidebar + room name on top,
 * top = task list in the sidebar + room name on top, off = task list only.
 */
final class Hud {
	private Hud() {}

	static final String[] TEAM_COLORS = {
		"red", "blue", "green", "light_purple", "gold", "yellow",
		"dark_gray", "white", "dark_purple", "dark_red", "aqua", "dark_green",
		"dark_blue", "dark_aqua", "gray", "black"
	};
	static final int SIDEBAR_SLOTS = TEAM_COLORS.length;

	static final int MODE_RIGHT = 0;
	static final int MODE_TOP = 1;
	static final int MODE_OFF = 2;
	private static final Map<UUID, Integer> MODES = new HashMap<>();

	// ---- mini map geometry (layout coordinates, relative to the Cafeteria centre) ----
	private static final int MAP_X0 = -123;
	private static final int MAP_X1 = 107;
	private static final int MAP_Z0 = -53;
	private static final int MAP_Z1 = 53;
	private static final int COLS = 20;
	private static final int ROWS = 9;
	private static int[][] grid; // room index, 100 = corridor, -1 = nothing

	static int mode(Game.PState p) {
		if (p.slot >= SIDEBAR_SLOTS) return MODE_TOP;
		return MODES.getOrDefault(p.id, MODE_RIGHT);
	}

	static void setMode(Game.PState p, int m) {
		MODES.put(p.id, m);
		p.hudLines = null;
		p.locSent = "";
		refresh(p, true);
	}

	static String obj(Game.PState p) {
		return "au_s" + p.slot;
	}

	static String team(Game.PState p) {
		return "au_t" + p.slot;
	}

	static String bossId(Game.PState p) {
		return "amongus:loc" + p.slot;
	}

	/** Create the player's team (names visible in the lobby), their location bar, and put them in it. */
	static void setup(Game.PState p) {
		Game.cmd("team remove " + team(p));
		Game.cmd("team add " + team(p));
		Game.cmd("team modify " + team(p) + " color " + TEAM_COLORS[p.slot % SIDEBAR_SLOTS]);
		Game.cmd("team modify " + team(p) + " nametagVisibility always");
		Game.cmd("team modify " + team(p) + " collisionRule never");
		Game.cmd("team join " + team(p) + " " + p.name);
		Game.cmd("bossbar remove " + bossId(p));
		Game.cmd("bossbar add " + bossId(p) + " " + Game.txt("Location: ...", "aqua"));
		Game.cmd("bossbar set " + bossId(p) + " color white");
		Game.cmd("bossbar set " + bossId(p) + " style progress");
		Game.cmd("bossbar set " + bossId(p) + " max 100");
		Game.cmd("bossbar set " + bossId(p) + " value 100");
		p.hudLines = null;
		p.locSent = "";
		refresh(p, true);
	}

	static void hideNames(Game.PState p, boolean hide) {
		Game.cmd("team modify " + team(p) + " nametagVisibility " + (hide ? "never" : "always"));
	}

	static void teardown(Game.PState p) {
		Game.cmd("scoreboard objectives remove " + obj(p));
		Game.cmd("team remove " + team(p));
		Game.cmd("bossbar remove " + bossId(p));
		p.hudLines = null;
	}

	static void refreshAll(boolean force) {
		for (Game.PState p : Game.players.values()) refresh(p, force);
	}

	// =====================================================================
	// rooms
	// =====================================================================

	private static int roomIndex(int rx, int rz) {
		for (int i = 0; i < Layout.ROOMS.length; i++) {
			int[] r = Layout.ROOMS[i];
			if (rx >= r[0] && rx <= r[2] && rz >= r[1] && rz <= r[3]) return i;
		}
		return -1;
	}

	private static boolean inCorridor(int rx, int rz) {
		for (int[] c : Layout.CORRIDORS) {
			if (rx >= c[0] && rx <= c[2] && rz >= c[1] && rz <= c[3]) return true;
		}
		return false;
	}

	private static int nearestRoom(int rx, int rz) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < Layout.ROOMS.length; i++) {
			int[] r = Layout.ROOMS[i];
			double dx = Math.max(Math.max(r[0] - rx, 0), rx - r[2]);
			double dz = Math.max(Math.max(r[1] - rz, 0), rz - r[3]);
			double d = dx * dx + dz * dz;
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	/** Name of the place at a world position. */
	static String roomAt(double x, double z) {
		MapData m = Game.map;
		int rx = (int) Math.floor(x) - m.originX();
		int rz = (int) Math.floor(z) - m.originZ();
		int i = roomIndex(rx, rz);
		if (i >= 0) return Layout.ROOM_NAMES[i];
		if (inCorridor(rx, rz)) return "Hallway near " + Layout.ROOM_NAMES[nearestRoom(rx, rz)];
		return "Outside the ship";
	}

	private static int currentRoomIndex(double x, double z) {
		MapData m = Game.map;
		return roomIndex((int) Math.floor(x) - m.originX(), (int) Math.floor(z) - m.originZ());
	}

	// =====================================================================
	// mini map
	// =====================================================================

	private static void buildGrid() {
		grid = new int[ROWS][COLS];
		double cw = (MAP_X1 - MAP_X0 + 1) / (double) COLS;
		double ch = (MAP_Z1 - MAP_Z0 + 1) / (double) ROWS;
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				double x0 = MAP_X0 + c * cw;
				double x1 = x0 + cw;
				double z0 = MAP_Z0 + r * ch;
				double z1 = z0 + ch;
				int best = -1;
				double bestA = 0;
				for (int i = 0; i < Layout.ROOMS.length; i++) {
					int[] q = Layout.ROOMS[i];
					double a = overlap(x0, x1, q[0], q[2] + 1) * overlap(z0, z1, q[1], q[3] + 1);
					if (a > bestA) {
						bestA = a;
						best = i;
					}
				}
				if (best < 0) {
					for (int[] q : Layout.CORRIDORS) {
						if (overlap(x0, x1, q[0], q[2] + 1) * overlap(z0, z1, q[1], q[3] + 1) > 0) best = 100;
					}
				}
				grid[r][c] = best;
			}
		}
	}

	private static double overlap(double a0, double a1, double b0, double b1) {
		return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
	}

	private static int colOf(int rx) {
		double cw = (MAP_X1 - MAP_X0 + 1) / (double) COLS;
		return Math.max(0, Math.min(COLS - 1, (int) ((rx - MAP_X0) / cw)));
	}

	private static int rowOf(int rz) {
		double ch = (MAP_Z1 - MAP_Z0 + 1) / (double) ROWS;
		return Math.max(0, Math.min(ROWS - 1, (int) ((rz - MAP_Z0) / ch)));
	}

	private static boolean roomAlarm(int room) {
		if (Game.phase != Game.Phase.PLAYING) return false;
		String n = Layout.ROOM_NAMES[room];
		return (Game.reactorTicks > 0 && n.equals("Reactor"))
			|| (Game.o2Ticks > 0 && n.equals("O2"))
			|| (Game.lightsOut && n.equals("Electrical"));
	}

	/** The mini map as scoreboard lines (JSON text components). */
	static List<String> miniMap(Game.PState p, ServerPlayer sp) {
		if (grid == null) buildGrid();
		MapData m = Game.map;
		char[][] ch = new char[ROWS][COLS];
		String[][] col = new String[ROWS][COLS];
		int here = -1;
		int pr = -1;
		int pc = -1;
		if (sp != null) {
			int rx = (int) Math.floor(sp.getX()) - m.originX();
			int rz = (int) Math.floor(sp.getZ()) - m.originZ();
			here = roomIndex(rx, rz);
			pc = colOf(rx);
			pr = rowOf(rz);
		}
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				int g = grid[r][c];
				ch[r][c] = '█';
				if (g == -1) col[r][c] = "black";
				else if (g == 100) col[r][c] = "dark_gray";
				else if (roomAlarm(g)) col[r][c] = "red";
				else if (g == here) col[r][c] = "green";
				else col[r][c] = "gray";
			}
		}
		if (Game.phase != Game.Phase.LOBBY) {
			for (Game.TaskRef t : p.tasks) {
				if (t.done) continue;
				MapData.TaskDef d = m.tasks.get(t.idx);
				MapData.Step s = d.steps.get(Math.min(t.step, d.steps.size() - 1));
				int c = colOf(s.pos.x - m.originX());
				int r = rowOf(s.pos.z - m.originZ());
				ch[r][c] = '◆';
				col[r][c] = "gold";
			}
		}
		boolean[][] me = new boolean[ROWS][COLS];
		if (sp != null) {
			ch[pr][pc] = '●';
			col[pr][pc] = "white";
			me[pr][pc] = true;
		}
		List<String> rows = new ArrayList<>();
		for (int r = 0; r < ROWS; r++) {
			StringBuilder sb = new StringBuilder("[{\"text\":\"\"}");
			int c = 0;
			while (c < COLS) {
				int e = c;
				StringBuilder run = new StringBuilder();
				while (e < COLS && ch[r][e] == ch[r][c] && col[r][e].equals(col[r][c]) && me[r][e] == me[r][c]) {
					run.append(ch[r][e]);
					e++;
				}
				sb.append(",{\"text\":\"").append(run).append("\",\"color\":\"").append(col[r][c]).append("\"");
				if (me[r][c]) sb.append(",\"bold\":true");
				sb.append("}");
				c = e;
			}
			sb.append("]");
			rows.add(sb.toString());
		}
		return rows;
	}

	// =====================================================================
	// sidebar lines
	// =====================================================================

	private static String taskLine(Game.PState p) {
		for (Game.TaskRef t : p.tasks) {
			if (t.done) continue;
			MapData.TaskDef d = Game.map.tasks.get(t.idx);
			MapData.Step s = d.steps.get(Math.min(t.step, d.steps.size() - 1));
			String name = d.name.length() > 16 ? d.name.substring(0, 16) : d.name;
			return "Next: " + name + " @ " + s.room;
		}
		return p.role.impostor ? "Fake tasks: all done" : "All tasks done!";
	}

	static List<String> lines(Game.PState p, ServerPlayer sp, String room) {
		List<String> l = new ArrayList<>();
		Stats.S st = Stats.get(p.id);
		boolean right = mode(p) == MODE_RIGHT && Game.map.ready();
		if (right) l.add(Game.txt("Location: " + room, "aqua"));
		else l.add(Game.txt(" ", "white"));
		if (right) l.addAll(miniMap(p, sp));
		if (Game.phase == Game.Phase.LOBBY) {
			l.add(Game.txt("Game: " + (Game.created ? Game.gameName : "-"), "gold"));
			if (!right) l.add(Game.txt("Waiting in lobby", "yellow"));
			l.add(Game.txt("Players: " + Game.players.size() + Game.targetSuffix() + "  " + (Game.isPublic ? "Public" : "Private"), "white"));
			l.add(Game.txt("Coins: " + st.coins + "  Wins: " + st.wins, "gold"));
			if (!right) {
				l.add(Game.txt("Games: " + st.games, "gray"));
				l.add(Game.txt("Kills: " + st.kills + "  Tasks: " + st.tasks, "gray"));
			}
			return l;
		}
		int done = 0;
		for (Game.TaskRef t : p.tasks) if (t.done) done++;
		l.add(Game.txt("Role: " + p.role.display + (p.alive ? "" : " (ghost)"), p.role.color));
		l.add(Game.txt((p.role.impostor ? "Fake tasks " : "Tasks ") + done + "/" + p.tasks.size() + "  Coins: " + st.coins,
			p.role.impostor ? "red" : "green"));
		if (right) {
			if (p.role.impostor && p.alive) l.add(Game.txt("Kill: " + (p.killCd > 0 ? (p.killCd / 20 + 1) + "s" : "READY"), "red"));
			else l.add(Game.txt(taskLine(p), "yellow"));
			return l;
		}
		if (p.role.impostor && p.alive) l.add(Game.txt("Kill: " + (p.killCd > 0 ? (p.killCd / 20 + 1) + "s" : "READY"), "red"));
		int shown = 0;
		for
