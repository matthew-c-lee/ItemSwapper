package dev.tr7zw.itemswapper.server;

import dev.tr7zw.itemswapper.config.*;
import dev.tr7zw.transition.config.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.tr7zw.itemswapper.packets.serverbound.RefillItemPayload;
import dev.tr7zw.itemswapper.packets.serverbound.SwapItemPayload;
import dev.tr7zw.itemswapper.util.ShulkerHelper;
import dev.tr7zw.transition.mc.InventoryUtil;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import dev.tr7zw.itemswapper.packets.serverbound.StonecutterSwapPayload;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

public class ServerItemHandler {

    private static final Logger network_logger = LogManager.getLogger("ItemSwapper-Network");
    private static final ConfigManager<Config> configManager = ConfigHolder.getInstance().getGeneral();

    public void swapItem(ServerPlayer player, SwapItemPayload payload) {
        if (configManager.getConfig().disableShulkers) {
            // no refill allowed
            return;
        }
        try {
            if (ShulkerHelper.isShulker(InventoryUtil.getSelected(player.getInventory()).getItem())) {
                // Don't try to put a shulker into another shulker
                return;
            }
            ItemStack shulker = player.getInventory().getItem(payload.inventorySlot());
            NonNullList<ItemStack> content = ShulkerHelper.getItems(shulker);
            if (content != null) {
                ItemStack tmp = content.get(payload.slot());
                content.set(payload.slot(), InventoryUtil.getSelected(player.getInventory()));
                player.getInventory().setItem(InventoryUtil.getSelectedId(player.getInventory()), tmp);
                ShulkerHelper.setItem(shulker, content);
            }
        } catch (Throwable th) {
            network_logger.error("Error handeling network packet!", th);
        }
    }

    public void refillSlot(ServerPlayer player, RefillItemPayload payload) {
        if (configManager.getConfig().disableShulkers) {
            // no refill allowed
            return;
        }
        try {
            ItemStack target = player.getInventory().getItem(payload.slot());
            if (target == null || target.isEmpty()) {
                return;
            }
            int space = target.getMaxStackSize() - target.getCount();
            if (space <= 0) {
                // nothing to do
                return;
            }
            for (int i = 0; i < InventoryUtil.getNonEquipmentItems(player.getInventory()).size(); i++) {
                ItemStack shulker = InventoryUtil.getNonEquipmentItems(player.getInventory()).get(i);
                NonNullList<ItemStack> content = ShulkerHelper.getItems(shulker);
                if (content != null) {
                    boolean boxChanged = false;
                    for (int entry = 0; entry < content.size(); entry++) {
                        ItemStack boxItem = content.get(entry);
                        if (isSame(boxItem, target)) {
                            // same, use to restock
                            int amount = Math.min(space, boxItem.getCount());
                            target.setCount(target.getCount() + amount);
                            boxItem.setCount(boxItem.getCount() - amount);
                            space -= amount;
                            boxChanged = true;
                            if (space <= 0) {
                                break;
                            }
                        }
                    }
                    if (boxChanged) {
                        ShulkerHelper.setItem(shulker, content);
                    }
                }
            }
        } catch (Throwable th) {
            network_logger.error("Error handeling network packet!", th);
        }
    }

