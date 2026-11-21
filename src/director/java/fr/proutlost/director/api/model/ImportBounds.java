package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record ImportBounds(int minX,int minY,int minZ,int maxXExclusive,int maxYExclusive,int maxZExclusive) { public ImportBounds { require(minX<maxXExclusive&&minY<maxYExclusive&&minZ<maxZExclusive,"import bounds"); Math.multiplyExact(Math.multiplyExact((long)maxXExclusive-minX,(long)maxYExclusive-minY),(long)maxZExclusive-minZ); } }
