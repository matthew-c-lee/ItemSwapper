package dev.tr7zw.itemswapper.packets.serverbound;

import dev.tr7zw.itemswapper.ItemSwapperMod;
import dev.tr7zw.transition.loader.networking.*;
import dev.tr7zw.transition.mc.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.*;

/**
 * Payload for requesting a server-side stonecutter swap.
 *
 * @param targetItemId Raw item registry id for the item the player wants.
 */
public record StonecutterSwapPayload(int targetItemId) implements CustomPacketPayloadSupport {

    public static final StonecutterSwapPayload INSTANCE = new StonecutterSwapPayload(0);
    public static final Identifier ID = McId.create(ItemSwapperMod.MODID, "stonecutter_swap").id();

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(targetItemId);
    }

    @Override
    public CustomPacketPayloadSupport read(FriendlyByteBuf buffer) {
        return new StonecutterSwapPayload(buffer);
    }

    public StonecutterSwapPayload(FriendlyByteBuf buffer) {
        this(buffer.readVarInt());
    }

}