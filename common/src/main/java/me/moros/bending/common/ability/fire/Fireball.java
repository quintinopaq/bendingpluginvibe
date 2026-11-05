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

package me.moros.bending.common.ability.fire;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityInstance;
import me.moros.bending.api.ability.Activation;
import me.moros.bending.api.ability.Explosive;
import me.moros.bending.api.ability.common.basic.ParticleStream;
import me.moros.bending.api.collision.geometry.Collider;
import me.moros.bending.api.collision.geometry.Ray;
import me.moros.bending.api.config.Configurable;
import me.moros.bending.api.config.attribute.Attribute;
import me.moros.bending.api.config.attribute.Modifiable;
import me.moros.bending.api.platform.block.Block;
import me.moros.bending.api.platform.block.BlockType;
import me.moros.bending.api.platform.entity.Entity;
import me.moros.bending.api.platform.particle.Particle;
import me.moros.bending.api.platform.sound.Sound;
import me.moros.bending.api.platform.sound.SoundEffect;
import me.moros.bending.api.platform.world.WorldUtil;
import me.moros.bending.api.temporal.TempBlock;
import me.moros.bending.api.temporal.TempLight;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.BendingEffect;
import me.moros.bending.api.util.functional.Policies;
import me.moros.bending.api.util.functional.RemovalPolicy;
import me.moros.math.Vector3d;

public class Fireball extends AbilityInstance implements Explosive {
  private Config userConfig;
  private RemovalPolicy removalPolicy;
  private FireballStream stream;

  public Fireball(AbilityDescription desc) {
    super(desc);
  }

  @Override
  public boolean activate(User user, Activation method) {
    if (user.onCooldown(description())) {
      return false;
    }
    if (user.world().blockAt(user.mainHandSide()).type().isLiquid()) {
      return false;
    }
    if (Policies.UNDER_WATER.test(user, description())) {
      return false;
    }

    this.user = user;
    loadConfig();

    removalPolicy = Policies.builder().add(Policies.UNDER_WATER).build();
    Ray ray = Ray.of(user.eyeLocation(), user.direction().multiply(userConfig.range));
    stream = new FireballStream(ray);
    SoundEffect.FIRE.play(user.world(), user.eyeLocation());
    user.playSound(Sound.ITEM_FIRECHARGE_USE.asEffect(1.0f, 1.2f).sound());
    user.addCooldown(description(), userConfig.cooldown);
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
    return stream.update();
  }

  @Override
  public Collection<Collider> colliders() {
    return List.of(stream.collider());
  }

  @Override
  public void explode() {
    if (stream != null) {
      explode(stream.collider().position());
    }
  }

  private void explode(Vector3d pos) {
    Particle.EXPLOSION.builder(pos).count(1).spawn(user.world());
    Particle.FLAME.builder(pos).count(25).offset(0.5, 0.5, 0.5).speed(0.12).spawn(user.world());
    Particle.SMOKE.builder(pos).count(10).offset(0.3, 0.3, 0.3).speed(0.04).spawn(user.world());
    Sound.ENTITY_GENERIC_EXPLODE.asEffect(1.0f, 1.2f).play(user.world(), pos);
  }

  private class FireballStream extends ParticleStream {
    private int ticks = 0;

    public FireballStream(Ray ray) {
      super(user, ray, userConfig.speed, 0.85);
      canCollide = BlockType::isLiquid;
      singleCollision = true;
    }

    @Override
    public void render(Vector3d location) {
      Particle.FLAME.builder(location).count(6).offset(0.2, 0.2, 0.2).speed(0.02).spawn(user.world());
      Particle.SMOKE.builder(location).count(2).offset(0.1, 0.1, 0.1).speed(0.01).spawn(user.world());
      if (ticks % 2 == 0) {
        Particle.LAVA.builder(location).count(1).offset(0.05, 0.05, 0.05).spawn(user.world());
      }
      TempLight.builder(++ticks).build(user.world().blockAt(location));
    }

    @Override
    public void postRender(Vector3d location) {
      if (ThreadLocalRandom.current().nextInt(5) == 0) {
        SoundEffect.FIRE.play(user.world(), location);
      }
    }

    @Override
    public boolean onEntityHit(Entity entity) {
      explode(collider().position());
      BendingEffect.FIRE_TICK.apply(user, entity, userConfig.fireTicks);
      entity.damage(userConfig.damage, user, description());
      entity.applyVelocity(Fireball.this, ray.direction().normalize().multiply(0.6));
      return true;
    }

    @Override
    public boolean onBlockHit(Block block) {
      Vector3d hitPos = collider().position();
      explode(hitPos);
      WorldUtil.tryLightBlock(block);
      Block above = block.offset(0, 1, 0);
      if (above.type().isAir() && user.canBuild(above)) {
        TempBlock.fire().duration(1500).ability(Fireball.this).build(above);
      }
      return true;
    }
  }

  private static final class Config implements Configurable {
    @Modifiable(Attribute.COOLDOWN)
    private long cooldown = 2500;
    @Modifiable(Attribute.DAMAGE)
    private double damage = 4.0;
    @Modifiable(Attribute.FIRE_TICKS)
    private int fireTicks = 60;
    @Modifiable(Attribute.RANGE)
    private double range = 24.0;
    @Modifiable(Attribute.SPEED)
    private double speed = 1.1;

    @Override
    public List<String> path() {
      return List.of("abilities", "fire", "fireball");
    }
  }
}

