package ru.lct.heat.geojson;

/** Run separately with -Xmx64m. Generates input lazily, including one huge Feature. */
public class StreamingMemoryCheck {
    public static void main(String[] args) throws Exception {
        long repeats = Long.parseLong(args.length == 0 ? "160000000" : args[0]);
        StreamingValidatorTest.RepeatingInputStream input = new StreamingValidatorTest.RepeatingInputStream(repeats);
        long started = System.nanoTime();
        long count = new StreamingValidator().validate(input, (i,id,t) -> {});
        if(count != 1) throw new AssertionError("Expected one feature");
        System.out.printf("PASS bytes=%d features=%d maxHeap=%d elapsedSeconds=%.2f%n", input.position-1, count, Runtime.getRuntime().maxMemory(), (System.nanoTime()-started)/1e9);
    }
}
