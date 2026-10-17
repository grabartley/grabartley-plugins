package com.example.mymod.qa;

// TEMPORARY QA COMMANDS TEMPLATE - copy into src/main/java/<pkg>/qa/, NEVER commit.
// Register with `QaCommands.register();` in onInitialize(), revert after QA.
//
// A /qa command tree for staging scenes the live driver photographs. Because these are real
// brigadier commands, the same tree works in singleplayer (the driver runs them on the integrated
// server) and multiplayer (an op player sends them as chat commands), with no stdin plumbing.
// Entities are found by a scoreboard tag, which survives save and reload where entity ids do not.
// Every reply starts with "qa: "; "qa: var <tag>=<entityId>" sets $<tag> in the client driver.
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
import java.util.Locale;
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

public final class QaCommands {

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
                                    .executes(QaCommands::spawn))))))))
            .then(literal("ai").then(tag()
                .then(argument("on", BoolArgumentType.bool()).executes(QaCommands::ai))))
            .then(literal("sit").then(tag()
                .then(argument("on", BoolArgumentType.bool()).executes(QaCommands::sit))))
            .then(literal("hurt").then(tag()
                .then(argument("amount", FloatArgumentType.floatArg()).executes(QaCommands::hurt))))
            .then(literal("find").then(tag().executes(QaCommands::find)))
            .then(literal("probe").then(tag().executes(QaCommands::probe)))
            .then(literal("mobs").executes(QaCommands::mobs))
            .then(literal("players").executes(QaCommands::players))
            .then(literal("clear").executes(QaCommands::clear)));
  }

  private static void say(final CommandContext<ServerCommandSource> c, final String line) {
    c.getSource().sendFeedback(() -> Text.literal("qa: " + line), false);
    System.out.println("[QA-S] " + line);
  }

  private static Entity byTag(final CommandContext<ServerCommandSource> c) {
    final String tag = StringArgumentType.getString(c, "tag");
    for (final ServerWorld world : c.getSource().getServer().getWorlds()) {
      for (final Entity entity : world.iterateEntities()) {
        if (entity.getCommandTags().contains(tag)) {
          return entity;
        }
      }
    }
    throw new IllegalArgumentException(
        "no loaded entity tagged " + tag + " (move a player near it first)");
  }

  private static int spawn(final CommandContext<ServerCommandSource> c) {
    final ServerCommandSource source = c.getSource();
    final ServerWorld world = source.getWorld();
    final String tag = StringArgumentType.getString(c, "tag");
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
    if (entity instanceof LivingEntity living) {
      living.setBodyYaw(yaw);
      living.setHeadYaw(yaw);
    }
    if (entity instanceof MobEntity mob) {
      mob.setPersistent();
      mob.setAiDisabled(true);
    }
    if (entity instanceof TameableEntity pet && source.getEntity() instanceof ServerPlayerEntity owner) {
      pet.setOwnerUuid(owner.getUuid());
      pet.setTamed(true, true);
      pet.setSitting(true);
      pet.setInSittingPose(true);
    }
    world.spawnEntity(entity);
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

  private static int clear(final CommandContext<ServerCommandSource> c) {
    final ServerCommandSource source = c.getSource();
    int removed = 0;
    for (final Entity entity : source.getWorld().getOtherEntities(
        null, Box.of(source.getPosition(), 256, 256, 256), e -> !(e instanceof ServerPlayerEntity))) {
      entity.discard();
      removed++;
    }
    say(c, "cleared " + removed);
    return 1;
  }
}
