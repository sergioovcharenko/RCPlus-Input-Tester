package com.serhii.rcplustester;

import android.graphics.Bitmap;

/**
 * Offline screen-image calibration for the passive virtual joysticks drawn by
 * DJI Virtual Flight. Scans white semi-transparent circular HUD outlines in
 * the left and right lower quadrants. It neither controls aircraft nor sends
 * gestures. Fail closed: low-confidence estimates are NOT applied.
 */
public final class JoystickScreenDetector {
    public static final class Circle {
        public final float x, y, radius, confidence;
        Circle(float x, float y, float radius, float confidence) {
            this.x=x; this.y=y; this.radius=radius; this.confidence=confidence;
        }
    }
    public static final class Result {
        public final Circle left, right;
        public final String detail;
        Result(Circle left, Circle right, String detail) {
            this.left=left; this.right=right; this.detail=detail;
        }
        public boolean valid() { return left != null && right != null; }
    }
    private static final int DIRECTIONS = 40;
    private final int w,h;
    private final int[] lum;
    private final float[] cs = new float[DIRECTIONS], ss = new float[DIRECTIONS];

    private JoystickScreenDetector(Bitmap input) {
        float scale = Math.min(1f, 480f / input.getWidth());
        w = Math.max(10, Math.round(input.getWidth()*scale));
        h = Math.max(10, Math.round(input.getHeight()*scale));
        Bitmap mini = Bitmap.createScaledBitmap(input,w,h,true);
        int[] pixels = new int[w*h];
        mini.getPixels(pixels,0,w,0,0,w,h);
        lum=new int[w*h];
        for (int i=0;i<pixels.length;i++) {
            int p=pixels[i];
            lum[i] = (((p>>>16)&255)*77+((p>>>8)&255)*150+(p&255)*29)>>>8;
        }
        if(mini!=input) mini.recycle();
        for (int i=0;i<DIRECTIONS;i++) {
            double a=2*Math.PI*i/DIRECTIONS;
            cs[i]=(float)Math.cos(a);
            ss[i]=(float)Math.sin(a);
        }
    }

    private int at(float xf,float yf) {
        int x=Math.round(xf), y=Math.round(yf);
        if(x<0||x>=w||y<0||y>=h) return -1;
        return lum[y*w+x];
    }
    private float score(int cx,int cy,int r) {
        float sum=0f;
        int positives=0, quadrants=0;
        int[] quad=new int[4];
        for(int i=0;i<DIRECTIONS;i++) {
            float x=cx+r*cs[i],y=cy+r*ss[i];
            int boundary=at(x,y);
            int interior=at(cx+(r-4)*cs[i],cy+(r-4)*ss[i]);
            int exterior=at(cx+(r+4)*cs[i],cy+(r+4)*ss[i]);
            if(boundary<0||interior<0||exterior<0) return -1f;
            float highlight=boundary-(interior+exterior)*0.5f;
            // The joystick perimeter is a pale circular outline. Cap scores to
            // stop a few scene edges from overpowering a real complete ring.
            if(highlight>3f) {
                positives++;
                int q=i/(DIRECTIONS/4);
                quad[q]++;
                sum+=Math.min(30f,highlight);
            }
        }
        for(int v:quad) if(v>=2) quadrants++;
        if(quadrants<4||positives<14) return -1f;
        // Favor a complete ring and penalize sparse high-contrast scenery.
        return (sum / DIRECTIONS) * (positives/(float)DIRECTIONS)
            + (positives - 14) * 0.19f;
    }
    private Circle detect(boolean isLeft) {
        int minX=Math.round(w*(isLeft?.055f:.675f));
        int maxX=Math.round(w*(isLeft?.36f:.96f));
        int minY=Math.round(h*.57f), maxY=Math.round(h*.90f);
        int minR=Math.max(10,Math.round(h*.09f));
        int maxR=Math.max(minR+2,Math.round(h*.255f));
        int bx=0,by=0,br=0;float best=-1f;
        for(int cy=minY;cy<=maxY;cy+=5) {
            for(int cx=minX;cx<=maxX;cx+=5) {
                for(int r=minR;r<=maxR;r+=3) {
                    if(cx-r<0||cy-r<0||cx+r>=w||cy+r>=h)continue;
                    float value=score(cx,cy,r);
                    if(value>best){best=value;bx=cx;by=cy;br=r;}
                }
            }
        }
        if(best<0) return null;
        for(int cy=Math.max(minY,by-7);cy<=Math.min(maxY,by+7);cy+=2) {
            for(int cx=Math.max(minX,bx-7);cx<=Math.min(maxX,bx+7);cx+=2) {
                for(int r=Math.max(minR,br-5);r<=Math.min(maxR,br+5);r++) {
                    if(cx-r<0||cy-r<0||cx+r>=w||cy+r>=h)continue;
                    float value=score(cx,cy,r);
                    if(value>best){best=value;bx=cx;by=cy;br=r;}
                }
            }
        }
        // Conservative application threshold, only accept strong full circles.
        if(best<4.2f) return null;
        return new Circle(bx*100f/w,by*100f/h,br*100f/Math.min(w,h),best);
    }
    public static Result detect(Bitmap image) {
        if(image==null||image.getWidth()<300||image.getHeight()<300)
            return new Result(null,null,"screenshot resolution too small");
        JoystickScreenDetector d=new JoystickScreenDetector(image);
        Circle left=d.detect(true), right=d.detect(false);
        if(left==null||right==null)
            return new Result(left,right,"circle not confidently detected: L="
                +(left==null?"missing":left.confidence)+" R="
                +(right==null?"missing":right.confidence));
        float sep=right.x-left.x;
        if(sep<35f || Math.abs(left.y-right.y)>16f)
            return new Result(null,null,"inconsistent joystick locations");
        return new Result(left,right,"L "+Math.round(left.x)+"/"+Math.round(left.y)
            +" r="+Math.round(left.radius)+"% confidence="+left.confidence
            +"; R "+Math.round(right.x)+"/"+Math.round(right.y)
            +" r="+Math.round(right.radius)+"% confidence="+right.confidence);
    }
}
