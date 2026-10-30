package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.net.*;
import java.util.*;

/** Literal IP/CIDR rules only. Configuration parsing must never perform a DNS lookup. */
public final class IpAccess {
    private record Network(byte[] address, int prefix) {
        boolean contains(byte[] peer) {
            if (peer.length != address.length) return false;
            for (int i = 0; i < prefix / 8; i++) if (address[i] != peer[i]) return false;
            int bits = prefix % 8, index = prefix / 8;
            return bits == 0 || ((address[index] ^ peer[index]) & (0xff << (8 - bits))) == 0;
        }
    }
    private final List<Network> allowed, denied;
    private IpAccess(List<Network> allowed, List<Network> denied) { this.allowed = allowed; this.denied = denied; }
    public static IpAccess compile(List<String> allowed, List<String> denied) throws IOException {
        return new IpAccess(parse(allowed, "allowedIps"), parse(denied, "deniedIps"));
    }
    private static List<Network> parse(List<String> rules, String field) throws IOException {
        if (rules == null || rules.size() > 1024) throw new IOException("Invalid " + field);
        List<Network> result = new ArrayList<>();
        for (String rule : rules) {
            if (rule == null || rule.length() > 128) throw new IOException("Invalid " + field + " rule");
            String[] parts = rule.strip().split("/", -1);
            if (parts.length > 2) throw new IOException("Invalid " + field + " CIDR");
            String literal = parts[0];
            boolean ipv6 = literal.contains(":");
            byte[] address;
            try {
                if (ipv6) {
                    if (!literal.matches("[0-9a-fA-F:.]+")) throw new IOException("Invalid IPv6 literal");
                    address = InetAddress.getByName(literal).getAddress();
                } else {
                    String[] octets = literal.split("\\.", -1);
                    if (octets.length != 4) throw new IOException("IPv4 requires four octets");
                    address = new byte[4];
                    for (int i = 0; i < 4; i++) {
                        if (!octets[i].matches("0|[1-9][0-9]{0,2}")) throw new IOException("Invalid IPv4 octet");
                        int value = Integer.parseInt(octets[i]);
                        if (value > 255) throw new IOException("Invalid IPv4 octet");
                        address[i] = (byte) value;
                    }
                }
                int width = ipv6 ? 128 : 32;
                int prefix = width;
                if (parts.length == 2) {
                    if (!parts[1].matches("0|[1-9][0-9]{0,2}")) throw new IOException("Invalid prefix");
                    prefix = Integer.parseInt(parts[1]);
                    if (prefix > width) throw new IOException("Prefix out of range");
                }
                // Java represents IPv4-mapped IPv6 literals as IPv4. Preserve their CIDR meaning.
                if (ipv6 && address.length == 4) {
                    if (prefix < 96) throw new IOException("Mapped IPv6 requires prefix >= 96");
                    prefix -= 96;
                }
                result.add(new Network(address, prefix));
            } catch (IOException | IllegalArgumentException e) { throw new IOException("Invalid IP/CIDR in " + field, e); }
        }
        return List.copyOf(result);
    }
    public String rejection(InetAddress peer) {
        if (allowed.isEmpty() && denied.isEmpty()) return null;
        if (peer == null) return "peer_unknown";
        byte[] address = peer.getAddress();
        if (address.length == 16 && mapped(address)) address = Arrays.copyOfRange(address, 12, 16);
        final byte[] normalized = address;
        if (denied.stream().anyMatch(rule -> rule.contains(normalized))) return "ip_denied";
        return !allowed.isEmpty() && allowed.stream().noneMatch(rule -> rule.contains(normalized)) ? "ip_not_allowed" : null;
    }
    private static boolean mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }
}
