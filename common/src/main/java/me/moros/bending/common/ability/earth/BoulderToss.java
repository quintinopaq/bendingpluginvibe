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

package me.moros.bending.common.ability.earth;

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
import me.moros.bending.api.platform.block.BlockType;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.entity.LivingEntity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.functional.SwappedSlotsRemovalPolicy;
import me.moros.bending.api.util.material.MaterialUtil;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class BoulderToss extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private boolean thrown;
  private Vector3d location;
  private Vector3d velocity;
  private final Set<UUID> hitEntities = new HashSet<>();

  public BoulderToss(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    this.user = user;
    loadConfig();

    for (BoulderToss toss : user.game().abilityManager(user.worldKey()).userInstances(user, BoulderToss.class).toList()) {
      if (!toss.thrown) {
        toss.launch();
        return false;
      }
    }

    Vector3d eye = user.eyePosition();
    Vector3d groundCheck = eye.subtract(0, 1.5, 0);
    Block block = user.world().blockAt(groundCheck);
    if (!MaterialUtil.isEarth(block)) {
      Block under = user.world().blockAt(groundCheck.subtract(0, 1.0, 0));
      if (!MaterialUtil.isEarth(under)) {
        return false;
      }
    }

    this.thrown = false;
    this.location = eye.add(user.direction().multiply(1.5));

    removalPolicy = Policies.builder()
      .add(SwappedSlotsRemovalPolicy.of(description()))
      .add(Policies.UNDER_LAVA)
      .build();

    Sound.BLOCK_STONE_BREAK.asEffect(1.0f, 0.8f).play(user.world(), location);
    SoundEffect.EARTH_DIG.play(user.world(), location);
    BlockType.STONE.asParticle(location).count(15).offset(0.4).spawn(user.world());

    if (method == Activation.ATTACK) {
      launch();
    }
    return true;
  }

  @Override
  public void loadConfig() {
    userConfig = user.game().configProcessor().calculate(this, Config.class);
  }

  public void launch() {
    this.thrown = true;
    this.location = user.eyePosition().add(user.direction().multiply(1.2));
    this.velocity = user.direction().multiply(userConfig.speed);
    user.addCooldown(description(), userConfig.cooldown);
    Sound.ENTITY_ARROW_SHOOT.asEffect(1.2f, 0.6f).play(user.world(), location);
    Sound.BLOCK_STONE_PLACE.asEffect(1.2f, 0.9f).play(user.world(), location);
  }

  @Override
  public UpdateResult update() {
    if (removalPolicy.test(user, description())) {
      return UpdateResult.REMOVE;
    }

    if (!thrown) {
      if (!user.sneaking()) {
        launch();
        return UpdateResult.CONTINUE;
      }
      location = user.eyePosition().add(user.direction().multiply(1.8));
      BlockType.COBBLESTONE.asParticle(location).count(6).offset(0.3).spawn(user.world());
      BlockType.STONE.asParticle(location).count(4).offset(0.2).spawn(user.world());
      return UpdateResult.CONTINUE;
    }

    // Physics arc: slight gravity
    velocity = velocity.subtract(0, 0.035, 0);
    location = location.add(velocity);

    BlockType.STONE.asParticle(location).count(8).offset(0.35).spawn(user.world());
    BlockType.COBBLESTONE.asParticle(location).count(5).offset(0.25).spawn(user.world());

    if (location.distanceSq(user.eyePosition()) > userConfig.range * userConfig.range) {
      explode();
      return UpdateResult.REMOVE;
    }

    Block block = user.world().blockAt(location);
    if (block.type().isSolid()) {
      explode();
      return UpdateResult.REMOVE;
    }

    AABB hitBox = AABB.of(location, userConfig.hitboxRadius);
    for (LivingEntity target : user.world().nearbyLivingEntities(hitBox)) {
      if (!target.equals(user) && hitEntities.add(target.uuid())) {
        explode();
        return UpdateResult.REMOVE;
      }
    }

    return UpdateResult.CONTINUE;
  }

  private void explode() {
    Sound.BLOCK_STONE_BREAK.asEffect(1.5f, 0.7f).play(user.world(), location);
    SoundEffect.EARTH_CRACK.play(user.world(), location);
    Particle.EXPLOSION.builder(location).count(1).spawn(user.world());
    BlockType.STONE.asParticle(location).count(30).offset(0.6).speed(0.15).spawn(user.world());
    BlockType.COBBLESTONE.asParticle(location).count(20).offset(0.5).speed(0.12).spawn(user.world());

    AABB aoeBox = AABB.of(location, userConfig.aoeRadius);
    for (LivingEntity target : user.world().nearbyLivingEntities(aoeBox)) {
      if (!target.equals(user)) {
        double dist = target.center().distance(location);
        double damage = Math.max(1.0, userConfig.damage * (1.0 - (dist / (userConfig.aoeRadius + 1.0))));
        target.damage(damage, user, description());
        Vector3d fling = target.center().subtract(location).normalize().multiply(userConfig.knockback).add(0, 0.35, 0);
        target.applyVelocity(this, fling);
      }
    }
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(AABB.of(location, userConfig.hitboxRadius));
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 4500;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 5.0;
    @Modifiable(Attribute.RANGE)
    private double range = 24.0;
    @Modifiable(Attribute.SPEED)
    private double speed = 1.35;
    @Modifiable(Attribute.KNOCKBACK)
    private double knockback = 0.85;
    @Modifiable(Attribute.RADIUS)
    private double aoeRadius = 3.2;
    private double hitboxRadius = 0.9;

    @Override
    public List<String> path() {
      return List.of("abilities", "earth", "bouldertoss");
    }
  }
}

