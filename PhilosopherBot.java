package Bv4;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.util.Random;

public class PhilosopherBot extends Robot {
    private double fieldWidth;
    private double fieldHeight;
    private int moveDir = 1;
    private Random rand = new Random();

    public void run() {
       
        fieldWidth = getBattleFieldWidth();
        fieldHeight = getBattleFieldHeight();

        setBodyColor(new Color(20, 20, 20));
        setGunColor(Color.ORANGE);
        setRadarColor(Color.CYAN);

        emergencyEscapeToCenter();

        while (true) {
           
            double moveDistance = 100 + rand.nextInt(50);
            ahead(moveDistance * moveDir);
            
           
            if (!isInsideSafetyZone()) {
                emergencyEscapeToCenter();
            }

            turnRight(30 + rand.nextInt(20));
            turnGunRight(360); // Сканируем всё вокруг
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        String name = e.getName().toLowerCase();
        if (name.contains("sentry") || name.contains("border")) return;

       
        if (e.getDistance() < 120) {
            moveDir *= -1; 
            double gunTurn = Utils.normalRelativeAngleDegrees(getHeading() + e.getBearing() - getGunHeading());
            turnGunRight(gunTurn);
            fire(3.0);
            ahead(80 * moveDir);
            return;
        }

     
        double angleToEnemy = getHeading() + e.getBearing();
        double gunTurn = Utils.normalRelativeAngleDegrees(angleToEnemy - getGunHeading());
        turnGunRight(gunTurn);

   

        if (Math.abs(gunTurn) < 4) {
            if (e.getDistance() < 300) {
                fire(2.5);
            } else {
                fire(0.5);
            }
        }
    }


    private void emergencyEscapeToCenter() {
        double centerX = fieldWidth / 2;
        double centerY = fieldHeight / 2;
        
        double angleToCenter = Math.toDegrees(Math.atan2(centerX - getX(), centerY - getY()));
        double turnAngle = Utils.normalRelativeAngleDegrees(angleToCenter - getHeading());
        
        turnRight(turnAngle);
        ahead(150);
    }

    private boolean isInsideSafetyZone() {
     
        double margin = 250;
        double minX = (fieldWidth / 2) - margin;
        double maxX = (fieldWidth / 2) + margin;
        double minY = (fieldHeight / 2) - margin;
        double maxY = (fieldHeight / 2) + margin;

        return (getX() > minX && getX() < maxX && getY() > minY && getY() < maxY);
    }

    public void onHitWall(HitWallEvent e) {
       
        emergencyEscapeToCenter();
    }

    public void onHitRobot(HitRobotEvent e) {
        
        fire(3.0);
        moveDir *= -1;
        ahead(50 * moveDir);
    }

    public void onHitByBullet(HitByBulletEvent e) {
        turnLeft(45);
        moveDir *= -1;
    }
}
