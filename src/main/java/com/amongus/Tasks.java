package com.amongus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Task mechanics. Every task is a list of steps; every step uses one mechanic:
 * clicks, hold, sneak, code, math, swipe, bar, order, wires, memory, asteroids, shields, sample.
 * Instructions and inputs are shown in dialog screens (GUI) - not in the chat.
 */
final class Tasks {
	private Tasks() {}

	private static final Random RNG = new Random();
	private static final String[] WIRE_NAMES = {"Red", "Blue", "Yellow", "Pink", "Orange", "Green"};
	private static final String[] WIRE_HEX = {"#FF3030", "#3050FF", "#F5F557", "#ED54BA", "#FF9020", "#30D030"};
	private static final String[] MEM_NAMES = {"RED", "GREEN", "BLUE", "YELLOW"};
	private static final String[] MEM_HEX = {"#FF3030", "#30D030", "#3050FF", "#F5F557"};

	static final class Run {
		final Game.TaskRef ref;
		final MapData.TaskDef def;
		final MapData.Step step;
		int timer;
		int n;
		int aux;
		int target;
		int phase;
		int code;
		int progress;
		long stamp;
		int[] arr;
		boolean[] flags;
		String entry = "";
		String text = "";

		Run(Game.TaskRef ref, MapData.TaskDef def, MapData.Step step) {
			this.ref = ref;
			this.def = def;
			this.step = step;
		}
	}

	/** Task time limit in ticks, scaled by /among set tasktime (percent). */
	private static int lim(int ticks) {
		return Math.max(20, ticks * Game.taskTime / 100);
	}

	private static double dist2(ServerPlayer sp, MapData.P q) {
		double dx = sp.getX() - q.cx();
		double dy = sp.getY() - q.y;
		double dz = sp.getZ() - q.cz();
		return dx * dx + dy * dy + dz * dz;
	}

	private static void shuffle(int[] a) {
		for (int i = a.length - 1; i > 0; i--) {
			int j = RNG.nextInt(i + 1);
			int t = a[i];
			a[i] = a[j];
			a[j] = t;
		}
	}

	// =====================================================================
	// GUI helpers (1.21.6+ dialog screens)
	// =====================================================================

	private static String progress(int n, int total, int width) {
		int k = total <= 0 ? 0 : Math.min(width, n * width / total);
		return "█".repeat(k) + "░".repeat(width - k);
	}

	private static String[] b(String label, String color, String command) {
		return new String[] {label, color, command};
	}

	private static String head(Run r) {
		return r.def.name + (r.def.steps.size() > 1 ? "   (step " + (r.ref.step + 1) + "/" + r.def.steps.size() + ")" : "");
	}

	private static void closeGui(Game.PState p) {
		Game.cmd("dialog clear " + p.name);
	}

	/** A task screen: title, text lines, and a grid of buttons that run "among pick N". */
	private static void gui(Game.PState p, Run r, List<String> body, List<String[]> buttons, int columns, int width) {
		StringBuilder sb = new StringBuilder("{type:\"minecraft:multi_action\",title:" + Game.comp(head(r), "gold")
			+ ",columns:" + columns + ",body:[");
		for (int i = 0; i < body.size(); i++) {
			if (i > 0) sb.append(",");
			sb.append(body.get(i));
		}
		sb.append("],actions:[");
		for (int i = 0; i < buttons.size(); i++) {
			String[] bt = buttons.get(i);
			if (i > 0) sb.append(",");
			sb.append("{label:").append(Game.comp(bt[0], bt[1])).append(",width:").append(width)
				.append(",action:{type:\"minecraft:run_command\",command:").append(Game.sn(bt[2])).append("}}");
		}
		sb.append("]}");
		Game.cmd("dialog show " + p.name + " " + sb);
	}

