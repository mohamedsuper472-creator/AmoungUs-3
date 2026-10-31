package com.amongus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.Random;

/**
 * Task mechanics. Every task is a list of steps; every step uses one mechanic:
 * clicks, hold, sneak, code, swipe, bar, order, wires, memory, asteroids, shields, sample.
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
				Game.bar(p.name, head + ": click the station " + st.a + " times (1/" + st.a + ") - " + (r.timer / 20) + "s", "aqua");
			}
			case "hold" -> Game.bar(p.name, head + ": stay near the station for " + st.a + " seconds...", "aqua");
			case "sneak" -> Game.bar(p.name, head + ": HOLD SNEAK near the station for " + st.a + " seconds", "aqua");
			case "code" -> {
				int digits = Math.max(3, st.a);
				int lo = (int) Math.pow(10, digits - 1);
				r.code = lo + RNG.nextInt(lo * 9);
				r.timer = lim((st.b > 0 ? st.b : 20) * 20);
				Game.tell(p.name, head + ": type /among code " + r.code + " within " + (r.timer / 20) + " seconds", "aqua");
				Game.title(p.name, "CODE: " + r.code, "/among code " + r.code, "aqua");
			}
			case "math" -> {
				int lvl = Math.max(1, Math.min(3, st.a));
				String q;
				if (lvl == 1) {
					int x = 20 + RNG.nextInt(80);
					int y = 20 + RNG.nextInt(80);
					q = x + " + " + y;
					r.code = x + y;
				} else if (lvl == 2) {
					int x = 6 + RNG.nextInt(14);
					int y = 3 + RNG.nextInt(9);
					int z = 10 + RNG.nextInt(60);
					q = x + " x " + y + " + " + z;
					r.code = x * y + z;
				} else {
					int x = 4 + RNG.nextInt(9);
					int y = 3 + RNG.nextInt(9);
					int z = 5 + RNG.nextInt(9);
					int w = 10 + RNG.nextInt(25);
					q = "(" + x + " + " + y + ") x " + z + " - " + w;
					r.code = (x + y) * z - w;
				}
				r.timer = lim((st.b > 0 ? st.b : 45) * 20);
				Game.tell(p.name, head + ": solve  " + q + " = ?  then type /among code <answer> within " + (r.timer / 20) + " seconds", "aqua");
				Game.title(p.name, q + " = ?", "/among code <answer>", "aqua");
			}
			case "swipe" -> {
				r.stamp = Game.tickN;
				r.timer = lim(100);
				Game.bar(p.name, head + ": click again after 1-2 seconds - not too fast, not too slow!", "aqua");
			}
			case "bar" -> {
				r.n = 0;
				r.target = 3 + RNG.nextInt(15);
				r.stamp = Game.tickN;
				r.timer = lim(900);
				Game.tell(p.name, head + ": right-click when the marker | is inside the green ### zone (" + st.a + " time(s) in a row).", "aqua");
			}
			case "order" -> {
				r.arr = new int[st.a];
				for (int i = 0; i < st.a; i++) r.arr[i] = i + 1;
				shuffle(r.arr);
				r.n = 1;
				Game.tell(p.name, head + ": click the numbers in order, 1 to " + st.a + ".", "aqua");
				sendOrder(p, r);
			}
			case "wires" -> {
				int n = Math.min(6, Math.max(2, st.a));
				r.arr = new int[n];
				for (int i = 0; i < n; i++) r.arr[i] = i;
				shuffle(r.arr);
				r.flags = new boolean[n];
				Game.tell(p.name, head + ": connect each wire to the matching colour on the right side.", "aqua");
				sendWires(p, r);
			}
			case "memory" -> {
				r.arr = new int[Math.max(3, st.a)];
				for (int i = 0; i < r.arr.length; i++) r.arr[i] = RNG.nextInt(4);
				r.timer = 0;
				r.phase = 0;
				r.n = 0;
				Game.tell(p.name, head + ": WATCH the colour sequence, then repeat it with the buttons.", "aqua");
			}
			case "asteroids" -> {
				r.n = 0;
				r.phase = 0;
				r.aux = 20;
				Game.tell(p.name, head + ": click the asteroid [*] before it is too late! (" + st.a + " hits in a row)", "aqua");
			}
			case "shields" -> {
				int n = Math.max(5, st.a);
				r.arr = new int[n];
				int presses = 3 + RNG.nextInt(2) + n / 4;
				for (int k = 0; k < presses; k++) toggle(r.arr, RNG.nextInt(n));
				boolean any = false;
				for (int v : r.arr) if (v != 0) any = true;
				if (!any) toggle(r.arr, 0);
				Game.tell(p.name, head + ": make every cell WHITE. A click flips that cell and its two neighbours.", "aqua");
				sendShields(p, r);
			}
			case "sample" -> {
				if (ref.stage == 0) {
					ref.stage = 1;
					ref.waitUntil = Game.tickN + 1200;
					p.run = null;
					Game.tell(p.name, head + ": sample is being analysed. Come back in 60 seconds - do other tasks meanwhile.", "aqua");
				} else if (Game.tickN < ref.waitUntil) {
					p.run = null;
					Game.bar(p.name, head + ": still analysing - " + ((ref.waitUntil - Game.tickN) / 20 + 1) + "s left", "gray");
				} else {
					ref.stage = 2;
					r.target = 1 + RNG.nextInt(5);
					Game.tell(p.name, head + ": analysis done! Pick the ANOMALY vial (the one with the odd colour).", "aqua");
					sendVials(p, r);
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
			case "clicks" -> {
				r.n++;
				if (r.n >= st.a) stepDone(p, r);
				else Game.bar(p.name, r.def.name + ": " + r.n + "/" + st.a + " clicks", "aqua");
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
						Game.tell(p.name, "Nice! " + r.n + "/" + st.a, "green");
					}
				} else {
					r.n = 0;
					Game.tell(p.name, "Missed! Back to 0/" + st.a, "red");
				}
			}
			case "sample" -> {
				if (r.ref.stage == 2) sendVials(p, r);
			}
			case "order" -> sendOrder(p, r);
			case "wires" -> sendWires(p, r);
			case "shields" -> sendShields(p, r);
			default -> Game.bar(p.name, "Use the chat buttons / watch the screen.", "gray");
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
					Game.bar(p.name, r.def.name + ": " + (r.progress * 100 / (st.a * 20)) + "%", "aqua");
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
						Game.bar(p.name, r.def.name + ": " + (r.progress * 100 / (st.a * 20)) + "%", "aqua");
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

	/** /among pick <n> - chat button presses. */
	static void pick(ServerPlayer sp, int k) {
		Game.PState p = Game.players.get(sp.getUUID());
		if (p == null || Game.phase != Game.Phase.PLAYING || !p.alive) return;
		Run r = p.run;
		if (r == null) {
			Game.tell(p.name, "No active task. Click a task station first.", "gray");
			return;
		}
		switch (r.step.mech) {
			case "order" -> {
				if (k == r.n) {
					r.n++;
					if (r.n > r.step.a) stepDone(p, r);
					else sendOrder(p, r);
				} else {
					r.n = 1;
					shuffle(r.arr);
					Game.tell(p.name, "Wrong number! Start again from 1.", "red");
					sendOrder(p, r);
				}
			}
			case "wires" -> {
				int left = k / 10 - 1;
				int pos = k % 10;
				int n = r.arr.length;
				if (left < 0 || left >= n || pos < 1 || pos > n || r.flags[left]) return;
				if (r.arr[pos - 1] == left) {
					r.flags[left] = true;
					boolean all = true;
					for (boolean f : r.flags) if (!f) all = false;
					if (all) stepDone(p, r);
					else sendWires(p, r);
				} else {
					r.flags = new boolean[n];
					Game.tell(p.name, "Wrong connection! All wires reset.", "red");
					sendWires(p, r);
				}
			}
			case "memory" -> {
				if (r.phase != 1) return;
				if (k == r.arr[r.n]) {
					r.n++;
					if (r.n >= r.arr.length) stepDone(p, r);
					else Game.bar(p.name, "Correct " + r.n + "/" + r.arr.length, "green");
				} else {
					for (int i = 0; i < r.arr.length; i++) r.arr[i] = RNG.nextInt(4);
					r.timer = 0;
					r.phase = 0;
					r.n = 0;
					Game.tell(p.name, "Wrong! A new sequence is coming - watch carefully.", "red");
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
				else sendShields(p, r);
			}
			case "sample" -> {
				if (r.ref.stage != 2) return;
				if (k == r.target) {
					stepDone(p, r);
				} else {
					r.ref.stage = 0;
					p.run = null;
					Game.tell(p.name, "Wrong vial! The sample was ruined - start the analysis again.", "red");
				}
			}
			default -> Game.tell(p.name, "This task doesn't use buttons.", "gray");
		}
	}

	static void code(ServerPlayer sp, int value) {
		Game.PState p = Game.players.get(sp.getUUID());
		if (p == null || Game.phase != Game.Phase.PLAYING || !p.alive) return;
		if (p.run == null || !("code".equals(p.run.step.mech) || "math".equals(p.run.step.mech))) {
			Game.tell(p.name, "You don't have a code task active. Click a task station first.", "gray");
			return;
		}
		if (p.run.code != value) {
			Game.tell(p.name, "Wrong code!", "red");
			return;
		}
		stepDone(p, p.run);
	}

	// =====================================================================
	// completion
	// =====================================================================

	static void fail(Game.PState p, String msg) {
		p.run = null;
		Game.bar(p.name, msg, "red");
	}

	static void stepDone(Game.PState p, Run r) {
		p.run = null;
		Game.TaskRef ref = r.ref;
		MapData.TaskDef d = r.def;
		ref.step++;
		ref.stage = 0;
		if (ref.step >= d.steps.size()) {
			Game.completeTask(p, ref, d);
		} else {
			MapData.Step nx = d.steps.get(ref.step);
			Game.tell(p.name, d.name + ": step " + ref.step + "/" + d.steps.size() + " done. Next: go to " + nx.room
				+ " (" + nx.pos.x + ", " + nx.pos.y + ", " + nx.pos.z + ")", "green");
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

	private static void sendOrder(Game.PState p, Run r) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < r.arr.length; i++) {
			int v = r.arr[i];
			if (i > 0) sb.append(",");
			sb.append(Game.btn(String.valueOf(v), v < r.n ? "dark_gray" : "aqua", "/among pick " + v));
		}
		sb.append("]");
		Game.raw(p.name, sb.toString());
	}

	private static void sendWires(Game.PState p, Run r) {
		int n = r.arr.length;
		StringBuilder right = new StringBuilder("{\"text\":\"\",\"extra\":[{\"text\":\"Right side:  \",\"color\":\"gray\"}");
		for (int pos = 0; pos < n; pos++) {
			int c = r.arr[pos];
			right.append(",{\"text\":\"").append(pos + 1).append("=").append(WIRE_NAMES[c]).append("   \",\"color\":\"")
				.append(WIRE_HEX[c]).append("\"}");
		}
		right.append("]}");
		Game.raw(p.name, right.toString());
		for (int left = 0; left < n; left++) {
			if (r.flags[left]) continue;
			StringBuilder row = new StringBuilder("[{\"text\":\"" + WIRE_NAMES[left] + " -> \",\"color\":\"" + WIRE_HEX[left] + "\"}");
			for (int pos = 1; pos <= n; pos++) {
				row.append(",").append(Game.btn(String.valueOf(pos), WIRE_HEX[left], "/among pick " + ((left + 1) * 10 + pos)));
			}
			row.append("]");
			Game.raw(p.name, row.toString());
		}
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
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < 4; i++) {
				if (i > 0) sb.append(",");
				sb.append(Game.btn(MEM_NAMES[i], MEM_HEX[i], "/among pick " + i));
			}
			sb.append("]");
			Game.tell(p.name, "Now repeat the sequence (" + len + " colours):", "aqua");
			Game.raw(p.name, sb.toString());
		}
	}

	private static void asteroidsTick(Game.PState p, Run r) {
		if (r.phase == 0) {
			if (--r.aux <= 0) {
				r.phase = 1;
				r.target = 1 + RNG.nextInt(6);
				r.timer = lim(60);
				StringBuilder sb = new StringBuilder("[");
				for (int i = 1; i <= 6; i++) {
					if (i > 1) sb.append(",");
					sb.append(Game.btn(i == r.target ? "*" : "o", i == r.target ? "gold" : "dark_gray", "/among pick " + i));
				}
				sb.append("]");
				Game.raw(p.name, sb.toString());
			}
		} else if (--r.timer <= 0) {
			asteroidMiss(p, r, "Too slow!");
		}
	}

	private static void asteroidMiss(Game.PState p, Run r, String why) {
		r.n = 0;
		r.phase = 0;
		r.aux = 30;
		Game.tell(p.name, why + " The asteroids got through - start over (0/" + r.step.a + ").", "red");
	}

	private static void toggle(int[] a, int i) {
		int n = a.length;
		a[i] ^= 1;
		a[(i + 1) % n] ^= 1;
		a[(i - 1 + n) % n] ^= 1;
	}

	private static void sendShields(Game.PState p, Run r) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < r.arr.length; i++) {
			if (i > 0) sb.append(",");
			sb.append(Game.btn("#", r.arr[i] == 1 ? "red" : "white", "/among pick " + (i + 1)));
		}
		sb.append("]");
		Game.raw(p.name, sb.toString());
	}

	private static void sendVials(Game.PState p, Run r) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 1; i <= 5; i++) {
			if (i > 1) sb.append(",");
			sb.append(Game.btn("Vial " + i, i == r.target ? "#8A5BDD" : "#4466DD", "/among pick " + i));
		}
		sb.append("]");
		Game.raw(p.name, sb.toString());
	}
}
