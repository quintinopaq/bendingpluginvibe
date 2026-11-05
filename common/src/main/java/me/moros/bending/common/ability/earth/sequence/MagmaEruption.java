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
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.temporal.TempBlock;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.BendingEffect;
import me.moros.bending.api.util.functional.ExpireRemovalPolicy;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.material.EarthMaterials;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class MagmaEruption extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private final Set<UUID> damagedEntities = new HashSet<>();
  private final Set<Block> lavaBlocks = new HashSet<>();

  private Block baseGround;
  private Vector3d center;
  private Collider collider;
  private int currentHeight = 0;
  private long nextEruptTime = 0;

  public MagmaEruption(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    if (user.game().abilityManager(user.worldKey()).hasAbility(user, MagmaEruption.class)) {
      return false;
    }

    this.user = user;
    loadConfig();

    Block target = user.rayTrace(userConfig.range).ignoreLiquids(false).blocks(user.world()).block();
    if (target == null) {
      return false;
    }

    baseGround = findGround(target);
    if (baseGround == null || !EarthMaterials.isEarthbendable(user, baseGround)) {
      return false;
    }

    center = baseGround.center();
    collider = AABB.of(
      Vector3d.of(-userConfig.radius, 0, -userConfig.radius),
      Vector3d.of(userConfig.radius, userConfig.height, userConfig.radius)
    ).at(center);

    removalPolicy = Policies.builder()
      .add(ExpireRemovalPolicy.of(userConfig.duration))
      .build();

    user.addCooldown(description(), userConfig.cooldown);
    SoundEffect.LAVA.play(user.world(), center);
    SoundEffect.COMBUSTION.play(user.world(), center);
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
    if (currentHeight < userConfig.height && time >= nextEruptTime) {
      currentHeight++;
      nextEruptTime = time + 100;

      Block eruptBlock = baseGround.offset(Direction.UP, currentHeight);
      if (eruptBlock.type().isAir() && lavaBlocks.add(eruptBlock)) {
        TempBlock.builder(BlockType.LAVA).duration(userConfig.lavaDuration).build(eruptBlock);
      }
    }

    renderEruption();

    CollisionUtil.handle(user, collider, entity -> {
      if (damagedEntities.add(entity.uuid())) {
        entity.damage(userConfig.damage, user, description());
        BendingEffect.FIRE_TICK.apply(user, entity, userConfig.fireTicks);

        Vector3d launch = Vector3d.of(
          ThreadLocalRandom.current().nextDouble(-0.3, 0.3),
          userConfig.launchVelocity,
          ThreadLocalRandom.current().nextDouble(-0.3, 0.3)
        );
        entity.applyVelocity(this, launch);
      }
      return false;
    });

    return UpdateResult.CONTINUE;
  }

  private void renderEruption() {
    double h = Math.max(1, currentHeight);
    for (int i = 0; i < 5; i++) {
      double yOffset = ThreadLocalRandom.current().nextDouble(0, h + 1.2);
      double r = ThreadLocalRandom.current().nextDouble(0, userConfig.radius);
      double theta = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);

      Vector3d p = center.add(Math.cos(theta) * r, yOffset, Math.sin(theta) * r);
      Particle.LAVA.builder(p).count(1).spawn(user.world());
      Particle.FLAME.builder(p).count(2).offset(0.15).speed(0.04).spawn(user.world());
      Particle.LARGE_SMOKE.builder(p).count(1).offset(0.1).speed(0.02).spawn(user.world());
    }

    if (ThreadLocalRandom.current().nextInt(5) == 0) {
      SoundEffect.LAVA.play(user.world(), center.add(0, h * 0.5, 0));
    }
  }

  private Block findGround(Block origin) {
    for (int y = 2; y >= -3; y--) {
      Block b = origin.offset(0, y, 0);
      if (EarthMaterials.isEarthbendable(user, b)) {
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
    private long cooldown = 9000;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 6.0;
    @Modifiable(Attribute.RANGE)
    private double range = 16.0;
    @Modifiable(Attribute.RADIUS)
    private double radius = 2.2;
    @Comment("Maximum height in blocks of the erupting magma column")
    private int height = 4;
    @Modifiable(Attribute.FIRE_TICKS)
    private int fireTicks = 80;
    @Comment("Upward velocity applied to targets caught inside the geyser")
    @Modifiable(Attribute.SPEED)
    private double launchVelocity = 1.1;
    @Comment("Duration in milliseconds for the magma geyser active lifecycle")
    @Modifiable(Attribute.DURATION)
    private long duration = 3000;
    @Comment("Duration in milliseconds for the temporary lava blocks")
    private long lavaDuration = 4000;

    @Override
    public List<String> path() {
      return List.of("abilities", "earth", "sequences", "magmaeruption");
    }
  }
}
