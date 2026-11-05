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

package me.moros.bending.common.ability.earth.sequence;

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
import me.moros.bending.api.collision.geometry.AABB;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.Direction;
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.block.BlockType;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.temporal.TempBlock;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.material.EarthMaterials;
import me.moros.bending.api.util.material.MaterialUtil;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class SeismicFissure extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private final Set<UUID> affectedEntities = new HashSet<>();
  private final Set<Block> affectedBlocks = new HashSet<>();

  private Vector3d currentPos;
  private Vector3d direction;
  private Block currentGroundBlock;

  private int stepsTaken = 0;
  private long nextStepTime = 0;
  private Collider collider;

  public SeismicFissure(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    if (user.game().abilityManager(user.worldKey()).hasAbility(user, SeismicFissure.class)) {
      return false;
    }

    this.user = user;
    loadConfig();

    Block ground = findGround(user.world().blockAt(user.location()));
    if (ground == null || !EarthMaterials.isEarthNotLava(user, ground)) {
      return false;
    }

    currentGroundBlock = ground;
    currentPos = ground.center().withY(ground.y() + 1);
    direction = user.direction().withY(0).normalize();

    removalPolicy = Policies.defaults();
    user.addCooldown(description(), userConfig.cooldown);
    SoundEffect.EARTH.play(user.world(), currentPos);
    collider = AABB.of(Vector3d.of(-1, 0, -1), Vector3d.of(1, 2, 1)).at(currentPos);
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

    long time = System.currentTimeMillis();
    if (time >= nextStepTime) {
      if (stepsTaken >= userConfig.length) {
        return UpdateResult.REMOVE;
      }
      advanceFissure();
      nextStepTime = time + userConfig.stepInterval;
    }

    return UpdateResult.CONTINUE;
  }

  private void advanceFissure() {
    stepsTaken++;
    currentPos = currentPos.add(direction.multiply(userConfig.stepDistance));

    Block checkBlock = user.world().blockAt(currentPos);
    Block ground = findGround(checkBlock);
    if (ground == null || !EarthMaterials.isEarthNotLava(user, ground)) {
      stepsTaken = userConfig.length;
      return;
    }
    currentGroundBlock = ground;

    // Raise jagged pillar/slab of stone/earth
    Block pillarBase = ground.offset(Direction.UP);
    if (pillarBase.type().isAir() && affectedBlocks.add(pillarBase)) {
      BlockType earthType = MaterialUtil.solidType(ground.type(), BlockType.COBBLESTONE);
      TempBlock.builder(earthType).duration(userConfig.blockDuration).build(pillarBase);

      if (stepsTaken % 3 == 0) {
        Block pillarTop = pillarBase.offset(Direction.UP);
        if (pillarTop.type().isAir() && affectedBlocks.add(pillarTop)) {
          TempBlock.builder(earthType).duration(userConfig.blockDuration).build(pillarTop);
        }
      }
    }

    // Visuals & sound
    Vector3d effectPos = ground.center().add(0, 1.0, 0);
    ground.type().asParticle(effectPos).count(20).offset(0.4).speed(0.12).spawn(user.world());
    Particle.CAMPFIRE_COSY_SMOKE.builder(effectPos).count(2).offset(0.2).speed(0.02).spawn(user.world());
    Particle.DUST_PILLAR.builder(effectPos).count(1).spawn(user.world());

    if (stepsTaken % 2 == 0) {
      SoundEffect.EARTH.play(user.world(), effectPos);
    }

    // Damage & knockback
    collider = AABB.of(Vector3d.of(-1.2, 0, -1.2), Vector3d.of(1.2, 2.5, 1.2)).at(effectPos);
    CollisionUtil.handle(user, collider, entity -> {
      if (affectedEntities.add(entity.uuid())) {
        entity.damage(userConfig.damage, user, description());
        Vector3d fling = direction.multiply(0.4).add(0, userConfig.upwardForce, 0);
        entity.applyVelocity(this, fling);
      }
      return false;
    });
  }

  private Block findGround(Block origin) {
    for (int y = 2; y >= -3; y--) {
      Block b = origin.offset(0, y, 0);
      if (EarthMaterials.isEarthNotLava(user, b)) {
        return b;
      }
    }
    return null;
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(collider);
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 8000;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 4.2;
    @Comment("Length of the fissure in steps")
    private int length = 16;
    @Comment("Distance in blocks per step")
    private double stepDistance = 0.9;
    @Comment("Milliseconds between each step advance")
    private long stepInterval = 65;
    @Comment("Upward velocity applied to entities caught in the fissure")
    private double upwardForce = 0.95;
    @Comment("Duration in milliseconds for raised earthen teeth")
    private long blockDuration = 6000;

    @Override
    public List<String> path() {
      return List.of("abilities", "earth", "sequences", "seismicfissure");
    }
  }
}
