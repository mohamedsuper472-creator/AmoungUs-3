package com.amongus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent player statistics and coins (config/amongus-stats.json). */
public final class Stats {
	public static final class S {
		public int coins;
		public int wins;
		public int games;
		public int kills;
		public int tasks;
	}

	private static Map<String, S> data = new HashMap<>();
	private static boolean loaded;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private Stats() {}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("amongus-stats.json");
	}

	private static void load() {
		loaded = true;
		try {
			Path p = path();
			if (Files.exists(p)) {
				Map<String, S> m = GSON.fromJson(Files.readString(p), new TypeToken<Map<String, S>>() {}.getType());
				if (m != null) data = m;
			}
		} catch (Exception e) {
			AmongUsMod.LOGGER.warn("Could not read amongus-stats.json", e);
		}
	}

	public static S get(UUID id) {
		if (!loaded) load();
		return data.computeIfAbsent(id.toString(), k -> new S());
	}

	public static void save() {
		try {
			Files.writeString(path(), GSON.toJson(data));
		} catch (Exception e) {
			AmongUsMod.LOGGER.warn("Could not save amongus-stats.json", e);
		}
	}
}
