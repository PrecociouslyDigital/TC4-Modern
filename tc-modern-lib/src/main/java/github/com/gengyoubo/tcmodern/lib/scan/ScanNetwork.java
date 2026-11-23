package github.com.gengyoubo.tcmodern.lib.scan;

import github.com.gengyoubo.tcmodern.api.scan.*;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

/** Registration sync only. Original TC4 packets carry scan completions through MFCL. */
public final class ScanNetwork {
    /** One slice of the scan rules, since a big pack has more than fit in one packet. The client installs them all once the slice with {@code last} arrives. */
    record Rules(Map<ScanKey, AspectValues> values, Set<ScanKey> overrides, boolean last) { }
    static final int RULES_PER_PACKET = 4096;
    private static SimpleChannel channel;
    private static final Map<ScanKey, AspectValues> receivedValues = new LinkedHashMap<>();
    private static final Set<ScanKey> receivedOverrides = new HashSet<>();
    private ScanNetwork() { }
    @SuppressWarnings("removal")
    public static void register() {
        channel = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath("tcmodernlib", "scans"), () -> "3", "3"::equals, "3"::equals);
        channel.registerMessage(0, Rules.class, ScanNetwork::writeRules, ScanNetwork::readRules,
                (value, context) -> {
                    context.get().enqueueWork(() -> receive(value));
                    context.get().setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void rules(ServerPlayer player) {
        var target = PacketDistributor.PLAYER.with(() -> player);
        slices(ScanRules.server(), OriginalScanBridge.overrides()).forEach(slice -> channel.send(target, slice));
    }
    static List<Rules> slices(Map<ScanKey, AspectValues> values, Set<ScanKey> overrides) {
        var entries = List.copyOf(values.entrySet());
        var slices = new ArrayList<Rules>();
        int from = 0;
        do {
            int to = Math.min(from + RULES_PER_PACKET, entries.size());
            var slice = new LinkedHashMap<ScanKey, AspectValues>();
            entries.subList(from, to).forEach(entry -> slice.put(entry.getKey(), entry.getValue()));
            var sliceOverrides = new HashSet<>(slice.keySet()); sliceOverrides.retainAll(overrides);
            slices.add(new Rules(slice, Set.copyOf(sliceOverrides), to == entries.size()));
            from = to;
        } while (from < entries.size());
        return slices;
    }
    private static void receive(Rules slice) {
        receivedValues.putAll(slice.values());
        receivedOverrides.addAll(slice.overrides());
        if (!slice.last()) return;
        var values = Map.copyOf(receivedValues); var overrides = Set.copyOf(receivedOverrides);
        receivedValues.clear(); receivedOverrides.clear();
        OriginalScanBridge.install(values, overrides);
        ScanRules.receive(values);
    }
    static void writeRules(Rules rules, FriendlyByteBuf out) {
        out.writeBoolean(rules.last());
        out.writeVarInt(rules.values().size());
        rules.values().forEach((key, values) -> {
            out.writeEnum(key.kind()); out.writeUtf(key.id(), 256); out.writeBoolean(rules.overrides().contains(key));
            out.writeVarInt(values.amounts().size());
            values.amounts().forEach((tag, count) -> { out.writeUtf(tag, 64); out.writeVarInt(count); });
        });
    }
    static Rules readRules(FriendlyByteBuf in) {
        boolean last = in.readBoolean();
        int size = bounded(in.readVarInt(), RULES_PER_PACKET);
        var result = new LinkedHashMap<ScanKey, AspectValues>(); var overrides = new HashSet<ScanKey>();
        for (int i = 0; i < size; i++) {
            var key = new ScanKey(in.readEnum(ScanKey.Kind.class), in.readUtf(256));
            if (in.readBoolean()) overrides.add(key);
            int count = bounded(in.readVarInt(), 64); var aspects = new LinkedHashMap<String, Integer>();
            for (int a = 0; a < count; a++) aspects.put(in.readUtf(64), bounded(in.readVarInt(), 32767));
            result.put(key, new AspectValues(aspects));
        }
        return new Rules(Map.copyOf(result), Set.copyOf(overrides), last);
    }
    private static int bounded(int value, int max) { if (value < 0 || value > max) throw new IllegalArgumentException("Invalid scan payload size"); return value; }
}
