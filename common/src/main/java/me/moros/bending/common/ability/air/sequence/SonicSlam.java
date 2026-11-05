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

package me.moros.bending.common.ability.air.sequence;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityInstance;
import me.moros.bending.api.ability.Activation;
import me.moros.bending.api.collision.CollisionUtil;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.collision.geometry.Sphere;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.Direction;
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.entity.EntityProperties;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.platform.world.WorldUtil;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.functional.ExpireRemovalPolicy;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.material.MaterialUtil;
import me.moros.math.FastMath;
import me.moros.math.Vector3d;
import me.moros.math.VectorUtil;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class SonicSlam extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private final Set<UUID> affectedEntities = new HashSet<>();
  private Collider collider;
  private Vector3d slamCenter;
  private boolean detonated = false;
  private int flightTicks = 0;

  public SonicSlam(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    if (user.game().abilityManager(user.worldKey()).hasAbility(user, SonicSlam.class)) {
      return false;
    }

    this.user = user;
    loadConfig();

    Vector3d leapDirection = user.direction().normalize().multiply(userConfig.leapVelocity);
    if (method == Activation.FALL) {
      double fallDist = user.propertyValue(EntityProperties.FALL_DISTANCE);
      if (fallDist < 2.0) {
        return false;
      }
      leapDirection = user.direction().withY(-1.2).normalize().multiply(userConfig.leapVelocity * 1.3);
    } else {
      leapDirection = leapDirection.add(0, 0.4, 0);
    }

    user.applyVelocity(this, leapDirection);
    removalPolicy = Policies.builder()
      .add(ExpireRemovalPolicy.of(userConfig.maxFlightTime))
      .build();

    user.addCooldown(description(), userConfig.cooldown);
    SoundEffect.AIR_FAST.play(user.world(), user.location());
    collider = Sphere.of(user.location(), 1.5);
    return true;
  }

  @Override
  public void loadConfig() {
    userConfig = user.game().configProcessor().calculate(this, Config.class);
  }

  @Override
  public UpdateResult update() {
    if (detonated) {
      return UpdateResult.REMOVE;
    }

    flightTicks++;
    Vector3d currentLoc = user.location();
    collider = Sphere.of(currentLoc, 1.5);

    // Trail particles
    ParticleBuilder.air(currentLoc.add(0, 0.6, 0)).spawn(user.world());
    Particle.CLOUD.builder(currentLoc.add(0, 0.3, 0)).count(2).offset(0.2).speed(0.02).spawn(user.world());

    Block blockBelow = user.world().blockAt(currentLoc.subtract(0, 0.2, 0));
    boolean hitGround = !MaterialUtil.isTransparent(blockBelow) && flightTicks > 2;

    if (hitGround || removalPolicy.test(user, description())) {
      slamCenter = currentLoc;
      executeSlam();
      return UpdateResult.REMOVE;
    }

    return UpdateResult.CONTINUE;
  }

  private void executeSlam() {
    detonated = true;
    Sound.ENTITY_BREEZE_WIND_BURST.asEffect(1.6F, 0.75F).play(user.world(), slamCenter);
    SoundEffect.AIR_FAST.play(user.world(), slamCenter);

    // Concentric shockwave visual
    double maxRadius = userConfig.radius;
    for (int ring = 1; ring <= 4; ring++) {
      double r = (maxRadius / 4.0) * ring;
      boolean isOuter = (ring == 4);
      VectorUtil.circle(Vector3d.of(r, 0, 0), Vector3d.PLUS_J, 28).forEach(v -> {
        Vector3d p = slamCenter.add(v).add(0, 0.15, 0);
        ParticleBuilder.air(p).spawn(user.world());
        if (isOuter) {
          Particle.POOF.builder(p).count(1).offset(0.1).speed(0.05).spawn(user.world());
        }
      });
    }
    Particle.GUST_EMITTER_LARGE.builder(slamCenter.add(0, 0.5, 0)).count(1).spawn(user.world());

    // Affect entities
    Collider shockCollider = Sphere.of(slamCenter, maxRadius);
    CollisionUtil.handle(user, shockCollider, entity -> {
      if (affectedEntities.add(entity.uuid())) {
        Vector3d delta = entity.center().subtract(slamCenter);
        double dist = Math.max(1.0, delta.length());
        double force = Math.max(0.6, (1.0 - (dist / maxRadius)) * userConfig.knockback);

        Vector3d knockbackDir = delta.normalize().multiply(force).add(0, 0.55 * force, 0);
        entity.applyVelocity(this, knockbackDir);
        entity.damage(userConfig.damage * (1.2 - 0.4 * (dist / maxRadius)), user, description());
      }
      return false;
    });

    // Clear nearby plants/fire
    Block centerBlock = user.world().blockAt(slamCenter);
    for (int x = -3; x <= 3; x++) {
      for (int z = -3; z <= 3; z++) {
        Block target = centerBlock.offset(x, 0, z);
        WorldUtil.tryExtinguishFire(user, target);
        WorldUtil.tryBreakPlant(target);
      }
    }
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(collider);
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 7000;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 4.5;
    @Comment("Knockback strength")
    private double knockback = 2.2;
    @Modifiable(Attribute.RADIUS)
    private double radius = 6.0;
    @Comment("Velocity applied during leap")
    @Modifiable(Attribute.SPEED)
    private double leapVelocity = 1.35;
    @Comment("Maximum time in milliseconds allowed in air before forced slam")
    private long maxFlightTime = 1600;

    @Override
    public List<String> path() {
      return List.of("abilities", "air", "sequences", "sonicslam");
    }
  }
}
