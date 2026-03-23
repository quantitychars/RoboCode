### Class `PhilosopherBotG4`

#### Main loop and modes

- `public void run()` — the main bot loop: initializes battlefield and colors, picks the first macro target, then runs an infinite loop updating timers, mode, waves, micro‑movement (SURVIVAL bounce, sentry evade, blind evade, macro target updates), choosing movement (wave‑surf / minimum‑risk) and controlling the radar. **[STRATEGIC]**

- `private void updateBotMode()` — selects `botMode` (AGGRESSIVE, CONTROLLED, SURVIVAL) based on current energy level and number of enemies. **[STRATEGIC]**

- `private void switchMovementPattern()` — randomly selects a new `movementMode` (wallhugger / zigzag / pendulum / center orbit), slightly adjusts the position relative to the center, and logs the change. **[STRATEGIC]**

- `private int getEnemyCount()` — returns the current number of enemies based on the size of the `enemyData` map.


#### Radar

- `private void doHybridRadar()` — controls radar/gun rotation: periodically points at the Sentry, otherwise does a full sweep when enemies are lost or swings back and forth in a pendulum pattern. **[STRATEGIC]**


#### Wave surfing and waves

- `private void doWaveSurf()` — finds the closest dangerous wave and chooses a movement direction (left / right / stop) with the lowest estimated danger; falls back to minimum‑risk movement if there is no relevant wave. **[STRATEGIC]**

- `private double surfDanger(Wave w, int dir)` — simulates movement in a given direction relative to the wave and computes total danger based on distance to the wave front, distance to walls, and Sentry danger. **[STRATEGIC]**

- `private Wave getNearestWave()` — finds the nearest active wave (by remaining time before it reaches us).

- `private void pruneWaves()` — removes waves that have already passed our position (bullet distance > distance to us + margin).


#### Minimum‑risk movement and macro movement

- `private void doMinimumRiskMove()` — iterates over `MRM_CANDIDATES` random candidate points around the bot (adjusted by `movementMode`), evaluates risk for each point, picks the lowest‑risk point, and calls `navigateTo()`. **[STRATEGIC]**

- `private double calcPointRisk(double px, double py)` — computes total risk for a point using:  
  enemies (overall and nearest), clusters, Sentry, walls, waves, and an “attraction” term towards `macroTarget`. **[STRATEGIC]**

- `private void navigateTo(double tx, double ty)` — chooses turn angle and movement distance to move towards the target point, adding small random jitter and using reverse movement when the turn angle is large. **[STRATEGIC]**

- `private void pickNewMacroTarget()` — periodically (every `macroInterval`) selects a new macro target in one of four battlefield quadrants, restricted to a safe rectangle. **[STRATEGIC]**

- `private int getQuadrant()` — returns the quadrant index (0–3) based on the bot’s current coordinates.


#### Firing and fire control

- `private void logShot(String method, double power, double distance)` — records shot information (targeting method, power, distance, time) to the circular `fireLog`.

- `private void logHit(String method)` — marks the latest shot with the given method in `fireLog` as a hit (`hit = true`).

- `private boolean canBurst(String method, double distance, double myEnergy)` — decides whether BURST firing is allowed for a given method, requiring sufficient energy and successful recent hits with this method. **[STRATEGIC]**

- `private double[] calcAimPoint(ScannedRobotEvent e, EnemyProfile p, double eX, double eY, double time)` — selects the aiming strategy: tries `patternMatchAim` first, then `circularAim` for zigzag‑like targets, otherwise falls back to linear prediction. Returns the target point for firing. **[STRATEGIC]**

- `private double[] patternMatchAim(EnemyProfile p, double eX, double eY, double time)` — searches the `EnemySnapshot` history for a movement segment similar to the current one and simulates continuation of that pattern to predict a future position. **[STRATEGIC]**

- `private double[] circularAim(ScannedRobotEvent e, double eX, double eY, double time)` — models enemy movement with constant turn rate (circular/arc trajectory) and predicts the future position. **[STRATEGIC]**

- `private boolean isBulletShadowed(double myAngleRad, double targetDist, double myBulletSpeed)` — checks if our bullet’s path will intersect in space and time with a Sentry bullet; if so, the shot is considered “shadowed”. **[STRATEGIC]**


#### Classification and Sentry danger

- `private String classify(EnemyProfile p)` — returns an enemy type string (`UNKNOWN`, `CAMPER`, `ZIGZAGGER`, `PENDULUM`, `LINEAR`, `WALLHUGGER`) based on average velocity, heading change, `zigzagScore`, and pendulum‑like movement flags. **[STRATEGIC]**

