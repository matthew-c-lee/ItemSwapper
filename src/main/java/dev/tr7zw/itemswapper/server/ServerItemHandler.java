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

            var recipes = player.level().recipeAccess().stonecutterRecipes();

            for (var entry : recipes.entries()) {
                var display = entry.recipe().optionDisplay();

                ItemStack recipeResult = display.resolveForFirstStack(
                        new net.minecraft.util.context.ContextMap.Builder()
                                .create(new net.minecraft.util.context.ContextKeySet.Builder().build())
                );

                if (recipeResult.isEmpty()) {
                    continue;
                }

                boolean forward = false;
                boolean reverse = false;

                ItemStack outputTemplate = ItemStack.EMPTY;
                int inputNeeded = 1;
                int outputPerCraft = 1;

                // Forward: input stack -> target output
                if (entry.input().test(inputStack) && recipeResult.getItem() == targetItem) {
                    forward = true;
                    outputTemplate = recipeResult.copy();
                    inputNeeded = 1;
                    outputPerCraft = recipeResult.getCount();
                }

                // Reverse: input stack is recipe output -> target is recipe input
                if (!forward
                        && recipeResult.getItem() == inputStack.getItem()
                        && entry.input().test(new ItemStack(targetItem))) {
                    reverse = true;
                    outputTemplate = new ItemStack(targetItem);
                    inputNeeded = recipeResult.getCount(); // e.g. 2 slabs -> 1 stone
                    outputPerCraft = 1;
                }

                if (!forward && !reverse) {
                    continue;
                }

                if (inputNeeded <= 0 || outputPerCraft <= 0) {
                    continue;
                }

                int maxOutputStackSize = outputTemplate.getMaxStackSize();
                int crafts = Math.min(
                        inputStack.getCount() / inputNeeded,
                        maxOutputStackSize / outputPerCraft
                );

                if (crafts <= 0) {
                    return;
                }

                int inputConsumed = crafts * inputNeeded;
                int outputProduced = crafts * outputPerCraft;
                int inputLeftover = inputStack.getCount() - inputConsumed;

                ItemStack outputStack = outputTemplate.copy();
                outputStack.setCount(outputProduced);

                ItemStack leftoverInput = ItemStack.EMPTY;
                if (inputLeftover > 0) {
                    leftoverInput = inputStack.copy();
                    leftoverInput.setCount(inputLeftover);
                }

                ItemStack displacedSelected = ItemStack.EMPTY;
                if (inputSlot != selectedSlot && oldSelectedStack != null && !oldSelectedStack.isEmpty()) {
                    displacedSelected = oldSelectedStack.copy();
                }

                if (!canFitStonecutterTransaction(player, selectedSlot, inputSlot, outputStack, leftoverInput,
                        displacedSelected)) {
                    System.out.println("SERVER: stonecutter swap blocked, no room for leftovers. leftoverInput="
                            + leftoverInput + ", displacedSelected=" + displacedSelected);
                    return;
                }

                // Commit transaction:
                // 1. Output goes into selected slot.
                player.getInventory().setItem(selectedSlot, outputStack);

                // 2. If the input came from somewhere else, clear that input slot.
                //    If inputSlot == selectedSlot, it was already replaced by outputStack.
                if (inputSlot != selectedSlot) {
                    player.getInventory().setItem(inputSlot, ItemStack.EMPTY);
                }

                // 3. Reinsert leftover input, if any.
                if (!leftoverInput.isEmpty()) {
                    boolean inserted = insertIntoInventoryExceptSelected(player, leftoverInput, selectedSlot);
                    if (!inserted) {
                        System.out.println("SERVER: unexpected insert failure for leftover input");
                        return;
                    }
                }

                // 4. Reinsert the old selected stack if the input came from another slot.
                if (!displacedSelected.isEmpty()) {
                    boolean inserted = insertIntoInventoryExceptSelected(player, displacedSelected, selectedSlot);
                    if (!inserted) {
                        System.out.println("SERVER: unexpected insert failure for displaced selected stack");
                        return;
                    }
                }

                player.inventoryMenu.broadcastChanges();

                System.out.println("SERVER: stonecutter swap executed: inputSlot="
                        + inputSlot
                        + ", selectedSlot=" + selectedSlot
                        + ", input=" + inputStack
                        + ", output=" + outputStack
                        + ", leftoverInput=" + leftoverInput
                        + ", displacedSelected=" + displacedSelected);

                return;
            }

            System.out.println("SERVER: no matching stonecutter recipe for target " + targetItem + " from " + inputStack);
        } catch (Throwable th) {
            network_logger.error("Error handling stonecutter swap packet!", th);
        }
    }

    private boolean canFitStonecutterTransaction(ServerPlayer player, int selectedSlot, int inputSlot,
                                                 ItemStack outputStack, ItemStack leftoverInput, ItemStack displacedSelected) {
        var realItems = InventoryUtil.getNonEquipmentItems(player.getInventory());
        java.util.List<ItemStack> simulated = new java.util.ArrayList<>();

        for (ItemStack stack : realItems) {
            simulated.add(stack.copy());
        }

        // Selected slot is reserved for the final output.
        simulated.set(selectedSlot, outputStack.copy());

        // If input came from another slot, clear it in the simulation.
        // That slot may then receive leftover input or the displaced selected item.
        if (inputSlot != selectedSlot) {
            simulated.set(inputSlot, ItemStack.EMPTY);
        }

        if (!insertIntoSimulatedInventoryExceptSelected(simulated, leftoverInput.copy(), selectedSlot)) {
            return false;
        }

        if (!insertIntoSimulatedInventoryExceptSelected(simulated, displacedSelected.copy(), selectedSlot)) {
            return false;
        }

        return true;
    }

    private boolean insertIntoSimulatedInventoryExceptSelected(java.util.List<ItemStack> items, ItemStack stack,
                                                               int selectedSlot) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }

        // First merge into existing compatible stacks.
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

        // Then place into empty non-equipment slots.
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

        // First merge into existing compatible stacks.
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

        // Then place into empty non-equipment slots only.
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

    private boolean isSame(ItemStack a, ItemStack b) {
        //? if < 1.17.0 {

        // return ItemStack.isSame(a, b);
        //? } else if <= 1.20.4 {

        /*return ItemStack.isSameItemSameTags(a, b);
         *///? } else {

        return ItemStack.isSameItemSameComponents(a, b);
        //? }
    }

}
