
package Tallaght;

import robocode.*;
import robocode.util.Utils;

import java.awt.Color;
import java.util.*;

/**
* PhilosopherBotG4 — melee survival bot vs. BorderSentry + aggressive bots.
*
* - Movement: wave-surfing + minimum-risk + sentry-like hazard model + avoidance of clusters and nearby enemies.
* - Fire: fire manager, limited BURST, reduced fire rate in dire situations.
* - Targeting: pattern-match / circular / linear.
* - States: AGGRESSIVE / CONTROLLED / SURVIVAL (sniper-kite at low HP).
*/
public class PhilosopherBotG4 extends Robot {

    // ─────────────────────────────────────────────────────────────
    // CONSTANTS
    // ─────────────────────────────────────────────────────────────

    private static final double WALL_MARGIN = 55.0;
    private static final double SAFE_MARGIN = 280.0;
    private static final double SENTRY_BORDER = 300.0;
    private static final int BLIND_EVADE = 28;

    private static final int PM_BUFFER_LEN = 100;
    private static final int PM_MATCH_LEN = 5;
    private static final double PM_VEL_W = 2.0;
    private static final double PM_HDG_W = 0.05;

    private static final int MRM_CANDIDATES = 12;

    private static final int FIRE_LOG_LEN = 40;

    private static final int MODE_AGGRESSIVE = 0;
    private static final int MODE_CONTROLLED = 1;
    private static final int MODE_SURVIVAL = 2;

    private static final int MOVE_WALLHUGGER = 0;
    private static final int MOVE_ZIGZAG = 1;
    private static final int MOVE_PENDULUM = 2;
    private static final int MOVE_CENTER_ORBIT = 3;

    // ─────────────────────────────────────────────────────────────
    // FIELDS
    // ─────────────────────────────────────────────────────────────

    private double fieldWidth, fieldHeight;
    private int moveDir = 1;
    private Random rand = new Random();

    private Map<String, double[]> enemyData = new HashMap<String, double[]>();
    private Map<String, Double> enemyEnergy = new HashMap<String, Double>();
    private Map<String, EnemyProfile> profiles = new HashMap<String, EnemyProfile>();

    private double sentryX = -1, sentryY = -1;

    private int macroTimer = 0;
    private int macroInterval = 180;
    private double macroTargetX = -1, macroTargetY = -1;

    private boolean sentryEvasionPending = false;
    private long sentryEvasionTime = -1;

    private double radarDir = 1;
    private int lostEnemyTimer = 0;

    private List<Wave> waves = new ArrayList<Wave>();

    private int sentryScanCount = 0, sentryHitCount = 0;
    private int sentryBlindEvades = 0;
    private double sentryTotalDamage = 0;
    private int enemyHitMeCount = 0;
    private double enemyTotalDamage = 0;

    private FireRecord[] fireLog = new FireRecord[FIRE_LOG_LEN];
    private int fireLogHead = 0;
    private int fireLogSize = 0;

    private int botMode = MODE_AGGRESSIVE;
    private int movementMode = MOVE_CENTER_ORBIT;
    private int patternStreak = 0;

    // ─────────────────────────────────────────────────────────────
    // INNER CLASSES
    // ─────────────────────────────────────────────────────────────

    static class EnemySnapshot {
        double velocity, heading, x, y;
        long time;
        EnemySnapshot(double v, double h, double x, double y, long t) {
            velocity = v;
            heading = h;
            this.x = x;
            this.y = y;
            time = t;
        }
    }

    static class EnemyProfile {
        double avgDeltaPos = 0, avgVelocity = 0;
        int scanCount = 0;
        double prevDelta1 = 0, prevDelta2 = 0;
        boolean isPendulum = false;
        int scansInCenter = 0;
        double sentryAwareness = 0;
        double pauseScore = 0, headingChange = 0;
        double zigzagScore = 0;
        double lastX = 0, lastY = 0, lastVelocity = 0, lastHeading = 0;
        int shotsFired = 0;
        String lastStrategy = "UNKNOWN";

        double confidence = 0.0;
        int ourShots = 0;
        int ourHits = 0;

        EnemySnapshot[] pmBuffer = new EnemySnapshot[PM_BUFFER_LEN];
        int pmHead = 0, pmSize = 0;

        void addSnapshot(EnemySnapshot s) {
            pmBuffer[pmHead] = s;
            pmHead = (pmHead + 1) % PM_BUFFER_LEN;
            if (pmSize < PM_BUFFER_LEN) pmSize++;
        }

        EnemySnapshot getFromTail(int offset) {
            if (offset >= pmSize) return null;
            int idx = (pmHead - 1 - offset + PM_BUFFER_LEN) % PM_BUFFER_LEN;
            return pmBuffer[idx];
        }

