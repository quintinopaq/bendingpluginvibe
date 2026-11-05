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
import java.util.concurrent.ThreadLocalRandom;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityInstance;
import me.moros.bending.api.ability.Activation;
import me.moros.bending.api.collision.CollisionUtil;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.collision.geometry.Sphere;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.particle.ParticleBuilder;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.platform.world.WorldUtil;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.ExpiringSet;
import me.moros.bending.api.util.functional.OutOfRangeRemovalPolicy;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.bending.api.util.material.MaterialUtil;
import me.moros.math.FastMath;
import me.moros.math.Vector3d;
import org.spongepowered.configurate.objectmapping.meta.Comment;

public class TwisterBurst extends AbilityInstance {
  private Config userConfig;
  private RemovalPolicy removalPolicy;

  private final ExpiringSet<UUID> damageCooldown = new ExpiringSet<>(400);
  private final Set<UUID> caughtEntities = new HashSet<>();

  private Vector3d origin;
  private Vector3d location;
  private Vector3d direction;
  private Vector3d right;
  private Vector3d up;

  private Collider collider;
  private double distanceTravelled = 0;
  private double angle = 0;

  public TwisterBurst(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    if (user.game().abilityManager(user.worldKey()).hasAbility(user, TwisterBurst.class)) {
      return false;
    }

    this.user = user;
    loadConfig();

    origin = user.eyeLocation();
    location = origin.add(user.direction().multiply(0.8));
    direction = user.direction().normalize();

    right = direction.cross(Vector3d.PLUS_J);
    if (right.lengthSq() < 0.001) {
      right = Vector3d.PLUS_I;
    } else {
      right = right.normalize();
    }
    up = right.cross(direction).normalize();

    collider = Sphere.of(location, userConfig.baseRadius);
    removalPolicy = Policies.builder()
      .add(OutOfRangeRemovalPolicy.of(userConfig.range, origin, () -> location))
      .build();

    user.addCooldown(description(), userConfig.cooldown);
    SoundEffect.AIR_FAST.play(user.world(), location);
    return true;
  }

  @Override
  public void loadConfig() {
    userConfig = user.game().configProcessor().calculate(this, Config.class);
  }

  @Override
  public UpdateResult update() {
    if (removalPolicy.test(user, description())) {
      detonate();
      return UpdateResult.REMOVE;
    }

    double progress = distanceTravelled / userConfig.range;
    double currentRadius = userConfig.baseRadius + progress * (userConfig.maxRadius - userConfig.baseRadius);

    location = location.add(direction.multiply(userConfig.speed));
    distanceTravelled += userConfig.speed;
    collider = Sphere.of(location, currentRadius);

    Block block = user.world().blockAt(location);
    if (!MaterialUtil.isTransparent(block) && !block.type().isLiquid()) {
      detonate();
      return UpdateResult.REMOVE;
    }

    Block floor = user.world().blockAt(location.subtract(0, currentRadius, 0));
    WorldUtil.tryExtinguishFire(user, floor);
    WorldUtil.tryCoolLava(user, floor);

    render(currentRadius);

    CollisionUtil.handle(user, collider, entity -> onEntityHit(entity, currentRadius));

    if (ThreadLocalRandom.current().nextInt(3) == 0) {
      SoundEffect.AIR_FAST.play(user.world(), location);
    }

    return UpdateResult.CONTINUE;
  }

  private boolean onEntityHit(Entity entity, double radius) {
    caughtEntities.add(entity.uuid());

    Vector3d toCenter = location.subtract(entity.center());
    Vector3d tangent = toCenter.cross(direction).normalize().multiply(0.4);
    Vector3d pull = toCenter.normalize().multiply(0.5).add(tangent).add(0, 0.25, 0);
    entity.applyVelocity(this, pull);

    if (damageCooldown.add(entity.uuid())) {
      entity.damage(userConfig.damage, user, description());
    }
    return false;
  }

  private void render(double radius) {
    angle += 0.45;
    int particles = 8;
    for (int i = 0; i < particles; i++) {
      double a = angle + (i * Math.PI * 2 / particles);
      double r = radius * (0.4 + 0.6 * ((double) i / particles));
      Vector3d offset = right.multiply(Math.cos(a) * r).add(up.multiply(Math.sin(a) * r));
      Vector3d pos = location.add(offset);
      ParticleBuilder.air(pos).spawn(user.world());
      if (i % 2 == 0) {
        Particle.GUST.builder(pos).count(1).spawn(user.world());
      }
    }
  }

  private void detonate() {
    SoundEffect.AIR.play(user.world(), location);
    Particle.CLOUD.builder(location).count(25).offset(1.2).speed(0.15).spawn(user.world());
    Particle.POOF.builder(location).count(15).offset(0.8).speed(0.1).spawn(user.world());

    Collider blastRadius = Sphere.of(location, userConfig.maxRadius * 1.5);
    CollisionUtil.handle(user, blastRadius, entity -> {
      Vector3d blastDir = entity.center().subtract(location).normalize().multiply(1.2).add(0, 0.6, 0);
      entity.applyVelocity(this, blastDir);
      entity.damage(userConfig.burstDamage, user, description());
      return false;
    });
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(collider);
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 6000;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 1.5;
    @Comment("Damage dealt when the twister detonates at the end")
    @Modifiable(Attribute.DAMAGE)
    private double burstDamage = 3.0;
    @Modifiable(Attribute.RANGE)
    private double range = 18;
    @Modifiable(Attribute.SPEED)
    private double speed = 0.9;
    @Modifiable(Attribute.RADIUS)
    private double baseRadius = 1.2;
    @Modifiable(Attribute.RADIUS)
    private double maxRadius = 3.2;

    @Override
    public List<String> path() {
      return List.of("abilities", "air", "sequences", "twisterburst");
    }
  }
}
