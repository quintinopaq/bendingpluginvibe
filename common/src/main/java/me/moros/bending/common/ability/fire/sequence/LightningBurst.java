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

package me.moros.bending.common.ability.fire.sequence;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityInstance;
import me.moros.bending.api.ability.Activation;
import me.moros.bending.api.collision.CollisionUtil;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.collision.geometry.Ray;
import me.moros.bending.api.collision.geometry.Sphere;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.material.MaterialUtil;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class LightningBurst extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private final Set<UUID> hitEntities = new HashSet<>();
  private Collider collider;
  private boolean executed = false;

  public LightningBurst(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    if (user.game().abilityManager(user.worldKey()).hasAbility(user, LightningBurst.class)) {
      return false;
    }

    this.user = user;
    loadConfig();

    removalPolicy = Policies.defaults();
    user.addCooldown(description(), userConfig.cooldown);
    collider = Ray.of(user.eyeLocation(), user.direction().multiply(userConfig.range));
    return true;
  }

  @Override
  public void loadConfig() {
    userConfig = user.game().configProcessor().calculate(this, Config.class);
  }

  @Override
  public UpdateResult update() {
    if (executed || removalPolicy.test(user, description())) {
      return UpdateResult.REMOVE;
    }

    executed = true;
    discharge();
    return UpdateResult.REMOVE;
  }

  private void discharge() {
    Vector3d origin = user.eyeLocation().add(user.direction().multiply(0.5));
    Vector3d dir = user.direction().normalize();

    Sound.ENTITY_LIGHTNING_BOLT_THUNDER.asEffect(1.2F, 1.4F).play(user.world(), origin);
    SoundEffect.LIGHTNING.play(user.world(), origin);
    Particle.FLASH.builder(origin).count(1).spawn(user.world());

    // Cast zigzag primary bolt
    Vector3d current = origin;
    double dist = 0;
    Entity primaryHit = null;

    while (dist < userConfig.range) {
      double step = 0.7;
      Vector3d next = current.add(dir.multiply(step)).add(
        ThreadLocalRandom.current().nextDouble(-0.25, 0.25),
        ThreadLocalRandom.current().nextDouble(-0.25, 0.25),
        ThreadLocalRandom.current().nextDouble(-0.25, 0.25)
      );

      Block block = user.world().blockAt(next);
      if (!MaterialUtil.isTransparent(block) && !block.type().isLiquid()) {
        break;
      }

      Particle.ELECTRIC_SPARK.builder(next).count(2).offset(0.05).speed(0.02).spawn(user.world());
      ParticleBuilder.rgb(next, "#00E5FF", 1.3).spawn(user.world());

      Collider stepCollider = Sphere.of(next, 0.85);
      final Vector3d hitPos = next;
      Entity[] hitFound = new Entity[1];
      CollisionUtil.handle(user, stepCollider, entity -> {
        if (hitEntities.add(entity.uuid())) {
          hitFound[0] = entity;
          return true;
        }
        return false;
      });

      if (hitFound[0] != null) {
        primaryHit = hitFound[0];
        applyDamage(primaryHit);
        break;
      }

      current = next;
      dist += step;
    }

    // Chain lightning from primary hit to nearby targets
    if (primaryHit != null) {
      chainToNearby(primaryHit.center(), userConfig.chainCount);
    }
  }

  private void chainToNearby(Vector3d start, int remainingChains) {
    if (remainingChains <= 0) {
      return;
    }

    Collider searchArea = Sphere.of(start, userConfig.chainRadius);
    Entity[] nextTarget = new Entity[1];
    CollisionUtil.handle(user, searchArea, entity -> {
      if (hitEntities.add(entity.uuid())) {
        nextTarget[0] = entity;
        return true;
      }
      return false;
    });

    if (nextTarget[0] != null) {
      Vector3d targetPos = nextTarget[0].center();
      drawZigZag(start, targetPos);
      applyDamage(nextTarget[0]);
      SoundEffect.LIGHTNING.play(user.world(), targetPos);
      chainToNearby(targetPos, remainingChains - 1);
    }
  }

  private void drawZigZag(Vector3d from, Vector3d to) {
    int segments = 6;
    Vector3d stepVec = to.subtract(from).multiply(1.0 / segments);
    Vector3d curr = from;
    for (int i = 0; i < segments; i++) {
      Vector3d next = curr.add(stepVec).add(
        ThreadLocalRandom.current().nextDouble(-0.3, 0.3),
        ThreadLocalRandom.current().nextDouble(-0.3, 0.3),
        ThreadLocalRandom.current().nextDouble(-0.3, 0.3)
      );
      Particle.ELECTRIC_SPARK.builder(next).count(3).offset(0.08).speed(0.04).spawn(user.world());
      ParticleBuilder.rgb(next, "#00E5FF", 1.2).spawn(user.world());
      curr = next;
    }
  }

  private void applyDamage(Entity entity) {
    double dmg = userConfig.damage;
    Block block = user.world().blockAt(entity.location());
    if (block.type().isLiquid()) {
      dmg *= 1.4;
      Particle.ELECTRIC_SPARK.builder(entity.location()).count(12).offset(0.4).speed(0.1).spawn(user.world());
    }
    entity.damage(dmg, user, description());
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(collider);
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 8500;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 6.5;
    @Modifiable(Attribute.RANGE)
    private double range = 18.0;
    @Comment("Maximum number of chain jumps to other entities")
    private int chainCount = 3;
    @Comment("Search radius in blocks for chain lightning jumps")
    private double chainRadius = 6.0;

    @Override
    public List<String> path() {
      return List.of("abilities", "fire", "sequences", "lightningburst");
    }
  }
}
