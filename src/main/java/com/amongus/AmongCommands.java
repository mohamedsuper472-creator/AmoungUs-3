package com.amongus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Arrays;

public final class AmongCommands {
	private AmongCommands() {}

	private static final SuggestionProvider<CommandSourceStack> COLORS =
		(ctx, b) -> SharedSuggestionProvider.suggest(AmongColor.names(), b);
	private static final SuggestionProvider<CommandSourceStack> VOTES =
		(ctx, b) -> {
			java.util.List<String> l = new java.util.ArrayList<>(AmongColor.names());
			l.add("skip");
			return SharedSuggestionProvider.suggest(l, b);
		};
	private static final SuggestionProvider<CommandSourceStack> GAMES =
		(ctx, b) -> SharedSuggestionProvider.suggest(
			Game.created ? java.util.List.of(Game.gameName) : java.util.List.<String>of(), b);
	private static final SuggestionProvider<CommandSourceStack> KEYS =
		(ctx, b) -> SharedSuggestionProvider.suggest(
			Arrays.asList("impostors", "crewmates", "shapeshifters", "engineers", "scientists", "easytasks", "mediumtasks", "hardtasks", "killcd", "discuss",
				"vote", "meetings", "shiftduration", "ventduration", "ventcooldown", "buttoncd", "o2easy", "redsword", "tasktime"), b);
	private static final SuggestionProvider<CommandSourceStack> TYPES =
		(ctx, b) -> SharedSuggestionProvider.suggest(Arrays.asList("clicks", "hold", "sneak", "code", "math", "swipe", "bar", "order", "wires", "memory", "asteroids", "shields"), b);

	private static ServerPlayer pl(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Game.server = c.getSource().getServer();
		return c.getSource().getPlayerOrException();
	}