    public void stonecutterSwap(ServerPlayer player, StonecutterSwapPayload payload) {
        try {
            Item targetItem = Item.byId(payload.targetItemId());

            int selectedSlot = InventoryUtil.getSelectedId(player.getInventory());
            int inputSlot = payload.inputSlot();

            var items = InventoryUtil.getNonEquipmentItems(player.getInventory());

            if (inputSlot < 0 || inputSlot >= items.size()) {
                System.out.println("SERVER: invalid stonecutter input slot " + inputSlot);
                return;
            }

            ItemStack oldSelectedStack = items.get(selectedSlot);
            ItemStack inputStack = items.get(inputSlot);

            if (inputStack == null || inputStack.isEmpty()) {
                return;
            }

            List<StonecutterStep> path = findStonecutterPath(player, inputStack.getItem(), targetItem);

            if (path.isEmpty()) {
                System.out.println("SERVER: no stonecutter path for target " + targetItem + " from " + inputStack);
                return;
            }

            StonecutterPlan plan = calculateStonecutterPlan(inputStack, path);

            if (plan == null || plan.outputStack().isEmpty()) {
                System.out.println("SERVER: could not calculate stonecutter plan for target " + targetItem
                        + " from " + inputStack);
                return;
            }

            ItemStack displacedSelected = ItemStack.EMPTY;
            if (inputSlot != selectedSlot && oldSelectedStack != null && !oldSelectedStack.isEmpty()) {
                displacedSelected = oldSelectedStack.copy();
            }

            if (!canFitStonecutterTransaction(player, selectedSlot, inputSlot, plan.leftoverInput(),
                    plan.leftoverIntermediate(), displacedSelected, plan.outputStack())) {
                System.out.println("SERVER: stonecutter swap blocked, no room for leftovers. leftoverInput="
                        + plan.leftoverInput()
                        + ", leftoverIntermediate=" + plan.leftoverIntermediate()
                        + ", displacedSelected=" + displacedSelected);
                return;
            }

            // Snapshot before mutating, so unexpected insert failures cannot delete items.
            List<ItemStack> before = new ArrayList<>();
            for (ItemStack stack : items) {
                before.add(stack.copy());
            }

            // Commit transaction:
            // 1. Final output always goes into selected slot.
            player.getInventory().setItem(selectedSlot, plan.outputStack().copy());

            // 2. If input came from another slot, clear that input slot.
            //    Leftover original input is reinserted below.
            if (inputSlot != selectedSlot) {
                player.getInventory().setItem(inputSlot, ItemStack.EMPTY);
            }

            // 3. Reinsert leftover original input.
            if (!plan.leftoverInput().isEmpty()) {
                boolean inserted = insertIntoInventoryExceptSelected(player, plan.leftoverInput().copy(), selectedSlot);
                if (!inserted) {
                    restoreNonEquipmentInventory(player, before);
                    player.inventoryMenu.broadcastChanges();
                    System.out.println("SERVER: unexpected insert failure for leftover input");
                    return;
                }
            }

            // 4. Reinsert leftover intermediate item, if a two-hop path created extra intermediate.
            if (!plan.leftoverIntermediate().isEmpty()) {
                boolean inserted = insertIntoInventoryExceptSelected(player, plan.leftoverIntermediate().copy(), selectedSlot);
                if (!inserted) {
                    restoreNonEquipmentInventory(player, before);
                    player.inventoryMenu.broadcastChanges();
                    System.out.println("SERVER: unexpected insert failure for leftover intermediate");
                    return;
                }
            }

            // 5. Reinsert the old selected stack if the input came from another slot.
            if (!displacedSelected.isEmpty()) {
                boolean inserted = insertIntoInventoryExceptSelected(player, displacedSelected.copy(), selectedSlot);
                if (!inserted) {
                    restoreNonEquipmentInventory(player, before);
                    player.inventoryMenu.broadcastChanges();
                    System.out.println("SERVER: unexpected insert failure for displaced selected stack");
                    return;
                }
            }

            player.inventoryMenu.broadcastChanges();

            System.out.println("SERVER: stonecutter swap executed: inputSlot="
                    + inputSlot
                    + ", selectedSlot=" + selectedSlot
                    + ", input=" + inputStack
                    + ", path=" + path
                    + ", output=" + plan.outputStack()
                    + ", leftoverInput=" + plan.leftoverInput()
                    + ", leftoverIntermediate=" + plan.leftoverIntermediate()
                    + ", displacedSelected=" + displacedSelected);
        } catch (Throwable th) {
            network_logger.error("Error handling stonecutter swap packet!", th);
        }
    }

