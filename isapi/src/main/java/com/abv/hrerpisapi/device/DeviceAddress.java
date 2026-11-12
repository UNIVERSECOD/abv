package com.abv.hrerpisapi.device;

import java.net.InetAddress;
import java.net.URI;

/** Literal IP endpoints only. No DNS lookup, paths, credentials or redirects. */
public final class DeviceAddress {
    private DeviceAddress() {}

    public static String normalize(String value) {
        try {
            URI uri = URI.create("http://" + value);
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()) {
                throw new IllegalArgumentException();
            }
            host = host.replace("[", "").replace("]", "");
            if (!host.matches("[0-9.]+") && !(host.contains(":") && host.matches("[0-9a-fA-F:]+"))) {
                throw new IllegalArgumentException();
            }
            if (!host.contains(":") && !host.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")) {
                throw new IllegalArgumentException();
            }
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            String canonical = address.getHostAddress();
            return (canonical.contains(":") ? "[" + canonical + "]" : canonical) + ":" + port;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cihaz üçün düzgün IP və port daxil edin");
        }
    }
}
