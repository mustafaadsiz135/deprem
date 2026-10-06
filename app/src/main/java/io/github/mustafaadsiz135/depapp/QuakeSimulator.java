package io.github.mustafaadsiz135.depapp;

/**
 * Sunum ve test için yapay deprem sinyali üretir.
 * Önce zayıf ve daha dikey P dalgası, ardından güçlü yatay S dalgası,
 * sonra sönümlenen sarsıntı. Sinyal gerçek sensör verisinin üzerine eklenir.
 */
public class QuakeSimulator {

    public static final double DURATION = 16.0;
    private static final double AMPLITUDE = 0.8; // m/s² (~%8 g, orta şiddette sarsıntı)

    private double start = -1;

    public void start(double t) { start = t; }

    public boolean isActive(double t) {
        return start >= 0 && t >= start && t - start < DURATION;
    }

    /** t anında eklenecek ivme (x, y, z). Etkin değilse sıfır döner. */
    public double[] offset(double t) {
        double[] o = new double[3];
        if (!isActive(t)) return o;
        double s = t - start;
        double p, sw;
        if (s < 2.0) {          // P dalgası
            p = 0.25 * (s / 2.0);
            sw = 0;
        } else if (s < 3.0) {   // S dalgası geliyor
            p = 0.25;
            sw = s - 2.0;
        } else if (s < 9.0) {   // en güçlü sarsıntı
            p = 0.2;
            sw = 1.0;
        } else {                // sönümlenme
            double d = Math.exp(-(s - 9.0) / 2.0);
            p = 0.2 * d;
            sw = d;
        }
        double w = 2 * Math.PI;
        double a = AMPLITUDE;
        o[0] = a * sw * (0.6 * Math.sin(w * 2.1 * s) + 0.4 * Math.sin(w * 4.3 * s + 1.0))
                + a * p * 0.3 * Math.sin(w * 7.0 * s);
        o[1] = a * sw * (0.5 * Math.sin(w * 1.6 * s + 2.0) + 0.5 * Math.sin(w * 3.7 * s));
        o[2] = a * p * Math.sin(w * 6.2 * s) + a * sw * 0.25 * Math.sin(w * 5.1 * s + 0.5);
        return o;
    }
}
