package io.github.mustafaadsiz135.depapp;

/**
 * Telefon sensörlerinden deprem sarsıntısı algılayan çekirdek algoritma.
 * Android'e bağımlı değildir; bu sayede bilgisayarda da test edilebilir.
 *
 * Adımlar:
 *  1) Yerçekimi, alçak geçiren filtre ile ivmeden ayıklanır (doğrusal ivme).
 *  2) STA/LTA: kısa süreli ortalama (0,5 s) / uzun süreli ortalama (10 s).
 *     Sismolojide deprem dalgasının başlangıcını yakalamak için kullanılan klasik yöntem.
 *  3) Jiroskop: telefon döndürülüyorsa (elde, cepte, taşınıyor) algılama durdurulur.
 *  4) Sarsıntı en az "minDuration" saniye sürmeli ve sürekli olmalı
 *     (tepe/RMS oranı düşük, aktif örnek oranı yüksek). Kapı çarpması,
 *     ayak sesi gibi kısa darbeler böylece elenir.
 */
public class QuakeDetector {

    public static final int STATE_CALIBRATING = 0;
    public static final int STATE_MONITORING = 1;
    public static final int STATE_UNSTABLE = 2;
    public static final int STATE_SUSPICIOUS = 3;
    public static final int STATE_QUAKE = 4;

    public static final int EVT_NONE = 0;
    public static final int EVT_ALARM = 1;
    public static final int EVT_END = 2;

    // Sabitler (saniye, m/s², rad/s)
    private static final double T_STA = 0.5;
    private static final double T_LTA = 10.0;
    private static final double T_LTA_FAST = 2.0;
    private static final double T_GRAVITY = 1.0;
    private static final double T_GYRO = 0.3;
    private static final double CALIBRATION_TIME = 8.0;
    private static final double UNSTABLE_HOLD = 3.0;
    private static final double GYRO_IDLE_LIMIT = 0.25;
    private static final double GYRO_TRIGGERED_LIMIT = 0.8;
    private static final double RATIO_OFF = 1.6;
    private static final double OFF_HOLD = 1.5;
    private static final double MAX_CREST = 5.0;
    private static final double MIN_ACTIVE_FRACTION = 0.6;
    private static final double DECISION_WINDOW = 4.0;
    private static final double MAX_EVENT = 120.0;
    private static final double LTA_FLOOR = 0.004;

    // Hassasiyet seviyeleri 1..5
    private static final double[] LEVEL_MIN_AMP = {0.30, 0.18, 0.10, 0.06, 0.04};
    private static final double[] LEVEL_RATIO_ON = {6.0, 5.0, 4.0, 3.5, 3.0};
    private static final double[] LEVEL_MIN_DURATION = {2.0, 1.5, 1.2, 1.0, 0.8};

    private volatile double minAmp = LEVEL_MIN_AMP[2];
    private volatile double ratioOn = LEVEL_RATIO_ON[2];
    private volatile double minDuration = LEVEL_MIN_DURATION[2];

    private boolean initialized = false;
    private double tStart, tPrev;
    private double gx, gy, gz;
    private double linX, linY, linZ, mag;
    private double sta, lta, ratio, gyroS;
    private int state = STATE_CALIBRATING;
    private double unstableUntil = -1;

    private boolean triggered = false;
    private boolean alarmed = false;
    private double trigStart, belowSince = -1, sumSq, peak;
    private int n, active;

    private double eventPeak, eventDuration;

    public void setSensitivity(int level) {
        int i = Math.max(1, Math.min(5, level)) - 1;
        minAmp = LEVEL_MIN_AMP[i];
        ratioOn = LEVEL_RATIO_ON[i];
        minDuration = LEVEL_MIN_DURATION[i];
    }

    public void reset() {
        initialized = false;
        triggered = false;
        alarmed = false;
        state = STATE_CALIBRATING;
        unstableUntil = -1;
    }

