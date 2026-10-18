package com.example.mymod.qa;

// TEMPORARY QA COMMANDS TEMPLATE - copy into src/main/java/<pkg>/qa/, NEVER commit.
// Register with `QaCommands.register();` in onInitialize(), revert after QA.
//
// A /qa command tree for staging scenes the live driver photographs. Because these are real
// brigadier commands, the same tree works in singleplayer (the driver runs them on the integrated
// server) and multiplayer (an op player sends them as chat commands), with no stdin plumbing.
// Entities are found by a scoreboard tag, which survives save and reload where entity ids do not.
// A tag must match exactly one loaded entity: spawn refuses a tag already in use and every lookup
// fails on zero or several matches, so a stale subject is never photographed by mistake.
// Spawned subjects are neutral by default (untamed, no AI, silent, invulnerable, no gravity) so the
// mod under test has nothing to react to; opt into behaviour with spawn flags. Each spawn records
// the staged pose, and `qa check` fails if any staged subject has moved, turned or gone.
// Every reply starts with "qa: "; "qa: var <tag>=<entityId>" sets $<tag> in the client driver and
// "qa: FAIL ..." makes the driver stop the batch.
// Add mod-specific subcommands (equip, pose, put to bed, ...) next to the generic ones below.

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;

public final class QaCommands {

  private static final double DRIFT_BLOCKS = 0.05;
  private static final float DRIFT_DEGREES = 3.0f;
  private static final Set<String> FLAGS = Set.of("tame", "sit", "ai", "gravity", "sound", "vulnerable");

  private record Staged(Vec3d pos, float yaw) {}

  private static final Map<String, Staged> STAGED = new LinkedHashMap<>();

  private QaCommands() {}

