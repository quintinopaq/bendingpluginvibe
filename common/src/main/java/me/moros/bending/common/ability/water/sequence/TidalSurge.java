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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityInstance;
import me.moros.bending.api.ability.Activation;
import me.moros.bending.api.ability.MultiUpdatable;
import me.moros.bending.api.ability.common.basic.ParticleStream;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.collision.geometry.Ray;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.block.BlockType;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.entity.EntityProperties;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.platform.world.WorldUtil;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.ExpiringSet;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.material.MaterialUtil;
import me.moros.math.Vector3d;
import me.moros.math.VectorUtil;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class TidalSurge extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private final ExpiringSet<UUID> hitCooldown = new ExpiringSet<>(450);
  private final MultiUpdatable<WaveStream> streams = MultiUpdatable.empty();

  public TidalSurge(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    if (user.game().abilityManager(user.worldKey()).hasAbility(user, TidalSurge.class)) {
      return false;
    }

    this.user = user;
    loadConfig();

    Vector3d origin = user.location().add(0, 0.4, 0);
    Vector3d dir = user.direction().withY(0).normalize();
    Vector3d rotateAxis = Vector3d.PLUS_J;

    int streamCount = 14;
    double arcAngle = Math.toRadians(65);
    VectorUtil.createArc(dir, rotateAxis, arcAngle / streamCount, streamCount).forEach(rayDir -> {
      Vector3d streamOrigin = origin.add(rayDir.cross(Vector3d.PLUS_J).multiply(0.3));
      streams.add(new WaveStream(Ray.of(streamOrigin, rayDir.multiply(userConfig.range))));
    });

    removalPolicy = Policies.defaults();
    user.addCooldown(description(), userConfig.cooldown);
    SoundEffect.WATER.play(user.world(), origin);
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
    return streams.update();
  }

  @Override
  public Collection<Collider> colliders() {
    return streams.stream().map(ParticleStream::collider).toList();
  }

  private class WaveStream extends ParticleStream {
    private int ticks = 0;

    public WaveStream(Ray ray) {
      super(user, ray, userConfig.speed, 0.7);
      canCollide = b -> MaterialUtil.isFire(b) || MaterialUtil.BREAKABLE_PLANTS.isTagged(b);
      livingOnly = false;
    }

    @Override
    public void render(Vector3d location) {
      ticks++;
      double waveHeight = 0.5 + Math.sin(ticks * 0.3) * 0.4;
      Vector3d renderPos = location.add(0, waveHeight, 0);

      Particle.SPLASH.builder(renderPos).count(6).offset(0.2).speed(0.08).spawn(user.world());
      Particle.FALLING_WATER.builder(renderPos).count(4).offset(0.15).spawn(user.world());
      if (ticks % 2 == 0) {
        ParticleBuilder.bubble(renderPos).spawn(user.world());
      }

      Block ground = user.world().blockAt(location.subtract(0, 0.5, 0));
      WorldUtil.tryExtinguishFire(user, ground);
      WorldUtil.tryCoolLava(user, ground);
    }

    @Override
    public void postRender(Vector3d location) {
      if (ThreadLocalRandom.current().nextInt(8) == 0) {
        SoundEffect.WATER.play(user.world(), location);
      }
    }

    @Override
    public boolean onEntityHit(Entity entity) {
      if (hitCooldown.add(entity.uuid())) {
        entity.setProperty(EntityProperties.FIRE_TICKS, 0);
        entity.damage(userConfig.damage, user, description());

        Vector3d pushDir = ray.direction().normalize().multiply(userConfig.knockback).add(0, 0.45, 0);
        entity.applyVelocity(TidalSurge.this, pushDir);
      }
      return false;
    }

    @Override
    public boolean onBlockHit(Block block) {
      if (MaterialUtil.isFire(block)) {
        WorldUtil.tryExtinguishFire(user, block);
        return false;
      }
      WorldUtil.tryBreakPlant(block);
      WorldUtil.tryCoolLava(user, block);
      return !MaterialUtil.isTransparent(block);
    }
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 6500;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 3.5;
    @Comment("Knockback force applied to caught entities")
    private double knockback = 1.4;
    @Modifiable(Attribute.RANGE)
    private double range = 14;
    @Modifiable(Attribute.SPEED)
    private double speed = 0.75;

    @Override
    public List<String> path() {
      return List.of("abilities", "water", "sequences", "tidalsurge");
    }
  }
}
