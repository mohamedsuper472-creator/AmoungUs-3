package com.amongus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The Among Us map: where everything is. Saved to config/amongus-map.json. */
public final class MapData {
	public static final class P {
		public int x;
		public int y;
		public int z;

		public P() {}

		public P(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		public boolean is(BlockPos b) {
			return b.getX() == x && b.getY() == y && b.getZ() == z;
		}

		public double cx() {
			return x + 0.5;
		}

		public double cz() {
			return z + 0.5;
		}
	}

	/** One step of a task: which mechanic, where. */
	public static final class Step {
		public String mech; // clicks hold sneak code swipe bar order wires memory asteroids shields sample
		public int a;
		public int b;
		public String room;
		public P pos;
	}

	public static final class TaskDef {
		public String name;
		public int diff; // 1 easy, 2 medium, 3 very hard
		public List<Step> steps = new ArrayList<>();
	}

	public static final class Vent {
		public P pos;
		public int group;
	}

	public P lobby;
	public P button;
	public List<P> switches = new ArrayList<>();   // electrical levers
	public P o2Panel;                              // keypad in O2
	public P o2Code;                               // code display (Admin)
	public List<P> reactors = new ArrayList<>();   // reactor panels
	public List<Vent> vents = new ArrayList<>();
	public List<TaskDef> tasks = new ArrayList<>();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean ready() {
		return lobby != null && button != null && !tasks.isEmpty();
	}

	public static String diffName(int d) {
		return d == 1 ? "Easy" : d == 2 ? "Medium" : "Very Hard";
	}

	public static String diffTag(int d) {
		return d == 1 ? "E" : d == 2 ? "M" : "VH";
	}

	public static String diffColor(int d) {
		return d == 1 ? "green" : d == 2 ? "yellow" : "red";
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("amongus-map.json");
	}

	public static MapData load() {
		try {
			Path p = path();
			if (Files.exists(p)) {
				MapData m = GSON.fromJson(Files.readString(p), MapData.class);
				if (m != null) {
					if (m.vents == null) m.vents = new ArrayList<>();
					if (m.tasks == null) m.tasks = new ArrayList<>();
					if (m.switches == null) m.switches = new ArrayList<>();
					if (m.reactors == null) m.reactors = new ArrayList<>();
					return m;
				}
			}
		} catch (Exception e) {
			AmongUsMod.LOGGER.warn("Could not read amongus-map.json", e);
		}
		return new MapData();
	}

	public void save() {
		try {
			Files.writeString(path(), GSON.toJson(this));
		} catch (IOException e) {
			AmongUsMod.LOGGER.warn("Could not save amongus-map.json", e);
		}
	}
}
