package org.worldspeaker.compat;

public record RuntimeFacts(
        String minecraftVersion,
        String fabricLoaderVersion,
        String fabricApiVersion,
        int javaMajor,
        String javaVendor,
        String javaRuntimeVersion) {

    public static RuntimeFacts current(String minecraft, String loader, String api) {
        return new RuntimeFacts(
                minecraft,
                loader,
                api,
                Integer.parseInt(System.getProperty("java.specification.version", "0")),
                System.getProperty("java.vendor", "unknown"),
                System.getProperty("java.runtime.version", "unknown"));
    }
}
