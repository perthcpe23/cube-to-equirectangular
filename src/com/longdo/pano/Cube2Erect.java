package com.longdo.pano;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

public class Cube2Erect {

	// TODO: Enable this will avoid aliasing effects, but get a blurry image
	boolean doAvgColor = false;
	
	/**
	 * Convert 6 images of cube face2 to a single equirectangular image.
	 * @param imBack
	 * @param imBottom
	 * @param imFront
	 * @param imLeft
	 * @param imRight
	 * @param imTop
	 * @return equirectangular image
	 * @throws IOException
	 */
    public BufferedImage convert(BufferedImage imBack,BufferedImage imBottom,BufferedImage imFront,BufferedImage imLeft,BufferedImage imRight,BufferedImage imTop) throws IOException{
        int w = imBack.getWidth();
        int h = imBack.getHeight();
		
		// output image size, will be decrease 90% later
        int finalW = w*4;
        int finalH = h*2;
        
        BufferedImage imOutput = new BufferedImage(finalW, finalH, BufferedImage.TYPE_INT_RGB);
        
        float fW = finalW;
        float fH = finalH;
        
        int[] targetColor = new int[3];
        
        for (int j=0;j<finalH;j++) {
			for (int i=0;i<finalW;i++) {
				// value range from -1 to 1
				float u = 2*i/fW - 1;
				float v = 2*j/fH - 1;
				
				// convert to horizontal and vertical angle
				double theta = u * Math.PI;
				double phi = v * Math.PI / 2;
				
				// find corresponding location on sphere (inside a cube)
				double x = Math.cos(phi)*Math.cos(theta);
				double y = Math.sin(phi);
				double z = Math.cos(phi)*Math.sin(theta);
				
				// find appropriate color from cube faces
				targetColor = findColor(imBack, imBottom, imFront, imLeft, imRight, imTop, w, h, targetColor, x, y, z);
				
				imOutput.getRaster().setPixel(i, j, targetColor);
			}
        }
        
        imOutput = smooth(imOutput);
        
        return imOutput;
    }

	private int[] findColor(BufferedImage imBack, BufferedImage imBottom, BufferedImage imFront, BufferedImage imLeft, BufferedImage imRight, BufferedImage imTop, int w, int h, int[] targetColor, double x, double y, double z) {
		BufferedImage target;
		double absX = Math.abs(x);
		double absY = Math.abs(y);
		double absZ = Math.abs(z);
		
		if(absX > absY && absX > absZ) { // on left or right face
			boolean isLeft = x < 0;

			// intersection point on x plane
			z /= absX;
			y /= absX;
			
			if(isLeft) {
				z = -z;
			}
			
			int xx = (int) (((z + 1) * w)/2);
			int yy = (int) (((y + 1) * h)/2);
			
			xx = Math.max(0, Math.min(w-1, xx));
			yy = Math.max(0, Math.min(h-1, yy));
			
			if(isLeft) {
				target = imLeft;
			}
			else {
				target = imRight;
			}
			
			selectColor(w, h, targetColor, target, xx, yy);
		}
		else if(absY > absX && absY > absZ) { // on top or bottom face
			boolean isTop = y < 0;

			// intersection point on y plane
			z /= absY;
			x /= absY;
			
			if(isTop) {
				z = -z;
			}
			
			int xx = (int) (((x + 1) * w)/2);
			int yy = (int) (((z + 1) * h)/2);
			
			xx = Math.max(0, Math.min(w-1, xx));
			yy = Math.max(0, Math.min(h-1, yy));
			
			if(isTop) {
				target = imTop;
			}
			else {
				target = imBottom;
			}
			
			selectColor(w, h, targetColor, target, xx, yy);
		}
		else { // on front or back face
			boolean isFront = z < 0;

			// intersection point on z plane
			y /= absZ;
			x /= absZ;
			
			if(!isFront) {
				x = -x;
			}
			
			int xx = (int) Math.round(((x + 1) * w)/2);
			int yy = (int) Math.round(((y + 1) * h)/2);
			
			xx = Math.max(0, Math.min(w-1, xx));
			yy = Math.max(0, Math.min(h-1, yy));
			
			if(isFront) {
				target = imFront;
			}
			else {
				target = imBack;
			}
			
			selectColor(w, h, targetColor, target, xx, yy);
		}
		return targetColor;
	}

	private BufferedImage smooth(BufferedImage before) {
		double scale = 0.9;
		BufferedImage after = new BufferedImage((int) (before.getWidth()*scale), (int) (before.getHeight()*scale), BufferedImage.TYPE_INT_RGB);
		AffineTransform at = new AffineTransform();
		at.scale(scale, scale);
		AffineTransformOp scaleOp = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
		after = scaleOp.filter(before, after);
		
		return after;
	}
	
	// 3x3 average color
	private void selectColor(int w, int h, int[] targetColor, BufferedImage target, int xx, int yy) {
		if(doAvgColor && xx > 0 && xx < w-1 && yy > 0 && yy < h-1) {
			int[] color = new int[27];
			target.getRaster().getPixels(xx-1,yy-1,3,3,color);
			targetColor[0] = (color[0] + color[3] + color[6] + color[9] + color[12] + color[15] + color[18] + color[21] + color[24])/9;
			targetColor[1] = (color[1] + color[4] + color[7] + color[10] + color[13] + color[16] + color[19] + color[22] + color[25])/9;
			targetColor[2] = (color[2] + color[5] + color[8] + color[11] + color[14] + color[17] + color[20] + color[23] + color[26])/9;
		}
		else {
			target.getRaster().getPixel(xx,yy,targetColor);
		}
	}

    public static void main(String args[]){
        if(args.length != 7){
            System.err.println("Usage: Cube2Erect <back> <bottom> <front> <left> <right> <top> <output>");
            System.exit(-1);
        }

        try {
        		Cube2Erect c = new Cube2Erect();
        		
        		BufferedImage imBack = ImageIO.read(new File(args[0]));
				BufferedImage imBottom = ImageIO.read(new File(args[1]));
				BufferedImage imFront = ImageIO.read(new File(args[2]));
				BufferedImage imLeft = ImageIO.read(new File(args[3]));
				BufferedImage imRight = ImageIO.read(new File(args[4]));
				BufferedImage imTop = ImageIO.read(new File(args[5]));
					
				BufferedImage bi = c.convert(imBack,imBottom,imFront,imLeft,imRight,imTop);
				ImageIO.write(bi, "JPG", new File(args[6]));
		} catch (IOException e) {
			e.printStackTrace();
		}
    }
}