  public static void register() {
    CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> build(dispatcher));
  }

  private static ArgumentBuilder<ServerCommandSource, ?> tag() {
    return argument("tag", StringArgumentType.word());
  }

  private static void build(final CommandDispatcher<ServerCommandSource> d) {
    d.register(
        literal("qa")
            .requires(s -> s.hasPermissionLevel(2))
            .then(literal("spawn").then(tag()
                .then(argument("type", IdentifierArgumentType.identifier())
                    .then(argument("x", DoubleArgumentType.doubleArg())
                        .then(argument("y", DoubleArgumentType.doubleArg())
                            .then(argument("z", DoubleArgumentType.doubleArg())
                                .then(argument("yaw", FloatArgumentType.floatArg())
                                    .executes(c -> spawn(c, ""))
                                    .then(argument("flags", StringArgumentType.greedyString())
                                        .executes(c -> spawn(c, StringArgumentType.getString(c, "flags")))))))))))
            .then(literal("ai").then(tag()
                .then(argument("on", BoolArgumentType.bool()).executes(QaCommands::ai))))
            .then(literal("sit").then(tag()
                .then(argument("on", BoolArgumentType.bool()).executes(QaCommands::sit))))
            .then(literal("hurt").then(tag()
                .then(argument("amount", FloatArgumentType.floatArg()).executes(QaCommands::hurt))))
            .then(literal("find").then(tag().executes(QaCommands::find)))
            .then(literal("restage").then(tag().executes(QaCommands::restage)))
            .then(literal("unstage").then(tag().executes(QaCommands::unstage)))
            .then(literal("check").executes(QaCommands::check))
            .then(literal("scene")
                .executes(c -> scene(c, 32))
                .then(argument("radius", DoubleArgumentType.doubleArg(1)).executes(
                    c -> scene(c, DoubleArgumentType.getDouble(c, "radius")))))
            .then(literal("reset").then(argument("radius", DoubleArgumentType.doubleArg(1))
                .executes(QaCommands::reset)))
            .then(literal("quiet").executes(QaCommands::quiet))
            .then(literal("probe").then(tag().executes(QaCommands::probe)))
            .then(literal("mobs").executes(QaCommands::mobs))
            .then(literal("players").executes(QaCommands::players)));
  }

  private static void say(final CommandContext<ServerCommandSource> c, final String line) {
    c.getSource().sendFeedback(() -> Text.literal("qa: " + line), false);
    System.out.println("[QA-S] " + line);
  }

  private static void fail(final CommandContext<ServerCommandSource> c, final String line) {
    say(c, "FAIL " + line);
  }

  private static List<Entity> tagged(final CommandContext<ServerCommandSource> c, final String tag) {
    final List<Entity> found = new ArrayList<>();
    for (final ServerWorld world : c.getSource().getServer().getWorlds()) {
      for (final Entity entity : world.iterateEntities()) {
        if (entity.getCommandTags().contains(tag)) {
          found.add(entity);
        }
      }
    }
    return found;
  }

  private static Entity byTag(final CommandContext<ServerCommandSource> c) {
    return byTag(c, StringArgumentType.getString(c, "tag"));
  }

  private static Entity byTag(final CommandContext<ServerCommandSource> c, final String tag) {
    final List<Entity> found = tagged(c, tag);
    if (found.size() != 1) {
      final String problem = found.size() + " loaded entities tagged " + tag
          + " (want exactly 1; run qa scene, or move a player near it)";
      fail(c, problem);
      throw new IllegalArgumentException(problem);
    }
    return found.get(0);
  }

  private static int spawn(final CommandContext<ServerCommandSource> c, final String flagText) {
    final ServerCommandSource source = c.getSource();
    final ServerWorld world = source.getWorld();
    final String tag = StringArgumentType.getString(c, "tag");
    final Set<String> flags = flagText.isBlank() ? Set.of() : Set.of(flagText.trim().split("\\s+"));
    for (final String flag : flags) {
      if (!FLAGS.contains(flag)) {
        fail(c, "unknown spawn flag " + flag + ", expected some of " + FLAGS);
        return 0;
      }
    }
    if (!tagged(c, tag).isEmpty()) {
      fail(c, "tag " + tag + " is already in use; pick a new tag or qa reset first");
      return 0;
    }
    final EntityType<?> type =
        Registries.ENTITY_TYPE.get(IdentifierArgumentType.getIdentifier(c, "type"));
    final double x = DoubleArgumentType.getDouble(c, "x");
    final double y = DoubleArgumentType.getDouble(c, "y");
    final double z = DoubleArgumentType.getDouble(c, "z");
    final float yaw = FloatArgumentType.getFloat(c, "yaw");
    world.getChunk(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
    final Entity entity = type.create(world);
    entity.refreshPositionAndAngles(x, y, z, yaw, 0.0f);
    entity.addCommandTag(tag);
    entity.setSilent(!flags.contains("sound"));
    entity.setInvulnerable(!flags.contains("vulnerable"));
    entity.setNoGravity(!flags.contains("gravity"));
    if (entity instanceof LivingEntity living) {
      living.setBodyYaw(yaw);
      living.setHeadYaw(yaw);
    }
    if (entity instanceof MobEntity mob) {
      mob.setPersistent();
      mob.setAiDisabled(!flags.contains("ai"));
    }
    if (flags.contains("tame")
        && entity instanceof TameableEntity pet
        && source.getEntity() instanceof ServerPlayerEntity owner) {
      pet.setOwnerUuid(owner.getUuid());
      pet.setTamed(true, true);
    }
    if (flags.contains("sit") && entity instanceof TameableEntity pet) {
      pet.setSitting(true);
      pet.setInSittingPose(true);
    }
    world.spawnEntity(entity);
    STAGED.put(tag, new Staged(new Vec3d(x, y, z), yaw));
    say(c, "var " + tag + "=" + entity.getId());
    return 1;
  }

  private static int ai(final CommandContext<ServerCommandSource> c) {
    final MobEntity mob = (MobEntity) byTag(c);
    mob.setAiDisabled(!BoolArgumentType.getBool(c, "on"));
    say(c, "ai " + !mob.isAiDisabled());
    return 1;
  }

  private static int sit(final CommandContext<ServerCommandSource> c) {
    final TameableEntity pet = (TameableEntity) byTag(c);
    final boolean on = BoolArgumentType.getBool(c, "on");
    pet.setSitting(on);
    pet.setInSittingPose(on);
    say(c, "sitting " + pet.isInSittingPose());
    return 1;
  }

  private static int hurt(final CommandContext<ServerCommandSource> c) {
    final LivingEntity living = (LivingEntity) byTag(c);
    final boolean hit =
        living.damage(living.getDamageSources().generic(), FloatArgumentType.getFloat(c, "amount"));
    say(c, "hurt hit=" + hit + " " + describe(living));
    return 1;
  }

  private static int find(final CommandContext<ServerCommandSource> c) {
    say(c, "var " + StringArgumentType.getString(c, "tag") + "=" + byTag(c).getId());
    return 1;
  }

  private static int restage(final CommandContext<ServerCommandSource> c) {
    final Entity entity = byTag(c);
    STAGED.put(StringArgumentType.getString(c, "tag"), new Staged(entity.getPos(), bodyYaw(entity)));
    say(c, "restaged " + StringArgumentType.getString(c, "tag"));
    return 1;
  }

  private static int unstage(final CommandContext<ServerCommandSource> c) {
    STAGED.remove(StringArgumentType.getString(c, "tag"));
    say(c, "unstaged " + StringArgumentType.getString(c, "tag"));
    return 1;
  }

  private static int check(final CommandContext<ServerCommandSource> c) {
    final List<String> problems = new ArrayList<>();
    for (final Map.Entry<String, Staged> staged : STAGED.entrySet()) {
      final List<Entity> found = tagged(c, staged.getKey());
      if (found.size() != 1) {
        problems.add(staged.getKey() + " matches " + found.size() + " entities");
        continue;
      }
      final Entity entity = found.get(0);
      final double moved = entity.getPos().distanceTo(staged.getValue().pos());
      final float turned =
          Math.abs(MathHelper.wrapDegrees(bodyYaw(entity) - staged.getValue().yaw()));
      if (!entity.isAlive() || moved > DRIFT_BLOCKS || turned > DRIFT_DEGREES) {
        problems.add(String.format(Locale.ROOT, "%s alive=%s moved=%.2f turned=%.1f",
            staged.getKey(), entity.isAlive(), moved, turned));
      }
    }
    if (problems.isEmpty()) {
      say(c, "check ok staged=" + STAGED.size());
    } else {
      fail(c, "scene drifted: " + String.join("; ", problems));
    }
    return problems.isEmpty() ? 1 : 0;
  }

  private static float bodyYaw(final Entity entity) {
    return entity instanceof LivingEntity living ? living.getBodyYaw() : entity.getYaw();
  }

  private static int scene(final CommandContext<ServerCommandSource> c, final double radius) {
    final ServerCommandSource source = c.getSource();
    final List<Entity> near = source.getWorld().getOtherEntities(
        null, Box.of(source.getPosition(), radius * 2, radius * 2, radius * 2),
        e -> !(e instanceof ServerPlayerEntity));
    say(c, "scene " + near.size() + " entities within " + radius);
    for (final Entity entity : near) {
      final StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
          "id=%d %s tags=%s yaw=%.0f", entity.getId(), EntityType.getId(entity.getType()),
          entity.getCommandTags(), bodyYaw(entity)));
      if (entity instanceof MobEntity mob) {
        line.append(" ai=").append(!mob.isAiDisabled()).append(" persistent=").append(mob.isPersistent());
      }
      say(c, line + " " + describe(entity));
    }
    return 1;
  }

  private static int reset(final CommandContext<ServerCommandSource> c) {
    final ServerCommandSource source = c.getSource();
    final double radius = DoubleArgumentType.getDouble(c, "radius");
    int removed = 0;
    for (final Entity entity : source.getWorld().getOtherEntities(
        null, Box.of(source.getPosition(), radius * 2, radius * 2, radius * 2),
        e -> !(e instanceof ServerPlayerEntity))) {
      entity.discard();
      removed++;
    }
    STAGED.clear();
    say(c, "reset removed=" + removed);
    return 1;
  }

  private static int quiet(final CommandContext<ServerCommandSource> c) {
    final ServerWorld world = c.getSource().getWorld();
    final GameRules rules = world.getGameRules();
    final var server = c.getSource().getServer();
    rules.get(GameRules.DO_MOB_SPAWNING).set(false, server);
    rules.get(GameRules.DO_PATROL_SPAWNING).set(false, server);
    rules.get(GameRules.DO_TRADER_SPAWNING).set(false, server);
    rules.get(GameRules.DO_WARDEN_SPAWNING).set(false, server);
    rules.get(GameRules.DO_INSOMNIA).set(false, server);
    rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
    rules.get(GameRules.DO_WEATHER_CYCLE).set(false, server);
    rules.get(GameRules.DO_FIRE_TICK).set(false, server);
    rules.get(GameRules.DO_MOB_GRIEFING).set(false, server);
    world.setTimeOfDay(6000);
    world.setWeather(0, 0, false, false);
    say(c, "quiet");
    return 1;
  }

  private static int probe(final CommandContext<ServerCommandSource> c) {
    say(c, StringArgumentType.getString(c, "tag") + " " + describe(byTag(c)));
    return 1;
  }

  private static String describe(final Entity entity) {
    final StringBuilder out =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "pos=%.1f,%.1f,%.1f alive=%s air=%d fire=%d",
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                entity.isAlive(),
                entity.getAir(),
                entity.getFireTicks()));
    if (entity instanceof LivingEntity living) {
      out.append(String.format(Locale.ROOT, " hp=%.1f/%.1f", living.getHealth(), living.getMaxHealth()))
          .append(" effects=")
          .append(
              living.getStatusEffects().stream()
                  .map(e -> e.getEffectType().getIdAsString() + ":" + e.getDuration())
                  .collect(Collectors.joining(",")));
    }
    if (entity instanceof TameableEntity pet) {
      out.append(" sitting=").append(pet.isInSittingPose())
          .append(" owner=").append(pet.getOwner() == null ? "none" : pet.getOwner().getName().getString());
    }
    return out.toString();
  }

  private static int mobs(final CommandContext<ServerCommandSource> c) {
    final ServerCommandSource source = c.getSource();
    for (final MobEntity mob :
        source.getWorld().getEntitiesByClass(
            MobEntity.class, Box.of(source.getPosition(), 96, 96, 96), m -> m instanceof HostileEntity)) {
      final LivingEntity target = mob.getTarget();
      say(c, String.format(Locale.ROOT, "mob %s tags=%s pos=%.1f,%.1f,%.1f target=%s",
          EntityType.getId(mob.getType()), mob.getCommandTags(), mob.getX(), mob.getY(), mob.getZ(),
          target == null ? "none" : target.getName().getString()));
    }
    return 1;
  }

  private static int players(final CommandContext<ServerCommandSource> c) {
    for (final ServerPlayerEntity player : c.getSource().getServer().getPlayerManager().getPlayerList()) {
      say(c, "player " + player.getName().getString() + " " + describe(player));
    }
    return 1;
  }
}
