package fr.proutlost.director.api.model;
public final class BudgetMath {
 private BudgetMath() {}
 public static BudgetVector zero(){return new BudgetVector(0,0,0,0,0,0,0,0,0,0);}
 public static BudgetVector add(BudgetVector a,BudgetVector b){return new BudgetVector(a.tickMillis()+b.tickMillis(),a.globalIntensity()+b.globalIntensity(),a.regionalIntensity()+b.regionalIntensity(),Math.addExact(a.liveEntities(),b.liveEntities()),Math.addExact(a.persistentEntities(),b.persistentEntities()),Math.addExact(a.mutationsInFlight(),b.mutationsInFlight()),Math.addExact(a.queuedIoBytes(),b.queuedIoBytes()),a.clientUnits()+b.clientUnits(),Math.addExact(a.payloadBytes(),b.payloadBytes()),Math.addExact(a.navigationNodes(),b.navigationNodes()));}
 public static boolean fits(BudgetVector a,BudgetVector b){return a.tickMillis()<=b.tickMillis()&&a.globalIntensity()<=b.globalIntensity()&&a.regionalIntensity()<=b.regionalIntensity()&&a.liveEntities()<=b.liveEntities()&&a.persistentEntities()<=b.persistentEntities()&&a.mutationsInFlight()<=b.mutationsInFlight()&&a.queuedIoBytes()<=b.queuedIoBytes()&&a.clientUnits()<=b.clientUnits()&&a.payloadBytes()<=b.payloadBytes()&&a.navigationNodes()<=b.navigationNodes();}
}