    private List<StonecutterStep> findStonecutterPath(ServerPlayer player, Item inputItem, Item targetItem) {
        List<StonecutterStep> directSteps = findSteps(player, inputItem, targetItem);

        if (!directSteps.isEmpty()) {
            return List.of(directSteps.get(0));
        }

        List<StonecutterStep> firstSteps = findStepsFrom(player, inputItem);

        for (StonecutterStep first : firstSteps) {
            List<StonecutterStep> secondSteps = findSteps(player, first.outputItem(), targetItem);

            if (!secondSteps.isEmpty()) {
                return List.of(first, secondSteps.get(0));
            }
        }

        return List.of();
    }

    private List<StonecutterStep> findStepsFrom(ServerPlayer player, Item inputItem) {
        List<StonecutterStep> steps = new ArrayList<>();

        var recipes = player.level().recipeAccess().stonecutterRecipes();

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

    private List<StonecutterStep> findSteps(ServerPlayer player, Item inputItem, Item targetItem) {
        List<StonecutterStep> steps = new ArrayList<>();

        var recipes = player.level().recipeAccess().stonecutterRecipes();

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

    private ItemStack resolveRecipeResult(Object entry) {
        var recipeEntry = (net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry<?>) entry;
        var display = recipeEntry.recipe().optionDisplay();

        return display.resolveForFirstStack(
                new net.minecraft.util.context.ContextMap.Builder()
                        .create(new net.minecraft.util.context.ContextKeySet.Builder().build())
        );
    }

    private boolean canFitStonecutterTransaction(ServerPlayer player, int selectedSlot, int inputSlot,
                                                 ItemStack leftoverInput, ItemStack leftoverIntermediate, ItemStack displacedSelected, ItemStack outputStack) {
        var realItems = InventoryUtil.getNonEquipmentItems(player.getInventory());
        List<ItemStack> simulated = new ArrayList<>();

        for (ItemStack stack : realItems) {
            simulated.add(stack.copy());
        }

        simulated.set(selectedSlot, outputStack.copy());

        if (inputSlot != selectedSlot) {
            simulated.set(inputSlot, ItemStack.EMPTY);
        }

        if (!insertIntoSimulatedInventoryExceptSelected(simulated, leftoverInput.copy(), selectedSlot)) {
            return false;
        }

        if (!insertIntoSimulatedInventoryExceptSelected(simulated, leftoverIntermediate.copy(), selectedSlot)) {
            return false;
        }

        if (!insertIntoSimulatedInventoryExceptSelected(simulated, displacedSelected.copy(), selectedSlot)) {
            return false;
        }

        return true;
    }

    private boolean insertIntoSimulatedInventoryExceptSelected(List<ItemStack> items, ItemStack stack, int selectedSlot) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }

        for (int i = 0; i < items.size(); i++) {
            if (i == selectedSlot) {
                continue;
            }

            ItemStack existing = items.get(i);

            if (existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }

            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, stack.getCount());
            existing.grow(moved);
            stack.shrink(moved);

            if (stack.isEmpty()) {
                return true;
            }
        }

        for (int i = 0; i < items.size(); i++) {
            if (i == selectedSlot) {
                continue;
            }

            ItemStack existing = items.get(i);

            if (!existing.isEmpty()) {
                continue;
            }

            items.set(i, stack.copy());
            stack.setCount(0);
            return true;
        }

