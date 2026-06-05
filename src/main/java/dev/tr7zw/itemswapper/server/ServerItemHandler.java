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
            ItemStack selected = InventoryUtil.getSelected(player.getInventory());

            if (selected == null || selected.isEmpty()) {
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

                // Forward: selected input -> target output
                if (entry.input().test(selected) && recipeResult.getItem() == targetItem) {
                    forward = true;
                    outputTemplate = recipeResult.copy();
                    inputNeeded = 1;
                    outputPerCraft = recipeResult.getCount();
                }

                // Reverse: selected output -> target input
                if (!forward
                        && recipeResult.getItem() == selected.getItem()
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
                        selected.getCount() / inputNeeded,
                        maxOutputStackSize / outputPerCraft
                );

                if (crafts <= 0) {
                    return;
                }

                int inputConsumed = crafts * inputNeeded;
                int outputProduced = crafts * outputPerCraft;
                int inputLeftover = selected.getCount() - inputConsumed;

                ItemStack outputStack = outputTemplate.copy();
                outputStack.setCount(outputProduced);

                ItemStack leftoverInput = ItemStack.EMPTY;
                if (inputLeftover > 0) {
                    leftoverInput = selected.copy();
                    leftoverInput.setCount(inputLeftover);
                }

                int selectedSlot = InventoryUtil.getSelectedId(player.getInventory());

                if (inputLeftover > 0 && !hasSpaceFor(player, leftoverInput, selectedSlot)) {
                    System.out.println("SERVER: stonecutter swap blocked, no room for leftover " + leftoverInput);
                    return;
                }

                player.getInventory().setItem(selectedSlot, outputStack);

                if (inputLeftover > 0) {
                    boolean inserted = insertIntoInventoryExceptSelected(player, leftoverInput, selectedSlot);
                    if (!inserted) {
                        // This should not happen because we checked before mutating.
                        // Do not drop items. Revert to safe state.
                        player.getInventory().setItem(selectedSlot, selected);
                        return;
                    }
                }

                player.inventoryMenu.broadcastChanges();

                System.out.println("SERVER: stonecutter swap executed: "
                        + selected + " -> " + outputStack
                        + ", leftover=" + leftoverInput);

                return;
            }

            System.out.println("SERVER: no matching stonecutter recipe for target " + targetItem + " from " + selected);
        } catch (Throwable th) {
            network_logger.error("Error handling stonecutter swap packet!", th);
        }
    }

    private boolean hasSpaceFor(ServerPlayer player, ItemStack stack, int selectedSlot) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }

        ItemStack testStack = stack.copy();
        return insertIntoInventoryExceptSelected(player, testStack, selectedSlot, true);
    }

    private boolean insertIntoInventoryExceptSelected(ServerPlayer player, ItemStack stack, int selectedSlot) {
        return insertIntoInventoryExceptSelected(player, stack, selectedSlot, false);
    }

    private boolean insertIntoInventoryExceptSelected(ServerPlayer player, ItemStack stack, int selectedSlot,
                                                      boolean simulate) {
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

            if (!simulate) {
                existing.grow(moved);
            }

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

            if (!simulate) {
                player.getInventory().setItem(i, stack.copy());
            }

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
