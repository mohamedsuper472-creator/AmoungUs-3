package com.amongus;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen box (scoreboard sidebar). Every player has their own team with a unique colour and
 * their own objective, shown only to them through "sidebar.team.<colour>".
 */
final class Hud {
	private Hud() {}

	static final String[] TEAM_COLORS = {
		"red", "blue", "green", "light_purple", "gold", "yellow",
		"dark_gray", "white", "dark_purple", "dark_red", "aqua", "dark_green"
	};

	static String obj(Game.PState p) {
		return "au_s" + p.slot;
	}

	static String team(Game.PState p) {
		return "au_t" + p.slot;
	}

	/** Create the player's team (names visible in the lobby) and put them in it. */
	static void setup(Game.PState p) {
		Game.cmd("team remove " + team(p));
		Game.cmd("team add " + team(p));
		Game.cmd("team modify " + team(p) + " color " + TEAM_COLORS[p.slot]);
		Game.cmd("team modify " + team(p) + " nametagVisibility always");
		Game.cmd("team modify " + team(p) + " collisionRule never");
		Game.cmd("team join " + team(p) + " " + p.name);
		p.hudLines = null;
		refresh(p, true);
	}

	static void hideNames(Game.PState p, boolean hide) {
		Game.cmd("team modify " + team(p) + " nametagVisibility " + (hide ? "never" : "always"));
	}

	static void teardown(Game.PState p) {
		Game.cmd("scoreboard objectives remove " + obj(p));
		Game.cmd("team remove " + team(p));
		p.hudLines = null;
	}

	static void refreshAll(boolean force) {
		for (Game.PState p : Game.players.values()) refresh(p, force);
	}

	static List<String> lines(Game.PState p) {
		List<String> l = new ArrayList<>();
		Stats.S st = Stats.get(p.id);
		l.add(Game.txt(" ", "white"));
		if (Game.phase == Game.Phase.LOBBY) {
			l.add(Game.txt("Waiting in lobby", "yellow"));
			l.add(Game.txt("Players: " + Game.players.size() + Game.targetSuffix(), "white"));
			l.add(Game.txt(" ", "white"));
			l.add(Game.txt("Coins: " + st.coins, "gold"));
			l.add(Game.txt("Wins: " + st.wins + "  Games: " + st.games, "gray"));
			l.add(Game.txt("Kills: " + st.kills + "  Tasks: " + st.tasks, "gray"));
			return l;
		}
		l.add(Game.txt("Role: " + p.role.display, p.role.color));
		l.add(Game.txt("Coins: " + st.coins, "gold"));
		if (!p.alive) l.add(Game.txt("You are a ghost", "gray"));
		int done = 0;
		for (Game.TaskRef t : p.tasks) if (t.done) done++;
		if (p.role.impostor) {
			l.add(Game.txt("Fake Tasks: " + done + "/" + p.tasks.size(), "red"));
			if (p.alive) l.add(Game.txt("Kill: " + (p.killCd > 0 ? (p.killCd / 20 + 1) + "s" : "READY"), "red"));
		} else {
			l.add(Game.txt("Tasks: " + done + "/" + p.tasks.size(), "green"));
		}
		l.add(Game.txt(" ", "white"));
		int shown = 0;
		for (Game.TaskRef t : p.tasks) {
			if (shown >= 8) break;
			MapData.TaskDef d = Game.map.tasks.get(t.idx);
			String name = d.name.length() > 20 ? d.name.substring(0, 20) : d.name;
			String prog = d.steps.size() > 1 && !t.done ? " " + t.step + "/" + d.steps.size() : "";
			String text = (t.done ? "+ " : "- ") + name + prog + " [" + MapData.diffTag(d.diff) + "]";
			l.add(Game.txt(text, t.done ? "dark_gray" : MapData.diffColor(d.diff)));
			shown++;
		}
		return l;
	}

	static void refresh(Game.PState p, boolean force) {
		List<String> now = lines(p);
		String o = obj(p);
		if (p.hudLines == null || p.hudLines.size() != now.size() || force) {
			Game.cmd("scoreboard objectives remove " + o);
			Game.cmd("scoreboard objectives add " + o + " dummy " + Game.txt("AMONG US", "gold"));
			Game.cmd("scoreboard objectives modify " + o + " numberformat blank");
			for (int k = 0; k < now.size(); k++) {
				Game.cmd("scoreboard players set l" + k + " " + o + " " + (now.size() - k));
				Game.cmd("scoreboard players display name l" + k + " " + o + " " + now.get(k));
			}
			Game.cmd("scoreboard objectives setdisplay sidebar.team." + TEAM_COLORS[p.slot] + " " + o);
		} else {
			for (int k = 0; k < now.size(); k++) {
				if (!now.get(k).equals(p.hudLines.get(k))) {
					Game.cmd("scoreboard players display name l" + k + " " + o + " " + now.get(k));
				}
			}
		}
		p.hudLines = now;
	}
}
