package net.zenzty.soullink.mixin.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.LocalDifficulty;
import net.zenzty.soullink.common.SoulLinkConstants;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.RunDifficulty;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.util.SwarmAlertAccessor;

@Mixin(MobEntity.class)
public class MobEntityMixin implements SwarmAlertAccessor {

    private static final int TIER_IRON = 0;
    private static final int TIER_DIAMOND = 1;
    private static final int TIER_NETHERITE = 2;

    private static final float EXTREME_NETHERITE_CHANCE = 0.06f;
    private static final float EXTREME_DIAMOND_CHANCE = 0.18f;
    private static final float EXTREME_IRON_CHANCE = 0.45f;
    private static final float EXTREME_ARMOR_PIECE_CHANCE = 0.75f;
    private static final float EXTREME_WEAPON_CHANCE = 0.60f;
    private static final int EXTREME_NETHERITE_ENCHANT_LEVEL = 28;
    private static final int EXTREME_DIAMOND_ENCHANT_LEVEL = 22;
    private static final int EXTREME_IRON_ENCHANT_LEVEL = 16;

    @Unique
    private long soullink$lastSwarmAlertTick = -SoulLinkConstants.SWARM_INTELLIGENCE_COOLDOWN_TICKS;

    @Unique
    private boolean soullink$skipSwarmAlert = false;

    @Override
    public void soullink$setSkipSwarmAlert(boolean skip) {
        this.soullink$skipSwarmAlert = skip;
    }

    @Override
    public boolean soullink$shouldSkipSwarmAlert() {
        return this.soullink$skipSwarmAlert;
    }

    @Inject(method = "initEquipment", at = @At("TAIL"))
    private void soullink$boostHostileEquipment(Random random, LocalDifficulty localDifficulty,
            CallbackInfo ci) {
        MobEntity self = (MobEntity) (Object) this;
        if (!(self instanceof HostileEntity)) {
            return;
        }

        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            return;
        }

        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        if (!(self.getEntityWorld() instanceof ServerWorld serverWorld)
                || !runManager.isTemporaryWorld(serverWorld.getRegistryKey())) {
            return;
        }

        if (Settings.getInstance().getDifficulty() != RunDifficulty.EXTREME) {
            return;
        }

        int armorTier = rollTier(random);
        if (armorTier != -1) {
            equipArmorIfEmpty(self, serverWorld, EquipmentSlot.HEAD, armorTier, random);
            equipArmorIfEmpty(self, serverWorld, EquipmentSlot.CHEST, armorTier, random);
            equipArmorIfEmpty(self, serverWorld, EquipmentSlot.LEGS, armorTier, random);
            equipArmorIfEmpty(self, serverWorld, EquipmentSlot.FEET, armorTier, random);
        }

