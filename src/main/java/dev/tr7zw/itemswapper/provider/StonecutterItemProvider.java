package dev.tr7zw.itemswapper.provider;

import java.util.Collections;
import java.util.List;

import dev.tr7zw.itemswapper.api.AvailableSlot;
import dev.tr7zw.itemswapper.api.client.ItemProvider;
import dev.tr7zw.transition.mc.InventoryUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class StonecutterItemProvider implements ItemProvider {

    public static final int STONECUTTER_INVENTORY_ID = -1000;

    private final Minecraft minecraft = Minecraft.getInstance();

    @Override
    public List<AvailableSlot> findSlotsMatchingItem(Item item, boolean limit) {
        if (minecraft.player == null || minecraft.level == null) {
            return Collections.emptyList();
        }

        var items = InventoryUtil.getNonEquipmentItems(minecraft.player.getInventory());
        int selectedSlot = InventoryUtil.getSelectedId(minecraft.player.getInventory());

        // Prefer the currently selected slot.
        if (selectedSlot >= 0 && selectedSlot < items.size()) {
            AvailableSlot selectedResult = findStonecutterSlot(item, items.get(selectedSlot), selectedSlot);
            if (selectedResult != null) {
                return List.of(selectedResult);
            }
        }

        // Then search the rest of the normal non-equipment inventory.
        for (int i = 0; i < items.size(); i++) {
            if (i == selectedSlot) {
                continue;
            }

            AvailableSlot result = findStonecutterSlot(item, items.get(i), i);
            if (result != null) {
                return List.of(result);
            }
        }

        return Collections.emptyList();
    }

    private AvailableSlot findStonecutterSlot(Item targetItem, ItemStack inputStack, int inputSlot) {
        if (inputStack == null || inputStack.isEmpty()) {
            return null;
        }

        var recipes = minecraft.level.recipeAccess().stonecutterRecipes();

        for (var entry : recipes.entries()) {
            var display = entry.recipe().optionDisplay();

            ItemStack recipeResult = display.resolveForFirstStack(
                    new ContextMap.Builder().create(new ContextKeySet.Builder().build())
            );

            if (recipeResult.isEmpty()) {
                continue;
            }

            // Forward: input stack -> target output
            if (entry.input().test(inputStack) && recipeResult.getItem() == targetItem) {
                int outputPerCraft = recipeResult.getCount();
                if (outputPerCraft <= 0) {
                    continue;
                }

                int maxStackSize = recipeResult.getMaxStackSize();

                int crafts = Math.min(
                        inputStack.getCount(),
                        maxStackSize / outputPerCraft
                );

                if (crafts <= 0) {
                    return null;
                }

                ItemStack result = recipeResult.copy();
                result.setCount(crafts * outputPerCraft);
                return new AvailableSlot(STONECUTTER_INVENTORY_ID, inputSlot, result);
            }

            // Reverse: input stack is recipe output -> target is recipe input
            if (recipeResult.getItem() == inputStack.getItem()
                    && entry.input().test(new ItemStack(targetItem))) {
                int inputNeeded = recipeResult.getCount(); // e.g. 2 slabs -> 1 stone
                int outputPerCraft = 1;

                if (inputNeeded <= 0) {
                    continue;
                }

                int crafts = Math.min(
                        inputStack.getCount() / inputNeeded,
                        targetItem.getDefaultInstance().getMaxStackSize() / outputPerCraft
                );

                if (crafts <= 0) {
                    return null;
                }

                ItemStack result = new ItemStack(targetItem, crafts * outputPerCraft);
                return new AvailableSlot(STONECUTTER_INVENTORY_ID, inputSlot, result);
            }
        }

        return null;
    }

}