    /**
     * Yeni bir ivmeölçer örneği işler.
     * @param t        zaman (saniye)
     * @param ax,ay,az ham ivme (m/s², yerçekimi dahil)
     * @param gyroMag  dönme hızı büyüklüğü (rad/s), jiroskop yoksa 0
     * @return EVT_NONE, EVT_ALARM (deprem algılandı) veya EVT_END (olay bitti)
     */
    public int feed(double t, double ax, double ay, double az, double gyroMag) {
        if (!initialized) {
            initialized = true;
            tStart = t;
            tPrev = t;
            gx = ax; gy = ay; gz = az;
            sta = 0;
            lta = 0.02;
            gyroS = gyroMag;
            state = STATE_CALIBRATING;
            return EVT_NONE;
        }
        double dt = t - tPrev;
        tPrev = t;
        if (dt <= 0) return EVT_NONE;
        if (dt > 0.2) dt = 0.2;

        // 1) Yerçekimini ayıkla
        double kg = Math.min(1.0, dt / T_GRAVITY);
        gx += (ax - gx) * kg;
        gy += (ay - gy) * kg;
        gz += (az - gz) * kg;
        linX = ax - gx;
        linY = ay - gy;
        linZ = az - gz;
        mag = Math.sqrt(linX * linX + linY * linY + linZ * linZ);

        // 3) Jiroskop: telefon hareket ediyor mu?
        gyroS += (gyroMag - gyroS) * Math.min(1.0, dt / T_GYRO);
        boolean calibrating = (t - tStart) < CALIBRATION_TIME;
        double gyroLimit = triggered ? GYRO_TRIGGERED_LIMIT : GYRO_IDLE_LIMIT;
        boolean movingNow = gyroS > gyroLimit;
        if (movingNow) unstableUntil = t + UNSTABLE_HOLD;
        boolean unstable = movingNow || t < unstableUntil;

        // 2) STA / LTA
        sta += (mag - sta) * Math.min(1.0, dt / T_STA);
        if (!triggered) {
            double tl = (calibrating || unstable) ? T_LTA_FAST : T_LTA;
            lta += (mag - lta) * Math.min(1.0, dt / tl);
        }
        ratio = sta / Math.max(lta, LTA_FLOOR);

        if (calibrating) {
            state = STATE_CALIBRATING;
            return EVT_NONE;
        }

        if (unstable) {
            int ev = EVT_NONE;
            if (triggered && movingNow) {
                if (alarmed) {
                    eventPeak = peak;
                    eventDuration = t - trigStart;
                    ev = EVT_END;
                }
                triggered = false;
                alarmed = false;
            }
            if (!triggered) {
                state = STATE_UNSTABLE;
                return ev;
            }
        }

        if (!triggered) {
            if (ratio > ratioOn && sta > minAmp) {
                triggered = true;
                alarmed = false;
                trigStart = t;
                belowSince = -1;
                sumSq = 0;
                peak = 0;
                n = 0;
                active = 0;
            } else {
                state = STATE_MONITORING;
                return EVT_NONE;
            }
        }

        // Tetiklenmiş durum: sarsıntıyı analiz et
        n++;
        sumSq += mag * mag;
        if (mag > peak) peak = mag;
        // Anlık ivme gürültünün belirgin üstündeyse bu örnek "aktif" sayılır.
        // Deprem sürekli sallar; darbe (kapı, ayak sesi) ise hemen söner.
        if (mag > Math.max(minAmp * 0.4, lta * 2.0)) active++;
        double elapsed = t - trigStart;

        if (ratio < RATIO_OFF) {
            if (belowSince < 0) belowSince = t;
        } else {
            belowSince = -1;
        }
        boolean ended = (belowSince >= 0 && t - belowSince >= OFF_HOLD) || elapsed > MAX_EVENT;
        if (ended) {
            int ev = EVT_NONE;
            if (alarmed) {
                eventPeak = peak;
                eventDuration = (belowSince >= 0 ? belowSince : t) - trigStart;
                ev = EVT_END;
            }
            triggered = false;
            alarmed = false;
            state = STATE_MONITORING;
            return ev;
        }

        // 4) Süre ve süreklilik kontrolü
        if (!alarmed && elapsed >= minDuration && elapsed <= minDuration + DECISION_WINDOW) {
            double rms = Math.sqrt(sumSq / n);
            double crest = rms > 0 ? peak / rms : 99;
            double frac = (double) active / n;
            if (crest < MAX_CREST && frac >= MIN_ACTIVE_FRACTION) {
                alarmed = true;
                state = STATE_QUAKE;
                return EVT_ALARM;
            }
        }
        state = alarmed ? STATE_QUAKE : STATE_SUSPICIOUS;
        return EVT_NONE;
    }

    public int getState() { return state; }
    public double getSta() { return sta; }
    public double getLta() { return lta; }
    public double getRatio() { return ratio; }
    public double getGyro() { return gyroS; }
    public double getLinX() { return linX; }
    public double getLinY() { return linY; }
    public double getLinZ() { return linZ; }
    public double getMag() { return mag; }
    public double getMinAmp() { return minAmp; }
    public double getCurrentPeak() { return peak; }
    public double getEventPeak() { return eventPeak; }
    public double getEventDuration() { return eventDuration; }

    /**
     * En yüksek yer ivmesinden (PGA) tahmini Mercalli şiddeti.
     * Wald ve diğ. (1999) bağıntısı; PGA cm/s² (gal) cinsinden.
     */
    public static double estimateMmi(double pgaMs2) {
        double gal = pgaMs2 * 100.0;
        if (gal <= 0) return 1.0;
        double l = Math.log10(gal);
        double mmi = 3.66 * l - 1.66;
        if (mmi < 5.0) mmi = 2.20 * l + 1.00;
        return Math.max(1.0, Math.min(10.0, mmi));
    }

    public static String toRoman(double mmi) {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        int i = (int) Math.round(mmi);
        i = Math.max(1, Math.min(10, i));
        return r[i - 1];
    }
}
