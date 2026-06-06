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

import java.util.ArrayList;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

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

        List<StonecutterStep> directSteps = findSteps(inputStack.getItem(), targetItem);

        for (StonecutterStep step : directSteps) {
            ItemStack result = calculatePathResult(inputStack, List.of(step));
            if (!result.isEmpty()) {
                return new AvailableSlot(STONECUTTER_INVENTORY_ID, inputSlot, result);
            }
        }

        List<StonecutterStep> firstSteps = findStepsFrom(inputStack.getItem());

        for (StonecutterStep first : firstSteps) {
            List<StonecutterStep> secondSteps = findSteps(first.outputItem(), targetItem);

            for (StonecutterStep second : secondSteps) {
                ItemStack result = calculatePathResult(inputStack, List.of(first, second));
                if (!result.isEmpty()) {
                    return new AvailableSlot(STONECUTTER_INVENTORY_ID, inputSlot, result);
                }
            }
        }

        return null;
    }

    private List<StonecutterStep> findStepsFrom(Item inputItem) {
        List<StonecutterStep> steps = new ArrayList<>();

        var recipes = minecraft.level.recipeAccess().stonecutterRecipes();

        for (var entry : recipes.entries()) {
            ItemStack recipeResult = resolveRecipeResult(entry);

            if (recipeResult.isEmpty()) {
                continue;
            }

            // Forward: concrete input item -> recipe result.
            if (entry.input().test(new ItemStack(inputItem))) {
                steps.add(new StonecutterStep(
                        inputItem,
                        recipeResult.getItem(),
                        1,
                        recipeResult.getCount()
                ));
            }

            // Reverse: recipe result -> every concrete item accepted by recipe input.
            //
            // Example:
            // recipe: stone -> 2 stone_slab
            // inputItem: stone_slab
            //
            // This adds:
            // stone_slab -> stone
            if (recipeResult.getItem() == inputItem) {
                for (Item candidateInput : BuiltInRegistries.ITEM) {
                    if (candidateInput == Items.AIR) {
                        continue;
                    }

                    if (!entry.input().test(new ItemStack(candidateInput))) {
                        continue;
                    }

                    steps.add(new StonecutterStep(
                            inputItem,
                            candidateInput,
                            recipeResult.getCount(),
                            1
                    ));
                }
            }
        }

        return steps;
    }

    private List<StonecutterStep> findSteps(Item inputItem, Item targetItem) {
        List<StonecutterStep> steps = new ArrayList<>();

        var recipes = minecraft.level.recipeAccess().stonecutterRecipes();

        for (var entry : recipes.entries()) {
            ItemStack recipeResult = resolveRecipeResult(entry);

            if (recipeResult.isEmpty()) {
                continue;
            }

            // Forward: concrete input item -> recipe result.
            if (entry.input().test(new ItemStack(inputItem)) && recipeResult.getItem() == targetItem) {
                steps.add(new StonecutterStep(
                        inputItem,
                        targetItem,
                        1,
                        recipeResult.getCount()
                ));
            }

            // Reverse: recipe result -> concrete target item.
            if (recipeResult.getItem() == inputItem && entry.input().test(new ItemStack(targetItem))) {
                steps.add(new StonecutterStep(
                        inputItem,
                        targetItem,
                        recipeResult.getCount(),
                        1
                ));
            }
        }

        return steps;
    }

    private ItemStack calculatePathResult(ItemStack inputStack, List<StonecutterStep> path) {
        int count = inputStack.getCount();
        Item currentItem = inputStack.getItem();

        for (StonecutterStep step : path) {
            if (step.inputItem() != currentItem) {
                return ItemStack.EMPTY;
            }

            int crafts = count / step.inputCount();
            if (crafts <= 0) {
                return ItemStack.EMPTY;
            }

            count = crafts * step.outputCount();
            currentItem = step.outputItem();

            int maxStackSize = currentItem.getDefaultInstance().getMaxStackSize();
            if (count > maxStackSize) {
                count = maxStackSize;
            }
        }

        return new ItemStack(currentItem, count);
    }

    private ItemStack resolveRecipeResult(Object entry) {
        var recipeEntry = (net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry<?>) entry;
        var display = recipeEntry.recipe().optionDisplay();

        return display.resolveForFirstStack(
                new ContextMap.Builder().create(new ContextKeySet.Builder().build())
        );
    }

    private record StonecutterStep(Item inputItem, Item outputItem, int inputCount, int outputCount) {
    }

}