        // For skeletons, ensure they have a bow in extreme mode
        if (self instanceof net.minecraft.entity.mob.SkeletonEntity) {
            if (self.getMainHandStack().isEmpty() || !self.getMainHandStack().isOf(Items.BOW)) {
                ItemStack bowStack = new ItemStack(Items.BOW);
                int weaponTier = rollTier(random);
                if (weaponTier != -1) {
                    int enchantLevel = getEnchantLevel(weaponTier);
                    ItemStack enchanted = EnchantmentHelper.enchant(random, bowStack, enchantLevel,
                            serverWorld.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT)
                                    .streamEntries()
                                    .map(entry -> (RegistryEntry<Enchantment>) entry));
                    self.equipStack(EquipmentSlot.MAINHAND, enchanted);
                } else {
                    self.equipStack(EquipmentSlot.MAINHAND, bowStack);
                }
            }
        } else if (self.getMainHandStack().isEmpty()
                && random.nextFloat() < EXTREME_WEAPON_CHANCE) {
            int weaponTier = rollTier(random);
            if (weaponTier != -1) {
                equipWeapon(self, serverWorld, weaponTier, random);
            }
        }
    }

    @Inject(method = "setTarget", at = @At("TAIL"))
    private void soullink$alertNearbyZombies(LivingEntity target, CallbackInfo ci) {
        MobEntity self = (MobEntity) (Object) this;
        if (!(self instanceof ZombieEntity) || soullink$skipSwarmAlert || target == null
                || !(target instanceof PlayerEntity)) {
            return;
        }

        if (!(self.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            return;
        }

        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        if (!runManager.isTemporaryWorld(serverWorld.getRegistryKey())) {
            return;
        }

        if (!Settings.getInstance().isSwarmIntelligenceEnabled()) {
            return;
        }

        long currentTick = serverWorld.getTime();
        if (currentTick
                - soullink$lastSwarmAlertTick < SoulLinkConstants.SWARM_INTELLIGENCE_COOLDOWN_TICKS) {
            return;
        }
        soullink$lastSwarmAlertTick = currentTick;

        Box searchBox = self.getBoundingBox().expand(SoulLinkConstants.SWARM_INTELLIGENCE_RANGE);
        java.util.List<ZombieEntity> nearbyZombies = serverWorld.getEntitiesByClass(
                ZombieEntity.class, searchBox, zombie -> zombie.isAlive() && zombie != self);
        for (ZombieEntity zombie : nearbyZombies) {
            if (zombie.getTarget() == target) {
                continue;
            }
            SwarmAlertAccessor accessor = (SwarmAlertAccessor) zombie;
            accessor.soullink$setSkipSwarmAlert(true);
            zombie.setTarget(target);
            accessor.soullink$setSkipSwarmAlert(false);
        }
    }

    private static void equipArmorIfEmpty(MobEntity mob, ServerWorld serverWorld,
            EquipmentSlot slot, int tier, Random random) {
        if (!mob.getEquippedStack(slot).isEmpty()) {
            return;
        }

        if (random.nextFloat() > EXTREME_ARMOR_PIECE_CHANCE) {
            return;
        }

        ItemStack stack = new ItemStack(getArmorItem(slot, tier));
        int enchantLevel = getEnchantLevel(tier);
        ItemStack enchanted = EnchantmentHelper.enchant(random, stack, enchantLevel,
                serverWorld.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT)
                        .streamEntries().map(entry -> (RegistryEntry<Enchantment>) entry));
        mob.equipStack(slot, enchanted);
    }

    private static void equipWeapon(MobEntity mob, ServerWorld serverWorld, int tier,
            Random random) {
        ItemStack stack = new ItemStack(getWeaponItem(tier));
        int enchantLevel = getEnchantLevel(tier);
        ItemStack enchanted = EnchantmentHelper.enchant(random, stack, enchantLevel,
                serverWorld.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT)
                        .streamEntries().map(entry -> (RegistryEntry<Enchantment>) entry));
        mob.equipStack(EquipmentSlot.MAINHAND, enchanted);
    }

    private static net.minecraft.item.Item getArmorItem(EquipmentSlot slot, int tier) {
        if (tier == TIER_NETHERITE) {
            return switch (slot) {
                case HEAD -> Items.NETHERITE_HELMET;
                case CHEST -> Items.NETHERITE_CHESTPLATE;
                case LEGS -> Items.NETHERITE_LEGGINGS;
                case FEET -> Items.NETHERITE_BOOTS;
                default -> Items.NETHERITE_HELMET;
            };
        }
        if (tier == TIER_DIAMOND) {
            return switch (slot) {
                case HEAD -> Items.DIAMOND_HELMET;
                case CHEST -> Items.DIAMOND_CHESTPLATE;
                case LEGS -> Items.DIAMOND_LEGGINGS;
                case FEET -> Items.DIAMOND_BOOTS;
                default -> Items.DIAMOND_HELMET;
            };
        }
        return switch (slot) {
            case HEAD -> Items.IRON_HELMET;
            case CHEST -> Items.IRON_CHESTPLATE;
            case LEGS -> Items.IRON_LEGGINGS;
            case FEET -> Items.IRON_BOOTS;
            default -> Items.IRON_HELMET;
        };
    }

    private static net.minecraft.item.Item getWeaponItem(int tier) {
        return switch (tier) {
            case TIER_NETHERITE -> Items.NETHERITE_SWORD;
            case TIER_DIAMOND -> Items.DIAMOND_SWORD;
            default -> Items.IRON_SWORD;
        };
    }

    private static int getEnchantLevel(int tier) {
        return switch (tier) {
            case TIER_NETHERITE -> EXTREME_NETHERITE_ENCHANT_LEVEL;
            case TIER_DIAMOND -> EXTREME_DIAMOND_ENCHANT_LEVEL;
            default -> EXTREME_IRON_ENCHANT_LEVEL;
        };
    }

    private static int rollTier(Random random) {
        float roll = random.nextFloat();
        if (roll < EXTREME_NETHERITE_CHANCE) {
            return TIER_NETHERITE;
        }
        if (roll < (EXTREME_NETHERITE_CHANCE + EXTREME_DIAMOND_CHANCE)) {
            return TIER_DIAMOND;
        }
        if (roll < (EXTREME_NETHERITE_CHANCE + EXTREME_DIAMOND_CHANCE + EXTREME_IRON_CHANCE)) {
            return TIER_IRON;
        }
        return -1;
    }
}
