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
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.entity.LivingEntity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.potion.PotionEffect;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.BendingEffect;
import me.moros.bending.api.util.entity.EntityUtil;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class VaporBlast extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private Vector3d origin;
  private Vector3d direction;
  private double distance;
  private final Set<UUID> scaldedEntities = new HashSet<>();

  public VaporBlast(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    this.user = user;
    loadConfig();

    this.origin = user.eyeLocation();
    this.direction = user.direction();
    this.distance = 0;

    user.addCooldown(description(), userConfig.cooldown);
    removalPolicy = Policies.defaults();

    Sound.BLOCK_LAVA_EXTINGUISH.asEffect(1.2f, 0.7f).play(user.world(), origin);
    Sound.ENTITY_GENERIC_EXTINGUISH_FIRE.asEffect(1.2f, 0.5f).play(user.world(), origin);
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

    Vector3d center = origin.add(direction.multiply(distance));
    double coneRadius = (distance / userConfig.range) * userConfig.maxRadius + 0.8;

    // Render expanding high-pressure steam cone
    int particles = (int) (coneRadius * 8);
    for (int i = 0; i < particles; i++) {
      double r = ThreadLocalRandom.current().nextDouble(0, coneRadius);
      double theta = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
      Vector3d right = direction.cross(Vector3d.PLUS_J).normalize();
      Vector3d up = direction.cross(right).normalize();
      Vector3d pos = center.add(right.multiply(Math.cos(theta) * r)).add(up.multiply(Math.sin(theta) * r));

      Particle.CLOUD.builder(pos).count(1).offset(0.1).speed(0.02).spawn(user.world());
      if (i % 3 == 0) {
        Particle.CAMPFIRE_COSY_SMOKE.builder(pos).count(1).offset(0.08).speed(0.01).spawn(user.world());
        Particle.SPLASH.builder(pos).count(1).offset(0.05).spawn(user.world());
      }
    }

    for (Entity e : user.world().nearbyEntities(center, coneRadius)) {
      if (e instanceof LivingEntity target && !target.equals(user) && scaldedEntities.add(target.uuid())) {
        target.damage(userConfig.damage, user, description());
        BendingEffect.FIRE_TICK.apply(user, target, userConfig.scaldTicks);
        EntityUtil.tryAddPotion(target, PotionEffect.BLINDNESS, 60, 0);
        Vector3d push = direction.multiply(userConfig.pushStrength).add(0, 0.25, 0);
        target.applyVelocity(this, push);
        Sound.BLOCK_LAVA_EXTINGUISH.asEffect(1.0f, 1.2f).play(user.world(), target.center());
      }
    }

    return UpdateResult.CONTINUE;
  }

  @Override
  public Collection<Collider> colliders() {
    Vector3d center = origin.add(direction.multiply(distance));
    double r = userConfig.maxRadius;
    return List.of(AABB.of(center.subtract(r, r, r), center.add(r, r, r)));
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 7500;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 6.0;
    @Modifiable(Attribute.RANGE)
    private double range = 18.0;
    @Modifiable(Attribute.RADIUS)
    private double maxRadius = 3.5;
    @Modifiable(Attribute.SPEED)
    private double speed = 1.4;
    @Modifiable(Attribute.STRENGTH)
    private double pushStrength = 1.1;
    @Modifiable(Attribute.FIRE_TICKS)
    private int scaldTicks = 60;

    @Override
    public List<String> path() {
      return List.of("abilities", "avatar", "vaporblast");
    }
  }
}

