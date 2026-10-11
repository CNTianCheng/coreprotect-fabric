package net.coreprotect.fabric.util;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;

/**
 * Serializes an item stack together with its data components.
 *
 * <p>Container records used to keep only the item id and a summed count, so a rollback turned an
 * enchanted sword into a plain one and carried shulker box contents were lost; a swap between two
 * stacks of the same item (different enchantment, name or damage) produced no difference at all and
 * was never logged. The stack is now stored as SNBT in {@code co_container.nbt} and restored from
 * there.
 */
public final class ItemStackCodec {

    private ItemStackCodec() {
    }

    /**
     * Serializes {@code stack} without its count - item plus data components, which is what makes two
     * stacks of the same item different.
     *
     * @return the SNBT text, or {@code null} when the stack is empty or cannot be encoded
     */
    public static String encodeIdentity(ItemStack stack, RegistryWrapper.WrapperLookup registries) {
        if (stack == null || stack.isEmpty() || registries == null) {
            return null;
        }
        try {
            RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, registries);
            return ItemStack.UNCOUNTED_CODEC.encodeStart(ops, stack)
                    .result()
                    .filter(NbtCompound.class::isInstance)
                    .map(element -> NbtHelper.toNbtProviderString((NbtCompound) element))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Restores a stack written by {@link #encodeIdentity}. The returned stack always has a count of
     * one; callers scale it with {@link ItemStack#copyWithCount(int)}.
     *
     * @return the stack, or {@code null} when the text is missing or cannot be decoded
     */
    public static ItemStack decode(String snbt, RegistryWrapper.WrapperLookup registries) {
        if (snbt == null || snbt.isEmpty() || registries == null) {
            return null;
        }
        try {
            RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, registries);
            NbtCompound compound = NbtHelper.fromNbtProviderString(snbt);
            return ItemStack.CODEC.parse(ops, compound).result().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
