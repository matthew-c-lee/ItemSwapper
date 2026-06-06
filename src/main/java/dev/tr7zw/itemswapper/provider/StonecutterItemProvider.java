package dev.tr7zw.itemswapper.provider;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dev.tr7zw.itemswapper.api.AvailableSlot;
import dev.tr7zw.itemswapper.api.client.ItemProvider;
import dev.tr7zw.transition.mc.InventoryUtil;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

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

        // Direct target is a cooking boundary, so don't offer it.
        if (isUnsafeStonecutterStep(inputStack.getItem(), targetItem)) {
            return null;
        }

        // One-hop direct path.
        List<StonecutterStep> directSteps = findSteps(inputStack.getItem(), targetItem);

        for (StonecutterStep step : directSteps) {
            ItemStack result = calculatePathResult(inputStack, List.of(step));
            if (!result.isEmpty()) {
                return new AvailableSlot(STONECUTTER_INVENTORY_ID, inputSlot, result);
            }
        }

        // Two-hop path: input -> intermediate -> target.
        List<StonecutterStep> firstSteps = findStepsFrom(inputStack.getItem());

        for (StonecutterStep first : firstSteps) {
            if (isUnsafeStonecutterStep(first.inputItem(), first.outputItem())) {
                continue;
            }

            List<StonecutterStep> secondSteps = findSteps(first.outputItem(), targetItem);

            for (StonecutterStep second : secondSteps) {
                if (isUnsafeStonecutterStep(second.inputItem(), second.outputItem())) {
                    continue;
                }

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
            if (entry.input().test(new ItemStack(inputItem))
                    && !isUnsafeStonecutterStep(inputItem, recipeResult.getItem())) {
                steps.add(new StonecutterStep(
                        inputItem,
                        recipeResult.getItem(),
                        1,
                        recipeResult.getCount()
                ));
            }

            // Reverse: recipe result -> every concrete item accepted by recipe input.
            if (recipeResult.getItem() == inputItem) {
                for (Item candidateInput : BuiltInRegistries.ITEM) {
                    if (candidateInput == Items.AIR) {
                        continue;
                    }

                    if (!entry.input().test(new ItemStack(candidateInput))) {
                        continue;
                    }

                    if (!isUnsafeStonecutterStep(inputItem, candidateInput)) {
                        steps.add(new StonecutterStep(
                                inputItem,
                                candidateInput,
                                recipeResult.getCount(),
                                1
                        ));
                    }
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
            if (entry.input().test(new ItemStack(inputItem))
                    && recipeResult.getItem() == targetItem
                    && !isUnsafeStonecutterStep(inputItem, targetItem)) {
                steps.add(new StonecutterStep(
                        inputItem,
                        targetItem,
                        1,
                        recipeResult.getCount()
                ));
            }

            // Reverse: recipe result -> concrete target item.
            if (recipeResult.getItem() == inputItem
                    && entry.input().test(new ItemStack(targetItem))
                    && !isUnsafeStonecutterStep(inputItem, targetItem)) {
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

        return display.resolveForFirstStack(createDisplayContext());
    }

    private boolean isUnsafeStonecutterStep(Item inputItem, Item outputItem) {
        // Direct material-processing boundary:
        // cobblestone <-> stone
        // stone <-> smooth_stone
        if (isUnsafeCookingBoundary(inputItem, outputItem)) {
            return true;
        }

        // If outputItem is a stonecutter variant that can also be produced
        // from another item which cooks into inputItem, then this step is unsafe.
        //
        // Example:
        // inputItem = stone
        // outputItem = cobblestone_stairs
        //
        // cobblestone can produce cobblestone_stairs
        // cobblestone cooks into stone
        // Therefore stone -> cobblestone_stairs is unsafe.
        for (Item alternateProducer : findStonecutterProducers(outputItem)) {
            if (alternateProducer == inputItem) {
                continue;
            }

            if (hasCookingRecipe(alternateProducer, inputItem)) {
                return true;
            }
        }

        // Same idea in reverse.
        //
        // Example:
        // inputItem = cobblestone_stairs
        // outputItem = stone
        //
        // cobblestone can produce cobblestone_stairs
        // cobblestone cooks into stone
        // Therefore cobblestone_stairs -> stone is unsafe.
        for (Item alternateProducer : findStonecutterProducers(inputItem)) {
            if (alternateProducer == outputItem) {
                continue;
            }

            if (hasCookingRecipe(alternateProducer, outputItem)) {
                return true;
            }
        }

        return false;
    }

    private List<Item> findStonecutterProducers(Item resultItem) {
        List<Item> producers = new ArrayList<>();

        var recipes = minecraft.level.recipeAccess().stonecutterRecipes();

        for (var entry : recipes.entries()) {
            ItemStack recipeResult = resolveRecipeResult(entry);

            if (recipeResult.isEmpty() || recipeResult.getItem() != resultItem) {
                continue;
            }

            for (Item candidateInput : BuiltInRegistries.ITEM) {
                if (candidateInput == Items.AIR) {
                    continue;
                }

                if (entry.input().test(new ItemStack(candidateInput))) {
                    producers.add(candidateInput);
                }
            }
        }

        return producers;
    }

    private boolean isUnsafeCookingBoundary(Item a, Item b) {
        return hasCookingRecipe(a, b) || hasCookingRecipe(b, a);
    }

    private boolean hasCookingRecipe(Item inputItem, Item outputItem) {
        ContextMap context = createDisplayContext();

        for (RecipeDisplayEntry entry : getKnownRecipeDisplays().values()) {
            if (!(entry.display() instanceof FurnaceRecipeDisplay furnaceDisplay)) {
                continue;
            }

            boolean inputMatches = false;

            for (ItemStack ingredientStack : furnaceDisplay.ingredient().resolveForStacks(context)) {
                if (!ingredientStack.isEmpty() && ingredientStack.getItem() == inputItem) {
                    inputMatches = true;
                    break;
                }
            }

            if (!inputMatches) {
                continue;
            }

            for (ItemStack resultStack : entry.resultItems(context)) {
                if (!resultStack.isEmpty() && resultStack.getItem() == outputItem) {
                    return true;
                }
            }
        }

        return false;
    }

    private ContextMap createDisplayContext() {
        return new ContextMap.Builder().create(new ContextKeySet.Builder().build());
    }

    @SuppressWarnings("unchecked")
    private Map<?, RecipeDisplayEntry> getKnownRecipeDisplays() {
        try {
            ClientRecipeBook recipeBook = minecraft.player.getRecipeBook();

            Field knownField = ClientRecipeBook.class.getDeclaredField("known");
            knownField.setAccessible(true);

            return (Map<?, RecipeDisplayEntry>) knownField.get(recipeBook);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return Map.of();
        }
    }

    private record StonecutterStep(Item inputItem, Item outputItem, int inputCount, int outputCount) {
    }

}