	/** The block the player is looking at (within 6 blocks), or null. */
	private static BlockPos lookedAt(ServerPlayer p) {
		try {
			HitResult hr = p.pick(6.0, 1.0f, false);
			if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) return bhr.getBlockPos();
		} catch (Exception ignored) {
			// fall through
		}
		return null;
	}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		LiteralCommandNode<CommandSourceStack> node = d.register(Commands.literal("among")
			.executes(c -> {
				Game.menu(pl(c));
				return 1;
			})
			.then(Commands.literal("help").executes(c -> {
				Game.help(pl(c));
				return 1;
			}))
			.then(Commands.literal("status").executes(c -> {
				Game.status(pl(c));
				return 1;
			}))
			.then(Commands.literal("create")
				.executes(c -> {
					Game.create(pl(c), null);
					return 1;
				})
				.then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
					Game.create(pl(c), StringArgumentType.getString(c, "name"));
					return 1;
				})))
			.then(Commands.literal("rename")
				.then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
					Game.rename(pl(c), StringArgumentType.getString(c, "name"));
					return 1;
				})))
			.then(Commands.literal("public").executes(c -> {
				Game.setPublic(pl(c));
				return 1;
			}))
			.then(Commands.literal("private")
				.executes(c -> {
					Game.setPrivate(pl(c), null);
					return 1;
				})
				.then(Commands.argument("code", StringArgumentType.word()).executes(c -> {
					Game.setPrivate(pl(c), StringArgumentType.getString(c, "code"));
					return 1;
				})))
			.then(Commands.literal("games").executes(c -> {
				Game.games(pl(c));
				return 1;
			}))
			.then(Commands.literal("minimap")
				.executes(c -> {
					Game.minimapInfo(pl(c));
					return 1;
				})
				.then(Commands.literal("right").executes(c -> {
					Game.setMinimap(pl(c), Hud.MODE_RIGHT);
					return 1;
				}))
				.then(Commands.literal("top").executes(c -> {
					Game.setMinimap(pl(c), Hud.MODE_TOP);
					return 1;
				}))
				.then(Commands.literal("off").executes(c -> {
					Game.setMinimap(pl(c), Hud.MODE_OFF);
					return 1;
				})))
			.then(Commands.literal("join")
				.executes(c -> {
					Game.join(pl(c), null, null);
					return 1;
				})
				.then(Commands.argument("name", StringArgumentType.word()).suggests(GAMES)
					.executes(c -> {
						Game.join(pl(c), StringArgumentType.getString(c, "name"), null);
						return 1;
					})
					.then(Commands.argument("code", StringArgumentType.word()).executes(c -> {
						Game.join(pl(c), StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "code"));
						return 1;
					}))))
			.then(Commands.literal("leave").executes(c -> {
				ServerPlayer p = pl(c);
				Game.leave(p.getUUID(), false);
				return 1;
			}))
			.then(Commands.literal("start")
				.executes(c -> {
					Game.startCmd(pl(c), false);
					return 1;
				})
				.then(Commands.literal("force").executes(c -> {
					Game.startCmd(pl(c), true);
					return 1;
				})))
			.then(Commands.literal("stop").executes(c -> {
				Game.stopCmd(pl(c));
				return 1;
			}))
			.then(Commands.literal("tasks").executes(c -> {
				Game.showTasks(pl(c));
				return 1;
			}))
			.then(Commands.literal("report").executes(c -> {
				Game.reportCmd(pl(c));
				return 1;
			}))
			.then(Commands.literal("vitals").executes(c -> {
				Game.vitals(pl(c));
				return 1;
			}))
			.then(Commands.literal("code")
				.then(Commands.argument("code", IntegerArgumentType.integer(0, 99999)).executes(c -> {
					Game.enterCode(pl(c), IntegerArgumentType.getInteger(c, "code"));
					return 1;
				})))
			.then(Commands.literal("vote")
				.executes(c -> {
					Game.voteReopen(pl(c));
					return 1;
				})
				.then(Commands.argument("target", StringArgumentType.word()).suggests(VOTES).executes(c -> {
					Game.vote(pl(c), StringArgumentType.getString(c, "target"));
					return 1;
				})))
			.then(Commands.literal("sabotage")
				.executes(c -> {
					Game.sabotageMenu(pl(c));
					return 1;
				})
				.then(Commands.literal("lights").executes(c -> {
					Game.sabotage(pl(c), "lights");
					return 1;
				}))
				.then(Commands.literal("o2").executes(c -> {
					Game.sabotage(pl(c), "o2");
					return 1;
				}))
				.then(Commands.literal("reactor").executes(c -> {
					Game.sabotage(pl(c), "reactor");
					return 1;
				})))
			.then(Commands.literal("stats").executes(c -> {
				Game.stats(pl(c));
				return 1;
			}))
			.then(Commands.literal("pick")
				.then(Commands.argument("n", IntegerArgumentType.integer(0, 99)).executes(c -> {
					Tasks.pick(pl(c), IntegerArgumentType.getInteger(c, "n"));
					return 1;
				})))
			.then(Commands.literal("o2")
				.then(Commands.argument("code", IntegerArgumentType.integer(0, 99999)).executes(c -> {
					Game.o2Enter(pl(c), IntegerArgumentType.getInteger(c, "code"));
					return 1;
				})))
			.then(Commands.literal("shift")
				.executes(c -> {
					Game.shift(pl(c), null);
					return 1;
				})
				.then(Commands.argument("color", StringArgumentType.word()).suggests(COLORS).executes(c -> {
					Game.shift(pl(c), StringArgumentType.getString(c, "color"));
					return 1;
				})))
			.then(Commands.literal("set")
				.then(Commands.argument("key", StringArgumentType.word()).suggests(KEYS)
					.then(Commands.argument("value", IntegerArgumentType.integer(0, 1000)).executes(c -> {
						ServerPlayer p = pl(c);
						String msg = setting(StringArgumentType.getString(c, "key").toLowerCase(), IntegerArgumentType.getInteger(c, "value"));
						Game.tell(p.getName().getString(), msg, msg.startsWith("Unknown") || msg.startsWith("Can't") ? "red" : "green");
						return 1;
					}))))
			.then(Commands.literal("map")
				.then(Commands.literal("buildmap").executes(c -> {
					ServerPlayer p = pl(c);
					String n = p.getName().getString();
					if (Game.phase != Game.Phase.LOBBY) {
						Game.tell(n, "Can't build during a game.", "red");
						return 0;
					}
					BlockPos pos = p.blockPosition();
					if (!Game.work.isEmpty()) {
						Game.tell(n, "A build is already running.", "red");
						return 0;
					}
					Game.tell(n, "Building the BIG Skeld map around you (about 250x120 blocks). It takes a few minutes. Stay in the area and do not stop the server!", "yellow");
					Game.tell(n, "Stand on flat ground with lots of free space. The Cafeteria is built where you stand.", "yellow");
					MapBuilder.build(pos.getX(), pos.getY(), pos.getZ(), m -> {
						Game.map = m;
						Game.tell(n, "Map built! Everyone can now /among join. (Cafeteria = lobby, red button = emergency, iron trapdoors = vents)", "green");
					});
					return 1;
				}))
				.then(Commands.literal("info").executes(c -> {
					ServerPlayer p = pl(c);
					Game.tell(p.getName().getString(), "Map ready=" + Game.map.ready() + " tasks=" + Game.map.tasks.size()
						+ " vents=" + Game.map.vents.size() + " switches=" + Game.map.switches.size()
						+ " reactors=" + Game.map.reactors.size() + " o2=" + (Game.map.o2Panel != null), "yellow");
					return 1;
				}))
				.then(Commands.literal("clear").executes(c -> {
					ServerPlayer p = pl(c);
					if (Game.phase != Game.Phase.LOBBY) return 0;
					Game.map = new MapData();
					Game.map.save();
					Game.tell(p.getName().getString(), "Map data cleared (blocks stay in the world).", "yellow");
					return 1;
				}))
				.then(Commands.literal("addvent").executes(c -> {
					ServerPlayer p = pl(c);
					BlockPos b = lookedAt(p);
					if (b == null) {
						Game.tell(p.getName().getString(), "Look at a block first (within 6 blocks).", "red");
						return 0;
					}
					MapData.Vent nv = new MapData.Vent();
					nv.pos = new MapData.P(b.getX(), b.getY(), b.getZ());
					nv.group = 0;
					Game.map.vents.add(nv);
					Game.map.save();
					Game.tell(p.getName().getString(), "Vent #" + Game.map.vents.size() + " added.", "green");
					return 1;
				}))
				.then(Commands.literal("addtask")
					.then(Commands.argument("type", StringArgumentType.word()).suggests(TYPES)
						.then(Commands.argument("diff", IntegerArgumentType.integer(1, 3))
							.then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
								ServerPlayer p = pl(c);
								String type = StringArgumentType.getString(c, "type").toLowerCase();
								BlockPos b = lookedAt(p);
								if (b == null) {
									Game.tell(p.getName().getString(), "Look at a block first (within 6 blocks).", "red");
									return 0;
								}
								MapData.TaskDef td = new MapData.TaskDef();
								td.name = StringArgumentType.getString(c, "name");
								td.diff = IntegerArgumentType.getInteger(c, "diff");
								MapData.Step st = new MapData.Step();
								st.mech = type;
								st.a = 3;
								st.b = 0;
								st.room = "Custom";
								st.pos = new MapData.P(b.getX(), b.getY(), b.getZ());
								td.steps.add(st);
								Game.map.tasks.add(td);
								Game.map.save();
								Game.tell(p.getName().getString(), "Task added (" + Game.map.tasks.size() + " total).", "green");
								return 1;
							})))))
				.then(Commands.literal("set")
					.then(Commands.literal("lobby").executes(c -> {
						ServerPlayer p = pl(c);
						BlockPos b = p.blockPosition();
						Game.map.lobby = new MapData.P(b.getX(), b.getY(), b.getZ());
						Game.map.save();
						Game.tell(p.getName().getString(), "Lobby set to where you stand.", "green");
						return 1;
					}))
					.then(Commands.literal("button").executes(c -> setLooked(c, "button")))))
		);
		d.register(Commands.literal("au").redirect(node));
		d.register(Commands.literal("impostor")
			.executes(c -> {
				Game.setImpostors(pl(c), null);
				return 1;
			})
			.then(Commands.argument("number", IntegerArgumentType.integer(0, 3)).executes(c -> {
				Game.setImpostors(pl(c), IntegerArgumentType.getInteger(c, "number"));
				return 1;
			})));
		d.register(Commands.literal("crewmate")
			.executes(c -> {
				Game.setCrewmates(pl(c), null);
				return 1;
			})
			.then(Commands.argument("number", IntegerArgumentType.integer(0, AmongColor.NAMES.length - 1)).executes(c -> {
				Game.setCrewmates(pl(c), IntegerArgumentType.getInteger(c, "number"));
				return 1;
			})));
	}

	private static int setLooked(CommandContext<CommandSourceStack> c, String what) throws CommandSyntaxException {
		ServerPlayer p = pl(c);
		BlockPos b = lookedAt(p);
		if (b == null) {
			Game.tell(p.getName().getString(), "Look at a block first (within 6 blocks).", "red");
			return 0;
		}
		MapData.P pos = new MapData.P(b.getX(), b.getY(), b.getZ());
		switch (what) {
			case "button" -> Game.map.button = pos;
			default -> Game.map.button = pos;
		}
		Game.map.save();
		Game.tell(p.getName().getString(), "Map " + what + " set.", "green");
		return 1;
	}

	private static String setting(String key, int v) {
		if (Game.phase != Game.Phase.LOBBY) return "Can't change settings during a game.";
		switch (key) {
			case "impostors" -> Game.impostors = Math.min(v, 3);
			case "crewmates" -> Game.crewmates = Math.min(v, AmongColor.NAMES.length - 1);
			case "easytasks" -> Game.easyTasks = Math.min(v, 8);
			case "mediumtasks" -> Game.mediumTasks = Math.min(v, 8);
			case "hardtasks" -> Game.hardTasks = Math.min(v, 6);
			case "tasktime" -> Game.taskTime = Math.max(50, Math.min(v, 1000));
			case "o2easy" -> Game.o2Easy = v != 0;
			case "shapeshifters" -> Game.shapeshifters = Math.min(v, 3);
			case "engineers" -> Game.engine
