package dev.sifucraft.blocks;

import net.minecraft.world.phys.Vec3;

/** Pure pre-collision piston budget. The caller must retain one state per scoped entity activation. */
public final class PistonMotionLimit {
    private PistonMotionLimit(){}
    private static final double LIMIT=.51;
    public record State(long tick,Vec3 used){
        public State {
            if(tick < -1 || !finite(used) || Math.abs(used.x)>LIMIT || Math.abs(used.y)>LIMIT || Math.abs(used.z)>LIMIT)
                throw new IllegalArgumentException("invalid piston budget");
        }
        public static State empty(){return new State(-1,Vec3.ZERO);}
    }
    public record Result(State state,Vec3 limited){}
    private static boolean finite(Vec3 v){return v!=null&&Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}

    /** Matches Entity.limitPistonMovement for finite axis-only piston requests, in blocks.
     * Usage is booked before collision, including blocked moves. It must not be refunded from actual displacement.
     * This has no world/bridge credential and does not authorize Native motion or vertical control.
     */
    public static Result plan(State state,long tick,Vec3 request){
        if(state==null || tick<0 || tick<state.tick || !finite(request) ||
            (request.x!=0?1:0)+(request.y!=0?1:0)+(request.z!=0?1:0)>1)
            throw new IllegalArgumentException("invalid or stale piston request");
        // Vanilla returns tiny requests before resetting the per-tick accumulator.
        if(request.lengthSqr()<=1e-7)return new Result(state,request);
        var used=tick==state.tick?state.used:Vec3.ZERO;
        int axis=request.x!=0?0:request.y!=0?1:2;
        double before=axis==0?used.x:axis==1?used.y:used.z;
        double amount=axis==0?request.x:axis==1?request.y:request.z;
        double total=Math.clamp(before+amount,-LIMIT,LIMIT);
        double delta=total-before;
        var after=new Vec3(axis==0?total:used.x,axis==1?total:used.y,axis==2?total:used.z);
        var limited=Math.abs(delta)<=(double)1e-5f?Vec3.ZERO:
            new Vec3(axis==0?delta:0,axis==1?delta:0,axis==2?delta:0);
        return new Result(new State(tick,after),limited);
    }
}
