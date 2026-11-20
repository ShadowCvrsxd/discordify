package com.discordify.gif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SsrfDnsTest {
    @Test
    void rejectsPrivateMappedAndCgnatAddresses() throws Exception {
        assertThrows(UnknownHostException.class, () -> ResourceDownloader.ssrfSafeDns("127.0.0.1"));
        assertThrows(UnknownHostException.class, () -> ResourceDownloader.ssrfSafeDns("::ffff:127.0.0.1"));
        assertThrows(UnknownHostException.class, () -> ResourceDownloader.ssrfSafeDns("100.64.0.1"));
        assertThrows(UnknownHostException.class, () -> ResourceDownloader.ssrfSafeDns("169.254.169.254"));
        assertThrows(UnknownHostException.class, () -> ResourceDownloader.ssrfSafeDns("localhost"));
        assertEquals(List.of(InetAddress.getByName("8.8.8.8")), ResourceDownloader.ssrfSafeDns("8.8.8.8"));
    }
}
