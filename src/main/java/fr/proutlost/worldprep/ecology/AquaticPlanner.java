package fr.proutlost.worldprep.ecology;
/** Two-stage, pure aquatic result. Registry validity is checked before mutation integration. */
public final class AquaticPlanner {
 public enum MarineClass{SHORELINE,SHALLOW_MARINE,MARINE,DEEP_MARINE}
 public enum Substrate{SAND,GRAVEL,STONE}
 public record Result(MarineClass marineClass,Substrate substrate,boolean vegetation){}
 public static Result plan(int depth,double underwaterSlope,int coastDistance,long noise){MarineClass c=depth<=2?MarineClass.SHORELINE:depth<=6?MarineClass.SHALLOW_MARINE:depth<=20?MarineClass.MARINE:MarineClass.DEEP_MARINE;Substrate s=underwaterSlope>5?Substrate.STONE:Math.floorMod(noise,3)==0?Substrate.GRAVEL:Substrate.SAND;boolean vegetation=depth>=2&&depth<=16&&underwaterSlope<6&&Math.floorMod(noise,5)<2;return new Result(c,s,vegetation);}
 private AquaticPlanner(){}
}
