package dev.sifucraft.blocks;

import net.minecraft.world.phys.Vec3;

/** State ownership and malformed-input checks; vanilla differential tests run in the disposable server. */
final class PistonMotionLimitTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError("piston budget: "+why);}
    private static boolean near(double a,double b){return Math.abs(a-b)<1e-12;}
    private static void invalid(Runnable action){try{action.run();throw new AssertionError("accepted invalid piston request");}catch(IllegalArgumentException expected){}}
    static void run(){
        var empty=PistonMotionLimit.State.empty();
        var first=PistonMotionLimit.plan(empty,10,new Vec3(.41,0,0));
        var second=PistonMotionLimit.plan(first.state(),10,new Vec3(.5,0,0));
        check(near(first.limited().x,.41)&&near(second.limited().x,.10),"two sources share the same entity tick budget");
        check(empty.tick()==-1&&empty.used().equals(Vec3.ZERO),"planning does not mutate published state");
        var reverse=PistonMotionLimit.plan(second.state(),10,new Vec3(-2,0,0));
        check(near(reverse.limited().x,-1.02)&&near(reverse.state().used().x,-.51),"opposing push uses signed net accumulation, not absolute travel");
        var next=PistonMotionLimit.plan(second.state(),11,new Vec3(.5,0,0));
        check(near(next.limited().x,.5),"later tick resets all axes");
        var other=PistonMotionLimit.plan(empty,10,new Vec3(.5,0,0));
        check(near(other.limited().x,.5),"another target does not inherit prior target budget");
        var tiny=PistonMotionLimit.plan(second.state(),12,new Vec3(.0001,0,0));
        check(tiny.state()==second.state()&&near(tiny.limited().x,.0001),"tiny requests return before tick reset like vanilla");
        var threshold=PistonMotionLimit.plan(new PistonMotionLimit.State(10,new Vec3(.509999,0,0)),10,new Vec3(.1,0,0));
        check(threshold.limited().equals(Vec3.ZERO)&&near(threshold.state().used().x,.51),"epsilon return still consumes accumulator");
        invalid(()->PistonMotionLimit.plan(first.state(),9,new Vec3(.1,0,0)));
        invalid(()->PistonMotionLimit.plan(empty,10,new Vec3(Double.NaN,0,0)));
        invalid(()->PistonMotionLimit.plan(empty,10,new Vec3(Double.POSITIVE_INFINITY,0,0)));
        invalid(()->PistonMotionLimit.plan(empty,10,new Vec3(.1,.1,0)));
        invalid(()->new PistonMotionLimit.State(10,new Vec3(.52,0,0)));
        System.out.println("P8 piston budget PASS: tick reset/identity separation/signed reverse/tiny/epsilon/stale/invalid input");
    }
}
