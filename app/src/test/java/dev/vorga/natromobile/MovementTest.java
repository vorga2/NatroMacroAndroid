package dev.vorga.natromobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class MovementTest {
    @Test public void tileTimingMatchesWalkIntegrationAtConstantSpeed(){
        assertEquals(139L,MovementMath.duration(1,28,1));
        assertEquals(1567L,MovementMath.duration(11,28,1));
        assertEquals(783L,MovementMath.duration(11,56,1));
        assertEquals(3134L,MovementMath.duration(11,28,2));
    }
    @Test public void buffsFollowUpstreamAddThenMultiplyOrder(){
        assertEquals(28,MovementMath.speed(28,false,false,0,false,false,false,false,false),.000001);
        assertEquals(42*1.1*1.15*2*2*1.2*1.25,MovementMath.speed(28,true,true,10,true,true,true,true,true),.000001);
    }
    @Test public void diagonalUsesSameJoystickRadiusAsCardinal(){
        float[] d=MovementMath.unit(1,1);assertEquals(1,Math.hypot(d[0],d[1]),.000001);assertEquals(d[0],d[1],0);
        assertArrayEquals(new float[]{0,0},MovementMath.unit(0,0),0);
    }
    @Test public void snakePreservesUpstreamFirstLegAndMirrorsSecondHalf(){
        List<PatternStep> s=PatternFactory.create("Snake",.5,3);
        assertEquals(24,s.size());assertEquals(5.5,s.get(0).tiles,0);
        assertEquals(-1,s.get(1).y,0);assertEquals(1,s.get(13).y,0);
    }
    @Test public void closedPatternsDoNotAccumulateGeometricDrift(){
        for(String name:new String[]{"Snake","Lines","Squares","Diamonds"})for(int reps=1;reps<=10;reps++){
            double x=0,y=0;
            for(PatternStep s:PatternFactory.create(name,1,reps)){
                float[] d=MovementMath.unit(s.x,s.y);x+=d[0]*s.tiles;y+=d[1]*s.tiles;
            }
            assertEquals(name,0,x,.00001);assertEquals(name,0,y,.00001);
        }
    }
    @Test public void eLolPreservesSpacingAndCornerCoefficient(){
        List<PatternStep> s=PatternFactory.create("e_lol",1,2,false,false,false);
        assertEquals(20,s.size());assertEquals(6.165,s.get(0).tiles,.00001);
        assertEquals(4.923,s.get(5).tiles,.00001);
        assertEquals(5.0355,PatternFactory.create("e_lol",1,2,false,false,true).get(5).tiles,.00001);
    }
    @Test public void inversionsChangeDirectionsWithoutChangingDistance(){
        for(String name:PatternFactory.NAMES){
            List<PatternStep> a=PatternFactory.create(name,1,3),b=PatternFactory.create(name,1,3,true,true,false);
            assertEquals(a.size(),b.size());
            for(int i=0;i<a.size();i++){assertEquals(-a.get(i).x,b.get(i).x,0);assertEquals(-a.get(i).y,b.get(i).y,0);assertEquals(a.get(i).tiles,b.get(i).tiles,0);}
        }
    }
    @Test public void stationaryHasNoMovement(){assertTrue(PatternFactory.create("Stationary",1,1).isEmpty());}
    @Test(expected=IllegalArgumentException.class) public void unknownPatternFails(){PatternFactory.create("typo",1,1);}
    @Test(expected=IllegalArgumentException.class) public void nanSizeFails(){PatternFactory.create("Snake",Double.NaN,1);}
    @Test(expected=IllegalArgumentException.class) public void zeroSpeedFails(){MovementMath.duration(1,0,1);}
    @Test(expected=IllegalArgumentException.class) public void unboundedRepetitionsFail(){PatternFactory.create("Snake",1,100000);}
}