        return stack.isEmpty();
    }

    private boolean insertIntoInventoryExceptSelected(ServerPlayer player, ItemStack stack, int selectedSlot) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }

        var items = InventoryUtil.getNonEquipmentItems(player.getInventory());

        for (int i = 0; i < items.size(); i++) {
            if (i == selectedSlot) {
                continue;
            }

            ItemStack existing = items.get(i);

            if (existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }

            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, stack.getCount());
            existing.grow(moved);
            stack.shrink(moved);

            if (stack.isEmpty()) {
                return true;
            }
        }

        for (int i = 0; i < items.size(); i++) {
            if (i == selectedSlot) {
                continue;
            }

            ItemStack existing = items.get(i);

            if (!existing.isEmpty()) {
                continue;
            }

            player.getInventory().setItem(i, stack.copy());
            stack.setCount(0);
            return true;
        }

        return stack.isEmpty();
    }

    private StonecutterPlan calculateStonecutterPlan(ItemStack inputStack, List<StonecutterStep> path) {
        if (path.isEmpty()) {
            return null;
        }

        if (path.size() == 1) {
            StonecutterStep step = path.get(0);

            int crafts = Math.min(
                    inputStack.getCount() / step.inputCount(),
                    step.outputItem().getDefaultInstance().getMaxStackSize() / step.outputCount()
            );

            if (crafts <= 0) {
                return null;
            }

            int inputConsumed = crafts * step.inputCount();
            int outputProduced = crafts * step.outputCount();
            int inputLeftover = inputStack.getCount() - inputConsumed;

            ItemStack outputStack = new ItemStack(step.outputItem(), outputProduced);

            ItemStack leftoverInput = ItemStack.EMPTY;
            if (inputLeftover > 0) {
                leftoverInput = inputStack.copy();
                leftoverInput.setCount(inputLeftover);
            }

            return new StonecutterPlan(outputStack, leftoverInput, ItemStack.EMPTY);
        }

        if (path.size() == 2) {
            StonecutterStep first = path.get(0);
            StonecutterStep second = path.get(1);

            int maxFirstCrafts = inputStack.getCount() / first.inputCount();

            if (maxFirstCrafts <= 0) {
                return null;
            }

            int maxIntermediateProduced = maxFirstCrafts * first.outputCount();

            int maxSecondCraftsByIntermediate = maxIntermediateProduced / second.inputCount();
            int maxSecondCraftsByOutputStack = second.outputItem().getDefaultInstance().getMaxStackSize()
                    / second.outputCount();

            int secondCrafts = Math.min(maxSecondCraftsByIntermediate, maxSecondCraftsByOutputStack);

            if (secondCrafts <= 0) {
                return null;
            }

            // Use the minimum number of first-step crafts required to supply the second step.
            int intermediateNeeded = secondCrafts * second.inputCount();
            int firstCrafts = divideRoundUp(intermediateNeeded, first.outputCount());

            int inputConsumed = firstCrafts * first.inputCount();
            int intermediateProduced = firstCrafts * first.outputCount();
            int intermediateLeftover = intermediateProduced - intermediateNeeded;
            int outputProduced = secondCrafts * second.outputCount();
            int inputLeftover = inputStack.getCount() - inputConsumed;

            if (inputConsumed <= 0 || inputConsumed > inputStack.getCount()) {
                return null;
            }

            ItemStack outputStack = new ItemStack(second.outputItem(), outputProduced);

            ItemStack leftoverInput = ItemStack.EMPTY;
            if (inputLeftover > 0) {
                leftoverInput = inputStack.copy();
                leftoverInput.setCount(inputLeftover);
            }

            ItemStack leftoverIntermediate = ItemStack.EMPTY;
            if (intermediateLeftover > 0) {
                leftoverIntermediate = new ItemStack(first.outputItem(), intermediateLeftover);
            }

            return new StonecutterPlan(outputStack, leftoverInput, leftoverIntermediate);
        }

        return null;
    }

    private void restoreNonEquipmentInventory(ServerPlayer player, List<ItemStack> snapshot) {
        for (int i = 0; i < snapshot.size(); i++) {
            player.getInventory().setItem(i, snapshot.get(i).copy());
        }
    }

    private int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private boolean isSame(ItemStack a, ItemStack b) {
        //? if < 1.17.0 {

        // return ItemStack.isSame(a, b);
        //? } else if <= 1.20.4 {

        /*return ItemStack.isSameItemSameTags(a, b);
         *///? } else {

        return ItemStack.isSameItemSameComponents(a, b);
        //? }
    }

    private record StonecutterStep(Item inputItem, Item outputItem, int inputCount, int outputCount) {
    }

    private record StonecutterPlan(ItemStack outputStack, ItemStack leftoverInput, ItemStack leftoverIntermediate) {
    }

}
