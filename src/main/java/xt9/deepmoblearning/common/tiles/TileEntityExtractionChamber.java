package xt9.deepmoblearning.common.tiles;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.*;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.IInteractionObject;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import xt9.deepmoblearning.DeepConstants;
import xt9.deepmoblearning.common.Registry;
import xt9.deepmoblearning.common.config.Config;
import xt9.deepmoblearning.common.energy.DeepEnergyStorage;
import xt9.deepmoblearning.common.handlers.BaseItemHandler;
import xt9.deepmoblearning.common.handlers.OutputHandler;
import xt9.deepmoblearning.common.handlers.PristineHandler;
import xt9.deepmoblearning.common.inventory.ContainerExtractionChamber;
import xt9.deepmoblearning.common.inventory.ContainerSimulationChamber;
import xt9.deepmoblearning.common.items.ItemPristineMatter;
import xt9.deepmoblearning.common.util.MathHelper;
import xt9.deepmoblearning.common.util.Pagination;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Created by xt9 on 2017-06-15.
 */
public class TileEntityExtractionChamber extends TileEntity implements ITickable, IInteractionObject {
    private BaseItemHandler pristine = new PristineHandler();
    private BaseItemHandler output = new OutputHandler(16);
    private DeepEnergyStorage energyStorage = new DeepEnergyStorage(1000000, 25600 , 0, 0);

    public boolean isCrafting = false;
    public int energy = 0;
    public int ticks = 0;
    public int percentDone = 0;
    public Pagination pageHandler = new Pagination(0, getLootFromPristine().size(), 9);
    private String currentPristineMatter = "";
    public ItemStack resultingItem = ItemStack.EMPTY;
    public int energyCost = MathHelper.ensureRange(Config.rfCostExtractionChamber, 1, 18000);

    public TileEntityExtractionChamber() {
        super(Registry.tileExtractionChamber);
    }

    @Override
    public void tick() {
        ticks++;

        if(!world.isRemote) {
            if(pristineChanged()) {
                finishCraft(true);
                updatePageHandler(0);

                currentPristineMatter = ((ItemPristineMatter) getPristine().getItem()).getMobKey();
                resultingItem = ItemStack.EMPTY;
                updateState();
                return;
            }

            if (!isCrafting) {
                if (canStartCraft()) {
                    isCrafting = true;
                }
            } else {
                if (!canContinueCraft()) {
                    finishCraft(true);
                    return;
                }

                if(hasEnergyForNextTick()) {
                    energyStorage.voidEnergy(energyCost);
                    percentDone++;
                }

                // Notify while crafting every 5sec, this is done more frequently when the container is open
                if (ticks % (DeepConstants.TICKS_TO_SECOND * 5) == 0) {
                    updateState();
                }

                if (percentDone == 50) {
                    finishCraft(false);
                }
            }

            // Save to disk every 5 seconds if energy changed
            doStaggeredDiskSave(100);
        }
    }

    public void finishCraft(boolean abort) {
        isCrafting = false;
        percentDone = 0;
        if(!abort) {
            ItemStack remainder = output.setInFirstAvailableSlot(resultingItem);
            while (!remainder.isEmpty()) {
                remainder = output.setInFirstAvailableSlot(remainder);
            }

            getPristine().shrink(1);
        }
        markDirty();
        updateState();
    }


    public void updatePageHandler(int currentPage) {
        pageHandler.update(currentPage, getLootFromPristine().size());
    }

    public void updateState() {
        IBlockState state = world.getBlockState(getPos());
        world.notifyBlockUpdate(getPos(), state, state, 3);
    }

    private boolean canStartCraft() {
        return canContinueCraft() && canInsertItem();
    }

    private boolean canContinueCraft() {
        return !resultingItem.isEmpty() && getPristine().getItem() instanceof ItemPristineMatter;
    }

    public boolean pristineChanged() {
        return !getPristine().isEmpty() && !currentPristineMatter.equals(((ItemPristineMatter) getPristine().getItem()).getMobKey());
    }


