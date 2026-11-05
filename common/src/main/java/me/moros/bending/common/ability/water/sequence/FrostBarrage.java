/*
 * Copyright 2020-2026 Moros
 *
 * This file is part of Bending.
 *
 * Bending is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Bending is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bending. If not, see <https://www.gnu.org/licenses/>.
 */

package me.moros.bending.common.ability.water.sequence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityInstance;
import me.moros.bending.api.ability.Activation;
import me.moros.bending.api.ability.common.basic.ParticleStream;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.collision.geometry.Ray;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.Direction;
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.block.BlockType;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.platform.world.WorldUtil;
import me.moros.bending.api.temporal.TempBlock;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.BendingEffect;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.material.MaterialUtil;
import me.moros.math.Vector3d;
import me.moros.math.VectorUtil;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class FrostBarrage extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private final Collection<IceShardStream> streams = new ArrayList<>();
  private final Set<UUID> damagedEntities = new HashSet<>();

  private int firedShots = 0;
  private long nextFireTime = 0;

  public FrostBarrage(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    if (user.game().abilityManager(user.worldKey()).hasAbility(user, FrostBarrage.class)) {
      return false;
    }

    this.user = user;
    loadConfig();

    removalPolicy = Policies.defaults();
    user.addCooldown(description(), userConfig.cooldown);
    SoundEffect.ICE.play(user.world(), user.eyeLocation());
    return true;
  }

  @Override
  public void loadConfig() {
    userConfig = user.game().configProcessor().calculate(this, Config.class);
  }

  @Override
  public UpdateResult update() {
    if (removalPolicy.test(user, description())) {
      return UpdateResult.REMOVE;
    }

    long time = System.currentTimeMillis();
    if (firedShots < userConfig.totalShards && time >= nextFireTime) {
      fireShard();
      nextFireTime = time + userConfig.fireInterval;
    }

    // Passive visual halo of unshot shards hovering behind the user
    int remaining = userConfig.totalShards - firedShots;
    if (remaining > 0) {
      renderFloatingShards(remaining);
    }

    streams.removeIf(stream -> stream.update() == UpdateResult.REMOVE);

    return (streams.isEmpty() && firedShots >= userConfig.totalShards) ? UpdateResult.REMOVE : UpdateResult.CONTINUE;
  }

  private void renderFloatingShards(int count) {
    Vector3d center = user.location().add(0, 1.8, 0);
    Vector3d dir = user.direction().withY(0).normalize();
    Vector3d right = dir.cross(Vector3d.PLUS_J).normalize();

    int displayCount = Math.min(count, 8);
    for (int i = 0; i < displayCount; i++) {
      double offsetFactor = ((double) i / (displayCount - 1 + 0.001)) - 0.5;
      Vector3d shardPos = center.subtract(dir.multiply(0.5)).add(right.multiply(offsetFactor * 1.6));
      ParticleBuilder.rgb(shardPos, "#AEEEEE", 1.2).spawn(user.world());
      Particle.SNOWFLAKE.builder(shardPos).count(1).spawn(user.world());
    }
  }

  private void fireShard() {
    firedShots++;
    Vector3d origin = user.eyeLocation().add(user.direction().multiply(0.5));
    Vector3d target = user.rayTrace(userConfig.range).cast(user.world()).position();
    double dist = target.distance(origin);

    Vector3d trajectory = VectorUtil.gaussianOffset(target, dist * 0.02).subtract(origin).normalize();
    streams.add(new IceShardStream(Ray.of(origin, trajectory.multiply(userConfig.range))));
    SoundEffect.ICE.play(user.world(), origin);
  }

  @Override
  public Collection<Collider> colliders() {
    return streams.stream().map(ParticleStream::collider).toList();
  }

  private class IceShardStream extends ParticleStream {
    public IceShardStream(Ray ray) {
      super(user, ray, userConfig.speed, 0.45);
      canCollide = MaterialUtil::isFire;
      livingOnly = true;
      singleCollision = true;
    }

    @Override
    public void render(Vector3d location) {
      ParticleBuilder.rgb(location, "#AEEEEE", 1.4).spawn(user.world());
      Particle.SNOWFLAKE.builder(location).count(2).offset(0.08).speed(0.01).spawn(user.world());
    }

    @Override
    public boolean onEntityHit(Entity entity) {
      damagedEntities.add(entity.uuid());
      entity.damage(userConfig.shardDamage, user, description());
      BendingEffect.FROST_TICK.apply(user, entity, userConfig.frostTicks);

      SoundEffect.ICE.play(user.world(), entity.location());
      Particle.SNOWFLAKE.builder(entity.location().add(0, 1, 0)).count(12).offset(0.4).speed(0.1).spawn(user.world());
      return true;
    }

    @Override
    public boolean onBlockHit(Block block) {
      SoundEffect.ICE.play(user.world(), block.center());
      Particle.SNOWFLAKE.builder(block.center()).count(10).offset(0.3).speed(0.05).spawn(user.world());

      Block top = block.offset(Direction.UP);
      if (top.type().isAir()) {
        TempBlock.builder(BlockType.SNOW).duration(4000).build(top);
      }
      return true;
    }
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 7500;
    @Modifiable(Attribute.DAMAGE)
    private double shardDamage = 1.8;
    @Comment("Ticks of freezing applied per shard hit")
    private int frostTicks = 50;
    @Modifiable(Attribute.RANGE)
    private double range = 22;
    @Modifiable(Attribute.SPEED)
    private double speed = 1.5;
    @Comment("Total number of ice shards launched")
    private int totalShards = 8;
    @Comment("Milliseconds between each fired shard")
    private long fireInterval = 140;

    @Override
    public List<String> path() {
      return List.of("abilities", "water", "sequences", "frostbarrage");
    }
  }
}

