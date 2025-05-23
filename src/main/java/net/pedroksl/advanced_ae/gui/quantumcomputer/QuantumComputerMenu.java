package net.pedroksl.advanced_ae.gui.quantumcomputer;

import java.util.*;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.definitions.AAEMenus;
import net.pedroksl.advanced_ae.common.entities.AdvCraftingBlockEntity;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.GenericStack;
import appeng.menu.guisync.GuiSync;
import appeng.menu.guisync.PacketWritable;
import appeng.menu.me.crafting.CraftingCPUMenu;

public class QuantumComputerMenu extends CraftingCPUMenu {

    private static final String ACTION_SELECT_CPU = "selectCpu";

    private WeakHashMap<ICraftingCPU, Integer> cpuSerialMap = new WeakHashMap<>();

    private int nextCpuSerial = 1;

    private List<AdvCraftingCPU> lastCpuSet = List.of();

    private int lastUpdate = 0;

    @GuiSync(8)
    public CraftingCpuList cpuList = EMPTY_CPU_LIST;

    // This is server-side
    @Nullable
    private ICraftingCPU selectedCpu = null;

    @GuiSync(9)
    private int selectedCpuSerial = -1;

    @GuiSync(10)
    public CpuSelectionMode selectionMode = CpuSelectionMode.ANY;

    private final AdvCraftingBlockEntity host;

    public QuantumComputerMenu(int id, Inventory ip, AdvCraftingBlockEntity te) {
        this(AAEMenus.QUANTUM_COMPUTER.get(), id, ip, te);
    }

    public QuantumComputerMenu(MenuType<?> menuType, int id, Inventory ip, AdvCraftingBlockEntity te) {
        super(menuType, id, ip, te);
        this.cpuList = EMPTY_CPU_LIST;
        this.selectedCpu = null;
        this.selectedCpuSerial = -1;
        this.host = te;

        if (te.getCluster() != null) {
            selectionMode = te.getCluster().getSelectionMode();
        }

        this.registerClientAction("selectCpu", Integer.class, this::selectCpu);
    }

    private static final CraftingCpuList EMPTY_CPU_LIST = new CraftingCpuList(Collections.emptyList());

    private static final Comparator<CraftingCpuListEntry> CPU_COMPARATOR = Comparator.comparing(
                    (CraftingCpuListEntry e) -> e.name() == null)
            .thenComparing(e -> e.name() != null ? e.name().getString() : "")
            .thenComparingInt(CraftingCpuListEntry::serial);

    @Override
    protected void setCPU(ICraftingCPU c) {
        super.setCPU(c);
        this.selectedCpuSerial = getOrAssignCpuSerial(c);
    }

    @Override
    public void broadcastChanges() {
        if (this.host == null) {
            super.broadcastChanges();
            return;
        }

        if (isServerSide() && this.host.getCluster() != null) {
            List<AdvCraftingCPU> newCpuSet = this.host.getCluster().getActiveCPUs();
            newCpuSet.add(this.host.getCluster().getRemainingCapacityCPU());
            if (!lastCpuSet.equals(newCpuSet)
                    // Always try to update once every second to show job progress
                    || ++lastUpdate >= 20) {
                lastCpuSet = newCpuSet;
                cpuList = createCpuList();
            }
        } else {
            lastUpdate = 20;
            if (!lastCpuSet.isEmpty()) {
                cpuList = EMPTY_CPU_LIST;
                lastCpuSet = List.of();
            }
        }

        // Clear selection if CPU is no longer in list
        if (selectedCpuSerial != -1) {
            if (cpuList.cpus().stream().noneMatch(c -> c.serial() == selectedCpuSerial)) {
                selectCpu(-1);
            }
        }

        // Select a suitable CPU if none is selected
        if (selectedCpuSerial == -1) {
            // Try busy CPUs first
            for (var cpu : cpuList.cpus()) {
                if (cpu.currentJob() != null) {
                    selectCpu(cpu.serial());
                    break;
                }
            }
            // If we couldn't find a busy one, just select the first
            if (selectedCpuSerial == -1 && !cpuList.cpus().isEmpty()) {
                selectCpu(cpuList.cpus().getFirst().serial());
            }
        }

        if (this.host.getCluster() != null) {
            selectionMode = this.host.getCluster().getSelectionMode();
        }

        super.broadcastChanges();
    }

