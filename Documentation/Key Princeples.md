### 1. Key strategic principles

#### Bot modes by energy level and enemy count

The bot operates in three modes: `AGGRESSIVE`, `CONTROLLED`, and `SURVIVAL`.

- **AGGRESSIVE**: high energy (>50) and at least 2 enemies — the bot plays actively and fires with higher power.
- **CONTROLLED**: medium energy (>25) — moderate aggression with more restraint.
- **SURVIVAL**: low energy (≤25) — survival is the priority, firepower is reduced and movement becomes more cautious.

This allows the bot to adapt risk‑taking and energy usage to the current battle situation.


#### Multi‑layered movement: wave surfing + minimum‑risk + patterns

Core movement is built from wave surfing (dodging enemy bullet waves), minimum‑risk evaluation of candidate points on the map, and switchable movement patterns (`wallhugger`, `zigzag`, `pendulum`, center‑orbit).

If there are active waves, wave surfing is used; otherwise the bot picks the point with the lowest risk and moves there. Changing patterns based on targeting streaks makes the bot less predictable for opponents.


#### Explicit modeling of Sentry / border and wall danger

The bot identifies the “sentry” robot (by name) and builds a dedicated danger model near the battlefield borders (`SENTRY_BORDER`, `SAFE_MARGIN`).

The `sentryDanger` function increases risk near the edges and reduces it in the center in normal modes, while in `SURVIVAL` it boosts overall caution. This helps the bot survive against `BorderSentry` and avoid dying near walls.


#### Macro‑positioning by quadrants (macro target)

In parallel to local dodging, the bot keeps a macro timer and periodically chooses a new “far” safe target — a point in another quadrant of the battlefield, but inside a safe region (offset from the walls).

In `calcPointRisk`, this target slightly “pulls” the bot towards itself, preventing it from getting stuck in a single corner and helping it flow across the arena while avoiding enemy clusters and the Sentry.


#### Point risk model: enemies, clusters, waves, walls, Sentry, macro target

When picking the next movement point, the bot sums several risk factors:

- Distance to each enemy,
- Extra penalty for a very close enemy (<350),
- Additional penalty for being near an enemy “cluster”,
- Sentry danger via `sentryDanger`,
- Penalty for proximity to walls,
- Danger from existing bullet waves,
- Small “bonus” for being closer to `macroTarget`.

This combined risk function leads to behavior like “stay away from crowds and walls but still move along a meaningful trajectory.”


#### Wave surfing focused on lateral movement and walls/Sentry

When waves are present, the bot simulates several options: moving left, moving right, or stopping, and evaluates danger for each.

Danger is based on lateral distance to the wave front (cross product), distance to walls (quadratic penalty), and extra Sentry danger. The bot picks the direction with the lowest total danger and sometimes prefers “stop” if it is only slightly worse than the best moving option.


#### Hybrid radar: Sentry priority, then search/sweep

On a timer, the gun/radar turns toward the Sentry to keep track of its position. At other times it either does a full 360° sweep when targets are lost or swings in a pendulum pattern (steady left/right turns).

This lets the bot track the Sentry while also refreshing data on other enemies without implementing a complex lock‑radar.


#### Enemy profiles and movement type classification

For each enemy, the bot maintains an `EnemyProfile`: average step size, average velocity, heading change, `zigzagScore`, `isPendulum`, `pauseScore`, `sentryAwareness`, recent coordinates, and snapshots (`EnemySnapshot`) for pattern‑matching.

Using these metrics, `classify()` assigns a type such as `CAMPER`, `ZIGZAGGER`, `PENDULUM`, `LINEAR`, or `WALLHUGGER` (or `UNKNOWN` when scans are insufficient). This provides a high‑level label for targeting and analysis strategies.


#### Three targeting modes: pattern‑matching, circular, linear

To compute the aim point, `calcAimPoint()` first tries `patternMatchAim()` over the `EnemySnapshot` buffer if enough data is available.

If no pattern is found and the enemy exhibits zigzag behavior (high `zigzagScore`) with enough scans, `circularAim()` is used; otherwise a simple linear lead prediction is applied. This gives a flexible trade‑off between accuracy and complexity.


