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

package me.moros.bending.common.ability.air;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityInstance;
import me.moros.bending.api.ability.Activation;
import me.moros.bending.api.collision.geometry.AABB;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.entity.LivingEntity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.functional.SwappedSlotsRemovalPolicy;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class CycloneTrap extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private Vector3d location;
  private Vector3d velocity;
  private boolean active;
  private long startTime;
  private double currentRadius;
  private double angle;
  private final Set<UUID> caughtEntities = new HashSet<>();

  public CycloneTrap(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    this.user = user;
    loadConfig();
    this.location = user.eyePosition();
    this.velocity = user.direction().multiply(userConfig.speed);
    this.active = false;
    this.startTime = System.currentTimeMillis();

    removalPolicy = Policies.builder()
      .add(SwappedSlotsRemovalPolicy.of(description()))
      .add(Policies.UNDER_WATER)
      .add(Policies.UNDER_LAVA)
      .build();

    user.addCooldown(description(), userConfig.cooldown);
    SoundEffect.AIR_FAST.play(user.world(), location);
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

    long now = System.currentTimeMillis();

    if (!active) {
      location = location.add(velocity);
      ParticleBuilder.air(location).count(3).offset(0.15).spawn(user.world());

      if (user.world().blockAt(location).type().isSolid() || location.distanceSq(user.eyePosition()) >= userConfig.range * userConfig.range) {
        active = true;
        startTime = now;
        Sound.ENTITY_BREEZE_WIND_BURST.asEffect(1.0f, 1.2f).play(user.world(), location);
      } else {
        AABB aabb = AABB.of(location, 1.0);
        for (LivingEntity target : user.world().nearbyLivingEntities(aabb)) {
          if (!target.equals(user)) {
            active = true;
            startTime = now;
            Sound.ENTITY_BREEZE_WIND_BURST.asEffect(1.0f, 1.2f).play(user.world(), location);
            break;
          }
        }
      }
      return UpdateResult.CONTINUE;
    }

    if (now - startTime > userConfig.duration) {
      detonate();
      return UpdateResult.REMOVE;
    }

    angle += Math.PI / 6.0;
    currentRadius = Math.min(userConfig.radius, currentRadius + 0.05);

    // Render swirling funnel
    for (int y = 0; y < userConfig.height; y++) {
      double r = currentRadius * (1.0 + (double) y / userConfig.height * 0.5);
      double theta = angle + (y * Math.PI / 4.0);
      Vector3d point = location.add(Math.cos(theta) * r, y * 0.6, Math.sin(theta) * r);
      ParticleBuilder.air(point).count(2).offset(0.1).spawn(user.world());
      if (ThreadLocalRandom.current().nextInt(4) == 0) {
        Particle.CLOUD.builder(point).count(1).offset(0.05).speed(0.02).spawn(user.world());
      }
    }

    if (ThreadLocalRandom.current().nextInt(5) == 0) {
      SoundEffect.AIR_FAST.play(user.world(), location);
    }

    AABB vortexBox = AABB.of(location.add(0, userConfig.height * 0.3, 0), currentRadius + 1.0);
    for (LivingEntity entity : user.world().nearbyLivingEntities(vortexBox)) {
      if (entity.equals(user)) {
        continue;
      }
      Vector3d toCenter = location.subtract(entity.center());
      Vector3d pull = toCenter.multiply(0.2).add(0, 0.25, 0);
      entity.applyVelocity(this, pull);
      entity.damage(userConfig.damagePerSecond * 0.05, user, description());
      caughtEntities.add(entity.uuid());
    }

    return UpdateResult.CONTINUE;
  }

  private void detonate() {
    Sound.ENTITY_PHANTOM_FLAP.asEffect(1.2f, 0.8f).play(user.world(), location);
    Particle.EXPLOSION.builder(location.add(0, 1.0, 0)).count(2).spawn(user.world());
    for (int i = 0; i < 20; i++) {
      double a = i * (Math.PI / 10.0);
      Vector3d dir = Vector3d.of(Math.cos(a), 0.3, Math.sin(a));
      ParticleBuilder.air(location.add(0, 1.0, 0)).count(3).offset(0.2).speed(0.3).spawn(user.world());
    }

    AABB burstBox = AABB.of(location.add(0, 1.0, 0), currentRadius + 1.5);
    for (LivingEntity target : user.world().nearbyLivingEntities(burstBox)) {
      if (!target.equals(user)) {
        Vector3d fling = target.center().subtract(location).normalize().multiply(1.2).add(0, 0.4, 0);
        target.applyVelocity(this, fling);
        target.damage(userConfig.detonationDamage, user, description());
      }
    }
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(AABB.of(location, active ? currentRadius : 0.8));
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 6000;
    @Modifiable(Attribute.DAMAGE)
    private double damagePerSecond = 1.5;
    @Modifiable(Attribute.DAMAGE)
    private double detonationDamage = 3.0;
    @Modifiable(Attribute.DURATION)
    private long duration = 3500;
    @Modifiable(Attribute.RANGE)
    private double range = 18.0;
    @Modifiable(Attribute.RADIUS)
    private double radius = 2.8;
    @Modifiable(Attribute.SPEED)
    private double speed = 1.0;
    @Comment("Funnel vertical height in layers")
    private int height = 5;

    @Override
    public List<String> path() {
      return List.of("abilities", "air", "cyclonetrap");
    }
  }
}

