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

package me.moros.bending.common.ability.fire;

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
import me.moros.bending.api.platform.potion.PotionEffect;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.temporal.TempLight;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.BendingEffect;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.functional.SwappedSlotsRemovalPolicy;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class FlameDaggers extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private long startTime;
  private boolean crossSlashActive;
  private Vector3d crossSlashLoc;
  private Vector3d crossSlashDir;
  private double distanceTraveled;
  private final Set<UUID> slashedEntities = new HashSet<>();

  public FlameDaggers(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    this.user = user;
    loadConfig();

    for (FlameDaggers activeDaggers : user.game().abilityManager(user.worldKey()).userInstances(user, FlameDaggers.class).toList()) {
      if (!activeDaggers.crossSlashActive) {
        activeDaggers.launchCrossSlash();
        return false;
      }
    }

    this.startTime = System.currentTimeMillis();
    this.crossSlashActive = false;

    removalPolicy = Policies.builder()
      .add(SwappedSlotsRemovalPolicy.of(description()))
      .add(Policies.UNDER_WATER)
      .build();

    Sound.ITEM_FIRECHARGE_USE.asEffect(1.0f, 1.4f).play(user.world(), user.eyePosition());
    SoundEffect.FIRE_BURST.play(user.world(), user.eyePosition());
    return true;
  }

  @Override
  public void loadConfig() {
    userConfig = user.game().configProcessor().calculate(this, Config.class);
  }

  public void launchCrossSlash() {
    this.crossSlashActive = true;
    this.crossSlashLoc = user.eyePosition().add(user.direction().multiply(1.2));
    this.crossSlashDir = user.direction();
    this.distanceTraveled = 0;
    user.addCooldown(description(), userConfig.cooldown);
    Sound.ENTITY_PLAYER_ATTACK_SWEEP.asEffect(1.2f, 1.3f).play(user.world(), crossSlashLoc);
    Sound.ITEM_FIRECHARGE_USE.asEffect(1.2f, 1.0f).play(user.world(), crossSlashLoc);
  }

  @Override
  public UpdateResult update() {
    if (removalPolicy.test(user, description())) {
      return UpdateResult.REMOVE;
    }

    long now = System.currentTimeMillis();

    if (!crossSlashActive) {
      if (now - startTime > userConfig.duration) {
        user.addCooldown(description(), userConfig.cooldown);
        return UpdateResult.REMOVE;
      }

      // Render glowing flame blades at hands
      Vector3d rightHand = user.mainHandSide();
      Vector3d leftHand = user.offHandSide();

      ParticleBuilder.flame(rightHand).count(2).offset(0.05).speed(0.01).spawn(user.world());
      ParticleBuilder.flame(leftHand).count(2).offset(0.05).speed(0.01).spawn(user.world());
      if (ThreadLocalRandom.current().nextInt(3) == 0) {
        Particle.SOUL_FIRE_FLAME.builder(rightHand).count(1).offset(0.02).spawn(user.world());
        Particle.SOUL_FIRE_FLAME.builder(leftHand).count(1).offset(0.02).spawn(user.world());
      }

      // Proximity melee slash check
      AABB meleeBox = AABB.of(user.eyePosition().add(user.direction().multiply(1.2)), 1.2);
      for (LivingEntity target : user.world().nearbyLivingEntities(meleeBox)) {
        if (!target.equals(user) && slashedEntities.add(target.uuid())) {
          target.damage(userConfig.meleeDamage, user, description());
          BendingEffect.FIRE_TICK.apply(target, userConfig.fireTicks);
          SoundEffect.FIRE_IGNITE.play(user.world(), target.center());
          Particle.LAVA.builder(target.center()).count(3).spawn(user.world());
        }
      }

      return UpdateResult.CONTINUE;
    }

    // Update Cross Slash projectile
    distanceTraveled += userConfig.speed;
    crossSlashLoc = crossSlashLoc.add(crossSlashDir.multiply(userConfig.speed));

    if (distanceTraveled > userConfig.range) {
      return UpdateResult.REMOVE;
    }

    Block block = user.world().blockAt(crossSlashLoc);
    if (block.type().isSolid()) {
      ParticleBuilder.flame(crossSlashLoc).count(10).offset(0.3).speed(0.1).spawn(user.world());
      return UpdateResult.REMOVE;
    }

    TempLight.create(13, 200).build(block);

    // Cross X pattern
    Vector3d right = crossSlashDir.cross(Vector3d.PLUS_J).normalize();
    Vector3d up = crossSlashDir.cross(right).normalize();
    double span = 0.9;
    for (double i = -span; i <= span; i += 0.3) {
      Vector3d p1 = crossSlashLoc.add(right.multiply(i)).add(up.multiply(i));
      Vector3d p2 = crossSlashLoc.add(right.multiply(i)).add(up.multiply(-i));
      ParticleBuilder.flame(p1).count(1).offset(0.02).spawn(user.world());
      ParticleBuilder.flame(p2).count(1).offset(0.02).spawn(user.world());
    }

    AABB hitBox = AABB.of(crossSlashLoc, 1.2);
    for (LivingEntity target : user.world().nearbyLivingEntities(hitBox)) {
      if (!target.equals(user) && slashedEntities.add(target.uuid())) {
        target.damage(userConfig.crossSlashDamage, user, description());
        BendingEffect.FIRE_TICK.apply(target, userConfig.fireTicks * 2);
        target.applyVelocity(this, crossSlashDir.multiply(0.7).add(0, 0.2, 0));
        Sound.ENTITY_PLAYER_ATTACK_CRIT.asEffect(1.0f, 1.1f).play(user.world(), target.center());
        Particle.LAVA.builder(target.center()).count(6).spawn(user.world());
      }
    }

    return UpdateResult.CONTINUE;
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(AABB.of(crossSlashActive ? crossSlashLoc : user.eyePosition(), 1.0));
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 4500;
    @Modifiable(Attribute.DAMAGE)
    private double meleeDamage = 2.5;
    @Modifiable(Attribute.DAMAGE)
    private double crossSlashDamage = 5.0;
    @Modifiable(Attribute.DURATION)
    private long duration = 6000;
    @Modifiable(Attribute.RANGE)
    private double range = 16.0;
    @Modifiable(Attribute.SPEED)
    private double speed = 1.3;
    @Modifiable(Attribute.FIRE_TICKS)
    private int fireTicks = 50;

    @Override
    public List<String> path() {
      return List.of("abilities", "fire", "flamedaggers");
    }
  }
}

