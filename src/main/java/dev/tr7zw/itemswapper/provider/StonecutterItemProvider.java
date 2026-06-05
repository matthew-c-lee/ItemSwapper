package dev.tr7zw.itemswapper.provider;

import java.util.Collections;
import java.util.List;

import dev.tr7zw.itemswapper.api.AvailableSlot;
import dev.tr7zw.itemswapper.api.client.ItemProvider;
import dev.tr7zw.transition.mc.InventoryUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.context.ContextMap;
import net.minecraft.util.context.ContextKeySet;

public class StonecutterItemProvider implements ItemProvider {

    public static final int STONECUTTER_INVENTORY_ID = -1000;

    private final Minecraft minecraft = Minecraft.getInstance();

    @Override
    public List<AvailableSlot> findSlotsMatchingItem(Item item, boolean limit) {
        if (minecraft.player == null) {
            return Collections.emptyList();
        }

        ItemStack selected = InventoryUtil.getSelected(minecraft.player.getInventory());

        if (selected == null || selected.isEmpty()) {
            return Collections.emptyList();
        }

        // First spike: hardcode Stone -> Stone Slab.
        // Later this becomes recipe lookup.
        var recipes = minecraft.level.recipeAccess().stonecutterRecipes();

        for (var entry : recipes.entries()) {
            var display = entry.recipe().optionDisplay();

            ItemStack recipeResult = display.resolveForFirstStack(
                    new ContextMap.Builder().create(new ContextKeySet.Builder().build())
            );

            if (recipeResult.isEmpty()) {
                continue;
            }

            // Forward: selected input -> target output
            if (entry.input().test(selected) && recipeResult.getItem() == item) {
                int outputPerCraft = recipeResult.getCount();
                int maxStackSize = recipeResult.getMaxStackSize();

                int crafts = Math.min(selected.getCount(), maxStackSize / outputPerCraft);
                if (crafts <= 0) {
                    return Collections.emptyList();
                }

                ItemStack result = recipeResult.copy();
                result.setCount(crafts * outputPerCraft);
                return List.of(new AvailableSlot(STONECUTTER_INVENTORY_ID, 0, result));
            }

            // Reverse: selected output -> target input
            if (recipeResult.getItem() == selected.getItem()
                    && entry.input().test(new ItemStack(item))) {

                int inputNeeded = recipeResult.getCount(); // e.g. 2 slabs -> 1 stone
                int outputPerCraft = 1;

                int crafts = Math.min(
                        selected.getCount() / inputNeeded,
                        item.getDefaultInstance().getMaxStackSize() / outputPerCraft
                );

                if (crafts <= 0) {
                    return Collections.emptyList();
                }

                ItemStack result = new ItemStack(item, crafts * outputPerCraft);
                return List.of(new AvailableSlot(STONECUTTER_INVENTORY_ID, 0, result));
            }
        }

        return Collections.emptyList();
    }

}