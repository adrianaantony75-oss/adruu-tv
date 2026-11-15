package com.adruu.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Generates ADRUU's sounds in code: a soft menu click and the jet-landing intro. No audio files. */
final class Synth {
    private static final int RATE = 22050;
    private static AudioTrack click;

    private Synth() {}

    static synchronized void click() {
        try {
            if (click == null) click = track(makeClick());
            click.stop();
            click.reloadStaticData();
            click.play();
        } catch (Exception ignored) {
        }
    }

    /** Plays the startup sound once: jet landing, touchdown, roll-out, then a cabin chime. */
    static void intro() {
        new Thread(() -> {
            try {
                short[] pcm = makeLanding();
                AudioTrack t = track(pcm);
                t.play();
                Thread.sleep(pcm.length * 1000L / RATE + 200);
                t.release();
            } catch (Exception ignored) {
            }
        }, "adruu-engine").start();
    }

    /**
     * Media usage, not "system sound": this TV has system sounds switched off, which silenced the intro.
     * Media sound goes through the normal volume to the TV speakers or the soundbar.
     */
    private static AudioTrack track(short[] pcm) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(pcm, 0, pcm.length);
        return t;
    }

    private static short[] makeClick() {
        int n = RATE * 22 / 1000;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.exp(-t * 260);
            double v = Math.sin(2 * Math.PI * 1900 * t) * 0.6 + Math.sin(2 * Math.PI * 3100 * t) * 0.25;
            s[i] = (short) (v * env * 9000);
        }
        return s;
    }

    /**
     * Timed to IntroView: approach 0-2.0 s (jet roar + turbine whine), touchdown thump at 2.0 s,
     * reverse-thrust roar while braking until 2.8 s, engines spool down, two-tone chime at 4.8 s.
     */
    private static short[] makeLanding() {
        double dur = 6.0;
        int n = (int) (RATE * dur);
        short[] s = new short[n];
        java.util.Random r = new java.util.Random(11);
        double lp = 0, lp2 = 0, whine = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            // roar: low-passed noise, louder during reverse thrust, then spooling down
            double roarAmp = t < 2.0 ? 0.30 + 0.10 * t / 2.0
                    : t < 2.8 ? 0.62
                    : Math.max(0, 0.62 * (1 - (t - 2.8) / 0.9));
            double cut = t < 2.8 ? 0.10 : Math.max(0.03, 0.10 - (t - 2.8) * 0.08);
            lp += cut * ((r.nextDouble() * 2 - 1) - lp);
            lp2 += 0.25 * (lp - lp2);
            double v = lp2 * roarAmp * 3.2;
            // turbine whine, pitch falling as the engines slow
            double wf = t < 2.8 ? 1450 - 120 * t : Math.max(500, 1114 - (t - 2.8) * 600);
            double wa = t < 2.8 ? 0.07 : Math.max(0, 0.07 * (1 - (t - 2.8) / 0.9));
            whine += wf / RATE;
            v += Math.sin(2 * Math.PI * whine) * wa;
            // touchdown thump + tyre chirp
            if (t >= 2.0) {
                double d = t - 2.0;
                v += Math.sin(2 * Math.PI * 52 * d) * Math.exp(-d * 11) * 0.85;
                if (d < 0.12) v += (r.nextDouble() * 2 - 1) * Math.exp(-d * 40) * 0.35;
            }
            // cabin chime: two soft tones
            v += chime(t - 4.8, 880) + chime(t - 5.05, 659);
            double env = Math.min(1, t / 0.6);
            v = Math.tanh(v * 1.4) * env;
            s[i] = (short) (v * 16000);
        }
        return s;
    }

    private static double chime(double d, double f) {
        if (d < 0) return 0;
        double e = Math.exp(-d * 3.2) * Math.min(1, d / 0.01);
        return (Math.sin(2 * Math.PI * f * d) + 0.2 * Math.sin(4 * Math.PI * f * d)) * e * 0.32;
    }
}
