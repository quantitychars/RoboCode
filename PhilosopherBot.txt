package Bv8;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.util.Random;

public class PhilosopherBot extends Robot {
    private int moveDir = 1;
    private Random rand = new Random();

    public void run() {
       
        setBodyColor(Color.BLACK);
        setGunColor(Color.YELLOW);
        setRadarColor(Color.GREEN);

        goToSafeCenter();

        while (true) {
           
            if (getX() < 330 || getX() > 470 || getY() < 330 || getY() > 470) {
                goToSafeCenter();
            }

            
            double moveDistance = 50 + rand.nextInt(40);
            ahead(moveDistance * moveDir);
            
           
            turnRight(20 + rand.nextInt(20));

            turnGunRight(360);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        String name = e.getName().toLowerCase();
       
        if (name.contains("sentry") || name.contains("border")) return;

    
        if (e.getDistance() < 140) {
            moveDir *= -1;
        }

       
        double angleToEnemy = getHeading() + e.getBearing();
        double gunTurn = Utils.normalRelativeAngleDegrees(angleToEnemy - getGunHeading());
        turnGunRight(gunTurn);

       
        if (Math.abs(gunTurn) < 5) {
            if (e.getDistance() < 200) {
                fire(3.0); 
            } else if (e.getDistance() < 450) {
                fire(1.5); 
            } else {
                fire(0.5); 
            }
        }
    }

    
    private void goToSafeCenter() {
        double centerX = getBattleFieldWidth() / 2;
        double centerY = getBattleFieldHeight() / 2;
        double angleToCenter = Math.toDegrees(Math.atan2(centerX - getX(), centerY - getY()));
        double turnAngle = Utils.normalRelativeAngleDegrees(angleToCenter - getHeading());
        
        turnRight(turnAngle);
        
        double dist = Math.hypot(centerX - getX(), centerY - getY());
        ahead(dist); 
    }

    public void onHitByBullet(HitByBulletEvent e) {
       
        turnLeft(45);
        moveDir *= -1;
        ahead(40 * moveDir);
    }

    public void onHitRobot(HitRobotEvent e) {
       
        if (!e.getName().toLowerCase().contains("sentry")) {
            fire(3.0);
        }
        moveDir *= -1;
        ahead(30 * moveDir);
    }

    public void onHitWall(HitWallEvent e) {
        goToSafeCenter();
    }
}
