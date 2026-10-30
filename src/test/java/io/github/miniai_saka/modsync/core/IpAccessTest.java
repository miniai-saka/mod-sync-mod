package io.github.miniai_saka.modsync.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.*;
import java.util.*;

class IpAccessTest {
    private IpAccess rules(List<String> allow, List<String> deny) throws IOException { return IpAccess.compile(allow, deny); }
    private String denied(IpAccess rules, String peer) throws Exception { return rules.rejection(InetAddress.getByName(peer)); }
    @Test void emptyListsDoNotRestrictEitherFamilyOrUnknownPeers() throws Exception {
        var rules = rules(List.of(), List.of());
        assertNull(denied(rules, "192.0.2.1")); assertNull(denied(rules, "2001:db8::1")); assertNull(rules.rejection(null));
    }
    @Test void ipv4CidrIncludesOnlyTheExpectedBoundaryAndSupportsHostBits() throws Exception {
        var rules = rules(List.of("192.0.2.17/25", "198.51.100.9"), List.of());
        for (String peer : List.of("192.0.2.0", "192.0.2.127", "198.51.100.9")) assertNull(denied(rules, peer));
        for (String peer : List.of("192.0.2.128", "192.0.3.1", "198.51.100.10")) assertEquals("ip_not_allowed", denied(rules, peer));
    }
    @Test void denyTakesPriorityAndAnEmptyAllowListStillAppliesDenials() throws Exception {
        var rules = rules(List.of("192.0.2.0/24"), List.of("192.0.2.64/26"));
        assertNull(denied(rules, "192.0.2.63")); assertEquals("ip_denied", denied(rules, "192.0.2.64"));
        assertEquals("ip_denied", denied(rules, "192.0.2.127")); assertNull(denied(rules, "192.0.2.128"));
        assertEquals("ip_denied", denied(rules(List.of(), List.of("127.0.0.1")), "127.0.0.1"));
    }
    @Test void ipv6PrefixesExactMatchesAndBoundariesWork() throws Exception {
        var rules = rules(List.of("2001:db8::1234/65", "::1"), List.of("2001:db8::a/128"));
        assertNull(denied(rules, "::1")); assertNull(denied(rules, "2001:db8::"));
        assertNull(denied(rules, "2001:db8:0:0:7fff:ffff:ffff:ffff"));
        assertEquals("ip_not_allowed", denied(rules, "2001:db8:0:0:8000::"));
        assertEquals("ip_denied", denied(rules, "2001:db8::a"));
    }
    @Test void mappedIpv6CannotBypassIpv4RulesAndMappedRulesKeepTheirPrefixMeaning() throws Exception {
        var rules = rules(List.of("::ffff:192.0.2.0/120"), List.of("192.0.2.9"));
        assertNull(denied(rules, "192.0.2.8")); assertEquals("ip_denied", denied(rules, "::ffff:192.0.2.9"));
        byte[] mapped = new byte[16]; mapped[10] = mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 192; mapped[14] = 2; mapped[15] = 9;
        assertEquals("ip_denied", rules.rejection(Inet6Address.getByAddress(null, mapped, -1)));
        assertEquals("ip_not_allowed", denied(rules, "192.0.3.8"));
        assertNull(denied(rules(List.of("::ffff:0:0/96"), List.of()), "203.0.113.4"));
    }
    @Test void zeroPrefixesCoverTheirOwnAddressFamilyAndMissingPeersFailClosed() throws Exception {
        assertNull(denied(rules(List.of("0.0.0.0/0"), List.of()), "203.0.113.255"));
        assertEquals("ip_not_allowed", denied(rules(List.of("::/0"), List.of()), "203.0.113.255"));
        assertNull(denied(rules(List.of("::/0"), List.of()), "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"));
        assertEquals("peer_unknown", rules(List.of(), List.of("127.0.0.1")).rejection(null));
    }
    @Test void invalidAndDnsLikeRulesAreRejectedInsteadOfBecomingPermissive() {
        for (String invalid : List.of("", "localhost", "example.com", "127.1", "2130706433", "0177.0.0.1", "127.0.0.256",
                "127.0.0.1/33", "127.0.0.1/-1", "127.0.0.1/", "127.0.0.1/1/2", "[::1]", "::1/129", "fe80::1%eth0", "::ffff:192.0.2.0/80", "*"))
            assertThrows(IOException.class, () -> rules(List.of(invalid), List.of()), invalid);
        assertThrows(IOException.class, () -> rules(null, List.of()));
        assertThrows(IOException.class, () -> rules(List.of(), Arrays.asList((String) null)));
        assertThrows(IOException.class, () -> rules(Collections.nCopies(1025, "127.0.0.1"), List.of()));
    }
}