    private boolean hasEnergyForNextTick() {
        return energyStorage.getEnergyStored() >= energyCost;
    }


    private boolean canInsertItem() {
        return output.canInsertItem(resultingItem);
    }


    public ItemStack getItemFromList(int index) {
        if(index >= 0 && index < getLootFromPristine().size()) {
            return getLootFromPristine().get(index);
        } else {
            return ItemStack.EMPTY;
        }
    }

    public NonNullList<ItemStack> getLootFromPristine() {
        ItemStack stack = pristine.getStackInSlot(0);

        if(stack.getItem() instanceof ItemPristineMatter) {
            return ((ItemPristineMatter) stack.getItem()).getLootTable();
        } else {
           return NonNullList.create();
        }
    }

    public ItemStack getPristine() {
        return pristine.getStackInSlot(0);
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(getPos(), 3, write(new NBTTagCompound()));
    }

    @Override
    public final NBTTagCompound getUpdateTag() {
        return this.write(new NBTTagCompound());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        read(packet.getNbtCompound());
    }

    @Nonnull
    @Override
    public NBTTagCompound write(NBTTagCompound compound) {
        compound.setTag("pristine", pristine.serializeNBT());
        compound.setTag("output", output.serializeNBT());
        compound.setTag("pageHandler", pageHandler.serializeNBT());
        compound.setTag("resultingItem", resultingItem.serializeNBT());
        compound.setBoolean("isCrafting", isCrafting);
        compound.setInt("crafingProgress", percentDone);
        compound.setString("currentPristine", currentPristineMatter);
        energyStorage.writeEnergy(compound);
        return super.write(compound);
    }

    @Override
    public void read(NBTTagCompound compound) {
        pristine.deserializeNBT(compound.getCompound("pristine"));
        output.deserializeNBT(compound.getCompound("output"));
        pageHandler.deserializeNBT(compound.getCompound("pageHandler"));
        resultingItem = ItemStack.read(compound.getCompound("resultingItem"));
        isCrafting = compound.hasKey("isCrafting") ? compound.getBoolean("isCrafting") : isCrafting;
        percentDone = compound.hasKey("crafingProgress") ? compound.getInt("crafingProgress") : 0;
        currentPristineMatter = compound.hasKey("currentPristine") ? compound.getString("currentPristine") : "";
        energyStorage.readEnergy(compound);
        super.read(compound);
    }

    private void doStaggeredDiskSave(int divisor) {
        if(ticks % divisor == 0) {
            if(energy != energyStorage.getEnergyStored()) {
                this.energy = energyStorage.getEnergyStored();
                markDirty();
            }
        }
    }

    @Nullable
    @Override
    @SuppressWarnings({"unchecked", "NullableProblems"})
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if(capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            if(facing == null) {
                return LazyOptional.of(() -> (T) new CombinedInvWrapper(pristine, output));
            } else if(facing == EnumFacing.UP) { // Input side
                return LazyOptional.of(() -> (T) pristine);
            } else { // Outputs from all other sides
                return LazyOptional.of(() -> (T) output);
            }
        } else if(capability == CapabilityEnergy.ENERGY) {
            return LazyOptional.of(() -> (T) energyStorage);
        }

        return super.getCapability(capability, facing);
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public Container createContainer(InventoryPlayer inventory, EntityPlayer player) {
        return new ContainerExtractionChamber(this, inventory, this.world);
    }

    @Override
    @SuppressWarnings({"NullableProblems", "ConstantConditions"})
    public String getGuiID() {
        return new ResourceLocation(DeepConstants.MODID, "tile/extraction_chamber").toString();
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public ITextComponent getName() {
        return new TextComponentString("Loot Fabricator");
    }

    @Override
    public boolean hasCustomName() {
        return false;
    }

    @Nullable
    @Override
    public ITextComponent getCustomName() {
        return null;
    }
}
