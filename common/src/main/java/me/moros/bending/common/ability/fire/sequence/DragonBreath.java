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

package me.moros.bending.common.ability.fire.sequence;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityInstance;
import me.moros.bending.api.ability.Activation;
import me.moros.bending.api.collision.CollisionUtil;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.collision.geometry.Ray;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.block.BlockType;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.temporal.TempLight;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.BendingEffect;
import me.moros.bending.api.util.ExpiringSet;
import me.moros.bending.api.util.functional.ExpireRemovalPolicy;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.material.MaterialUtil;
import me.moros.math.FastMath;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class DragonBreath extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private final ExpiringSet<UUID> hitCooldown = new ExpiringSet<>(400);
  private Collider collider;
  private double helixAngle = 0;
  private int ticks = 0;

  public DragonBreath(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    if (user.game().abilityManager(user.worldKey()).hasAbility(user, DragonBreath.class)) {
      return false;
    }

    this.user = user;
    loadConfig();

    removalPolicy = Policies.builder()
      .add(ExpireRemovalPolicy.of(userConfig.duration))
      .add(Policies.UNDER_WATER)
      .build();

    user.addCooldown(description(), userConfig.cooldown);
    Sound.ENTITY_ENDER_DRAGON_GROWL.asEffect(0.65F, 1.35F).play(user.world(), user.eyeLocation());
    SoundEffect.FIRE.play(user.world(), user.eyeLocation());
    collider = Ray.of(user.eyeLocation(), user.direction().multiply(userConfig.range));
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

    ticks++;
    Vector3d origin = user.eyeLocation().add(user.direction().multiply(0.4));
    Vector3d dir = user.direction().normalize();
    collider = Ray.of(origin, dir.multiply(userConfig.range));

    Vector3d right = dir.cross(Vector3d.PLUS_J);
    if (right.lengthSq() < 0.001) {
      right = Vector3d.PLUS_I;
    } else {
      right = right.normalize();
    }
    Vector3d up = right.cross(dir).normalize();

    helixAngle += 0.55;
    double stepDist = 0.8;
    double currentDistance = 1.0;

    while (currentDistance <= userConfig.range) {
      Vector3d center = origin.add(dir.multiply(currentDistance));
      Block block = user.world().blockAt(center);
      if (!MaterialUtil.isTransparent(block) && !block.type().isLiquid()) {
        break;
      }

      double radius = 0.4 + (currentDistance / userConfig.range) * 0.8;
      double a1 = helixAngle + (currentDistance * 0.4);
      double a2 = a1 + Math.PI;

      Vector3d flamePos = center.add(right.multiply(Math.cos(a1) * radius)).add(up.multiply(Math.sin(a1) * radius));
      Vector3d soulFlamePos = center.add(right.multiply(Math.cos(a2) * radius)).add(up.multiply(Math.sin(a2) * radius));

      Particle.FLAME.builder(flamePos).count(1).spawn(user.world());
      Particle.SOUL_FIRE_FLAME.builder(soulFlamePos).count(1).spawn(user.world());

      if (ticks % 3 == 0) {
        TempLight.builder(2).build(block);
      }

      currentDistance += stepDist;
    }

    CollisionUtil.handle(user, collider, entity -> {
      if (hitCooldown.add(entity.uuid())) {
        entity.damage(userConfig.damage, user, description());
        BendingEffect.FIRE_TICK.apply(user, entity, userConfig.fireTicks);

        Vector3d push = dir.multiply(userConfig.knockback).add(0, 0.25, 0);
        entity.applyVelocity(this, push);
      }
      return false;
    });

    if (ThreadLocalRandom.current().nextInt(4) == 0) {
      SoundEffect.FIRE.play(user.world(), origin.add(dir.multiply(3.0)));
    }

    return UpdateResult.CONTINUE;
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(collider);
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 7500;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 2.4;
    @Modifiable(Attribute.RANGE)
    private double range = 12.0;
    @Modifiable(Attribute.FIRE_TICKS)
    private int fireTicks = 60;
    @Comment("Knockback force applied along the breath trajectory")
    private double knockback = 0.55;
    @Comment("Channelling duration in milliseconds")
    @Modifiable(Attribute.DURATION)
    private long duration = 2500;

    @Override
    public List<String> path() {
      return List.of("abilities", "fire", "sequences", "dragonbreath");
    }
  }
}