        void registerShot(boolean hit) {
            ourShots++;
            if (hit) ourHits++;
            double hitRate = ourShots == 0 ? 0.0 : (double) ourHits / ourShots;
            confidence = hitRate;
        }
    }

    static class Wave {
        double originX, originY;
        double bulletSpeed, firePower;
        long fireTime;
        double bulletHeadingRad;
        boolean isSentry;

        double distanceTraveled(long now) {
            return (now - fireTime) * bulletSpeed;
        }
    }

    static class FireRecord {
        String method;
        double power;
        double distance;
        long time;
        boolean hit;

        FireRecord(String method, double power, double distance, long time) {
            this.method = method;
            this.power = power;
            this.distance = distance;
            this.time = time;
            this.hit = false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // MAIN LOOP
    // ─────────────────────────────────────────────────────────────

    public void run() {
        fieldWidth = getBattleFieldWidth();
        fieldHeight = getBattleFieldHeight();

        setBodyColor(new Color(75, 0, 130));
        setGunColor(new Color(0, 255, 0));
        setRadarColor(new Color(255, 255, 255));
        setBulletColor(new Color(255, 0, 255));

        pickNewMacroTarget();
        out.println("[INIT] PhilosopherBotG4. Field=" + (int) fieldWidth + "x" + (int) fieldHeight);

        while (true) {
            macroTimer++;
            lostEnemyTimer++;

            updateBotMode();
            pruneWaves();

            // SURVIVAL: obvious enemy bounce
            if (botMode == MODE_SURVIVAL && !enemyData.isEmpty()) {
                String closest = null;
                double bestDist = Double.MAX_VALUE;
                for (Map.Entry<String, double[]> en : enemyData.entrySet()) {
                    double[] d = en.getValue();
                    double dist = Math.hypot(d[0] - getX(), d[1] - getY());
                    if (dist < bestDist) {
                        bestDist = dist;
                        closest = en.getKey();
                    }
                }
                if (closest != null && bestDist < 350) {
                    double[] d = enemyData.get(closest);
                    double angleFromEnemy = Math.toDegrees(Math.atan2(getX() - d[0], getY() - d[1]));
                    double turn = Utils.normalRelativeAngleDegrees(angleFromEnemy - getHeading());
                    turnRight(limit(turn, -25, 25));
                    ahead(120);
                }
            }

            // Sentry evasion
            if (sentryEvasionPending && getTime() >= sentryEvasionTime) {
                moveDir *= -1;
                turnRight(20 * moveDir);
                ahead(90 * moveDir);
                sentryEvasionPending = false;
            }

            // Blind evade
            if (getTime() % BLIND_EVADE == 0) {
                double blindTurn;
                if (sentryX >= 0) {
                    blindTurn = Math.max(-15, Math.min(15, perpAngleAwayFromSentry()));
                } else {
                    blindTurn = moveDir * (15 + rand.nextInt(15));
                }
                turnRight(blindTurn);
                sentryBlindEvades++;
            }

            // Macro
            if (macroTimer >= macroInterval) {
                pickNewMacroTarget();
                macroTimer = 0;
                macroInterval = 150 + rand.nextInt(100);
            }

            // Movement
            if (!waves.isEmpty()) {
                doWaveSurf();
            } else {
                doMinimumRiskMove();
            }

            // Radar
            doHybridRadar();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RADAR
    // ─────────────────────────────────────────────────────────────

    private void doHybridRadar() {
        if (getTime() % 5 == 0 && sentryX >= 0) {
            double angleToSentry = Math.toDegrees(Math.atan2(sentryX - getX(), sentryY - getY()));
            double turn = Utils.normalRelativeAngleDegrees(angleToSentry - getGunHeading());
            turnGunRight(turn * 0.35);
            return;
        }

        if (lostEnemyTimer > 2) {
            turnGunRight(360);
        } else {
            turnGunRight(40 * radarDir);
            radarDir *= -1;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // WAVE SURFING
    // ─────────────────────────────────────────────────────────────

    private void doWaveSurf() {
        Wave threat = getNearestWave();
        if (threat == null) {
            doMinimumRiskMove();
            return;
        }

        double dangerLeft = surfDanger(threat, -1);
        double dangerRight = surfDanger(threat, 1);
        double dangerStop = surfDanger(threat, 0);

        double best = Math.min(dangerLeft, Math.min(dangerRight, dangerStop));

        if (best == dangerStop && dangerStop < dangerLeft * 1.1 && dangerStop < dangerRight * 1.1) {
            return;
        }

        int dir = (dangerLeft < dangerRight) ? -1 : 1;

        double angleToWave = Math.toDegrees(Math.atan2(threat.originX - getX(), threat.originY - getY()));
        double perp = Utils.normalRelativeAngleDegrees(angleToWave + dir * 90 - getHeading());

        double jitter = getEnemyCount() <= 2 ? (5 + rand.nextDouble() * 5) : (2 + rand.nextDouble() * 3);
        perp += (rand.nextBoolean() ? jitter : -jitter);

        double step = 80 + rand.nextInt(40);
        if (Math.abs(perp) > 90) {
            turnRight(limit(perp + 180, -30, 30));
            ahead(-step);
        } else {
            turnRight(limit(perp, -30, 30));
            ahead(step);
        }
    }

    private double surfDanger(Wave w, int dir) {
        double simX = getX(), simY = getY();
        double simHeading = getHeading();
        double simSpeed = 8.0;

        for (int i = 0; i < 15; i++) {
            double angleToWave = Math.toDegrees(Math.atan2(w.originX - simX, w.originY - simY));
            double perp = Utils.normalRelativeAngleDegrees(angleToWave + dir * 90 - simHeading);
            simHeading += limit(perp, -10, 10);
            double rad = Math.toRadians(simHeading);
            if (dir != 0) {
                simX += Math.sin(rad) * simSpeed;
                simY += Math.cos(rad) * simSpeed;
            }
            simX = Math.max(WALL_MARGIN, Math.min(fieldWidth - WALL_MARGIN, simX));
            simY = Math.max(WALL_MARGIN, Math.min(fieldHeight - WALL_MARGIN, simY));
        }

        double waveAngle = w.bulletHeadingRad;
        double dx = simX - w.originX, dy = simY - w.originY;
        double cross = Math.abs(dx * Math.cos(waveAngle) - dy * Math.sin(waveAngle));
        double danger = 1.0 / Math.max(1, cross);

        double wallDist = Math.min(Math.min(simX, fieldWidth - simX), Math.min(simY, fieldHeight - simY));
        danger += 500.0 / Math.max(1, wallDist * wallDist);

        danger += 800.0 * sentryDanger(simX, simY);

        return danger;
    }

    private Wave getNearestWave() {
        Wave best = null;
        double minTime = Double.MAX_VALUE;
        for (Wave w : waves) {
            double dist = Math.hypot(getX() - w.originX, getY() - w.originY);
            double remaining = dist - w.distanceTraveled(getTime());
            if (remaining > 0 && remaining < minTime) {
                minTime = remaining;
                best = w;
            }
        }
        return best;
    }

    private void pruneWaves() {
        Iterator<Wave> it = waves.iterator();
        while (it.hasNext()) {
            Wave w = it.next();
            double dist = Math.hypot(getX() - w.originX, getY() - w.originY);
            if (w.distanceTraveled(getTime()) > dist + 60) {
                it.remove();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // MINIMUM-RISK MOVEMENT
    // ─────────────────────────────────────────────────────────────

    private void doMinimumRiskMove() {
        double bestRisk = Double.MAX_VALUE;
        double bestX = getX(), bestY = getY();

        for (int i = 0; i < MRM_CANDIDATES; i++) {
            double angle = i * (360.0 / MRM_CANDIDATES);
            double dist = 140 + rand.nextDouble() * 80;

            if (movementMode == MOVE_ZIGZAG) {
                dist = 100 + rand.nextDouble() * 60;
            } else if (movementMode == MOVE_PENDULUM) {
                dist = 70 + rand.nextDouble() * 40;
            }

            double cx = getX() + Math.sin(Math.toRadians(angle)) * dist;
            double cy = getY() + Math.cos(Math.toRadians(angle)) * dist;

            if (cx < WALL_MARGIN || cx > fieldWidth - WALL_MARGIN ||
                cy < WALL_MARGIN || cy > fieldHeight - WALL_MARGIN) {
                continue;
            }

            double risk = calcPointRisk(cx, cy);
            if (risk < bestRisk) {
                bestRisk = risk;
                bestX = cx;
                bestY = cy;
            }
        }

        navigateTo(bestX, bestY);
    }

    private double calcPointRisk(double px, double py) {
        double risk = 0;

        // base risk from all enemies
        for (double[] d : enemyData.values()) {
            double dist = Math.max(1, Math.hypot(px - d[0], py - d[1]));
            risk += 10000.0 / (dist * dist);
        }

        // closest enemy separately — avoid 0–350
        double closest = Double.MAX_VALUE;
        for (double[] d : enemyData.values()) {
            double dist = Math.hypot(px - d[0], py - d[1]);
            if (dist < closest) closest = dist;
        }
        if (closest < 350) {
            double k = (350 - closest) / 350.0;
            risk += 30000.0 * k * k;
        }

        // cluster of enemies
        for (double[] d : enemyData.values()) {
            double dist = Math.max(1, Math.hypot(px - d[0], py - d[1]));
            if (dist < 250) {
                risk += 15000.0 / (dist * dist);
            }
        }

        // sentry danger
        risk += 35000.0 * sentryDanger(px, py);

        double wallDist = Math.min(Math.min(px, fieldWidth - px), Math.min(py, fieldHeight - py));
        risk += 5000.0 / Math.max(1, wallDist * wallDist);

        for (Wave w : waves) {
            double traveled = w.distanceTraveled(getTime() + 10);
            double waveToPoint = Math.hypot(px - w.originX, py - w.originY);
            double proximity = Math.abs(waveToPoint - traveled);
            if (proximity < 80) {
                risk += 8000.0 / Math.max(1, proximity * proximity);
            }
        }

        if (macroTargetX >= 0) {
            double dist = Math.max(1, Math.hypot(px - macroTargetX, py - macroTargetY));
            risk -= 800.0 / dist;
        }

        return risk;
    }

    private void navigateTo(double tx, double ty) {
        double angle = Math.toDegrees(Math.atan2(tx - getX(), ty - getY()));
        double turn = Utils.normalRelativeAngleDegrees(angle - getHeading());
        double dist = Math.hypot(tx - getX(), ty - getY());
        double step = Math.min(dist, 130 + rand.nextInt(50));

        double jitter = getEnemyCount() <= 2 ? (4 + rand.nextDouble() * 4) : (2 + rand.nextDouble() * 2);
        turn += rand.nextBoolean() ? jitter : -jitter;

        if (Math.abs(turn) > 90) {
            turnRight(limit(turn + 180, -30, 30));
            ahead(-step);
        } else {
            turnRight(limit(turn, -30, 30));
            ahead(step);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // FIRE MANAGER
    // ─────────────────────────────────────────────────────────────

    private void logShot(String method, double power, double distance) {
        FireRecord r = new FireRecord(method, power, distance, getTime());
        fireLog[fireLogHead] = r;
        fireLogHead = (fireLogHead + 1) % FIRE_LOG_LEN;
        if (fireLogSize < FIRE_LOG_LEN) fireLogSize++;
    }

    private void logHit(String method) {
        for (int i = 0; i < fireLogSize; i++) {
            int idx = (fireLogHead - 1 - i + FIRE_LOG_LEN) % FIRE_LOG_LEN;
            FireRecord r = fireLog[idx];
            if (r != null && r.method.equals(method)) {
                r.hit = true;
                break;
            }
        }
    }

    private boolean canBurst(String method, double distance, double myEnergy) {
        if (myEnergy <= 40) return false;

        int considered = 0;
        int hits = 0;
        for (int i = 0; i < fireLogSize && considered < 3; i++) {
            int idx = (fireLogHead - 1 - i + FIRE_LOG_LEN) % FIRE_LOG_LEN;
            FireRecord r = fireLog[idx];
            if (r == null || !r.method.equals(method)) continue;
            considered++;
            if (r.hit) hits++;
        }
        return considered >= 2 && hits >= 1;
    }

    // ─────────────────────────────────────────────────────────────
    // ON SCANNED ROBOT
    // ─────────────────────────────────────────────────────────────

    public void onScannedRobot(ScannedRobotEvent e) {
        String lowerName = e.getName().toLowerCase();
        double absBearingRad = Math.toRadians(getHeading() + e.getBearing());
        double eX = getX() + Math.sin(absBearingRad) * e.getDistance();
        double eY = getY() + Math.cos(absBearingRad) * e.getDistance();

        // Sentry
        if (lowerName.contains("sentry") || lowerName.contains("border")) {
            sentryX = eX;
            sentryY = eY;
            sentryScanCount++;

            Wave w = new Wave();
            w.originX = eX;
            w.originY = eY;
            w.firePower = 2.0;
            w.bulletSpeed = 20 - 3 * w.firePower;
            w.fireTime = getTime();
            w.isSentry = true;
            w.bulletHeadingRad = Math.atan2(getX() - eX, getY() - eY);
            waves.add(w);

            double t = e.getDistance() / 11.0;
            sentryEvasionTime = getTime() + (long) (t * 0.4);
            sentryEvasionPending = true;
            return;
        }

        enemyData.put(e.getName(), new double[]{eX, eY, e.getDistance()});
        lostEnemyTimer = 0;

        EnemyProfile p = profiles.get(e.getName());
        if (p == null) {
            p = new EnemyProfile();
        }

        if (p.scanCount > 0) {
            double dPos = Math.hypot(eX - p.lastX, eY - p.lastY);
            double dVel = Math.abs(e.getVelocity() - p.lastVelocity);
            double dHed = Math.abs(Utils.normalRelativeAngleDegrees(e.getHeading() - p.lastHeading));

            p.avgDeltaPos = p.avgDeltaPos * 0.7 + dPos * 0.3;
            p.avgVelocity = p.avgVelocity * 0.7 + Math.abs(e.getVelocity()) * 0.3;

            boolean changedDir = dVel > 2.0 || dHed > 15;
            p.zigzagScore = p.zigzagScore * 0.8 + (changedDir ? 1.0 : 0.0) * 0.2;

            boolean bigNow = dPos > 40, bigPrev = p.prevDelta1 > 40, bigPrev2 = p.prevDelta2 > 40;
            p.isPendulum = (bigNow != bigPrev) && (bigPrev != bigPrev2);
            p.prevDelta2 = p.prevDelta1;
            p.prevDelta1 = dPos;

            boolean inCenter = eX > SAFE_MARGIN && eX < fieldWidth - SAFE_MARGIN
                    && eY > SAFE_MARGIN && eY < fieldHeight - SAFE_MARGIN;
            if (inCenter) p.scansInCenter++;
            p.sentryAwareness = (double) p.scansInCenter / Math.max(1, p.scanCount);
            p.headingChange = p.headingChange * 0.7 + dHed * 0.3;

            boolean slowingDown = e.getVelocity() < p.lastVelocity - 1.0;
            p.pauseScore = p.pauseScore * 0.8 + ((slowingDown && dHed > 15) ? 1.0 : 0.0) * 0.2;
        }

        Double prevE = enemyEnergy.get(e.getName());
        if (prevE == null) prevE = e.getEnergy();
        double drop = prevE - e.getEnergy();
        if (drop > 0.1 && drop <= 3.0) {
            Wave w = new Wave();
            w.originX = eX;
            w.originY = eY;
            w.firePower = drop;
            w.bulletSpeed = 20 - 3 * drop;
            w.fireTime = getTime();
            w.isSentry = false;
            w.bulletHeadingRad = Math.atan2(getX() - eX, getY() - eY);
            waves.add(w);
            moveDir *= -1;
        }

        p.addSnapshot(new EnemySnapshot(e.getVelocity(), e.getHeading(), eX, eY, getTime()));
        p.lastX = eX;
        p.lastY = eY;
        p.lastVelocity = e.getVelocity();
        p.lastHeading = e.getHeading();
        p.scanCount++;
        p.lastStrategy = classify(p);

        profiles.put(e.getName(), p);
        enemyEnergy.put(e.getName(), e.getEnergy());

        // ── FIRE ─────────────────────────────────────────────

        double enemyHp = enemyEnergy.get(e.getName());
        boolean nearDead = enemyHp < 25.0;
        boolean burstingAllowed = nearDead && canBurst(p.lastStrategy, e.getDistance(), getEnergy())
                && p.confidence > 0.4;

        double baseFp = 1.0;
        if (botMode == MODE_AGGRESSIVE) {
            if (e.getDistance() < 200) baseFp = 2.5;
            else if (e.getDistance() < 400) baseFp = 2.0;
            else baseFp = 1.5;
        } else if (botMode == MODE_CONTROLLED) {
            if (e.getDistance() < 250) baseFp = 2.0;
            else baseFp = 1.5;
        } else {
            baseFp = 1.0;
        }

        if ("UNKNOWN".equals(p.lastStrategy) || p.scanCount < 5) {
            baseFp = Math.min(baseFp, 1.5);
        }

        double fp = baseFp + 1.0 * p.confidence;

        if (!burstingAllowed) {
            fp = Math.min(fp, 3.0);
        } else {
            fp = 3.0;
        }

        if (getEnergy() < 40) {
            fp = Math.min(fp, 1.8);
        }

        if (botMode == MODE_SURVIVAL) {
            fp = Math.min(fp, 1.6);
            if (p.confidence < 0.35) {
                return;
            }
        }

        fp = Math.max(0.1, Math.min(3.0, fp));

        double bulletSpeed = 20 - 3 * fp;
        double timeToTarget = e.getDistance() / bulletSpeed;

        double[] aim = calcAimPoint(e, p, eX, eY, timeToTarget);
        aim[0] = Math.max(18, Math.min(fieldWidth - 18, aim[0]));
        aim[1] = Math.max(18, Math.min(fieldHeight - 18, aim[1]));

        double theta = Math.toDegrees(Math.atan2(aim[0] - getX(), aim[1] - getY()));
        double gunTurn = Utils.normalRelativeAngleDegrees(theta - getGunHeading());
        turnGunRight(gunTurn);

        double angleThreshold;
        if (burstingAllowed) {
            angleThreshold = 20.0;
        } else if (p.scanCount < 12) {
            angleThreshold = 15.0;
        } else {
            angleThreshold = 10.0;
        }

        double myAngleRad = Math.toRadians(getGunHeading());
        boolean shadowed = isBulletShadowed(myAngleRad, e.getDistance(), bulletSpeed);

        // situation: many enemies nearby or active wave close -> reduce fire rate
        int closeEnemies = 0;
        for (double[] d : enemyData.values()) {
            if (Math.hypot(d[0] - getX(), d[1] - getY()) < 300) closeEnemies++;
        }
        boolean badSignal = (e.getDistance() > 500 && p.scanCount < 10 && p.confidence < 0.25);

        boolean waveDanger = false;
        for (Wave w : waves) {
            if (w.isSentry) continue;
            double dist = Math.hypot(getX() - w.originX, getY() - w.originY);
            double remaining = dist - w.distanceTraveled(getTime());
            if (remaining > 0 && remaining < 60) {
                waveDanger = true;
                break;
            }
        }

        // heavy situation: shoot only every 3rd tick
        if ((closeEnemies >= 3 || waveDanger) && (getTime() % 3 != 0)) {
            return;
        }

        if (badSignal || closeEnemies >= 5) {
            return;
        }

        if (getGunHeat() == 0 && Math.abs(gunTurn) < angleThreshold && !shadowed) {
            fire(fp);
            p.shotsFired++;
            p.ourShots++;
            logShot(p.lastStrategy, fp, e.getDistance());
            out.println("[FIRE] method=" + p.lastStrategy +
                    " fp=" + String.format("%.1f", fp) +
                    (burstingAllowed ? " BURST" : "") +
                    " conf=" + String.format("%.2f", p.confidence));
        }

        if ("PATTERN".equals(p.lastStrategy)) {
            patternStreak++;
            if (patternStreak > 45) {
                switchMovementPattern();
                patternStreak = 0;
            }
        } else {
            patternStreak = 0;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AIMING
    // ─────────────────────────────────────────────────────────────

    private double[] calcAimPoint(ScannedRobotEvent e, EnemyProfile p,
                                  double eX, double eY, double time) {
        double[] pmAim = patternMatchAim(p, eX, eY, time);
        if (pmAim != null) {
            p.lastStrategy = "PATTERN";
            return pmAim;
        }

        if (p.zigzagScore > 0.25 && p.scanCount > 5) {
            p.lastStrategy = "CIRCULAR";
            return circularAim(e, eX, eY, time);
        }

        p.lastStrategy = "LINEAR";
        return new double[]{
                eX + Math.sin(e.getHeadingRadians()) * e.getVelocity() * time,
                eY + Math.cos(e.getHeadingRadians()) * e.getVelocity() * time
        };
    }

    private double[] patternMatchAim(EnemyProfile p, double eX, double eY, double time) {
        if (p.pmSize < PM_MATCH_LEN * 2 + 2) return null;

        double bestDist = Double.MAX_VALUE;
        int bestIdx = -1;

        EnemySnapshot[] current = new EnemySnapshot[PM_MATCH_LEN];
        for (int i = 0; i < PM_MATCH_LEN; i++) {
            current[i] = p.getFromTail(PM_MATCH_LEN - 1 - i);
            if (current[i] == null) return null;
        }

        int searchEnd = p.pmSize - PM_MATCH_LEN - 1;
        for (int i = 0; i < searchEnd; i++) {
            double d = 0;
            boolean valid = true;
            for (int j = 0; j < PM_MATCH_LEN; j++) {
                int bufIdx = (p.pmHead - p.pmSize + i + j + PM_BUFFER_LEN * 2) % PM_BUFFER_LEN;
                EnemySnapshot s = p.pmBuffer[bufIdx];
                if (s == null) {
                    valid = false;
                    break;
                }
                double dv = (s.velocity - current[j].velocity) * PM_VEL_W;
                double dh = Utils.normalRelativeAngleDegrees(s.heading - current[j].heading) * PM_HDG_W;
                d += dv * dv + dh * dh;
            }
            if (valid && d < bestDist) {
                bestDist = d;
                bestIdx = i + PM_MATCH_LEN;
            }
        }

        if (bestIdx < 0 || bestDist > 20.0) return null;

        int steps = (int) Math.ceil(time);
        double simX = eX, simY = eY;
        for (int k = 0; k < steps && k < 30; k++) {
            int bufIdx = (p.pmHead - p.pmSize + bestIdx + k + PM_BUFFER_LEN * 2) % PM_BUFFER_LEN;
            if (bufIdx < 0 || bufIdx >= PM_BUFFER_LEN) break;
            EnemySnapshot s = p.pmBuffer[bufIdx];
            if (s == null) break;
            simX += Math.sin(Math.toRadians(s.heading)) * s.velocity;
            simY += Math.cos(Math.toRadians(s.heading)) * s.velocity;
        }

        return new double[]{simX, simY};
    }

    private double[] circularAim(ScannedRobotEvent e, double eX, double eY, double time) {
        double lastHeading = e.getHeading();
        double turnRate = Math.toRadians(Utils.normalRelativeAngleDegrees(e.getHeading() - lastHeading));
        if (e.getVelocity() == 0) turnRate = 0;

        double simX = eX, simY = eY;
        double simHeading = e.getHeadingRadians();
        for (int i = 0; i < (int) time; i++) {
            simHeading += turnRate;
            simX += Math.sin(simHeading) * e.getVelocity();
            simY += Math.cos(simHeading) * e.getVelocity();
        }

        return new double[]{simX, simY};
    }

    // ─────────────────────────────────────────────────────────────
    // BULLET SHADOW
    // ─────────────────────────────────────────────────────────────

    private boolean isBulletShadowed(double myAngleRad, double targetDist, double myBulletSpeed) {
        for (Wave w : waves) {
            if (!w.isSentry) continue;

            double myDx = Math.sin(myAngleRad), myDy = Math.cos(myAngleRad);
            double wDx = Math.sin(w.bulletHeadingRad), wDy = Math.cos(w.bulletHeadingRad);

            double denom = myDx * wDy - myDy * wDx;
            if (Math.abs(denom) < 1e-6) continue;

            double ox = w.originX - getX(), oy = w.originY - getY();
            double tMine = (ox * wDy - oy * wDx) / denom;
            double tSentry = (ox * myDy - oy * wDx) / denom;

            if (tMine <= 0 || tSentry <= 0) continue;
            if (tMine > targetDist) continue;

            double timeMine = tMine / myBulletSpeed;
            double timeSentry = tSentry / w.bulletSpeed;

            if (Math.abs(timeMine - timeSentry) < 2) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // CLASSIFIER
    // ─────────────────────────────────────────────────────────────

    private String classify(EnemyProfile p) {
        if (p.scanCount < 3) return "UNKNOWN";
        if (p.avgVelocity < 0.5) return "CAMPER";
        if (p.zigzagScore > 0.25) return "ZIGZAGGER";
        if (p.isPendulum) return "PENDULUM";
        if (p.avgVelocity > 4.0 && p.headingChange < 5.0) return "LINEAR";
        return "WALLHUGGER";
    }

    // ─────────────────────────────────────────────────────────────
    // SENTRY DANGER
    // ─────────────────────────────────────────────────────────────

    private double sentryDanger(double x, double y) {
        double dx = Math.min(x, fieldWidth - x);
        double dy = Math.min(y, fieldHeight - y);

        double danger = 0.0;

        if (dx < SENTRY_BORDER) {
            double k = 1.0 - dx / SENTRY_BORDER;
            danger += k * k;
        }
        if (dy < SENTRY_BORDER) {
            double k = 1.0 - dy / SENTRY_BORDER;
            danger += k * k;
        }

        double innerMargin = SAFE_MARGIN;
        double outerMargin = fieldWidth - SAFE_MARGIN;

        if (x > innerMargin && x < outerMargin && y > innerMargin && y < outerMargin) {
            danger *= 0.4;
        }

        if (botMode == MODE_SURVIVAL) {
            danger *= 1.5;
        }

        return danger;
    }

    private double perpAngleAwayFromSentry() {
        if (sentryX < 0) return 0;
        double a = Math.toDegrees(Math.atan2(sentryX - getX(), sentryY - getY()));
        return Utils.normalRelativeAngleDegrees(a + (moveDir > 0 ? 90 : -90) - getHeading());
    }

    // ─────────────────────────────────────────────────────────────
    // MODES AND MOVEMENT
    // ─────────────────────────────────────────────────────────────

    private void updateBotMode() {
        int enemies = getEnemyCount();
        double myE = getEnergy();

        if (myE > 50 && enemies >= 2) {
            botMode = MODE_AGGRESSIVE;
        } else if (myE > 25) {
            botMode = MODE_CONTROLLED;
        } else {
            botMode = MODE_SURVIVAL;
        }
    }

    private void switchMovementPattern() {
        int oldMode = movementMode;
        int newMode = oldMode;
        int tries = 0;
        while (newMode == oldMode && tries < 10) {
            newMode = rand.nextInt(4);
            tries++;
        }
        movementMode = newMode;

        double centerX = fieldWidth / 2.0;
        double centerY = fieldHeight / 2.0;
        double angleOut = Math.toDegrees(Math.atan2(getX() - centerX, getY() - centerY));
        double turn = Utils.normalRelativeAngleDegrees(angleOut - getHeading());
        turnRight(limit(turn, -45, 45));
        ahead(40 * (rand.nextBoolean() ? 1 : -1));

        out.println("[MOVE] switchMovementPattern -> " + movementMode);
    }

    private int getEnemyCount() {
        return enemyData.size();
    }

    // ─────────────────────────────────────────────────────────────
    // EVENTS
    // ─────────────────────────────────────────────────────────────

    public void onHitByBullet(HitByBulletEvent e) {
        String shooter = e.getName().toLowerCase();
        double damage = e.getPower() * 4 + (e.getPower() > 1 ? (e.getPower() - 1) * 2 : 0);
        if (shooter.contains("sentry") || shooter.contains("border")) {
            sentryHitCount++;
            sentryTotalDamage += damage;
        } else {
            enemyHitMeCount++;
            enemyTotalDamage += damage;
        }
        moveDir *= -1;
    }

    public void onBulletHit(BulletHitEvent e) {
        EnemyProfile p = profiles.get(e.getName());
        if (p != null) {
            p.registerShot(true);
            logHit(p.lastStrategy);
        }
    }

    public void onBulletMissed(BulletMissedEvent e) {
        for (EnemyProfile p : profiles.values()) {
            p.confidence *= 0.9;
        }
    }

    public void onHitWall(HitWallEvent e) {
        moveDir *= -1;
    }

    public void onHitRobot(HitRobotEvent e) {
        if (e.isMyFault()) {
            moveDir *= -1;
            ahead(50 * moveDir);
        }
    }

    public void onRobotDeath(RobotDeathEvent e) {
        enemyData.remove(e.getName());
        profiles.remove(e.getName());
        enemyEnergy.remove(e.getName());
    }

    public void onDeath(DeathEvent e) {
        printFinalStats();
    }

    public void onWin(WinEvent e) {
        printFinalStats();
    }

    private void printFinalStats() {
        out.println("═══════════════════════════════════════");
        out.println("[G4] SENTRY: scans=" + sentryScanCount
                + " hits=" + sentryHitCount
                + " totalDmg=" + String.format("%.1f", sentryTotalDamage));
        out.println("[G4] ENEMIES: hits=" + enemyHitMeCount
                + " totalDmg=" + String.format("%.1f", enemyTotalDamage));
        out.println("═══════════════════════════════════════");
    }

    // ─────────────────────────────────────────────────────────────
    // MACRO TARGETING
    // ─────────────────────────────────────────────────────────────

    private void pickNewMacroTarget() {
        double margin = SAFE_MARGIN + 30;
        double safeW = fieldWidth - 2 * margin;
        double safeH = fieldHeight - 2 * margin;

        int cur = getQuadrant();
        int tgt = (cur + 1 + rand.nextInt(3)) % 4;
        switch (tgt) {
            case 0:
                macroTargetX = margin + rand.nextDouble() * safeW / 2.0;
                macroTargetY = margin + rand.nextDouble() * safeH / 2.0;
                break;
            case 1:
                macroTargetX = margin + safeW / 2.0 + rand.nextDouble() * safeW / 2.0;
                macroTargetY = margin + rand.nextDouble() * safeH / 2.0;
                break;
            case 2:
                macroTargetX = margin + rand.nextDouble() * safeW / 2.0;
                macroTargetY = margin + safeH / 2.0 + rand.nextDouble() * safeH / 2.0;
                break;
            default:
                macroTargetX = margin + safeW / 2.0 + rand.nextDouble() * safeW / 2.0;
                macroTargetY = margin + safeH / 2.0 + rand.nextDouble() * safeH / 2.0;
                break;
        }
    }

    private int getQuadrant() {
        if (getX() < fieldWidth / 2 && getY() < fieldHeight / 2) return 0;
        if (getX() >= fieldWidth / 2 && getY() < fieldHeight / 2) return 1;
        if (getX() < fieldWidth / 2 && getY() >= fieldHeight / 2) return 2;
        return 3;
    }

    // ─────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────

    private double limit(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
