package com.trollpack.entity;

import com.trollpack.TrollPack;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Thrown troll spear, and (missile = true) the homing voodoo skull (slows whoever it hits). */
public class SpearProjectile extends ThrowableItemProjectile {
    private final boolean missile;
    private LivingEntity target;
    private int life;

    public SpearProjectile(EntityType<? extends SpearProjectile> type, Level level) {
        this(type, level, false);
    }

    public SpearProjectile(EntityType<? extends SpearProjectile> type, Level level, boolean missile) {
        super(type, level);
        this.missile = missile;
        if (missile) setNoGravity(true);
    }

    public void setTarget(LivingEntity t) { this.target = t; }

    @Override
    protected Item getDefaultItem() {
        return missile ? TrollPack.VOODOO_SKULL.get() : TrollPack.TROLL_SPEAR.get();
    }

    @Override
    protected double getDefaultGravity() { return missile ? 0.0 : 0.05; }

    /** Trolls never hit each other (the thrower's friends are ignored). */
    @Override
    protected boolean canHitEntity(Entity e) { return !(e instanceof TrollEntity) && super.canHitEntity(e); }

    @Override
    public void tick() {
        super.tick();
        if (++life > 200) { discard(); return; }
        if (missile && target != null && target.isAlive() && !level().isClientSide) {
            Vec3 to = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(position()).normalize();
            setDeltaMovement(getDeltaMovement().scale(0.85).add(to.scale(0.12)));
            hasImpulse = true;
        }
        if (level().isClientSide) { // trails
            if (missile) {
                level().addParticle(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY(), getZ(), 0, 0.01, 0);
                if (tickCount % 2 == 0) level().addParticle(ParticleTypes.SCULK_SOUL, getX(), getY(), getZ(), 0, 0.02, 0);
            } else {
                level().addParticle(ParticleTypes.CRIT, getX(), getY(), getZ(), 0, 0, 0);
                if (tickCount % 2 == 0) level().addParticle(ParticleTypes.CLOUD, getX(), getY(), getZ(), 0, 0, 0);
            }
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (level().isClientSide) return;
        Entity e = hit.getEntity();
        if (e == getOwner() || e instanceof TrollEntity) return;
        if (e.hurt(damageSources().thrown(this, getOwner()), missile ? 8f : 9f) && e instanceof LivingEntity le) {
            Vec3 dir = getDeltaMovement().multiply(1, 0, 1).normalize();
            le.push(dir.x * 0.5, 0.2, dir.z * 0.5);
            le.hurtMarked = true;
            if (missile) le.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1)); // voodoo curse
        }
        impactFx();
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        if (level().isClientSide) return;
        impactFx();
        discard();
    }

    private void impactFx() {
        if (!(level() instanceof ServerLevel sl)) return;
        if (missile) {
            burst(sl, ParticleTypes.SOUL_FIRE_FLAME, 30, 0.35, 0.08);
            burst(sl, ParticleTypes.SCULK_SOUL, 10, 0.3, 0.05);
            level().playSound(null, getX(), getY(), getZ(), TrollPack.SPELL_ATTACK.get(), SoundSource.HOSTILE, 0.7f, 1.4f);
        } else {
            burst(sl, ParticleTypes.CRIT, 15, 0.3, 0.3);
            burst(sl, ParticleTypes.POOF, 6, 0.2, 0.02);
            level().playSound(null, getX(), getY(), getZ(), TrollPack.IMPACT.get(1).get(), SoundSource.HOSTILE, 1f, 1f);
        }
    }

    private void burst(ServerLevel sl, ParticleOptions p, int n, double spread, double speed) {
        sl.sendParticles(p, getX(), getY(), getZ(), n, spread, spread, spread, speed);
    }
}
