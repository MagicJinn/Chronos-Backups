package com.magicjinn.chronos.tooling.TestServers.rcon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import com.magicjinn.chronos.tooling.TestServers.docker.DockerMinecraftServer;

public final class RconClient {

    /** The RCON password for the test servers. */
    public static final String RCON_PASSWORD = "password";

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_RESPONSE = 0;

    private RconClient() {
    }

    public static void stopServer(DockerMinecraftServer server) {
        Thread stopper = new Thread(() -> stopServerBlocking(server.getRconPort()),
                "rcon-stop-" + server.containerName());
        stopper.setDaemon(true);
        stopper.start();
    }

    public static void stopServerBlocking(int rconPort) {
        try {
            sendStopGraceful("127.0.0.1", rconPort, RCON_PASSWORD);
        } catch (IOException e) {
            if (!isBenignShutdownIoMessage(e.getMessage()) && !(e instanceof SocketTimeoutException)) {
                System.err.println("RCON stop failed: " + e.getMessage());
            }
        }
    }

    public static String send(int port, String command) throws IOException {
        return send("127.0.0.1", port, RCON_PASSWORD, command, 60_000);
    }

    public static String send(String host, int port, String password, String command, int readTimeoutMs)
            throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return sendOnce(host, port, password, command, readTimeoutMs);
            } catch (java.net.ConnectException e) {
                last = e;
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ie);
                }
            }
        }
        if (last != null)
            throw last;
        throw new IOException("RCON failed after retries");
    }

    public static void sendStopGraceful(String host, int port, String password) throws IOException {
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress(host, port), 10_000);
            } catch (IOException e) {
                if (benignShutdownMessage(e.getMessage()))
                    return;
                throw e;
            }
            socket.setSoTimeout(8_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            int authId = 0x00112233;
            writePacket(out, authId, TYPE_AUTH, password);
            Packet authResp;
            try {
                authResp = readPacket(in);
            } catch (IOException e) {
                if (benignShutdownMessage(e.getMessage()))
                    return;
                throw e;
            }
            if (authResp.requestId == -1)
                throw new IOException("RCON authentication failed");
            int cmdId = 0x00445566;
            writePacket(out, cmdId, TYPE_COMMAND, "stop");
            // It's normal for the server to close the connection right after starting
            // shutdown, so we don't wait for the full RCON response loop here. That
            // response might never arrive.
            try {
                readPacket(in);
            } catch (IOException e) {
                if (benignShutdownMessage(e.getMessage()) || e instanceof SocketTimeoutException)
                    return;
                throw e;
            }
        }
    }

    private static String sendOnce(String host, int port, String password, String command, int readTimeoutMs)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setSoTimeout(readTimeoutMs);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            int authId = 0x00112233;
            writePacket(out, authId, TYPE_AUTH, password);
            Packet authResp = readPacket(in);
            if (authResp.requestId == -1)
                throw new IOException("RCON authentication failed");
            int cmdId = 0x00445566;
            writePacket(out, cmdId, TYPE_COMMAND, command);
            StringBuilder acc = new StringBuilder();
            boolean gotResponse = false;
            while (true) {
                Packet resp;
                try {
                    resp = readPacket(in);
                } catch (SocketTimeoutException e) {
                    if (gotResponse)
                        return acc.toString();
                    throw e;
                }
                if (resp.requestId != cmdId || resp.type != TYPE_RESPONSE)
                    throw new IOException("Unexpected RCON packet (id=" + resp.requestId + " type=" + resp.type + ")");
                gotResponse = true;
                acc.append(resp.payload);
                if (resp.payload.isEmpty())
                    break;
            }
            return acc.toString();
        }
    }

    private static void writePacket(OutputStream out, int requestId, int type, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.US_ASCII);
        int len = 4 + 4 + body.length + 2;
        ByteBuffer buf = ByteBuffer.allocate(4 + len).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(len);
        buf.putInt(requestId);
        buf.putInt(type);
        buf.put(body);
        buf.put((byte) 0);
        buf.put((byte) 0);
        out.write(buf.array());
        out.flush();
    }

    private static Packet readPacket(InputStream in) throws IOException {
        byte[] lenBuf = readFully(in, 4);
        int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 10 || len > 4096 * 1024)
            throw new IOException("Invalid RCON packet length: " + len);
        byte[] rest = readFully(in, len);
        ByteBuffer buf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int requestId = buf.getInt();
        int type = buf.getInt();
        int remain = buf.remaining();
        byte[] strBytes = new byte[Math.max(0, remain - 2)];
        buf.get(strBytes);
        return new Packet(requestId, type, new String(strBytes, StandardCharsets.US_ASCII));
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int o = 0;
        while (o < n) {
            int r = in.read(b, o, n - o);
            if (r < 0)
                throw new IOException("Unexpected end of stream reading RCON packet");
            o += r;
        }
        return b;
    }

    public static boolean isBenignShutdownIoMessage(String message) {
        return benignShutdownMessage(message);
    }

    private static boolean benignShutdownMessage(String message) {
        if (message == null || message.isEmpty())
            return false;
        return message.contains("Unexpected end of stream")
                || message.contains("Connection reset")
                || message.contains("Broken pipe")
                || message.contains("Connection reset by peer")
                || message.contains("An established connection was aborted")
                || message.contains("Connection refused");
    }

    private record Packet(int requestId, int type, String payload) {
    }
}
