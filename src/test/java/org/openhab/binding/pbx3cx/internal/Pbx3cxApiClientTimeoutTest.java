/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.pbx3cx.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The FortiGate restart of 2026-11-03 08:13: the network was back for every other thing at
 * 08:15, the 3CX bridge stayed OFFLINE until 08:29:59. A thread dump at 08:26 showed the trunk
 * and ring-group polls parked in {@code HttpClient.send()} for minutes, with the request's 5 s
 * timeout set - that timeout ends at the response headers, and a body that stops after them is
 * waited for until the kernel gives the dead connection up (tcp_retries2, ~15 min).
 *
 * The server here is that connection: TLS like the 3CX, a login that works, then a reply that
 * sends its headers and the first bytes of the body and goes silent. Both tests fail on the
 * client before the fix of 2026-11-04 (the first hangs, so the second never gets its answer).
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class Pbx3cxApiClientTimeoutTest {

    private static final String LOGIN_REPLY = "{\"Status\":\"AuthSuccess\",\"Token\":"
            + "{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"expires_in\":3600}}";

    private @Nullable SSLServerSocket server;
    private @Nullable File keystore;
    private final AtomicBoolean stallTrunks = new AtomicBoolean(true);
    private final AtomicBoolean stalledConnectionClosed = new AtomicBoolean(false);
    private final CountDownLatch stalled = new CountDownLatch(1);
    private final AtomicReference<@Nullable Socket> stalledSocket = new AtomicReference<>();

    @BeforeEach
    public void startServer() throws Exception {
        File ks = Files.createTempFile("pbx3cx-test", ".p12").toFile();
        ks.delete();
        keystore = ks;
        Process p = new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias",
                "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-storetype", "PKCS12", "-keystore", ks.getAbsolutePath(), "-storepass", "testpass", "-keypass",
                "testpass").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "keytool made the test certificate");

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(ks)) {
            store.load(in, "testpass".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "testpass".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        SSLServerSocket s = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0, 50,
                InetAddress.getLoopbackAddress());
        server = s;
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket c = s.accept();
                    Thread h = new Thread(() -> serve(c), "pbx3cx-test-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "pbx3cx-test-accept");
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    public void stopServer() throws Exception {
        SSLServerSocket s = server;
        if (s != null) {
            s.close();
        }
        Socket c = stalledSocket.get();
        if (c != null) {
            c.close();
        }
        File ks = keystore;
        if (ks != null) {
            ks.delete();
        }
    }

    /** One connection, HTTP/1.1 keep-alive: answer each request in turn. */
    private void serve(Socket c) {
        try {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            while (true) {
                String requestLine = r.readLine();
                if (requestLine == null) {
                    return;
                }
                int length = 0;
                String line;
                while ((line = r.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(15).trim());
                    }
                }
                for (int i = 0; i < length; i++) {
                    r.read();
                }
                if (requestLine.contains(Pbx3cxBindingConstants.API_LOGIN)) {
                    reply(out, LOGIN_REPLY);
                } else if (requestLine.contains(Pbx3cxBindingConstants.API_TRUNKS) && stallTrunks.get()) {
                    // the headers and the start of the body, then nothing: the dead connection
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 4000\r\n\r\n"
                            + "{\"value\":[").getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    stalledSocket.set(c);
                    stalled.countDown();
                    // the client closing its end is the only thing that ends this read
                    in.read();
                    stalledConnectionClosed.set(true);
                    return;
                } else {
                    reply(out, "{\"value\":[]}");
                }
            }
        } catch (IOException e) {
            if (c == stalledSocket.get()) {
                stalledConnectionClosed.set(true);
            }
        }
    }

    private static void reply(OutputStream out, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    private Pbx3cxApiClient client() {
        SSLServerSocket s = server;
        assertNotNull(s);
        Pbx3cxServerConfiguration config = new Pbx3cxServerConfiguration();
        config.hostname = "127.0.0.1";
        config.port = s.getLocalPort();
        config.username = "test";
        config.password = "test";
        config.verifySsl = false;
        return new Pbx3cxApiClient(config);
    }

    @Test
    public void aReplyWhoseBodyStopsDoesNotHoldThePoll() throws Exception {
        Pbx3cxApiClient client = client();
        long t0 = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(40), () -> {
            assertThrows(IOException.class, client::getTrunks, "a body that never ends is an error, not a wait");
        }, "11-03: the poll waited ~15 min here");
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0);
        assertTrue(stalled.await(1, TimeUnit.SECONDS), "the server did stall the reply");
        assertTrue(seconds < 30, "given up within the deadline, took " + seconds + " s");
        // the dead connection is dropped, not left for the kernel's retransmissions
        for (int i = 0; i < 50 && !stalledConnectionClosed.get(); i++) {
            Thread.sleep(100);
        }
        assertTrue(stalledConnectionClosed.get(), "the stalled connection was closed by the client");
    }

    @Test
    public void theNextCallAfterAHangIsAnswered() throws Exception {
        Pbx3cxApiClient client = client();
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            assertThrows(IOException.class, client::getTrunks);
            stallTrunks.set(false);
            assertEquals("{\"value\":[]}", client.getTrunks(), "11-03: connect timeouts for 15 min after the network was back");
        });
    }
}
