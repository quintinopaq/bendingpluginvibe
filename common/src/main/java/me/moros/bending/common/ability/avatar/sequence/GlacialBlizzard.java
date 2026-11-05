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
import me.moros.bending.api.platform.block.BlockType;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.entity.LivingEntity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.temporal.TempBlock;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.BendingEffect;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class GlacialBlizzard extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private Vector3d center;
  private double currentRadius;
  private final Set<UUID> frozenEntities = new HashSet<>();

  public GlacialBlizzard(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    this.user = user;
    loadConfig();

    this.center = user.center();
    this.currentRadius = 1.0;

    user.addCooldown(description(), userConfig.cooldown);
    removalPolicy = Policies.defaults();

    Sound.BLOCK_GLASS_BREAK.asEffect(1.2f, 0.6f).play(user.world(), center);
    SoundEffect.ICE.play(user.world(), center);
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

    currentRadius += userConfig.expansionSpeed;
    if (currentRadius > userConfig.maxRadius) {
      return UpdateResult.REMOVE;
    }

    // Expanding blizzard ring
    int points = (int) (currentRadius * 12);
    for (int i = 0; i < points; i++) {
      double angle = (Math.PI * 2 * i) / points;
      Vector3d pos = center.add(Math.cos(angle) * currentRadius, 0.5, Math.sin(angle) * currentRadius);

      Particle.ITEM_SNOWBALL.builder(pos).count(2).offset(0.15).speed(0.04).spawn(user.world());
      BlockType.PACKED_ICE.asParticle(pos).count(1).offset(0.1).spawn(user.world());
      if (i % 4 == 0) {
        ParticleBuilder.air(pos).count(1).offset(0.1).spawn(user.world());
      }

      // Freeze liquid / surface
      Block floor = user.world().blockAt(pos.subtract(0, 1.0, 0));
      if (floor.type().isLiquid()) {
        TempBlock.ice().duration(userConfig.iceDuration).build(floor);
      }
    }

    if (ThreadLocalRandom.current().nextInt(3) == 0) {
      Sound.BLOCK_SNOW_STEP.asEffect(1.0f, 0.8f).play(user.world(), center);
    }

    for (Entity e : user.world().nearbyEntities(center, currentRadius)) {
      if (e instanceof LivingEntity target && !target.equals(user) && frozenEntities.add(target.uuid())) {
        target.damage(userConfig.damage, user, description());
        BendingEffect.FROST_TICK.apply(user, target, userConfig.frostTicks);
        Vector3d push = target.center().subtract(center).normalize().multiply(userConfig.knockback).add(0, 0.3, 0);
        target.applyVelocity(this, push);

        // Encapsulate target's feet in ice
        Block targetFloor = user.world().blockAt(target.center());
        if (!targetFloor.type().isSolid()) {
          TempBlock.ice().duration(userConfig.iceDuration).build(targetFloor);
        }
        SoundEffect.ICE.play(user.world(), target.center());
      }
    }

    return UpdateResult.CONTINUE;
  }

  @Override
  public Collection<Collider> colliders() {
    double r = currentRadius;
    return List.of(AABB.of(center.subtract(r, r, r), center.add(r, r, r)));
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 9000;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 6.0;
    @Modifiable(Attribute.RADIUS)
    private double maxRadius = 9.0;
    @Modifiable(Attribute.SPEED)
    private double expansionSpeed = 0.65;
    @Modifiable(Attribute.STRENGTH)
    private double knockback = 0.9;
    @Modifiable(Attribute.DURATION)
    private long iceDuration = 4500;
    private int frostTicks = 120;

    @Override
    public List<String> path() {
      return List.of("abilities", "avatar", "glacialblizzard");
    }
  }
}

