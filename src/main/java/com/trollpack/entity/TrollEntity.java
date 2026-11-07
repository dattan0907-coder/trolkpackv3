package com.trollpack.entity;

import com.trollpack.TrollPack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.*;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * One class for every humanoid of the pack (troll / aggro troll / NPC troll / elite / voodoo / chief).
 * Skills (all plain Java, no MythicMobs):
 *  - Troll:  melee combo, spear throw, leap slam, war cry (pack buff), sit / sleep / forage idle behaviours
 *  - Elite:  melee combos + kick, leap slam (shockwave), charge dash, rage mode below 40 % HP, dances for golden apples
 *  - Voodoo: homing skull (volley when low HP, slows), heal aura for the pack, keeps its distance (retreats / strafes)
 *  - Chief:  aura, trades a Troll Mask for emeralds
 */
public class TrollEntity extends PathfinderMob implements GeoEntity {

    public enum Kind {
        NEUTRAL("troll", 50), AGGRO("troll", 50), NPC("troll", 50),
        ELITE("troll_elite", 40), VOODOO("troll_voodoo", 40), CHIEF("troll_chief", 20);

        public final String modelId;
        public final int deathDelay;

        Kind(String modelId, int deathDelay) {
            this.modelId = modelId;
            this.deathDelay = deathDelay;
        }

        public boolean isTroll() { return modelId.equals("troll"); }
        public boolean invulnerable() { return this == NPC || this == CHIEF; }
    }

    public static final int B_NONE = 0, B_SIT = 1, B_SLEEP = 2, B_DANCE = 3, B_GETUP_SIT = 4, B_GETUP_SLEEP = 5, B_FORAGE = 6;

    private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(TrollEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BEHAVIOR = SynchedEntityData.defineId(TrollEntity.class, EntityDataSerializers.INT);
    /** 0 = spear in hand, 1 = winding up a throw, 2 = hand empty (spear thrown) */
    private static final EntityDataAccessor<Integer> SPEAR_PHASE = SynchedEntityData.defineId(TrollEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> LEAPING = SynchedEntityData.defineId(TrollEntity.class, EntityDataSerializers.BOOLEAN);

    public final Kind kind;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private record Scheduled(int[] ticks, Runnable r) {}
    private final List<Scheduled> scheduled = new ArrayList<>();
    private int attackCooldown, throwCooldown, leapCooldown, spellCooldown, healCooldown, chargeCooldown, warCryCooldown;
    private int gcd;                        // shared "global cooldown" so skills never fire on the same tick
    private int behaviorTicks, leapAirTicks;
    private int chargeWind, chargeTicks;
    private Vec3 chargeDir = Vec3.ZERO;
    private int strafeTicks, strafeDir = 1;
    private boolean enraged;

    public TrollEntity(EntityType<? extends TrollEntity> type, Level level, Kind kind) {
        super(type, level);
        this.kind = kind;
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes(double health, double speed, double follow, double damage) {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, health)
                .add(Attributes.MOVEMENT_SPEED, speed)
                .add(Attributes.FOLLOW_RANGE, follow)
                .add(Attributes.ATTACK_DAMAGE, damage)
                .add(Attributes.STEP_HEIGHT, 1.25)          // climbs ledges / stairs like the original
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5);
    }

    // ------------------------------------------------------------------ data / variants
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(VARIANT, 0);
        b.define(BEHAVIOR, B_NONE);
        b.define(SPEAR_PHASE, 0);
        b.define(LEAPING, false);
    }

