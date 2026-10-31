package com.amongus;

import java.util.function.Consumer;

/**
 * Builds the big Skeld-style map with vanilla commands, spread over many ticks
 * (chunks are force-loaded first). The layout itself lives in the generated Layout class.
 */
public final class MapBuilder {
	private MapBuilder() {}

	static int ox;
	static int oy;
	static int oz;

	static void fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		Game.work.add("fill " + (ox + x1) + " " + (oy + y1) + " " + (oz + z1) + " "
			+ (ox + x2) + " " + (oy + y2) + " " + (oz + z2) + " " + block);
	}

	static void set(int x, int y, int z, String block) {
		Game.work.add("setblock " + (ox + x) + " " + (oy + y) + " " + (oz + z) + " " + block);
	}

	static MapData.P p(int x, int y, int z) {
		return new MapData.P(ox + x, oy + y, oz + z);
	}

	/** The Cafeteria centre ends up exactly where the player stands. */
	public static void build(int px, int py, int pz, Consumer<MapData> done) {
		ox = px;
		oy = py;
		oz = pz + 36;

		int fx1 = ox - 132;
		int fz1 = oz - 62;
		int fx2 = ox + 116;
		int fz2 = oz + 62;
		Game.work.add("forceload add " + fx1 + " " + fz1 + " " + fx2 + " " + fz2);
		Game.work.add(300); // wait 15 seconds for the chunks to load

		// clear the whole area (in slices so each fill stays under 32768 blocks)
		for (int x = -130; x <= 114; x += 40) {
			int xe = Math.min(x + 39, 114);
			fill(x, 0, -60, xe, 3, 60, "minecraft:air");
			fill(x, 4, -60, xe, 8, 60, "minecraft:air");
		}

		// solid shells (all rooms and corridors), then carve interiors
		for (int[] r : Layout.ROOMS) {
			fill(r[0] - 1, -1, r[1] - 1, r[2] + 1, 7, r[3] + 1, "minecraft:gray_concrete");
		}
		for (int[] c : Layout.CORRIDORS) {
			fill(c[0] - 1, -1, c[1] - 1, c[2] + 1, 5, c[3] + 1, "minecraft:gray_concrete");
		}
		for (int[] r : Layout.ROOMS) {
			fill(r[0], 0, r[1], r[2], 6, r[3], "minecraft:air");
		}
		for (int[] c : Layout.CORRIDORS) {
			fill(c[0], 0, c[1], c[2], 4, c[3], "minecraft:air");
		}

		// floors: corridors first, then rooms on top (so rooms keep their own floor)
		for (int[] c : Layout.CORRIDORS) {
			fill(c[0], -1, c[1], c[2], -1, c[3], "minecraft:smooth_stone");
		}
		for (int i = 0; i < Layout.ROOMS.length; i++) {
			int[] r = Layout.ROOMS[i];
			fill(r[0], -1, r[1], r[2], -1, r[3], Layout.ROOM_FLOORS[i]);
		}

		// ceiling lights
		for (int[] r : Layout.ROOMS) {
			for (int x = r[0] + 2; x <= r[2] - 2; x += 6) {
				for (int z = r[1] + 2; z <= r[3] - 2; z += 6) {
					set(x, 7, z, "minecraft:sea_lantern");
				}
			}
		}
		for (int[] c : Layout.CORRIDORS) {
			boolean horizontal = (c[2] - c[0]) > (c[3] - c[1]);
			if (horizontal) {
				int zc = (c[1] + c[3]) / 2;
				for (int x = c[0] + 2; x <= c[2] - 2; x += 6) set(x, 5, zc, "minecraft:sea_lantern");
			} else {
				int xc = (c[0] + c[2]) / 2;
				for (int z = c[1] + 2; z <= c[3] - 2; z += 6) set(xc, 5, z, "minecraft:sea_lantern");
			}
		}

		Layout.decor();

		MapData m = new MapData();
		m.hasOrigin = true;
		m.ox = ox;
		m.oz = oz;
		m.lobby = p(Layout.LOBBY_X, 0, Layout.LOBBY_Z);
		m.button = p(Layout.BUTTON_X, 1, Layout.BUTTON_Z);
		Layout.content(m);

		Game.work.add("difficulty peaceful");
		Game.work.add("forceload remove " + fx1 + " " + fz1 + " " + fx2 + " " + fz2);
		Game.work.add((Runnable) () -> {
			m.save();
			done.accept(m);
		});
	}
}