- `private double sentryDanger(double x, double y)` — computes the risk contribution from the Sentry and borders, increasing danger near field edges, reducing it in the safe center, and scaling it according to `botMode`. **[STRATEGIC]**

- `private double perpAngleAwayFromSentry()` — computes a perpendicular escape angle relative to the Sentry, taking current `moveDir` into account. **[STRATEGIC]**


#### Event handlers

- `public void onScannedRobot(ScannedRobotEvent e)` — central scan handler:  
  - Detects Sentry and creates its wave and evade timer.  
  - Updates `enemyData`, `enemyEnergy`, and `EnemyProfile` (speeds, deltas, `zigzagScore`, `isPendulum`, `sentryAwareness`, `pauseScore`, etc.).  
  - Creates waves from enemy energy drops (enemy shots).  
  - Updates the pattern buffer and `lastStrategy`.  
  - Computes `enemyHp`, `burstingAllowed`, base fire power from mode and distance, then adjusts it using `confidence` and safety constraints.  
  - Computes aim point, checks gun angle, bullet shadow, and heavy conditions (`closeEnemies`, `waveDanger`, `badSignal`).  
  - Fires when conditions are met, updates stats, and optionally triggers movement pattern switch after long `PATTERN` usage. **[STRATEGIC]**

- `public void onHitByBullet(HitByBulletEvent e)` — updates counters and total damage from Sentry and regular enemies, then inverts `moveDir` as a reactive dodge. **[STRATEGIC]**

- `public void onBulletHit(BulletHitEvent e)` — marks a hit: updates `confidence` via `EnemyProfile.registerShot(true)` and calls `logHit` for the last strategy. **[STRATEGIC]**

- `public void onBulletMissed(BulletMissedEvent e)` — penalizes `confidence` of all profiles (multiplies by 0.9) when our bullet misses. **[STRATEGIC]**

- `public void onHitWall(HitWallEvent e)` — inverts `moveDir` to move away from the wall. **[STRATEGIC]**

- `public void onHitRobot(HitRobotEvent e)` — on collisions caused by us, flips direction and performs a short step forward. **[STRATEGIC]**

- `public void onRobotDeath(RobotDeathEvent e)` — removes the dead robot’s entries from `enemyData`, `profiles`, and `enemyEnergy`. **[STRATEGIC]**

- `public void onDeath(DeathEvent e)` — prints final Sentry/enemy statistics via `printFinalStats()`.

- `public void onWin(WinEvent e)` — also prints final statistics.


#### Statistics and utilities

- `private void printFinalStats()` — prints formatted statistics: number of Sentry scans/hits/total damage and number of enemy hits/total damage.

- `private double limit(double value, double min, double max)` — clamps a value to the `[min, max]` range.


---

### Inner class `EnemySnapshot`

- Constructor `EnemySnapshot(double v, double h, double x, double y, long t)` — creates a snapshot of enemy state (velocity, heading, position, time) for later pattern‑matching.


### Inner class `EnemyProfile`

Fields:  
`avgDeltaPos`, `avgVelocity`, `scanCount`, `prevDelta1`, `prevDelta2`, `isPendulum`, `scansInCenter`, `sentryAwareness`, `pauseScore`, `headingChange`, `zigzagScore`, `lastX`, `lastY`, `lastVelocity`, `lastHeading`, `shotsFired`, `lastStrategy`, `confidence`, `ourShots`, `ourHits`, `pmBuffer`, `pmHead`, `pmSize`.

- `void addSnapshot(EnemySnapshot s)` — adds an `EnemySnapshot` to the circular `pmBuffer` and increments `pmSize`.

- `EnemySnapshot getFromTail(int offset)` — retrieves a snapshot from `pmBuffer` counted from the “tail” (most recent element).

- `void registerShot(boolean hit)` — updates `ourShots` / `ourHits` and recomputes `confidence` as hit ratio. **[STRATEGIC]**


### Inner class `Wave`

Fields:  
`originX`, `originY`, `bulletSpeed`, `firePower`, `fireTime`, `bulletHeadingRad`, `isSentry`.

- `double distanceTraveled(long now)` — calculates how far the wave front has traveled since `fireTime` using elapsed time and `bulletSpeed`.


### Inner class `FireRecord`

Fields:  
`method`, `power`, `distance`, `time`, `hit`.

- Constructor `FireRecord(String method, double power, double distance, long time)` — creates a log record for a shot with given parameters and initializes it as not yet a hit.
