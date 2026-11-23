package github.com.gengyoubo.tcmodern.lib.scan;

import github.com.gengyoubo.tcmodern.api.scan.AspectValues;
import github.com.gengyoubo.tcmodern.api.scan.ScanKey;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ScanNetworkTest {
    private static Map<ScanKey, AspectValues> rules(int count) {
        var rules = new LinkedHashMap<ScanKey, AspectValues>();
        var kinds = ScanKey.Kind.values();
        for (int i = 0; i < count; i++)
            rules.put(new ScanKey(kinds[i % kinds.length], "pack:subject_" + i), new AspectValues(Map.of("ordo", 1 + i % 7, "terra", 2)));
        return rules;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, ScanNetwork.RULES_PER_PACKET, ScanNetwork.RULES_PER_PACKET + 1, 21_927})
    void slicesFitThePacketBoundAndReassembleIntoTheRules(int count) {
        var rules = rules(count);
        var overrides = new HashSet<ScanKey>();
        rules.keySet().stream().filter(key -> key.id().hashCode() % 3 == 0).forEach(overrides::add);

        var slices = ScanNetwork.slices(rules, overrides);
        var values = new LinkedHashMap<ScanKey, AspectValues>(); var received = new HashSet<ScanKey>();
        for (int i = 0; i < slices.size(); i++) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            ScanNetwork.writeRules(slices.get(i), buffer);
            assertTrue(buffer.readableBytes() < 1 << 20, "a slice must fit one custom payload");
            var decoded = ScanNetwork.readRules(buffer);
            assertEquals(slices.get(i), decoded);
            assertEquals(i == slices.size() - 1, decoded.last());
            values.putAll(decoded.values()); received.addAll(decoded.overrides());
        }
        assertEquals(rules, values);
        assertEquals(overrides, received);
    }
}