#### Aiming feedback via confidence and fire log

`EnemyProfile` stores `ourShots` and `ourHits`, from which `confidence = hitRate` is calculated.

Additionally, there is a global `FireRecord` log per “method” (`PATTERN` / `CIRCULAR` / `LINEAR`) with shot power and distance, later marked with `hit`. This allows the bot to:

- Scale bullet power based on `confidence`,
- Decide whether BURST firing can be enabled for a given strategy (`canBurst()`).


#### Adaptive firepower and fire rate control

Base bullet power (`baseFp`) depends on bot mode and distance to the target, then is increased with higher `confidence`, but remains clamped, with extra limits in `SURVIVAL` and at low energy.

The bot also reduces firing frequency in heavy situations: many nearby enemies, dangerous waves close to us, or poor signal (long distance, few scans, low `confidence`). In some conditions it simply skips firing ticks.


#### BURST mode for finishing low‑HP targets

When the enemy is low on HP and the chosen targeting method is reliable according to the fire log (recent shots with this method have hit), `canBurst()` enables BURST — a fixed maximum firepower of 3.0 to finish the target faster.

BURST is blocked when our energy is low and is additionally gated by a `confidence` threshold, so the bot does not waste energy on blind aggression.


#### Skipping shots under poor signal and dangerous conditions

If the target is far away (>500), scans are few, and `confidence` is low (a `badSignal` situation), the bot simply does not fire.

It also refrains from firing when many enemies are nearby or an enemy wave is very close. This explicitly shifts priority from shooting towards dodging and survival.


#### SURVIVAL behavior: simple bounce away from closest enemy

In `SURVIVAL` mode, if there is an enemy closer than 350, the bot performs a “rough” escape: turns by a limited angle away from the enemy and moves forward by a fixed distance.

This is a fast and cheap way to open distance when there is no time or energy for more sophisticated maneuvering.


#### Specialized Sentry evasion: timed evasion and blind evade

When the Sentry is detected, a special wave is constructed and the time `t` for a notional bullet to reach us is estimated. Then `sentryEvasionTime = now + 0.4 * t`, and at that moment the bot changes direction and performs an evasion move.

In parallel, a periodic blind evade runs: every `BLIND_EVADE` ticks (28) the bot performs a small turn/step, either along the perpendicular to the Sentry or in a random direction if the Sentry is unknown.


#### Bullet shadowing versus Sentry bullets

The `isBulletShadowed` function models the intersection between our bullet’s trajectory and a Sentry bullet using ray geometry.

If the arrival times at the intersection point are close, the bullet is considered “shadowed” and that shot is blocked. This reduces the risk of wasting shots that will be intercepted by the Sentry.


#### Movement pattern switch after strong PATTERN targeting success

If the `PATTERN` strategy is used for a long streak (e.g. `patternStreak > 45`), the bot switches `movementMode` to a different one.

This deliberately “breaks” its own predictable movement pattern, making it harder for enemies that might have adapted to the current trajectory.


#### Simple reactive defenses to events (bullets, walls, collisions)

When taking bullet damage, the bot flips `moveDir`; on wall hits or collisions for which it is at fault, it also reverses direction and performs a small retreat.

This is a minimal but cheap defensive layer against getting stuck and against sustained fire along a straight path.


#### Using sentryAwareness and map center in enemy profiles

Each enemy profile stores the fraction of scans where the enemy was in the “central” safe zone (between `SAFE_MARGIN` and the battlefield borders), which is interpreted as `sentryAwareness`.

This indirectly indicates how much the enemy avoids the Sentry themselves and can influence classification and behavior around that enemy.


#### Sentry and enemy statistics logging at the end of the round

At the end of the round (`onDeath` and `onWin`), the bot outputs the number of Sentry scans, Sentry hits on us, total Sentry damage, as well as the number of hits and total damage from regular enemies.

This provides a compact report for analyzing how well the strategy performed against the Sentry and in terms of overall survivability.
