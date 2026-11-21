package fr.proutlost.worldprep.preview;
/** Overflow-safe downsampling choice; pixel allocation is always bounded. */
public record PreviewScale(int sourceWidth,int sourceHeight,int outputWidth,int outputHeight,int blocksPerPixel){
 public static PreviewScale fit(int width,int height,long maxPixels){if(width<1||height<1||maxPixels<1)throw new IllegalArgumentException();long pixels=Math.multiplyExact((long)width,height);if(pixels<=maxPixels)return new PreviewScale(width,height,width,height,1);int scale=(int)Math.ceil(Math.sqrt((double)pixels/maxPixels));int outW=Math.floorDiv(width-1,scale)+1,outH=Math.floorDiv(height-1,scale)+1;while(Math.multiplyExact((long)outW,outH)>maxPixels){scale++;outW=Math.floorDiv(width-1,scale)+1;outH=Math.floorDiv(height-1,scale)+1;}return new PreviewScale(width,height,outW,outH,scale);}
}
