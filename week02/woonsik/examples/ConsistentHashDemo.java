import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Single-process routing experiment; no storage, networking, or replication. */
public class ConsistentHashDemo {
    private static int checks;

    // Immutable routing snapshot. A topology change builds a new snapshot.
    static final class Ring {
        private final NavigableMap<Long, String> points;

        Ring(Map<Long, String> tokens) {
            if (tokens.isEmpty() || tokens.keySet().stream().anyMatch(p -> p < 0)) {
                throw new IllegalArgumentException("Nonempty ring with nonnegative positions required");
            }
            points = Collections.unmodifiableNavigableMap(new TreeMap<>(tokens));
        }

        static Ring build(List<String> nodes, int virtualNodes) {
            if (nodes.isEmpty() || nodes.stream().distinct().count() != nodes.size()
                    || nodes.stream().anyMatch(n -> n == null || n.isBlank() || n.indexOf('\0') >= 0)
                    || virtualNodes < 1) {
                throw new IllegalArgumentException("Unique nonblank node IDs and positive token count required");
            }
            var tokens = new TreeMap<Long, String>();
            for (String node : nodes) {
                for (int v = 0; v < virtualNodes; v++) {
                    // Stable ID: independent of member order and current node count.
                    long position = hash("node\0" + node + "\0" + v);
                    if (tokens.putIfAbsent(position, node) != null) {
                        throw new IllegalStateException("Token collision: " + position);
                    }
                }
            }
            return new Ring(tokens);
        }

        String owner(String key) {
            return ownerAt(hash("key\0" + key));
        }

        String ownerAt(long position) {
            if (position < 0) throw new IllegalArgumentException("Negative position");
            var entry = points.ceilingEntry(position);
            return (entry != null ? entry : points.firstEntry()).getValue();
        }
    }

    // Deterministic UTF-8 -> SHA-256 -> first 63 bits; no platform default encoding.
    private static long hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(bytes).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void verify(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    private static void boundaryChecks() {
        var before = new Ring(Map.of(10L, "A", 40L, "B", 80L, "C"));
        var added = new Ring(Map.of(10L, "A", 30L, "D", 40L, "B", 80L, "C"));
        var removed = new Ring(Map.of(10L, "A", 80L, "C"));
        verify(before.ownerAt(20).equals("B"), "clockwise successor");
        verify(before.ownerAt(40).equals("B"), "exact token belongs to itself");
        verify(before.ownerAt(90).equals("A") && before.ownerAt(0).equals("A"), "wrap around");
        boolean addLocal = true;
        boolean removeLocal = true;
        for (long p = 0; p < 100; p++) {
            boolean changed = !before.ownerAt(p).equals(added.ownerAt(p));
            addLocal &= changed == (p > 10 && p <= 30);
            removeLocal &= before.ownerAt(p).equals("B")
                    ? removed.ownerAt(p).equals("C")
                    : before.ownerAt(p).equals(removed.ownerAt(p));
        }
        verify(addLocal, "addition changes exactly (10,30]");
        verify(removeLocal, "removal moves only B keys to C");
        var single = new Ring(Map.of(50L, "A"));
        verify(single.ownerAt(0).equals("A") && single.ownerAt(99).equals("A"), "single node");
    }

    private static void experiment(int virtualNodes, long[] keyHashes, List<String> nodes) {
        var plus = new ArrayList<>(nodes);
        plus.add("D");
        var before = Ring.build(nodes, virtualNodes);
        var after = Ring.build(plus, virtualNodes);
        var removed = Ring.build(List.of("A", "C"), virtualNodes);
        int moved = 0;
        int removedKeys = 0;
        boolean addLocal = true;
        boolean removeLocal = true;
        var counts = new LinkedHashMap<String, Integer>();
        nodes.forEach(n -> counts.put(n, 0));
        for (long h : keyHashes) {
            String old = before.ownerAt(h);
            String now = after.ownerAt(h);
            counts.merge(old, 1, Integer::sum);
            if (!old.equals(now)) {
                moved++;
                addLocal &= now.equals("D");
            }
            String withoutB = removed.ownerAt(h);
            if (old.equals("B")) removedKeys++;
            removeLocal &= old.equals("B") ? !withoutB.equals("B") : old.equals(withoutB);
        }
        verify(addLocal && moved > 0, "V=" + virtualNodes + ": changed keys move only to D");
        verify(removeLocal && removedKeys > 0, "V=" + virtualNodes + ": only removed B keys move");
        var reverseOrder = Ring.build(List.of("C", "B", "A"), virtualNodes);
        verify(before.points.equals(reverseOrder.points), "V=" + virtualNodes + ": member order independent");
        double mean = keyHashes.length / (double) nodes.size();
        double variance = counts.values().stream().mapToDouble(c -> (c - mean) * (c - mean)).average().orElseThrow();
        System.out.printf(Locale.ROOT,
                "V=%d counts=%s CV=%.4f add(A,B,C -> +D)=%.2f%% remove(B)=%.2f%%%n",
                virtualNodes, counts, Math.sqrt(variance) / mean,
                moved * 100.0 / keyHashes.length, removedKeys * 100.0 / keyHashes.length);
    }

    public static void main(String[] args) {
        boundaryChecks();
        int toyMoved = 0;
        for (int h = 0; h < 12; h++) if (h % 3 != h % 4) toyMoved++;
        verify(toyMoved == 9, "modulo: 9 of 12 keys move for 3 -> 4");

        long[] hashes = new long[100_000];
        for (int i = 0; i < hashes.length; i++) hashes[i] = hash("key\0template:" + i);
        long moduloMoved = Arrays.stream(hashes).filter(h -> h % 3 != h % 4).count();
        System.out.printf(Locale.ROOT, "modulo add(3 -> 4)=%.2f%%%n", moduloMoved * 100.0 / hashes.length);
        experiment(1, hashes, List.of("A", "B", "C"));
        experiment(128, hashes, List.of("A", "B", "C"));
        System.out.println("All " + checks + " checks passed.");
    }
}