    private CraftingCpuList createCpuList() {
        var entries = new ArrayList<CraftingCpuListEntry>(lastCpuSet.size());
        for (var cpu : lastCpuSet) {
            var serial = getOrAssignCpuSerial(cpu);
            var status = cpu.getJobStatus();
            var progress = 0f;
            if (status != null && status.totalItems() > 0) {
                progress = (float) (status.progress() / (double) status.totalItems());
            }
            entries.add(new CraftingCpuListEntry(
                    serial,
                    cpu.getAvailableStorage(),
                    cpu.getCoProcessors(),
                    cpu.getName(),
                    cpu.getSelectionMode(),
                    status != null ? status.crafting() : null,
                    progress,
                    status != null ? status.elapsedTimeNanos() : 0));
        }
        entries.sort(CPU_COMPARATOR);
        return new CraftingCpuList(entries);
    }

    private int getOrAssignCpuSerial(ICraftingCPU cpu) {
        if (this.cpuSerialMap == null) {
            this.cpuSerialMap = new WeakHashMap<>();
        }
        return cpuSerialMap.computeIfAbsent(cpu, ignored -> nextCpuSerial++);
    }

    @Override
    public boolean allowConfiguration() {
        return false;
    }

    public void selectCpu(int serial) {
        if (isClientSide()) {
            selectedCpuSerial = serial;
            sendClientAction(ACTION_SELECT_CPU, serial);
        } else {
            ICraftingCPU newSelectedCpu = null;
            if (serial != -1) {
                for (var cpu : lastCpuSet) {
                    if (cpuSerialMap.getOrDefault(cpu, -1) == serial) {
                        newSelectedCpu = cpu;
                        break;
                    }
                }
            }

            if (newSelectedCpu != selectedCpu) {
                setCPU(newSelectedCpu);
            }
        }
    }

    public int getSelectedCpuSerial() {
        return selectedCpuSerial;
    }

    public CpuSelectionMode getSelectionMode() {
        return this.selectionMode;
    }

    public record CraftingCpuList(List<CraftingCpuListEntry> cpus) implements PacketWritable {
        public CraftingCpuList(RegistryFriendlyByteBuf data) {
            this(readFromPacket(data));
        }

        private static List<CraftingCpuListEntry> readFromPacket(RegistryFriendlyByteBuf data) {
            var count = data.readInt();
            var result = new ArrayList<CraftingCpuListEntry>(count);
            for (int i = 0; i < count; i++) {
                result.add(CraftingCpuListEntry.readFromPacket(data));
            }
            return result;
        }

        @Override
        public void writeToPacket(RegistryFriendlyByteBuf data) {
            data.writeInt(cpus.size());
            for (var entry : cpus) {
                entry.writeToPacket(data);
            }
        }
    }

    public record CraftingCpuListEntry(
            int serial,
            long storage,
            int coProcessors,
            Component name,
            CpuSelectionMode mode,
            GenericStack currentJob,
            float progress,
            long elapsedTimeNanos) {
        public static CraftingCpuListEntry readFromPacket(RegistryFriendlyByteBuf data) {
            return new CraftingCpuListEntry(
                    data.readInt(),
                    data.readLong(),
                    data.readInt(),
                    data.readBoolean() ? ComponentSerialization.TRUSTED_STREAM_CODEC.decode(data) : null,
                    data.readEnum(CpuSelectionMode.class),
                    GenericStack.readBuffer(data),
                    data.readFloat(),
                    data.readVarLong());
        }

        public void writeToPacket(RegistryFriendlyByteBuf data) {
            data.writeInt(serial);
            data.writeLong(storage);
            data.writeInt(coProcessors);
            data.writeBoolean(name != null);
            if (name != null) {
                ComponentSerialization.TRUSTED_STREAM_CODEC.encode(data, name);
            }
            data.writeEnum(mode);
            GenericStack.writeBuffer(currentJob, data);
            data.writeFloat(progress);
            data.writeVarLong(elapsedTimeNanos);
        }
    }
}
