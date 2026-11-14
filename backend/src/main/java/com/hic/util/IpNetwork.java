package com.hic.util;

import java.net.InetAddress;
import java.net.URI;

/** Literal IPv4/IPv6 CIDR matcher. Hostnames are never resolved. */
public final class IpNetwork {
    private final byte[] network;
    private final int prefix;

    private IpNetwork(byte[] network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    public static IpNetwork parse(String value) {
        try {
            String[] parts = value.strip().split("/", -1);
            if (parts.length != 2 || !literal(parts[0])) throw new IllegalArgumentException();
            byte[] address = InetAddress.getByName(parts[0]).getAddress();
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > address.length * 8) throw new IllegalArgumentException();
            return new IpNetwork(mask(address, prefix), prefix);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Etibarsız cihaz şəbəkəsi", invalid);
        }
    }

    public boolean containsEndpoint(String endpoint) {
        try {
            String normalized = DeviceAddress.normalize(endpoint);
            String host = URI.create("http://" + normalized).getHost();
            host = host.replace("[", "").replace("]", "");
            byte[] address = InetAddress.getByName(host).getAddress();
            return address.length == network.length
                    && java.util.Arrays.equals(mask(address, prefix), network);
        } catch (Exception invalid) {
            return false;
        }
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] result = address.clone();
        for (int bit = prefix; bit < result.length * 8; bit++) {
            result[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
        }
        return result;
    }

    private static boolean literal(String host) {
        return host.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")
                || host.matches("[0-9a-fA-F:]+");
    }
}
