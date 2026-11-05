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
import me.moros.bending.api.platform.block.BlockType;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.entity.LivingEntity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.temporal.TempBlock;
import me.moros.bending.api.temporal.TempLight;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.BendingEffect;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class VolcanicFissure extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private Vector3d location;
  private Vector3d direction;
  private double distance;
  private final Set<UUID> hitEntities = new HashSet<>();
  private final List<Vector3d> fissurePoints = new ArrayList<>();

  public VolcanicFissure(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    this.user = user;
    loadConfig();

    this.location = user.eyeLocation().subtract(0, 1.2, 0);
    this.direction = user.direction().multiply(1, 0, 1).normalize();
    this.distance = 0;

    user.addCooldown(description(), userConfig.cooldown);
    removalPolicy = Policies.defaults();

    Sound.BLOCK_STONE_BREAK.asEffect(1.2f, 0.6f).play(user.world(), location);
    SoundEffect.EARTH.play(user.world(), location);
    SoundEffect.FIRE.play(user.world(), location);
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

    distance += userConfig.speed;
    if (distance > userConfig.range) {
      return UpdateResult.REMOVE;
    }

    location = location.add(direction.multiply(userConfig.speed));

    // Ground tracking
    Block ground = user.world().blockAt(location);
    if (!ground.type().isSolid()) {
      for (int i = 0; i < 4; i++) {
        Block down = user.world().blockAt(location.subtract(0, i, 0));
        if (down.type().isSolid()) {
          location = down.toVector3d().add(0.5, 1.0, 0.5);
          break;
        }
      }
    } else {
      for (int i = 0; i < 4; i++) {
        Block up = user.world().blockAt(location.add(0, i, 0));
        if (!up.type().isSolid()) {
          location = up.toVector3d().add(0.5, 0.0, 0.5);
          break;
        }
      }
    }

    fissurePoints.add(location);

    Block blockUnder = user.world().blockAt(location.subtract(0, 0.5, 0));
    if (blockUnder.type().isSolid()) {
      TempBlock.builder(BlockType.MAGMA_BLOCK).duration(userConfig.lavaDuration).build(blockUnder);
    }

    Block airBlock = user.world().blockAt(location);
    TempLight.builder(15).duration(300).build(airBlock);

    // Volcanic eruption effects
    Particle.LAVA.builder(location).count(6).offset(0.3).spawn(user.world());
    ParticleBuilder.fire(user, location).count(8).offset(0.4).speed(0.1).spawn(user.world());
    BlockType.BASALT.asParticle(location).count(5).offset(0.3).speed(0.12).spawn(user.world());

    if (ThreadLocalRandom.current().nextInt(2) == 0) {
      Sound.BLOCK_LAVA_POP.asEffect(1.2f, 0.8f).play(user.world(), location);
    }

    for (Entity e : user.world().nearbyEntities(location, 1.8)) {
      if (e instanceof LivingEntity target && !target.equals(user) && hitEntities.add(target.uuid())) {
        target.damage(userConfig.damage, user, description());
        BendingEffect.FIRE_TICK.apply(user, target, userConfig.fireTicks);
        Vector3d geyserLaunch = Vector3d.of(0, userConfig.upwardLaunch, 0);
        target.applyVelocity(this, geyserLaunch);
        Sound.ENTITY_GENERIC_EXPLODE.asEffect(1.0f, 1.2f).play(user.world(), target.center());
      }
    }

    return UpdateResult.CONTINUE;
  }

  @Override
  public Collection<Collider> colliders() {
    return fissurePoints.stream().map(p -> (Collider) AABB.of(p.subtract(1.5, 1.5, 1.5), p.add(1.5, 1.5, 1.5))).toList();
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 8500;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 6.5;
    @Modifiable(Attribute.RANGE)
    private double range = 18.0;
    @Modifiable(Attribute.SPEED)
    private double speed = 0.95;
    @Modifiable(Attribute.STRENGTH)
    private double upwardLaunch = 0.85;
    @Modifiable(Attribute.DURATION)
    private long lavaDuration = 4500;
    @Modifiable(Attribute.FIRE_TICKS)
    private int fireTicks = 100;

    @Override
    public List<String> path() {
      return List.of("abilities", "avatar", "volcanicfissure");
    }
  }
}

