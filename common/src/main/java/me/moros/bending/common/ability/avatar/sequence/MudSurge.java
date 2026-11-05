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
import me.moros.bending.api.platform.potion.PotionEffect;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.temporal.TempBlock;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class MudSurge extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private Vector3d location;
  private Vector3d direction;
  private double distance;
  private final Set<UUID> hitEntities = new HashSet<>();

  public MudSurge(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    this.user = user;
    loadConfig();

    this.location = user.eyePosition().subtract(0, 1.0, 0);
    this.direction = user.direction().multiply(1, 0, 1).normalize();
    this.distance = 0;

    user.addCooldown(description(), userConfig.cooldown);
    removalPolicy = Policies.defaults();

    Sound.BLOCK_SLIME_BLOCK_BREAK.asEffect(1.2f, 0.6f).play(user.world(), location);
    SoundEffect.WATER_SPLASH.play(user.world(), location);
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
          location = down.location().toVector3d().add(0.5, 1.0, 0.5);
          break;
        }
      }
    } else {
      for (int i = 0; i < 4; i++) {
        Block up = user.world().blockAt(location.add(0, i, 0));
        if (!up.type().isSolid()) {
          location = up.location().toVector3d().add(0.5, 0.0, 0.5);
          break;
        }
      }
    }

    // Mud wave crest particles
    Vector3d left = direction.cross(Vector3d.PLUS_J).normalize();
    for (double i = -userConfig.waveWidth; i <= userConfig.waveWidth; i += 0.5) {
      Vector3d pt = location.add(left.multiply(i));
      BlockType.SOUL_SOIL.asParticle(pt).count(4).offset(0.2).speed(0.05).spawn(user.world());
      Particle.SPLASH.builder(pt.add(0, 0.3, 0)).count(2).offset(0.15).spawn(user.world());

      Block surface = user.world().blockAt(pt.subtract(0, 0.5, 0));
      if (surface.type().isSolid()) {
        TempBlock.block(BlockType.MUD).duration(userConfig.mudDuration).build(surface);
      }
    }

    if (ThreadLocalRandom.current().nextInt(3) == 0) {
      Sound.BLOCK_SLIME_BLOCK_STEP.asEffect(1.0f, 0.7f).play(user.world(), location);
    }

    AABB waveBox = AABB.of(location, userConfig.waveWidth);
    for (LivingEntity target : user.world().nearbyLivingEntities(waveBox)) {
      if (!target.equals(user) && hitEntities.add(target.uuid())) {
        target.damage(userConfig.damage, user, description());
        target.addPotionEffect(PotionEffect.SLOWNESS, userConfig.slownessDuration, 3);
        Vector3d push = direction.multiply(userConfig.pushStrength).add(0, 0.15, 0);
        target.applyVelocity(this, push);
        Sound.BLOCK_SLIME_BLOCK_BREAK.asEffect(1.0f, 0.8f).play(user.world(), target.center());
      }
    }

    return UpdateResult.CONTINUE;
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(AABB.of(location, userConfig.waveWidth));
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 8000;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 5.5;
    @Modifiable(Attribute.RANGE)
    private double range = 18.0;
    @Modifiable(Attribute.SPEED)
    private double speed = 0.95;
    @Modifiable(Attribute.RADIUS)
    private double waveWidth = 2.4;
    @Modifiable(Attribute.KNOCKBACK)
    private double pushStrength = 0.85;
    @Modifiable(Attribute.DURATION)
    private long mudDuration = 5000;
    private int slownessDuration = 100;

    @Override
    public List<String> path() {
      return List.of("abilities", "avatar", "mudsurge");
    }
  }
}