	private static List<String> lines(String... l) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i + 1 < l.length; i += 2) out.add(Game.line(l[i], l[i + 1]));
		return out;
	}

	/** Draw (or redraw) the screen of the current step. */
	static void show(Game.PState p, Run r) {
		MapData.Step st = r.step;
		switch (st.mech) {
			case "clicks" -> gui(p, r, lines(
				"Press the button as fast as you can!", "yellow",
				progress(r.n, st.a, 24) + "   " + r.n + "/" + st.a, "green",
				"Time limit: " + (lim(st.b > 0 ? st.b : 200) / 20) + " seconds", "gray"),
				List.<String[]>of(b("PRESS", "green", "among pick 1")), 1, 220);
			case "swipe" -> gui(p, r, lines(
				"Swipe the card at the right speed.", "yellow",
				"Not too fast, not too slow: wait about one second, then press SWIPE.", "gray"),
				List.<String[]>of(b("SWIPE CARD", "aqua", "among pick 1")), 1, 220);
			case "code", "math" -> {
				boolean math = "math".equals(st.mech);
				StringBuilder shown = new StringBuilder();
				for (int i = 0; i < Math.max(r.entry.length(), math ? 3 : Math.max(3, st.a)); i++) {
					shown.append(i < r.entry.length() ? r.entry.charAt(i) : '_').append(' ');
				}
				List<String> body = lines(
					math ? "Solve:  " + r.text + "  = ?" : "ID CODE:  " + r.code, math ? "aqua" : "aqua",
					"Your entry:  " + shown, "white",
					"Use the keypad, then press ENTER.", "gray");
				List<String[]> kp = new ArrayList<>();
				for (int d = 1; d <= 9; d++) kp.add(b(String.valueOf(d), "white", "among pick " + d));
				kp.add(b("CLEAR", "red", "among pick 10"));
				kp.add(b("0", "white", "among pick 0"));
				kp.add(b("ENTER", "green", "among pick 11"));
				gui(p, r, body, kp, 3, 70);
			}
			case "order" -> {
				List<String[]> bt = new ArrayList<>();
				for (int v : r.arr) bt.add(b(v < r.n ? "✓" : String.valueOf(v), v < r.n ? "dark_gray" : "aqua", "among pick " + v));
				gui(p, r, lines(
					"Press the numbers in order, from 1 to " + st.a + ".", "yellow",
					"Next number: " + Math.min(r.n, st.a), "gray"), bt, Math.min(r.arr.length, 4), 60);
			}
			case "wires" -> {
				int n = r.arr.length;
				List<String> body = new ArrayList<>();
				body.add(Game.line("Connect every wire on the LEFT to the matching colour on the RIGHT.", "yellow"));
				StringBuilder right = new StringBuilder();
				for (int pos = 0; pos < n; pos++) right.append(pos + 1).append("=").append(WIRE_NAMES[r.arr[pos]]).append("   ");
				body.add(Game.line("Right side:  " + right, "white"));
				List<String[]> bt = new ArrayList<>();
				for (int left = 0; left < n; left++) {
					if (r.flags[left]) {
						body.add(Game.line("✓ " + WIRE_NAMES[left] + " wire connected", "dark_gray"));
						continue;
					}
					for (int pos = 1; pos <= n; pos++) {
						bt.add(b(WIRE_NAMES[left] + " → " + pos, WIRE_HEX[left], "among pick " + ((left + 1) * 10 + pos)));
					}
				}
				gui(p, r, body, bt, n, 78);
			}
			case "memory" -> {
				if (r.phase != 1) return;
				List<String[]> bt = new ArrayList<>();
				for (int i = 0; i < 4; i++) bt.add(b(MEM_NAMES[i], MEM_HEX[i], "among pick " + i));
				gui(p, r, lines(
					"Repeat the sequence you just saw.", "yellow",
					progress(r.n, r.arr.length, 20) + "   " + r.n + "/" + r.arr.length, "green"), bt, 2, 120);
			}
			case "asteroids" -> {
				if (r.phase != 1) return;
				List<String[]> bt = new ArrayList<>();
				for (int i = 1; i <= 6; i++) bt.add(i == r.target ? b("★ ASTEROID", "gold", "among pick " + i) : b("·", "dark_gray", "among pick " + i));
				gui(p, r, lines(
					"Destroy the asteroid before it hits!", "red",
					"Hits: " + r.n + "/" + st.a + "   " + progress(r.n, st.a, 16), "green"), bt, 3, 110);
			}
			case "shields" -> {
				List<String[]> bt = new ArrayList<>();
				for (int i = 0; i < r.arr.length; i++) bt.add(b("██", r.arr[i] == 1 ? "red" : "white", "among pick " + (i + 1)));
				gui(p, r, lines(
					"Make every cell WHITE.", "yellow",
					"Pressing a cell flips it and its two neighbours (the row wraps around).", "gray"),
					bt, Math.min(r.arr.length, 6), 60);
			}
			case "sample" -> {
				if (r.ref.stage != 2) return;
				List<String[]> bt = new ArrayList<>();
				for (int i = 1; i <= 5; i++) bt.add(b("Vial " + i, i == r.target ? "#8A5BDD" : "#4466DD", "among pick " + i));
				gui(p, r, lines(
					"Analysis complete.", "green",
					"Pick the ANOMALY vial - the one with the odd colour.", "yellow"), bt, 3, 100);
			}
			default -> { }
		}
	}

	private static void info(Game.PState p, String title, String color, String... text) {
		List<String> l = new ArrayList<>();
		for (String t : text) l.add(Game.line(t, "white"));
		Game.notice(p.name, title, color, l);
	}

	// =====================================================================
	// entry points
	// =====================================================================

	/** A player right-clicked a block. Returns true if it was a task station (click consumed). */
	static boolean click(Game.PState p, ServerPlayer sp, BlockPos pos) {
		if (p.run != null && p.run.step.pos.is(pos)) {
			onClick(p, sp, p.run);
			return true;
		}
		Game.TaskRef match = null;
		MapData.TaskDef md = null;
		for (Game.TaskRef t : p.tasks) {
			if (t.done) continue;
			MapData.TaskDef d = Game.map.tasks.get(t.idx);
			if (t.step < d.steps.size() && d.steps.get(t.step).pos.is(pos)) {
				match = t;
				md = d;
				break;
			}
		}
		if (match == null) {
			boolean station = false;
			for (MapData.TaskDef d : Game.map.tasks) {
				for (MapData.Step s : d.steps) if (s.pos.is(pos)) station = true;
			}
			if (!station) return false;
			Game.bar(p.name, "That station is not part of your current tasks.", "gray");
			return true;
		}
		if (p.role.impostor) {
			Game.bar(p.name, "Fake task: " + md.name + " - you can only pretend, impostors can't finish tasks.", "gray");
			return true;
		}
		start(p, sp, match, md);
		return true;
	}

	static void start(Game.PState p, ServerPlayer sp, Game.TaskRef ref, MapData.TaskDef def) {
		MapData.Step st = def.steps.get(ref.step);
		Run r = new Run(ref, def, st);
		p.run = r;
		String head = def.name + (def.steps.size() > 1 ? " (" + (ref.step + 1) + "/" + def.steps.size() + ")" : "");
		switch (st.mech) {
			case "clicks" -> {
				r.n = 1;
				r.timer = lim(st.b > 0 ? st.b : 200);
				if (r.n >= st.a) stepDone(p, r);
				else show(p, r);
			}
			case "hold" -> Game.bar(p.name, head + ": stay near the station for " + st.a + " seconds...", "aqua");
			case "sneak" -> Game.bar(p.name, head + ": HOLD SNEAK near the station for " + st.a + " seconds", "aqua");
			case "code" -> {
				int digits = Math.max(3, st.a);
				int lo = (int) Math.pow(10, digits - 1);
				r.code = lo + RNG.nextInt(lo * 9);
				r.timer = lim((st.b > 0 ? st.b : 20) * 20);
				show(p, r);
			}
			case "math" -> {
				int lvl = Math.max(1, Math.min(3, st.a));
				if (lvl == 1) {
					int x = 20 + RNG.nextInt(80);
					int y = 20 + RNG.nextInt(80);
					r.text = x + " + " + y;
					r.code = x + y;
				} else if (lvl == 2) {
					int x = 6 + RNG.nextInt(14);
					int y = 3 + RNG.nextInt(9);
					int z = 10 + RNG.nextInt(60);
					r.text = x + " x " + y + " + " + z;
					r.code = x * y + z;
				} else {
					int x = 4 + RNG.nextInt(9);
					int y = 3 + RNG.nextInt(9);
					int z = 5 + RNG.nextInt(9);
					int w = 10 + RNG.nextInt(25);
					r.text = "(" + x + " + " + y + ") x " + z + " - " + w;
					r.code = (x + y) * z - w;
				}
				r.timer = lim((st.b > 0 ? st.b : 45) * 20);
				show(p, r);
			}
			case "swipe" -> {
				r.stamp = Game.tickN;
				r.timer = lim(100);
				show(p, r);
			}
			case "bar" -> {
				r.n = 0;
				r.target = 3 + RNG.nextInt(15);
				r.stamp = Game.tickN;
				r.timer = lim(900);
				info(p, head, "aqua",
					"Timing task: watch the bar at the top of your screen.",
					"Right-click the station when the marker | is inside the green ### zone.",
					"Hits needed in a row: " + st.a);
			}
			case "order" -> {
				r.arr = new int[st.a];
				for (int i = 0; i < st.a; i++) r.arr[i] = i + 1;
				shuffle(r.arr);
				r.n = 1;
				show(p, r);
			}
			case "wires" -> {
				int n = Math.min(6, Math.max(2, st.a));
				r.arr = new int[n];
				for (int i = 0; i < n; i++) r.arr[i] = i;
				shuffle(r.arr);
				r.flags = new boolean[n];
				show(p, r);
			}
			case "memory" -> {
				r.arr = new int[Math.max(3, st.a)];
				for (int i = 0; i < r.arr.length; i++) r.arr[i] = RNG.nextInt(4);
				r.timer = 0;
				r.phase = 0;
				r.n = 0;
				Game.bar(p.name, head + ": WATCH the colour sequence...", "aqua");
			}
			case "asteroids" -> {
				r.n = 0;
				r.phase = 0;
				r.aux = 20;
				Game.bar(p.name, head + ": get ready - asteroids incoming (" + st.a + " hits in a row)", "aqua");
			}
			case "shields" -> {
				int n = Math.max(5, st.a);
				r.arr = new int[n];
				int presses = 3 + RNG.nextInt(2) + n / 4;
				for (int k = 0; k < presses; k++) toggle(r.arr, RNG.nextInt(n));
				boolean any = false;
				for (int v : r.arr) if (v != 0) any = true;
				if (!any) toggle(r.arr, 0);
				show(p, r);
			}
			case "sample" -> {
				if (ref.stage == 0) {
					ref.stage = 1;
					ref.waitUntil = Game.tickN + 1200;
					p.run = null;
					info(p, head, "aqua", "The sample is being analysed.", "Come back in 60 seconds - do other tasks meanwhile.");
				} else if (Game.tickN < ref.waitUntil) {
					p.run = null;
					Game.bar(p.name, head + ": still analysing - " + ((ref.waitUntil - Game.tickN) / 20 + 1) + "s left", "gray");
				} else {
					ref.stage = 2;
					r.target = 1 + RNG.nextInt(5);
					show(p, r);
				}
			}
			default -> {
				p.run = null;
				Game.bar(p.name, "Unknown task type.", "red");
			}
		}
	}

	private static void onClick(Game.PState p, ServerPlayer sp, Run r) {
		MapData.Step st = r.step;
		switch (st.mech) {
			case "clicks", "swipe", "bar" -> press(p, r);
			default -> show(p, r); // re-open the screen if it was closed
		}
	}

	/** Pressing the big button / right-clicking the station for the timing mechanics. */
	private static void press(Game.PState p, Run r) {
		MapData.Step st = r.step;
		switch (st.mech) {
			case "clicks" -> {
				r.n++;
				if (r.n >= st.a) stepDone(p, r);
				else show(p, r);
			}
			case "swipe" -> {
				long dt = Game.tickN - r.stamp;
				if (dt < 20) fail(p, "Too fast! Click the station to try again.");
				else if (dt > 40) fail(p, "Too slow! Click the station to try again.");
				else stepDone(p, r);
			}
			case "bar" -> {
				int m = marker(r);
				if (Math.abs(m - r.target) <= st.b) {
					r.n++;
					if (r.n >= st.a) {
						stepDone(p, r);
					} else {
						r.target = 3 + RNG.nextInt(15);
						Game.title(p.name, "Nice!", r.n + "/" + st.a, "green");
					}
				} else {
					r.n = 0;
					Game.title(p.name, "Missed!", "Back to 0/" + st.a, "red");
				}
			}
			default -> { }
		}
	}

	static void tick(Game.PState p, ServerPlayer sp) {
		Run r = p.run;
		if (r == null) return;
		MapData.Step st = r.step;
		double d2 = dist2(sp, st.pos);
		switch (st.mech) {
			case "hold" -> {
				if (d2 > 16.0) {
					fail(p, "Task cancelled - you moved away.");
					return;
				}
				r.progress++;
				if (r.progress % 10 == 0) {
					Game.bar(p.name, r.def.name + ":  " + progress(r.progress, st.a * 20, 20) + "  " + (r.progress * 100 / (st.a * 20)) + "%", "aqua");
				}
				if (r.progress >= st.a * 20) stepDone(p, r);
			}
			case "sneak" -> {
				if (d2 > 16.0) {
					fail(p, "Task cancelled - you moved away.");
					return;
				}
				if (sp.isShiftKeyDown()) {
					r.progress++;
					if (r.progress % 10 == 0) {
						Game.bar(p.name, r.def.name + ":  " + progress(r.progress, st.a * 20, 20) + "  " + (r.progress * 100 / (st.a * 20)) + "%", "aqua");
					}
					if (r.progress >= st.a * 20) stepDone(p, r);
				} else if (Game.tickN % 20 == 0) {
					Game.bar(p.name, r.def.name + ": HOLD SNEAK (shift)!", "yellow");
				}
			}
			default -> {
				if (d2 > 100.0) {
					fail(p, "Task cancelled - you walked away.");
					return;
				}
				switch (st.mech) {
					case "clicks", "swipe", "code", "math" -> {
						if (--r.timer <= 0) fail(p, "Task failed (too slow). Click the station to try again.");
						else if (Game.tickN % 20 == 0 && !"swipe".equals(st.mech)) Game.bar(p.name, r.def.name + ": " + (r.timer / 20) + "s left", "yellow");
					}
					case "bar" -> {
						if (--r.timer <= 0) {
							fail(p, "Task timed out. Click the station to try again.");
						} else if (Game.tickN % 2 == 0) {
							showBar(p, r);
						}
					}
					case "memory" -> memoryTick(p, r);
					case "asteroids" -> asteroidsTick(p, r);
					default -> { }
				}
			}
		}
	}

	/** /among pick <n> - presses of the task screen buttons. */
	static void pick(ServerPlayer sp, int k) {
		Game.PState p = Game.players.get(sp.getUUID());
		if (p == null || Game.phase != Game.Phase.PLAYING || !p.alive) return;
		Run r = p.run;
		if (r == null) {
			Game.bar(p.name, "No active task. Right-click a task station first.", "gray");
			return;
		}
		switch (r.step.mech) {
			case "clicks", "swipe", "bar" -> press(p, r);
			case "code", "math" -> {
				if (k <= 9) {
					if (r.entry.length() < 6) r.entry += k;
					show(p, r);
				} else if (k == 10) {
					r.entry = "";
					show(p, r);
				} else if (k == 11) {
					if (r.entry.isEmpty()) {
						show(p, r);
					} else if (r.entry.equals(String.valueOf(r.code))) {
						stepDone(p, r);
					} else {
						r.entry = "";
						Game.title(p.name, "WRONG", "Try again", "red");
						show(p, r);
					}
				}
			}
			case "order" -> {
				if (k < r.n) {
					show(p, r);
				} else if (k == r.n) {
					r.n++;
					if (r.n > r.step.a) stepDone(p, r);
					else show(p, r);
				} else {
					r.n = 1;
					shuffle(r.arr);
					Game.title(p.name, "Wrong number", "Start again from 1", "red");
					show(p, r);
				}
			}
			case "wires" -> {
				int left = k / 10 - 1;
				int pos = k % 10;
				int n = r.arr.length;
				if (left < 0 || left >= n || pos < 1 || pos > n || r.flags[left]) {
					show(p, r);
					return;
				}
				if (r.arr[pos - 1] == left) {
					r.flags[left] = true;
					boolean all = true;
					for (boolean f : r.flags) if (!f) all = false;
					if (all) stepDone(p, r);
					else show(p, r);
				} else {
					r.flags = new boolean[n];
					Game.title(p.name, "Wrong connection", "All wires reset", "red");
					show(p, r);
				}
			}
			case "memory" -> {
				if (r.phase != 1) return;
				if (k == r.arr[r.n]) {
					r.n++;
					if (r.n >= r.arr.length) {
						stepDone(p, r);
					} else {
						show(p, r);
					}
				} else {
					for (int i = 0; i < r.arr.length; i++) r.arr[i] = RNG.nextInt(4);
					r.timer = 0;
					r.phase = 0;
					r.n = 0;
					Game.title(p.name, "Wrong!", "A new sequence is coming - watch carefully", "red");
				}
			}
			case "asteroids" -> {
				if (r.phase != 1) return;
				if (k == r.target) {
					r.n++;
					if (r.n >= r.step.a) {
						stepDone(p, r);
					} else {
						r.phase = 0;
						r.aux = 10;
						Game.bar(p.name, "Hit! " + r.n + "/" + r.step.a, "green");
					}
				} else {
					asteroidMiss(p, r, "Missed!");
				}
			}
			case "shields" -> {
				int n = r.arr.length;
				if (k < 1 || k > n) return;
				toggle(r.arr, k - 1);
				boolean allWhite = true;
				for (int v : r.arr) if (v != 0) allWhite = false;
				if (allWhite) stepDone(p, r);
				else show(p, r);
			}
			case "sample" -> {
				if (r.ref.stage != 2) return;
				if (k == r.target) {
					stepDone(p, r);
				} else {
					r.ref.stage = 0;
					p.run = null;
					info(p, "SAMPLE RUINED", "red", "Wrong vial! The sample was ruined.", "Start the analysis again at the station.");
				}
			}
			default -> Game.bar(p.name, "This task doesn't use buttons.", "gray");
		}
	}

	/** /among code <n> still works as a typed alternative to the keypad. */
	static void code(ServerPlayer sp, int value) {
		Game.PState p = Game.players.get(sp.getUUID());
		if (p == null || Game.phase != Game.Phase.PLAYING || !p.alive) return;
		if (p.run == null || !("code".equals(p.run.step.mech) || "math".equals(p.run.step.mech))) {
			Game.bar(p.name, "You don't have a code task active. Right-click a task station first.", "gray");
			return;
		}
		if (p.run.code != value) {
			Game.title(p.name, "WRONG", "Try again", "red");
			return;
		}
		stepDone(p, p.run);
	}

	// =====================================================================
	// completion
	// =====================================================================

	static void fail(Game.PState p, String msg) {
		p.run = null;
		closeGui(p);
		Game.bar(p.name, msg, "red");
	}

	static void stepDone(Game.PState p, Run r) {
		p.run = null;
		closeGui(p);
		Game.TaskRef ref = r.ref;
		MapData.TaskDef d = r.def;
		ref.step++;
		ref.stage = 0;
		if (ref.step >= d.steps.size()) {
			Game.completeTask(p, ref, d);
		} else {
			MapData.Step nx = d.steps.get(ref.step);
			info(p, "STEP " + ref.step + "/" + d.steps.size() + " DONE", "green",
				d.name,
				"Next: go to " + nx.room + "  (" + nx.pos.x + ", " + nx.pos.y + ", " + nx.pos.z + ")");
			Game.cmd("playsound minecraft:block.note_block.pling master " + p.name);
			Game.hudDirty = true;
		}
	}

	// =====================================================================
	// mechanic helpers
	// =====================================================================

	private static int marker(Run r) {
		int t = (int) ((Game.tickN - r.stamp) / 2 % 40);
		return t <= 20 ? t : 40 - t;
	}

	private static void showBar(Game.PState p, Run r) {
		int m = marker(r);
		StringBuilder sb = new StringBuilder("{\"text\":\"\",\"extra\":[");
		sb.append("{\"text\":\"").append(Game.esc(r.def.name)).append(" ").append(r.n).append("/").append(r.step.a)
			.append("  \",\"color\":\"aqua\"}");
		for (int i = 0; i <= 20; i++) {
			boolean zone = Math.abs(i - r.target) <= r.step.b;
			String ch;
			String color;
			if (i == m) {
				ch = "|";
				color = "white";
			} else if (zone) {
				ch = "#";
				color = "green";
			} else {
				ch = "-";
				color = "dark_gray";
			}
			sb.append(",{\"text\":\"").append(ch).append("\",\"color\":\"").append(color).append("\"}");
		}
		sb.append("]}");
		Game.cmd("title " + p.name + " actionbar " + sb);
	}

	private static void memoryTick(Game.PState p, Run r) {
		if (r.phase != 0) return;
		int t = r.timer++;
		int len = r.arr.length;
		int idx = t / 24;
		if (t % 24 == 0 && idx < len) {
			int v = r.arr[idx];
			Game.title(p.name, MEM_NAMES[v], (idx + 1) + "/" + len, MEM_HEX[v]);
		}
		if (t == len * 24 + 8) {
			r.phase = 1;
			r.n = 0;
			show(p, r);
		}
	}

	private static void asteroidsTick(Game.PState p, Run r) {
		if (r.phase == 0) {
			if (--r.aux <= 0) {
				r.phase = 1;
				r.target = 1 + RNG.nextInt(6);
				r.timer = lim(60);
				show(p, r);
			}
		} else if (--r.timer <= 0) {
			asteroidMiss(p, r, "Too slow!");
		}
	}

	private static void asteroidMiss(Game.PState p, Run r, String why) {
		r.n = 0;
		r.phase = 0;
		r.aux = 30;
		closeGui(p);
		Game.title(p.name, why, "The asteroids got through - start over (0/" + r.step.a + ")", "red");
	}

	private static void toggle(int[] a, int i) {
		int n = a.length;
		a[i] ^= 1;
		a[(i + 1) % n] ^= 1;
		a[(i - 1 + n) % n] ^= 1;
	}
}
