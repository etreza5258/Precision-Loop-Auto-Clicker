
package com.precision.loopclicker;

import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class NtpTimeSync {

    private static final String[] SERVERS = {
            "time.nist.gov",
            "ntp.nict.jp",
            "time.cloudflare.com"
    };

    private static final int NTP_PORT = 123;
    private static final int PACKET_SIZE = 48;
    private static final int TIMEOUT_MS = 1000;
    private static final int SAMPLES_PER_SERVER = 3;

    private static final long NTP_EPOCH_OFFSET = 2208988800L;

    private NtpTimeSync() {
    }

    public static Result synchronize() {

        ExecutorService executor =
                Executors.newFixedThreadPool(SERVERS.length);

        List<Future<HostResult>> futures = new ArrayList<>();

        try {

            for (String server : SERVERS) {

                futures.add(executor.submit(
                        () -> queryHost(server)
                ));
            }

            List<HostResult> hostResults = new ArrayList<>();

            for (Future<HostResult> future : futures) {

                try {

                    HostResult result =
                            future.get(5000, TimeUnit.MILLISECONDS);

                    if (result != null && !result.samples.isEmpty()) {
                        hostResults.add(result);
                    }

                } catch (Exception ignored) {
                    // ادامه با منابع دیگر
                }
            }

            if (hostResults.isEmpty()) {
                return null;
            }

            List<Double> hostOffsets = new ArrayList<>();

            for (HostResult host : hostResults) {
                hostOffsets.add(host.medianOffset);
            }

            double centerOffset = median(hostOffsets);

            List<Double> deviations = new ArrayList<>();

            for (double offset : hostOffsets) {
                deviations.add(Math.abs(offset - centerOffset));
            }

            double mad = median(deviations);

            double rejectionLimit = Math.max(
                    15.0,
                    mad * 3.0
            );

            List<HostResult> acceptedHosts = new ArrayList<>();

            for (HostResult host : hostResults) {

                if (Math.abs(host.medianOffset - centerOffset)
                        <= rejectionLimit) {

                    acceptedHosts.add(host);
                }
            }

            if (acceptedHosts.isEmpty()) {
                return null;
            }

            List<Double> acceptedOffsets = new ArrayList<>();

            List<Sample> acceptedSamples = new ArrayList<>();

            for (HostResult host : acceptedHosts) {

                acceptedOffsets.add(host.medianOffset);

                acceptedSamples.addAll(host.samples);
            }

            double finalOffset = median(acceptedOffsets);

            double minOffset = Collections.min(acceptedOffsets);
            double maxOffset = Collections.max(acceptedOffsets);

            double spread = maxOffset - minOffset;

            Sample bestSample = Collections.min(
                    acceptedSamples,
                    Comparator.comparingLong(
                            sample -> sample.networkRoundTripMs
                    )
            );

            long nowWall = System.currentTimeMillis();
            long nowElapsed = SystemClock.elapsedRealtime();

            long finalOffsetMs = Math.round(finalOffset);

            long estimatedEpoch =
                    nowWall + finalOffsetMs;

            long uncertainty = (long) Math.ceil(
                    bestSample.networkRoundTripMs / 2.0
                            + maxDeviation(acceptedOffsets, finalOffset)
                            + 1.0
            );

            boolean crossChecked =
                    acceptedHosts.size() >= 2;

            boolean withinTarget =
                    crossChecked
                            && spread <= 50.0
                            && uncertainty <= 50L;

            return new Result(
                    finalOffsetMs,
                    estimatedEpoch,
                    nowElapsed,
                    bestSample.networkRoundTripMs,
                    uncertainty,
                    (long) Math.ceil(spread),
                    acceptedHosts.size(),
                    acceptedSamples.size(),
                    bestSample.server,
                    crossChecked,
                    withinTarget
            );

        } finally {

            for (Future<HostResult> future : futures) {
                future.cancel(true);
            }

            executor.shutdownNow();
        }
    }

    private static HostResult queryHost(String server) {

        List<Sample> samples = new ArrayList<>();

        for (int i = 0; i < SAMPLES_PER_SERVER; i++) {

            try {

                Sample sample = queryServer(server);

                if (sample != null) {
                    samples.add(sample);
                }

            } catch (Exception ignored) {
                // نمونه ناموفق کنار گذاشته می‌شود
            }
        }

        if (samples.isEmpty()) {
            return null;
        }

        List<Double> offsets = new ArrayList<>();

        for (Sample sample : samples) {
            offsets.add(sample.offsetMs);
        }

        return new HostResult(
                server,
                samples,
                median(offsets)
        );
    }

    private static Sample queryServer(String server)
            throws IOException {

        InetAddress address =
                InetAddress.getByName(server);

        byte[] request = new byte[PACKET_SIZE];

        // LI=0, VN=4, Mode=3
        request[0] = 0x23;

        long t1Wall = System.currentTimeMillis();
        long t1Nanos = SystemClock.elapsedRealtimeNanos();

        writeTimestamp(request, 40, t1Wall);

        byte[] clientTransmit = new byte[8];

        System.arraycopy(
                request,
                40,
                clientTransmit,
                0,
                8
        );

        try (DatagramSocket socket = new DatagramSocket()) {

            socket.setSoTimeout(TIMEOUT_MS);
            socket.connect(address, NTP_PORT);

            DatagramPacket outgoing = new DatagramPacket(
                    request,
                    request.length
            );

            socket.send(outgoing);

            byte[] response = new byte[PACKET_SIZE];

            DatagramPacket incoming = new DatagramPacket(
                    response,
                    response.length
            );

            socket.receive(incoming);

            long t4Nanos = SystemClock.elapsedRealtimeNanos();

            if (incoming.getLength() < PACKET_SIZE) {
                return null;
            }

            if (!address.equals(incoming.getAddress())
                    || incoming.getPort() != NTP_PORT) {

                return null;
            }

            int leap = (response[0] >> 6) & 0x03;
            int version = (response[0] >> 3) & 0x07;
            int mode = response[0] & 0x07;
            int stratum = response[1] & 0xFF;

            if (leap == 3
                    || (version != 3 && version != 4)
                    || mode != 4
                    || stratum < 1
                    || stratum > 15) {

                return null;
            }

            for (int i = 0; i < 8; i++) {

                if (response[24 + i] != clientTransmit[i]) {
                    return null;
                }
            }

            long t2 = readTimestamp(response, 32);
            long t3 = readTimestamp(response, 40);

            if (t2 <= 0 || t3 <= 0 || t3 < t2) {
                return null;
            }

            long elapsedNanos = Math.max(
                    0L,
                    t4Nanos - t1Nanos
            );

            double elapsedMs = elapsedNanos / 1_000_000.0;

            long t4 = t1Wall + Math.round(elapsedMs);

            double offset =
                    ((double) (t2 - t1Wall)
                            + (double) (t3 - t4)) / 2.0;

            long serverProcessing = t3 - t2;

            long networkRoundTrip = Math.max(
                    0L,
                    Math.round(elapsedMs - serverProcessing)
            );

            long estimatedEpoch =
                    t4 + Math.round(offset);

            if (estimatedEpoch < 1577836800000L
                    || estimatedEpoch > 4102444800000L) {

                return null;
            }

            return new Sample(
                    server,
                    offset,
                    estimatedEpoch,
                    SystemClock.elapsedRealtime(),
                    networkRoundTrip
            );
        }
    }

    private static void writeTimestamp(
            byte[] data,
            int offset,
            long unixMillis
    ) {

        long seconds =
                unixMillis / 1000L + NTP_EPOCH_OFFSET;

        long millis = unixMillis % 1000L;

        long fraction =
                (millis << 32) / 1000L;

        writeUnsignedInt(data, offset, seconds);
        writeUnsignedInt(data, offset + 4, fraction);
    }

    private static void writeUnsignedInt(
            byte[] data,
            int offset,
            long value
    ) {

        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static long readTimestamp(
            byte[] data,
            int offset
    ) {

        long seconds = readUnsignedInt(data, offset);
        long fraction = readUnsignedInt(data, offset + 4);

        long unixSeconds =
                seconds - NTP_EPOCH_OFFSET;

        long fractionMillis =
                (fraction * 1000L) >>> 32;

        return unixSeconds * 1000L + fractionMillis;
    }

    private static long readUnsignedInt(
            byte[] data,
            int offset
    ) {

        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | ((long) (data[offset + 3] & 0xFF));
    }

    private static double median(List<Double> values) {

        if (values == null || values.isEmpty()) {
            return 0.0;
        }

        List<Double> sorted = new ArrayList<>(values);

        Collections.sort(sorted);

        int middle = sorted.size() / 2;

        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }

        return (sorted.get(middle - 1)
                + sorted.get(middle)) / 2.0;
    }

    private static double maxDeviation(
            List<Double> values,
            double center
    ) {

        double maximum = 0.0;

        for (double value : values) {

            maximum = Math.max(
                    maximum,
                    Math.abs(value - center)
            );
        }

        return maximum;
    }

    private static class Sample {

        final String server;
        final double offsetMs;
        final long estimatedEpochMs;
        final long receivedElapsedMs;
        final long networkRoundTripMs;

        Sample(
                String server,
                double offsetMs,
                long estimatedEpochMs,
                long receivedElapsedMs,
                long networkRoundTripMs
        ) {

            this.server = server;
            this.offsetMs = offsetMs;
            this.estimatedEpochMs = estimatedEpochMs;
            this.receivedElapsedMs = receivedElapsedMs;
            this.networkRoundTripMs = networkRoundTripMs;
        }
    }

    private static class HostResult {

        final String server;
        final List<Sample> samples;
        final double medianOffset;

        HostResult(
                String server,
                List<Sample> samples,
                double medianOffset
        ) {

            this.server = server;
            this.samples = samples;
            this.medianOffset = medianOffset;
        }
    }

    public static class Result {

        public final long offsetMs;
        public final long estimatedEpochAtReceiveMs;
        public final long receivedElapsedRealtimeMs;
        public final long networkRoundTripMs;
        public final long uncertaintyEstimateMs;
        public final long maxOffsetSpreadMs;
        public final int independentServerCount;
        public final int sampleCount;
        public final String server;
        public final boolean isCrossChecked;
        public final boolean withinTarget50ms;

        Result(
                long offsetMs,
                long estimatedEpochAtReceiveMs,
                long receivedElapsedRealtimeMs,
                long networkRoundTripMs,
                long uncertaintyEstimateMs,
                long maxOffsetSpreadMs,
                int independentServerCount,
                int sampleCount,
                String server,
                boolean isCrossChecked,
                boolean withinTarget50ms
        ) {

            this.offsetMs = offsetMs;
            this.estimatedEpochAtReceiveMs =
                    estimatedEpochAtReceiveMs;

            this.receivedElapsedRealtimeMs =
                    receivedElapsedRealtimeMs;

            this.networkRoundTripMs = networkRoundTripMs;

            this.uncertaintyEstimateMs =
                    uncertaintyEstimateMs;

            this.maxOffsetSpreadMs = maxOffsetSpreadMs;

            this.independentServerCount =
                    independentServerCount;

            this.sampleCount = sampleCount;
            this.server = server;
            this.isCrossChecked = isCrossChecked;
            this.withinTarget50ms = withinTarget50ms;
        }
    }
}
