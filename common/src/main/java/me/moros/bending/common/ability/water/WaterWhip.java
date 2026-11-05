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

package me.moros.bending.common.ability.water;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityInstance;
import me.moros.bending.api.ability.Activation;
import me.moros.bending.api.collision.geometry.AABB;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.entity.LivingEntity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.functional.SwappedSlotsRemovalPolicy;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class WaterWhip extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private boolean extended;
  private double currentLength;
  private Vector3d lashDirection;
  private final List<Vector3d> whipPoints = new ArrayList<>();
  private final Set<UUID> hitEntities = new HashSet<>();

  public WaterWhip(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    this.user = user;
    loadConfig();

    for (WaterWhip activeWhip : user.game().abilityManager(user.worldKey()).userInstances(user, WaterWhip.class).toList()) {
      if (!activeWhip.extended) {
        activeWhip.lash();
        return false;
      }
    }

    this.extended = false;
    this.currentLength = 0;
    this.lashDirection = user.direction();

    removalPolicy = Policies.builder()
      .add(SwappedSlotsRemovalPolicy.of(description()))
      .add(Policies.UNDER_LAVA)
      .build();

    if (method == Activation.ATTACK) {
      lash();
    } else {
      SoundEffect.WATER.play(user.world(), user.eyeLocation());
    }
    return true;
  }

  @Override
  public void loadConfig() {
    userConfig = user.game().configProcessor().calculate(this, Config.class);
  }

  public void lash() {
    this.extended = true;
    this.lashDirection = user.direction();
    user.addCooldown(description(), userConfig.cooldown);
    Sound.ENTITY_PLAYER_ATTACK_SWEEP.asEffect(1.0f, 1.4f).play(user.world(), user.eyeLocation());
    SoundEffect.WATER.play(user.world(), user.eyeLocation());
  }

  @Override
  public UpdateResult update() {
    if (removalPolicy.test(user, description())) {
      return UpdateResult.REMOVE;
    }

    Vector3d origin = user.mainHandSide();

    if (!extended) {
      if (!user.sneaking()) {
        lash();
        return UpdateResult.CONTINUE;
      }
      // Display swirling water around hand while held
      double t = (System.currentTimeMillis() % 1000) / 1000.0 * Math.PI * 2;
      Vector3d orb = origin.add(Math.cos(t) * 0.4, Math.sin(t) * 0.4, 0);
      Particle.SPLASH.builder(orb).count(2).offset(0.05).spawn(user.world());
      Particle.FALLING_WATER.builder(orb).count(1).offset(0.02).spawn(user.world());
      return UpdateResult.CONTINUE;
    }

    currentLength += userConfig.speed;
    if (currentLength > userConfig.range) {
      return UpdateResult.REMOVE;
    }

    whipPoints.clear();
    Vector3d current = origin;
    double step = 0.5;
    for (double d = 0; d <= currentLength; d += step) {
      double waveOffset = Math.sin((d - currentLength) * 1.5) * 0.35;
      Vector3d point = current.add(lashDirection.multiply(step)).add(0, waveOffset, 0);
      current = point;
      whipPoints.add(point);

      Particle.DRIPPING_WATER.builder(point).count(1).offset(0.05).spawn(user.world());
      Particle.SPLASH.builder(point).count(2).offset(0.1).spawn(user.world());

      Block block = user.world().blockAt(point);
      if (block.type().isSolid()) {
        Particle.SPLASH.builder(point).count(10).offset(0.3).speed(0.1).spawn(user.world());
        SoundEffect.WATER.play(user.world(), point);
        return UpdateResult.REMOVE;
      }

      for (Entity e : user.world().nearbyEntities(point, 0.8)) {
        if (e instanceof LivingEntity target && !target.equals(user) && hitEntities.add(target.uuid())) {
          target.damage(userConfig.damage, user, description());
          Sound.ENTITY_PLAYER_ATTACK_CRIT.asEffect(1.0f, 1.2f).play(user.world(), target.center());
          SoundEffect.WATER.play(user.world(), target.center());

          if (user.sneaking()) {
            // Drag toward the user
            Vector3d pull = user.center().subtract(target.center()).normalize().multiply(userConfig.pullStrength);
            target.applyVelocity(this, pull.add(0, 0.2, 0));
          } else {
            // Knock back and upwards
            Vector3d knock = lashDirection.multiply(userConfig.knockbackStrength).add(0, 0.3, 0);
            target.applyVelocity(this, knock);
          }
          return UpdateResult.REMOVE;
        }
      }
    }

    return UpdateResult.CONTINUE;
  }

  @Override
  public Collection<Collider> colliders() {
    return whipPoints.stream().map(p -> (Collider) AABB.of(p.subtract(0.5, 0.5, 0.5), p.add(0.5, 0.5, 0.5))).toList();
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 2500;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 3.5;
    @Modifiable(Attribute.RANGE)
    private double range = 14.0;
    @Modifiable(Attribute.SPEED)
    private double speed = 1.6;
    @Modifiable(Attribute.STRENGTH)
    private double pullStrength = 1.2;
    @Modifiable(Attribute.STRENGTH)
    private double knockbackStrength = 0.9;

    @Override
    public List<String> path() {
      return List.of("abilities", "water", "waterwhip");
    }
  }
}

