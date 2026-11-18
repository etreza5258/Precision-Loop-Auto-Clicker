
package com.precision.loopclicker;

import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final long MIN_VALID_EPOCH_MS =
            1577836800000L;

    private static final long MAX_VALID_EPOCH_MS =
            4102444800000L;

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
                hostOffsets.add(host.medianOffsetNanos);
            }

            double centerOffsetNanos = median(hostOffsets);

            List<Double> deviations = new ArrayList<>();

            for (double offset : hostOffsets) {

                deviations.add(
                        Math.abs(offset - centerOffsetNanos)
                );
            }

            double mad = median(deviations);

            double rejectionLimitNanos = Math.max(
                    15.0 * NANOS_PER_MILLI,
                    mad * 3.0
            );

            List<HostResult> acceptedHosts = new ArrayList<>();

            for (HostResult host : hostResults) {

                if (Math.abs(
                        host.medianOffsetNanos - centerOffsetNanos
                ) <= rejectionLimitNanos) {

                    acceptedHosts.add(host);
                }
            }

            if (acceptedHosts.isEmpty()) {
                return null;
            }

            List<Double> acceptedOffsets = new ArrayList<>();

            List<Sample> acceptedSamples = new ArrayList<>();

            for (HostResult host : acceptedHosts) {

                acceptedOffsets.add(host.medianOffsetNanos);

                acceptedSamples.addAll(host.samples);
            }

            double finalOffsetNanos = median(acceptedOffsets);

            double minOffsetNanos =
                    Collections.min(acceptedOffsets);

            double maxOffsetNanos =
                    Collections.max(acceptedOffsets);

            double spreadNanos =
                    maxOffsetNanos - minOffsetNanos;

            Sample bestSample = Collections.min(
                    acceptedSamples,
                    Comparator.comparingLong(
                            sample -> sample.networkRoundTripNanos
                    )
            );

            /*
             * ثبت یک جفت زمانی نزدیک به هم برای ساخت مرجع ساعت.
             * زمان سپری‌شده از ساعت monotonic گرفته می‌شود.
             */
            long receiveElapsedBefore =
                    SystemClock.elapsedRealtimeNanos();

            long receiveWallMs =
                    System.currentTimeMillis();

            long receiveElapsedAfter =
                    SystemClock.elapsedRealtimeNanos();

            long receivedElapsedNanos =
                    receiveElapsedBefore
                            + (receiveElapsedAfter
                            - receiveElapsedBefore) / 2L;

            long receiveWallEpochNanos =
                    receiveWallMs * NANOS_PER_MILLI;

            long estimatedEpochAtReceiveNanos =
                    receiveWallEpochNanos
                            + Math.round(finalOffsetNanos);

            /*
             * عدم‌قطعیت تخمینی:
             * نصف RTT + اختلاف سرورها + یک میلی‌ثانیه
             * برای درنظرگرفتن دقت ساعت دیوایس.
             */
            double uncertaintyNanos =
                    bestSample.networkRoundTripNanos / 2.0
                            + maxDeviation(
                                    acceptedOffsets,
                                    finalOffsetNanos
                            )
                            + NANOS_PER_MILLI;

            long uncertaintyEstimateNanos =
                    (long) Math.ceil(uncertaintyNanos);

            boolean crossChecked =
                    acceptedHosts.size() >= 2;

            boolean withinTarget =
                    crossChecked
                            && spreadNanos
                            <= 50.0 * NANOS_PER_MILLI
                            && uncertaintyEstimateNanos
                            <= 50L * NANOS_PER_MILLI;

            /*
             * فیلدهای قدیمی میلی‌ثانیه‌ای حفظ شده‌اند
             * تا MainActivity فعلی نیاز به تغییر نداشته باشد.
             */
            long offsetMs = Math.round(
                    finalOffsetNanos / NANOS_PER_MILLI
            );

            long estimatedEpochAtReceiveMs =
                    Math.round(
                            estimatedEpochAtReceiveNanos
                                    / (double) NANOS_PER_MILLI
                    );

            long receivedElapsedRealtimeMs =
                    receivedElapsedNanos / NANOS_PER_MILLI;

            long networkRoundTripMs =
                    Math.round(
                            bestSample.networkRoundTripNanos
                                    / (double) NANOS_PER_MILLI
                    );

            long uncertaintyEstimateMs =
                    (long) Math.ceil(
                            uncertaintyEstimateNanos
                                    / (double) NANOS_PER_MILLI
                    );

            long maxOffsetSpreadMs =
                    (long) Math.ceil(
                            spreadNanos / NANOS_PER_MILLI
                    );

            return new Result(
                    offsetMs,
                    estimatedEpochAtReceiveMs,
                    receivedElapsedRealtimeMs,
                    networkRoundTripMs,
                    uncertaintyEstimateMs,
                    maxOffsetSpreadMs,
                    acceptedHosts.size(),
                    acceptedSamples.size(),
                    bestSample.server,
                    crossChecked,
                    withinTarget,
                    Math.round(finalOffsetNanos),
                    estimatedEpochAtReceiveNanos,
                    receivedElapsedNanos,
                    bestSample.networkRoundTripNanos,
                    uncertaintyEstimateNanos,
                    (long) Math.ceil(spreadNanos)
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
            offsets.add(sample.offsetNanos);
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

        try (DatagramSocket socket = new DatagramSocket()) {

            socket.setSoTimeout(TIMEOUT_MS);

            socket.connect(address, NTP_PORT);

            /*
             * ثبت جفت زمانی قبل از ارسال.
             * زمان wall برای epoch و elapsed برای اندازه‌گیری فاصله.
             */
            long t1ElapsedBefore =
                    SystemClock.elapsedRealtimeNanos();

            long t1WallMs =
                    System.currentTimeMillis();

            long t1ElapsedAfter =
                    SystemClock.elapsedRealtimeNanos();

            long t1ElapsedNanos =
                    t1ElapsedBefore
                            + (t1ElapsedAfter
                            - t1ElapsedBefore) / 2L;

            long t1EpochNanos =
                    t1WallMs * NANOS_PER_MILLI;

            writeTimestamp(
                    request,
                    40,
                    t1EpochNanos
            );

            byte[] clientTransmit = new byte[8];

            System.arraycopy(
                    request,
                    40,
                    clientTransmit,
                    0,
                    8
            );

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

            // ثبت زمان دریافت بلافاصله پس از دریافت پاسخ
            long t4ElapsedNanos =
                    SystemClock.elapsedRealtimeNanos();

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

            long t2EpochNanos =
                    readTimestampNanos(response, 32);

            long t3EpochNanos =
                    readTimestampNanos(response, 40);

            if (t2EpochNanos <= 0
                    || t3EpochNanos <= 0
                    || t3EpochNanos < t2EpochNanos) {

                return null;
            }

            long elapsedNanos = Math.max(
                    0L,
                    t4ElapsedNanos - t1ElapsedNanos
            );

            /*
             * تخمین زمان epoch در لحظه دریافت پاسخ.
             * از ساعت monotonic برای گذشت زمان استفاده می‌شود.
             */
            long t4EpochNanos =
                    t1EpochNanosToEpoch(
                            t1EpochNanos,
                            elapsedNanos
                    );

            /*
             * فرمول استاندارد offset در NTP:
             *
             * offset = ((t2 - t1) + (t3 - t4)) / 2
             */
            double offsetNanos =
                    (
                            (double) (t2EpochNanos - t1EpochNanos)
                                    + (double) (t3EpochNanos - t4EpochNanos)
                    ) / 2.0;

            long serverProcessingNanos =
                    t3EpochNanos - t2EpochNanos;

            long networkRoundTripNanos = Math.max(
                    0L,
                    elapsedNanos - serverProcessingNanos
            );

            long estimatedEpochNanos =
                    t4EpochNanos + Math.round(offsetNanos);

            long estimatedEpochMs =
                    estimatedEpochNanos / NANOS_PER_MILLI;

            if (estimatedEpochMs < MIN_VALID_EPOCH_MS
                    || estimatedEpochMs > MAX_VALID_EPOCH_MS) {

                return null;
            }

            return new Sample(
                    server,
                    offsetNanos,
                    estimatedEpochNanos,
                    t4ElapsedNanos,
                    networkRoundTripNanos
            );
        }
    }

    private static long t1EpochNanosToEpoch(
            long t1EpochNanos,
            long elapsedNanos
    ) {

        return t1EpochNanos + elapsedNanos;
    }

    /*
     * تبدیل timestamp یونیکس نانوثانیه‌ای به قالب NTP.
     */
    private static void writeTimestamp(
            byte[] data,
            int offset,
            long unixEpochNanos
    ) {

        long unixSeconds =
                unixEpochNanos / NANOS_PER_SECOND;

        long nanos =
                unixEpochNanos % NANOS_PER_SECOND;

        long ntpSeconds =
                unixSeconds + NTP_EPOCH_OFFSET;

        long fraction =
                (nanos << 32) / NANOS_PER_SECOND;

        writeUnsignedInt(data, offset, ntpSeconds);
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

    /*
     * خواندن timestamp NTP و تبدیل آن به نانوثانیه یونیکس.
     */
    private static long readTimestampNanos(
            byte[] data,
            int offset
    ) {

        long seconds =
                readUnsignedInt(data, offset);

        long fraction =
                readUnsignedInt(data, offset + 4);

        long unixSeconds =
                seconds - NTP_EPOCH_OFFSET;

        long fractionNanos =
                (fraction * NANOS_PER_SECOND) >>> 32;

        return unixSeconds * NANOS_PER_SECOND
                + fractionNanos;
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
        final double offsetNanos;
        final long estimatedEpochNanos;
        final long receivedElapsedNanos;
        final long networkRoundTripNanos;

        Sample(
                String server,
                double offsetNanos,
                long estimatedEpochNanos,
                long receivedElapsedNanos,
                long networkRoundTripNanos
        ) {

            this.server = server;
            this.offsetNanos = offsetNanos;
            this.estimatedEpochNanos = estimatedEpochNanos;
            this.receivedElapsedNanos = receivedElapsedNanos;
            this.networkRoundTripNanos = networkRoundTripNanos;
        }
    }

    private static class HostResult {

        final String server;
        final List<Sample> samples;
        final double medianOffsetNanos;

        HostResult(
                String server,
                List<Sample> samples,
                double medianOffsetNanos
        ) {

            this.server = server;
            this.samples = samples;
            this.medianOffsetNanos = medianOffsetNanos;
        }
    }

    public static class Result {

        // فیلدهای قبلی برای سازگاری با MainActivity
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

        // فیلدهای جدید با واحد نانوثانیه
        public final long offsetNanos;
        public final long estimatedEpochAtReceiveNanos;
        public final long receivedElapsedRealtimeNanos;
        public final long networkRoundTripNanos;
        public final long uncertaintyEstimateNanos;
        public final long maxOffsetSpreadNanos;

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
                boolean withinTarget50ms,
                long offsetNanos,
                long estimatedEpochAtReceiveNanos,
                long receivedElapsedRealtimeNanos,
                long networkRoundTripNanos,
                long uncertaintyEstimateNanos,
                long maxOffsetSpreadNanos
        ) {

            this.offsetMs = offsetMs;
            this.estimatedEpochAtReceiveMs =
                    estimatedEpochAtReceiveMs;

            this.receivedElapsedRealtimeMs =
                    receivedElapsedRealtimeMs;

            this.networkRoundTripMs =
                    networkRoundTripMs;

            this.uncertaintyEstimateMs =
                    uncertaintyEstimateMs;

            this.maxOffsetSpreadMs =
                    maxOffsetSpreadMs;

            this.independentServerCount =
                    independentServerCount;

            this.sampleCount = sampleCount;
            this.server = server;
            this.isCrossChecked = isCrossChecked;
            this.withinTarget50ms = withinTarget50ms;

            this.offsetNanos = offsetNanos;

            this.estimatedEpochAtReceiveNanos =
                    estimatedEpochAtReceiveNanos;

            this.receivedElapsedRealtimeNanos =
                    receivedElapsedRealtimeNanos;

            this.networkRoundTripNanos =
                    networkRoundTripNanos;

            this.uncertaintyEstimateNanos =
                    uncertaintyEstimateNanos;

            this.maxOffsetSpreadNanos =
                    maxOffsetSpreadNanos;
        }
    }
}