    public TrollVariant variant() { return TrollVariant.unpack(entityData.get(VARIANT)); }
    public int behavior() { return entityData.get(BEHAVIOR); }
    public int spearPhase() { return entityData.get(SPEAR_PHASE); }
    public boolean isLeaping() { return entityData.get(LEAPING); }
    private void setBehavior(int p) { entityData.set(BEHAVIOR, p); }
    private void setLeaping(boolean b) { entityData.set(LEAPING, b); }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Variant", entityData.get(VARIANT));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Variant")) entityData.set(VARIANT, tag.getInt("Variant"));
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance diff, MobSpawnType type, @Nullable SpawnGroupData data) {
        if (kind.isTroll()) entityData.set(VARIANT, TrollVariant.random(random).pack());
        return super.finalizeSpawn(level, diff, type, data);
    }

    // ------------------------------------------------------------------ AI
    @Override
    protected void registerGoals() {
        if (kind == Kind.CHIEF) {
            goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 12f));
            return;
        }
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(0, new FreezeGoal());
        if (kind == Kind.NPC) {
            goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.5));
            goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 12f));
            goalSelector.addGoal(7, new RandomLookAroundGoal(this));
            return;
        }
        goalSelector.addGoal(1, new AttackGoal());
        goalSelector.addGoal(2, new ThrowSpearGoal());
        goalSelector.addGoal(2, new HealGoal());
        goalSelector.addGoal(3, new LeapGoal());
        goalSelector.addGoal(3, new ChargeGoal());
        goalSelector.addGoal(3, new WarCryGoal());
        goalSelector.addGoal(4, new SpellGoal());
        goalSelector.addGoal(5, new MoveTowardsTargetGoal(this, 1.0, 32f));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8f));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, TrollEntity.class).setAlertOthers(TrollEntity.class));
        if (kind == Kind.AGGRO) {
            targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        }
    }

    /** Stops movement while sitting / sleeping / dancing / foraging / getting up. */
    private class FreezeGoal extends Goal {
        FreezeGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)); }
        @Override public boolean canUse() { return behavior() != B_NONE; }
        @Override public boolean canContinueToUse() { return behavior() != B_NONE; }
        @Override public void start() { getNavigation().stop(); }
    }

    private double reach() { return kind == Kind.ELITE ? 3.8 : kind == Kind.VOODOO ? 3.2 : 3.4; }

    private boolean charging() { return chargeWind > 0 || chargeTicks > 0; }

    private boolean skillReady() { return gcd <= 0 && behavior() == B_NONE && !charging() && !isLeaping() && !isInWater(); }

    private class AttackGoal extends Goal {
        AttackGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { LivingEntity t = getTarget(); return t != null && t.isAlive(); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override
        public void tick() {
            LivingEntity t = getTarget();
            if (t == null) return;
            if (charging()) { return; }                      // the charge drives the movement itself
            getLookControl().setLookAt(t, 30f, 30f);
            double d = Math.sqrt(distanceToSqr(t.getX(), t.getY(), t.getZ()));
            boolean mage = kind == Kind.VOODOO;
            double speed = kind == Kind.ELITE ? 1.3 : 1.2;   // chase speed multiplier (sprint feel)

            if (mage) {
                // voodoo: keeps 6..14 blocks away, backs off when the player is close, strafes while casting
                if (d < 4.5 && attackCooldown > 0) {
                    if (getNavigation().isDone()) retreatFrom(t);
                } else if (d > 14) {
                    getNavigation().moveTo(t, 1.0);
                } else if (d > reach()) {
                    getNavigation().stop();
                    circle(0f);
                } else {
                    getNavigation().stop();
                }
            } else if (d > reach() - 0.5) {
                getNavigation().moveTo(t, speed);
            } else {
                getNavigation().stop();
                if (attackCooldown > 0) circle(d < reach() - 1.2 ? -0.25f : 0f); // circle the target while waiting for the next swing
            }
            if (attackCooldown <= 0 && d <= reach() && !isInWater() && !isLeaping() && spearPhase() == 0 && behavior() == B_NONE) startMelee(t);
        }
    }

    private void circle(float forward) {
        if (--strafeTicks <= 0) { strafeDir = random.nextBoolean() ? 1 : -1; strafeTicks = 30 + random.nextInt(40); }
        getMoveControl().strafe(forward, 0.45f * strafeDir);
    }

    private void retreatFrom(LivingEntity t) {
        Vec3 p = DefaultRandomPos.getPosAway(this, 8, 4, t.position());
        if (p != null) getNavigation().moveTo(p.x, p.y, p.z, 1.15);
    }

    private void startMelee(LivingEntity t) {
        attackCooldown = 20;
        swingSound();
        // small lunge forward (Mythic: lunge{v=.5})
        Vec3 f = Vec3.directionFromRotation(0, getYRot()).scale(0.35);
        setDeltaMovement(getDeltaMovement().add(f.x, 0, f.z));
        switch (kind) {
            case ELITE -> {
                boolean kick = random.nextBoolean();
                triggerAnim("action", kick ? "melee_2_1" : "melee_1_1");
                schedule(kick ? 8 : 6, () -> hitArea(15, 2.0, kick));
                if (!kick && random.nextFloat() < 0.5f) {
                    schedule(12, () -> { if (isAlive()) { triggerAnim("action", "melee_1_2"); swingSound(); } });
                    schedule(18, () -> hitArea(15, 2.0, false));
                }
            }
            case VOODOO -> {
                triggerAnim("action", "melee_1");
                schedule(6, () -> hitArea(8, 1.6, false));
            }
            default -> {
                boolean spear = variant().spear() && spearPhase() == 0;
                String a1 = spear ? "melee_1" : "melee_empty_1";
                String a2 = spear ? "melee_2" : "melee_empty_2";
                triggerAnim("action", a1);
                schedule(3, () -> hitArea(10, 1.8, false));
                if (random.nextFloat() < 0.75f) {
                    schedule(10, () -> { if (isAlive()) { triggerAnim("action", a2); swingSound(); } });
                    schedule(13, () -> hitArea(10, 1.8, true)); // second swing throws the target upwards
                    attackCooldown = 30;
                }
            }
        }
    }

    /** Damage everything that is not a troll in front of the mob (Mythic: totem{...} at the weapon hand). */
    private void hitArea(float dmg, double radius, boolean knockUp) {
        if (level().isClientSide || !isAlive()) return;
        Vec3 fwd = Vec3.directionFromRotation(0, getYRot());
        Vec3 c = position().add(fwd.scale(1.8)).add(0, 1.0, 0);
        boolean hit = false;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(radius, 1.2, radius),
                e -> e != this && !(e instanceof TrollEntity) && e.isAlive())) {
            if (e.hurt(damageSources().mobAttack(this), dmg)) {
                hit = true;
                e.push(fwd.x * 0.6, knockUp ? 0.9 : 0.25, fwd.z * 0.6);
                e.hurtMarked = true;
                fx(ParticleTypes.CRIT, e.getX(), e.getY(0.6), e.getZ(), knockUp ? 14 : 8, 0.3, 0.25);
            }
        }
        if (kind == Kind.ELITE) fx(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 1, 0, 0);
        if (hit) impactSound(1f);
    }

    // ------------------------------------------------------------------ skills
    private class ThrowSpearGoal extends Goal {
        @Override public boolean canUse() {
            LivingEntity t = getTarget();
            return kind.isTroll() && variant().spear() && spearPhase() == 0 && throwCooldown <= 0 && t != null && skillReady()
                    && onGround() && distanceToSqr(t) > 64 && distanceToSqr(t) < 28 * 28 && hasLineOfSight(t);
        }
        @Override public boolean canContinueToUse() { return false; }
        @Override public void start() {
            LivingEntity t = getTarget();
            throwCooldown = 100;
            attackCooldown = 40;
            gcd = 30;
            getNavigation().stop();
            addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1, false, false));
            entityData.set(SPEAR_PHASE, 1);
            triggerAnim("action", "throw_spear");
            schedule(18, () -> {
                if (!isAlive() || t == null) { entityData.set(SPEAR_PHASE, 0); return; }
                swingSound();
                SpearProjectile p = new SpearProjectile(TrollPack.SPEAR.get(), level());
                p.setOwner(TrollEntity.this);
                p.setPos(getX(), getEyeY() - 0.2, getZ());
                double dx = t.getX() - getX(), dz = t.getZ() - getZ();
                double dy = t.getY(0.5) - p.getY();
                double dist = Math.sqrt(dx * dx + dz * dz);
                p.shoot(dx, dy + dist * 0.12, dz, 1.7f, 1.0f);
                level().addFreshEntity(p);
                entityData.set(SPEAR_PHASE, 2);
            });
            schedule(30, () -> entityData.set(SPEAR_PHASE, 0));
        }
    }

    private class LeapGoal extends Goal {
        @Override public boolean canUse() {
            LivingEntity t = getTarget();
            if (t == null || leapCooldown > 0 || !onGround() || !skillReady() || spearPhase() != 0) return false;
            if (!(kind == Kind.ELITE || kind.isTroll() && variant().spear())) return false;
            double d = Math.sqrt(distanceToSqr(t));
            return d > 3 && d < 12 && t.getY() - getY() < 1;
        }
        @Override public boolean canContinueToUse() { return false; }
        @Override public void start() {
            LivingEntity t = getTarget();
            leapCooldown = kind == Kind.ELITE ? 120 : 140;
            attackCooldown = 20;
            gcd = 40;
            getNavigation().stop();
            triggerAnim("action", kind == Kind.ELITE ? "leap" : "jump_attack_leap");
            level().playSound(null, blockPosition(), TrollPack.JUMP.get(), SoundSource.HOSTILE, 1f, 1f);
            schedule(10, () -> {
                if (!isAlive() || t == null) return;
                // aim a little ahead of a moving target
                Vec3 lead = t.getDeltaMovement().multiply(1, 0, 1).scale(8);
                double dx = t.getX() + lead.x - getX(), dz = t.getZ() + lead.z - getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                double v = Math.min(1.2, Math.max(0.35, d / 11.0));
                Vec3 dir = new Vec3(dx, 0, dz).normalize();
                setDeltaMovement(dir.x * v, 0.6, dir.z * v);
                hasImpulse = true;
                setLeaping(true);
                leapAirTicks = 0;
                dust(getX(), getY() + 0.1, getZ(), 14, 0.6, 0.05);
            });
        }
    }

    /** Elite only: lowers its head, scrapes the ground, then dashes in a straight line and bowls the target over. */
    private class ChargeGoal extends Goal {
        @Override public boolean canUse() {
            LivingEntity t = getTarget();
            if (kind != Kind.ELITE || t == null || chargeCooldown > 0 || !onGround() || !skillReady()) return false;
            double d = Math.sqrt(distanceToSqr(t));
            return d > 8 && d < 20 && hasLineOfSight(t);
        }
        @Override public boolean canContinueToUse() { return false; }
        @Override public void start() {
            chargeCooldown = 180;
            attackCooldown = 40;
            gcd = 50;
            chargeWind = 14;
            getNavigation().stop();
            level().playSound(null, blockPosition(), TrollPack.JUMP.get(), SoundSource.HOSTILE, 1f, 0.7f);
            fx(ParticleTypes.ANGRY_VILLAGER, getX(), getY() + 2.6, getZ(), 3, 0.4, 0);
        }
    }

    /** Aggressive trolls: roar that buffs every troll nearby (speed + strength). */
    private class WarCryGoal extends Goal {
        @Override public boolean canUse() {
            LivingEntity t = getTarget();
            return kind.isTroll() && kind != Kind.NPC && t != null && warCryCooldown <= 0 && skillReady() && onGround()
                    && spearPhase() == 0 && distanceToSqr(t) < 24 * 24;
        }
        @Override public boolean canContinueToUse() { return false; }
        @Override public void start() {
            warCryCooldown = 700;
            gcd = 40;
            attackCooldown = 20;
            getNavigation().stop();
            level().playSound(null, blockPosition(), TrollPack.GREETINGS.get(), SoundSource.HOSTILE, 1.2f, 0.55f);
            ring(ParticleTypes.CLOUD, 1.2, 16, getY() + 0.2);
            for (TrollEntity o : level().getEntitiesOfClass(TrollEntity.class, getBoundingBox().inflate(14, 5, 14),
                    o -> o.isAlive() && o.kind.isTroll() && o.kind != Kind.NPC)) {
                o.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 200, 0));
                o.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0));
                fx(ParticleTypes.ANGRY_VILLAGER, o.getX(), o.getY() + o.getBbHeight() + 0.3, o.getZ(), 3, 0.3, 0);
            }
        }
    }

    /** Voodoo: heals itself and every troll around it. */
    private class HealGoal extends Goal {
        @Override public boolean canUse() {
            if (kind != Kind.VOODOO || healCooldown > 0 || !skillReady()) return false;
            if (getHealth() < getMaxHealth() * 0.6f) return true;
            return !level().getEntitiesOfClass(TrollEntity.class, getBoundingBox().inflate(12, 4, 12),
                    o -> o != TrollEntity.this && o.isAlive() && !o.kind.invulnerable() && o.getHealth() < o.getMaxHealth() * 0.65f).isEmpty();
        }
        @Override public boolean canContinueToUse() { return false; }
        @Override public void start() {
            healCooldown = 240;
            spellCooldown = Math.max(spellCooldown, 40);
            attackCooldown = 50;
            gcd = 50;
            getNavigation().stop();
            triggerAnim("action", "spell_1");
            schedule(10, () -> ring(ParticleTypes.ENCHANT, 1.5, 24, getY() + 1.2));
            schedule(30, () -> {
                if (!isAlive()) return;
                level().playSound(null, blockPosition(), TrollPack.HEAL.get(), SoundSource.HOSTILE, 1f, 1f);
                ring(ParticleTypes.HAPPY_VILLAGER, 3.0, 24, getY() + 0.3);
                ring(ParticleTypes.HAPPY_VILLAGER, 6.0, 36, getY() + 0.3);
                for (TrollEntity o : level().getEntitiesOfClass(TrollEntity.class, getBoundingBox().inflate(10, 4, 10),
                        o -> o.isAlive() && !o.kind.invulnerable())) {
                    o.heal(o.getMaxHealth() * 0.25f);
                    fx(ParticleTypes.HEART, o.getX(), o.getY() + o.getBbHeight() * 0.8, o.getZ(), 4, 0.4, 0);
                }
            });
        }
    }

    /** Voodoo: homing skull(s). Below 35 % HP it fires a volley of three and recasts faster. */
    private class SpellGoal extends Goal {
        @Override public boolean canUse() {
            LivingEntity t = getTarget();
            if (kind != Kind.VOODOO || t == null || spellCooldown > 0 || !skillReady()) return false;
            double d = Math.sqrt(distanceToSqr(t));
            return d >= 5 && d <= 18 && hasLineOfSight(t);
        }
        @Override public boolean canContinueToUse() { return false; }
        @Override public void start() {
            LivingEntity t = getTarget();
            boolean rage = getHealth() < getMaxHealth() * 0.35f;
            spellCooldown = rage ? 70 : 100;
            attackCooldown = 50;
            gcd = 40;
            getNavigation().stop();
            triggerAnim("action", "spell_1");
            schedule(15, () -> ring(ParticleTypes.SOUL_FIRE_FLAME, 1.0, 12, getY() + 2.4));
            int shots = rage ? 3 : 1;
            for (int i = 0; i < shots; i++) {
                final int n = i;
                schedule(35 + i * 6, () -> {
                    if (!isAlive() || t == null || !t.isAlive()) return;
                    level().playSound(null, blockPosition(), TrollPack.SPELL_ATTACK.get(), SoundSource.HOSTILE, 1f, 0.9f + 0.1f * n);
                    SpearProjectile m = new SpearProjectile(TrollPack.SKULL_MISSILE.get(), level(), true);
                    m.setOwner(TrollEntity.this);
                    m.setPos(getX(), getEyeY() - 0.3, getZ());
                    m.setTarget(t);
                    Vec3 to = t.position().subtract(position()).normalize();
                    m.setDeltaMovement(to.x * 0.4 + (n - 1) * 0.15, 0.35, to.z * 0.4 - (n - 1) * 0.15);
                    level().addFreshEntity(m);
                    fx(ParticleTypes.SOUL_FIRE_FLAME, getX(), getEyeY() - 0.3, getZ(), 12, 0.3, 0.05);
                });
            }
        }
    }

    // ------------------------------------------------------------------ tick
    private void schedule(int ticks, Runnable r) { scheduled.add(new Scheduled(new int[]{ticks}, r)); }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) { clientFx(); return; }
        if (attackCooldown > 0) attackCooldown--;
        if (throwCooldown > 0) throwCooldown--;
        if (leapCooldown > 0) leapCooldown--;
        if (spellCooldown > 0) spellCooldown--;
        if (healCooldown > 0) healCooldown--;
        if (chargeCooldown > 0) chargeCooldown--;
        if (warCryCooldown > 0) warCryCooldown--;
        if (gcd > 0) gcd--;

        // the target is not synced to clients, "aggressive" is -> drives the run animation
        boolean hasTarget = getTarget() != null;
        if (isAggressive() != hasTarget) setAggressive(hasTarget);

        for (int i = scheduled.size() - 1; i >= 0; i--) {
            if (i >= scheduled.size()) continue;
            Scheduled s = scheduled.get(i);
            if (--s.ticks()[0] <= 0) { scheduled.remove(i); s.r().run(); }
        }

        if (isLeaping()) {
            leapAirTicks++;
            if (leapAirTicks > 4 && onGround()) landed();
            else if (leapAirTicks > 80) setLeaping(false);
        }
        tickCharge();

        if (kind == Kind.ELITE && !enraged && tickCount % 20 == 0 && getHealth() < getMaxHealth() * 0.4f) rage();

        tickBehavior();

        // heavy footsteps: dust while running
        if (kind != Kind.CHIEF && onGround() && tickCount % 5 == 0 && getDeltaMovement().horizontalDistanceSqr() > 0.012) {
            dust(getX(), getY() + 0.1, getZ(), kind == Kind.ELITE ? 4 : 2, 0.35, 0.02);
        }
    }

    private void rage() {
        enraged = true;
        addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1200, 0));
        addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 1200, 0));
        level().playSound(null, blockPosition(), TrollPack.GREETINGS.get(), SoundSource.HOSTILE, 1.4f, 0.5f);
        ring(ParticleTypes.ANGRY_VILLAGER, 1.5, 10, getY() + 1.5);
        fx(ParticleTypes.LAVA, getX(), getY() + 1.5, getZ(), 12, 0.6, 0);
    }

    private void tickCharge() {
        if (chargeWind > 0) {
            chargeWind--;
            getNavigation().stop();
            LivingEntity t = getTarget();
            if (t != null) getLookControl().setLookAt(t, 60f, 60f);
            if (chargeWind % 3 == 0) dust(getX() + (random.nextDouble() - 0.5), getY() + 0.1, getZ() + (random.nextDouble() - 0.5), 4, 0.5, 0.08);
            if (chargeWind == 0) {
                if (t == null || !t.isAlive()) return;
                chargeDir = new Vec3(t.getX() - getX(), 0, t.getZ() - getZ()).normalize();
                chargeTicks = 20;
                swingSound();
            }
        } else if (chargeTicks > 0) {
            chargeTicks--;
            float yaw = (float) (Mth.atan2(-chargeDir.x, chargeDir.z) * Mth.RAD_TO_DEG);
            setYRot(yaw); yBodyRot = yaw; setYHeadRot(yaw);
            setDeltaMovement(chargeDir.x * 0.9, getDeltaMovement().y, chargeDir.z * 0.9);
            hasImpulse = true;
            if (tickCount % 2 == 0) dust(getX(), getY() + 0.1, getZ(), 5, 0.5, 0.05);
            boolean hit = false;
            for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(0.7, 0.2, 0.7),
                    e -> e != this && !(e instanceof TrollEntity) && e.isAlive())) {
                if (e.hurt(damageSources().mobAttack(this), 14)) {
                    e.push(chargeDir.x * 1.4, 0.5, chargeDir.z * 1.4);
                    e.hurtMarked = true;
                    fx(ParticleTypes.CRIT, e.getX(), e.getY(0.6), e.getZ(), 16, 0.35, 0.3);
                    hit = true;
                }
            }
            if (hit) {
                impactSound(0.8f);
                chargeTicks = 0;
                attackCooldown = 25;
            } else if (horizontalCollision) {   // crashed into a wall: stunned for a moment
                chargeTicks = 0;
                addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 3, false, false));
                impactSound(0.7f);
                dust(getX() + chargeDir.x, getY() + 1, getZ() + chargeDir.z, 20, 0.6, 0.1);
            }
        }
    }

    private void tickBehavior() {
        int p = behavior();
        if (p != B_NONE) {
            boolean calm = getTarget() == null;
            switch (p) {
                case B_SIT, B_SLEEP -> {
                    if (p == B_SLEEP && level().getDayTime() % 24000L < 13000L && behaviorTicks > 60) behaviorTicks = 60; // wake up at dawn
                    if (!calm) setBehavior(B_NONE);                                   // woken by a fight: instant (getup_damage)
                    else if (--behaviorTicks <= 0) {
                        setBehavior(p == B_SIT ? B_GETUP_SIT : B_GETUP_SLEEP);        // plays sit_getup / sleep_getup
                        behaviorTicks = p == B_SIT ? 20 : 60;
                    }
                }
                case B_DANCE, B_FORAGE -> { if (!calm || --behaviorTicks <= 0) setBehavior(B_NONE); }
                default -> { if (--behaviorTicks <= 0) setBehavior(B_NONE); }
            }
        } else if (kind.isTroll() && kind != Kind.NPC && getTarget() == null && onGround() && !isInWater() && tickCount % 20 == 0) {
            boolean night = level().getDayTime() % 24000L > 13000L;
            if (night && random.nextFloat() < 0.04f) { setBehavior(B_SLEEP); behaviorTicks = 600 + random.nextInt(600); }
            else if (random.nextFloat() < 0.015f) { setBehavior(B_SIT); behaviorTicks = 300 + random.nextInt(600); }
            else if (random.nextFloat() < 0.03f) { setBehavior(B_FORAGE); behaviorTicks = 40; }
        }
    }

    private void landed() {
        setLeaping(false);
        triggerAnim("action", kind == Kind.ELITE ? "land" : "jump_attack_hit");
        boolean elite = kind == Kind.ELITE;
        float dmg = elite ? 15 : 10;
        double rad = elite ? 3.5 : 2.5;
        Vec3 c = position();
        boolean hit = false;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(rad, 1.5, rad),
                e -> e != this && !(e instanceof TrollEntity) && e.isAlive())) {
            if (e.hurt(damageSources().mobAttack(this), dmg)) {
                Vec3 away = e.position().subtract(c).multiply(1, 0, 1).normalize();
                e.push(away.x * (elite ? 1.0 : 0.6), elite ? 0.7 : 0.4, away.z * (elite ? 1.0 : 0.6));
                e.hurtMarked = true;
                hit = true;
            }
        }
        // shockwave rings of dust, one every other tick
        for (int r = 1; r <= (elite ? 4 : 3); r++) {
            final int rr = r;
            schedule(r * 2 - 1, () -> {
                BlockState st = level().getBlockState(blockPosition().below());
                if (st.isAir() || !(level() instanceof ServerLevel sl)) return;
                int n = 10 + rr * 6;
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), getX() + Math.cos(a) * rr, getY() + 0.15, getZ() + Math.sin(a) * rr,
                            1, 0.05, 0.1, 0.05, 0.1);
                }
                if (rr == 1) fx(ParticleTypes.EXPLOSION, getX(), getY() + 0.3, getZ(), 1, 0, 0);
            });
        }
        impactSound(elite ? 0.7f : 0.9f);
        if (!hit) level().playSound(null, blockPosition(), TrollPack.IMPACT.get(0).get(), SoundSource.HOSTILE, 0.8f, 0.6f);
    }

    @Override
    public boolean hurt(DamageSource src, float amount) {
        boolean r = super.hurt(src, amount);
        if (r && behavior() != B_NONE && behavior() != B_GETUP_SIT && behavior() != B_GETUP_SLEEP) setBehavior(B_NONE); // getup_damage
        if (r && chargeWind > 0 && amount >= 12) chargeWind = 0;                                                         // heavy hit interrupts the wind-up
        return r;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource src) {
        if (kind.invulnerable() && !src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
        return super.isInvulnerableTo(src);
    }

    @Override
    public boolean isPushable() { return kind != Kind.VOODOO && kind != Kind.NPC && kind != Kind.CHIEF && super.isPushable(); }

    // ------------------------------------------------------------------ interaction
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level().isClientSide) return InteractionResult.sidedSuccess(true);
        switch (kind) {
            case ELITE -> {
                if (held.is(Items.GOLDEN_APPLE) && behavior() == B_NONE) {
                    if (!player.getAbilities().instabuild) held.shrink(1);
                    getLookControl().setLookAt(player);
                    fx(ParticleTypes.HEART, getX(), getY() + 2.5, getZ(), 5, 0.3, 0);
                    setBehavior(B_DANCE);
                    behaviorTicks = 60;
                    return InteractionResult.CONSUME;
                }
            }
            case CHIEF -> {
                getLookControl().setLookAt(player);
                triggerAnim("action", "hand_out");
                if (held.is(TrollPack.TROLL_MASK.get())) { // the chief pays for troll masks
                    if (!player.getAbilities().instabuild) held.shrink(1);
                    schedule(20, () -> {
                        Vec3 f = Vec3.directionFromRotation(0, getYRot());
                        var item = spawnAtLocation(new ItemStack(Items.EMERALD, 1 + random.nextInt(3)), 1.0f);
                        if (item != null) item.setDeltaMovement(f.x * 0.2, 0.2, f.z * 0.2);
                        fx(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 1.5, getZ(), 10, 0.5, 0);
                    });
                }
                return InteractionResult.CONSUME;
            }
            case VOODOO -> {
                triggerAnim("action", "greet");
                level().playSound(null, blockPosition(), TrollPack.GREETINGS.get(), SoundSource.NEUTRAL, 1f, 1f);
                return InteractionResult.CONSUME;
            }
            case NPC -> {
                getLookControl().setLookAt(player);
                level().playSound(null, blockPosition(), TrollPack.GREETINGS.get(), SoundSource.NEUTRAL, 1f, 1f);
                return InteractionResult.CONSUME;
            }
            default -> { }
        }
        return super.mobInteract(player, hand);
    }

    // ------------------------------------------------------------------ effects helpers
    private void fx(ParticleOptions p, double x, double y, double z, int n, double spread, double speed) {
        if (level() instanceof ServerLevel sl) sl.sendParticles(p, x, y, z, n, spread, spread, spread, speed);
    }

    private void ring(ParticleOptions p, double radius, int n, double y) {
        if (!(level() instanceof ServerLevel sl)) return;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            sl.sendParticles(p, getX() + Math.cos(a) * radius, y, getZ() + Math.sin(a) * radius, 1, 0, 0.02, 0, 0.01);
        }
    }

    /** Block-crack dust of whatever the troll is standing on. */
    private void dust(double x, double y, double z, int n, double spread, double speed) {
        if (!(level() instanceof ServerLevel sl)) return;
        BlockState st = level().getBlockState(BlockPos.containing(x, y - 0.3, z));
        if (st.isAir()) st = level().getBlockState(blockPosition().below());
        if (st.isAir()) return;
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), x, y, z, n, spread, 0.1, spread, speed);
    }

    /** Ambient particles that only the client needs to see. */
    private void clientFx() {
        if (kind == Kind.CHIEF && tickCount % 3 == 0) {
            level().addParticle(ParticleTypes.SMALL_FLAME, getX() + (random.nextDouble() - 0.5) * 1.2, getY() + 0.4 + random.nextDouble() * 1.4,
                    getZ() + (random.nextDouble() - 0.5) * 1.2, 0, 0.02, 0);
        } else if (kind == Kind.VOODOO && !isDeadOrDying() && tickCount % 4 == 0) {
            level().addParticle(ParticleTypes.SOUL, getX() + (random.nextDouble() - 0.5) * 1.4, getY() + 0.3 + random.nextDouble() * 2.2,
                    getZ() + (random.nextDouble() - 0.5) * 1.4, 0, 0.03, 0);
        } else if (kind == Kind.ELITE && enragedClient() && tickCount % 3 == 0) {
            level().addParticle(ParticleTypes.SMOKE, getX() + (random.nextDouble() - 0.5), getY() + 2.4, getZ() + (random.nextDouble() - 0.5), 0, 0.04, 0);
        }
    }

    private boolean enragedClient() { return hasEffect(MobEffects.DAMAGE_BOOST) && getHealth() < getMaxHealth() * 0.4f; }

    // ------------------------------------------------------------------ sounds / death / loot (Mythic: Silent: true)
    private void swingSound() {
        SoundEvent s = TrollPack.SWING.get(random.nextInt(TrollPack.SWING.size())).get();
        level().playSound(null, blockPosition(), s, SoundSource.HOSTILE, 1f, 1f);
    }

    private void impactSound(float pitch) {
        SoundEvent s = TrollPack.IMPACT.get(random.nextInt(TrollPack.IMPACT.size())).get();
        level().playSound(null, blockPosition(), s, SoundSource.HOSTILE, 1f, pitch);
    }

    @Override protected SoundEvent getHurtSound(DamageSource s) { return null; }
    @Override protected SoundEvent getDeathSound() { return null; }
    @Override protected void playStepSound(BlockPos p, BlockState s) { }

    @Override
    protected void tickDeath() {
        ++deathTime;
        if (deathTime >= kind.deathDelay && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            ((ServerLevel) level()).sendParticles(ParticleTypes.CLOUD, getX(), getY() + 1, getZ(), 100, 0.5, 0.5, 0.5, 0.05);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource src, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, src, recentlyHit);
        if (kind.isTroll()) {
            TrollVariant v = variant();
            if (v.shield() && random.nextBoolean()) spawnAtLocation(TrollPack.TROLL_SHIELD.get());
            if (random.nextBoolean()) spawnAtLocation(TrollPack.TROLL_MASK.get());
            if (v.spear() && random.nextBoolean()) spawnAtLocation(TrollPack.TROLL_SPEAR.get());
        } else if (kind == Kind.ELITE && random.nextFloat() < 0.4f) {
            spawnAtLocation(TrollPack.TROLL_ELITE_HELM.get());
        } else if (kind == Kind.VOODOO && random.nextBoolean()) {
            spawnAtLocation(TrollPack.TROLL_VOODOO_STAFF.get());
        }
    }

    // ------------------------------------------------------------------ animation
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state -> {
            if (isDeadOrDying()) return state.setAndContinue(RawAnimation.begin().thenPlayAndHold("death"));
            if (kind == Kind.CHIEF) return state.setAndContinue(RawAnimation.begin().thenLoop("idle"));
            state.getController().setAnimationSpeed(1.0);
            switch (behavior()) {
                case B_SIT -> { return state.setAndContinue(RawAnimation.begin().then("sit", Animation.LoopType.PLAY_ONCE).thenLoop("sit_idle")); }
                case B_SLEEP -> { return state.setAndContinue(RawAnimation.begin().then("sleep_laydown", Animation.LoopType.PLAY_ONCE).thenLoop("sleep_idle")); }
                case B_GETUP_SIT -> { return state.setAndContinue(RawAnimation.begin().then("sit_getup", Animation.LoopType.PLAY_ONCE).thenLoop("idle")); }
                case B_GETUP_SLEEP -> { return state.setAndContinue(RawAnimation.begin().then("sleep_getup", Animation.LoopType.PLAY_ONCE).thenLoop("idle")); }
                case B_FORAGE -> { return state.setAndContinue(RawAnimation.begin().then("forage", Animation.LoopType.PLAY_ONCE).thenLoop("idle")); }
                case B_DANCE -> { return state.setAndContinue(RawAnimation.begin().thenLoop("dance")); }
                default -> { }
            }
            if (kind.isTroll() && isInWater()) return state.setAndContinue(RawAnimation.begin().thenLoop("swim"));
            if (kind == Kind.ELITE && !onGround() && !isLeaping()) return state.setAndContinue(RawAnimation.begin().thenLoop("in_air"));
            if (state.isMoving()) {
                boolean run = isAggressive() && kind != Kind.VOODOO;
                // legs keep pace with the real speed (slow wander vs. sprinting at the player)
                state.getController().setAnimationSpeed(Mth.clamp(0.6 + walkAnimation.speed() * 0.9, 0.8, 1.5));
                return state.setAndContinue(RawAnimation.begin().thenLoop(run ? "run" : "walk"));
            }
            return state.setAndContinue(RawAnimation.begin().thenLoop("idle"));
        }));

        AnimationController<TrollEntity> action = new AnimationController<>(this, "action", 2, state -> PlayState.STOP);
        for (String n : new String[]{"melee_1", "melee_2", "melee_empty_1", "melee_empty_2", "throw_spear", "jump_attack_leap",
                "jump_attack_hit", "melee_1_1", "melee_1_2", "melee_2_1", "leap", "land", "spell_1", "greet", "hand_out"}) {
            action.triggerableAnim(n, RawAnimation.begin().then(n, Animation.LoopType.PLAY_ONCE));
        }
        controllers.add(action);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
