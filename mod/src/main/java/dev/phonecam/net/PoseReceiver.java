package dev.phonecam.net;

import dev.phonecam.PhoneCamClient;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Minimal JSON pose receiver over UDP. No external JSON lib — parses a flat object.
 *
 * Expected payload (one datagram, UTF-8 JSON):
 * {"v":1,"t":123,"yaw":12.5,"pitch":-3.2,"roll":0,"pos":[0,0,0],"zoom":1.0,"mode":"look"}
 */
public final class PoseReceiver {
    private final int port;
    private final Consumer<PosePacket> consumer;
    private final AtomicReference<String> lastStatus = new AtomicReference<>("idle");
    private final AtomicReference<Long> lastPacketAt = new AtomicReference<>(0L);
    private final AtomicReference<Integer> packetCount = new AtomicReference<>(0);

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread thread;

    public PoseReceiver(int port, Consumer<PosePacket> consumer) {
        this.port = port;
        this.consumer = consumer;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "PhoneCam-UdpReceiver");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    public String status() {
        long last = lastPacketAt.get();
        long age = last == 0 ? -1 : System.currentTimeMillis() - last;
        return "udp :" + port + " pkts=" + packetCount.get()
                + (age < 0 ? " (no data)" : " age=" + age + "ms");
    }

    private void loop() {
        try {
            socket = new DatagramSocket(new InetSocketAddress("0.0.0.0", port));
            socket.setSoTimeout(500);
            lastStatus.set("listening :" + port);
            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException timeout) {
                    continue;
                } catch (java.io.IOException ioError) {
                    if (!running) break;
                    PhoneCamClient.LOGGER.debug("UDP receive error: {}", ioError.toString());
                    continue;
                }
                if (!running) break;
                String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                try {
                    PosePacket pose = parse(json);
                    packetCount.updateAndGet(c -> c + 1);
                    lastPacketAt.set(System.currentTimeMillis());
                    consumer.accept(pose);
                } catch (Exception parseError) {
                    PhoneCamClient.LOGGER.debug("Bad pose packet: {} ({})", json, parseError.toString());
                }
            }
        } catch (SocketException e) {
            if (running) {
                lastStatus.set("bind failed: " + e.getMessage());
                PhoneCamClient.LOGGER.error("UDP bind failed on :{}", port, e);
            }
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
        }
    }

    /** Flat JSON subset parser for the pose packet. */
    static PosePacket parse(String json) {
        float yaw = (float) readNumber(json, "yaw", 0);
        float pitch = (float) readNumber(json, "pitch", 0);
        float roll = (float) readNumber(json, "roll", 0);
        float zoom = (float) readNumber(json, "zoom", 1.0);
        long t = (long) readNumber(json, "t", System.currentTimeMillis());
        String mode = readString(json, "mode");
        double[] pos = readArray3(json, "pos");
        return new PosePacket(t, yaw, pitch, roll, (float) pos[0], (float) pos[1], (float) pos[2], zoom, mode);
    }

    private static double readNumber(String json, String key, double fallback) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return fallback;
        int colon = json.indexOf(':', i);
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && "+-0123456789.eE".indexOf(json.charAt(end)) >= 0) end++;
        if (end == start) return fallback;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String readString(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static double[] readArray3(String json, String key) {
        double[] out = new double[3];
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return out;
        int lb = json.indexOf('[', i);
        if (lb < 0) return out;
        int rb = json.indexOf(']', lb);
        if (rb < 0) return out;
        String body = json.substring(lb + 1, rb);
        String[] parts = body.split(",");
        for (int n = 0; n < 3 && n < parts.length; n++) {
            try {
                out[n] = Double.parseDouble(parts[n].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
