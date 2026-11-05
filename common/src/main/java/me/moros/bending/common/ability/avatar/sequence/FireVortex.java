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

package me.moros.bending.common.ability.avatar.sequence;

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
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.entity.LivingEntity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.temporal.TempLight;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.BendingEffect;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class FireVortex extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private Vector3d location;
  private Vector3d direction;
  private long startTime;
  private double angle;
  private final Set<UUID> caughtEntities = new HashSet<>();

  public FireVortex(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    this.user = user;
    loadConfig();

    this.location = user.eyePosition().add(user.direction().multiply(2.0));
    this.direction = user.direction().multiply(1, 0, 1).normalize();
    this.startTime = System.currentTimeMillis();

    user.addCooldown(description(), userConfig.cooldown);
    removalPolicy = Policies.defaults();

    Sound.ITEM_FIRECHARGE_USE.asEffect(1.2f, 0.8f).play(user.world(), location);
    SoundEffect.AIR_BURST.play(user.world(), location);
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

    long elapsed = System.currentTimeMillis() - startTime;
    if (elapsed > userConfig.duration) {
      detonate();
      return UpdateResult.REMOVE;
    }

    // Slowly advance
    location = location.add(direction.multiply(userConfig.speed));

    Block block = user.world().blockAt(location);
    TempLight.create(14, 250).build(block);

    angle += Math.PI / 4.0;

    // Render multi-layered fiery vortex
    for (int y = 0; y < userConfig.height; y++) {
      double r = userConfig.radius * (0.6 + ((double) y / userConfig.height) * 0.8);
      double theta1 = angle + (y * Math.PI / 3.0);
      double theta2 = theta1 + Math.PI;

      Vector3d flame1 = location.add(Math.cos(theta1) * r, y * 0.7, Math.sin(theta1) * r);
      Vector3d flame2 = location.add(Math.cos(theta2) * r, y * 0.7, Math.sin(theta2) * r);

      ParticleBuilder.flame(flame1).count(2).offset(0.1).spawn(user.world());
      ParticleBuilder.air(flame2).count(2).offset(0.1).spawn(user.world());

      if (ThreadLocalRandom.current().nextInt(3) == 0) {
        Particle.SOUL_FIRE_FLAME.builder(flame1).count(1).offset(0.05).spawn(user.world());
        Particle.LAVA.builder(flame2).count(1).spawn(user.world());
      }
    }

    if (ThreadLocalRandom.current().nextInt(4) == 0) {
      SoundEffect.FIRE_BURST.play(user.world(), location);
    }

    // Pull entities in and launch up while burning
    AABB suctionBox = AABB.of(location.add(0, userConfig.height * 0.35, 0), userConfig.radius + 1.5);
    for (LivingEntity target : user.world().nearbyLivingEntities(suctionBox)) {
      if (!target.equals(user)) {
        Vector3d toCenter = location.subtract(target.center());
        Vector3d pull = toCenter.multiply(0.25).add(0, 0.35, 0);
        target.applyVelocity(this, pull);
        target.damage(userConfig.damagePerSecond * 0.05, user, description());
        BendingEffect.FIRE_TICK.apply(target, 40);
        caughtEntities.add(target.uuid());
      }
    }

    return UpdateResult.CONTINUE;
  }

  private void detonate() {
    Sound.ENTITY_GENERIC_EXPLODE.asEffect(1.2f, 0.9f).play(user.world(), location);
    Particle.EXPLOSION.builder(location.add(0, 2.0, 0)).count(3).spawn(user.world());
    ParticleBuilder.flame(location.add(0, 2.0, 0)).count(40).offset(1.2).speed(0.2).spawn(user.world());

    AABB explodeBox = AABB.of(location.add(0, 2.0, 0), userConfig.radius + 2.0);
    for (LivingEntity target : user.world().nearbyLivingEntities(explodeBox)) {
      if (!target.equals(user)) {
        target.damage(userConfig.detonationDamage, user, description());
        BendingEffect.FIRE_TICK.apply(target, 80);
        Vector3d fling = target.center().subtract(location).normalize().multiply(1.3).add(0, 0.5, 0);
        target.applyVelocity(this, fling);
      }
    }
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(AABB.of(location.add(0, userConfig.height * 0.35, 0), userConfig.radius));
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 8000;
    @Modifiable(Attribute.DAMAGE)
    private double damagePerSecond = 3.0;
    @Modifiable(Attribute.DAMAGE)
    private double detonationDamage = 6.5;
    @Modifiable(Attribute.DURATION)
    private long duration = 4000;
    @Modifiable(Attribute.RADIUS)
    private double radius = 3.2;
    @Modifiable(Attribute.SPEED)
    private double speed = 0.25;
    private int height = 6;

    @Override
    public List<String> path() {
      return List.of("abilities", "avatar", "firevortex");
    }
  }
}

