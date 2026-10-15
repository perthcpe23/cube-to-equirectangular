package com.longdo.pano;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

public class Cube2Erect {

	// Enable this will reduce aliasing effects, but result in more blurry image.
	boolean doAvgColor = false;

	/**
	 * Convert 6 images of cube face2 to a single equirectangular image.
	 */
	public BufferedImage convert(
		BufferedImage imBack,
		BufferedImage imBottom,
		BufferedImage imFront,
		BufferedImage imLeft,
		BufferedImage imRight,
		BufferedImage imTop
	) throws IOException {
		int w = imBack.getWidth();
		int h = imBack.getHeight();

		// output image size, render big at first, then reduce this size by 50% (anti-aliasing)
		// reduce these multiply factors will dramatically reduce processing time but with a smaller output image
		int finalW = w * 8;
		int finalH = h * 4;

		float fW = finalW;
		float fH = finalH;

		// precompute some values
		double[] precomputedCosPhi = new double[finalH];
		double[] precomputedSinPhi = new double[finalH];
		for (int j = 0; j < finalH; j++) {
			// value range from -1 to 1
			float v = 2 * j / fH - 1;

			// convert to horizontal and vertical angle
			double phi = v * Math.PI / 2;

			precomputedCosPhi[j] = Math.cos(phi);
			precomputedSinPhi[j] = Math.sin(phi);
		}

		double[] precomputedCosTheta = new double[finalW];
		double[] precomputedSinTheta = new double[finalW];
		double[] precomputedAbsCosTheta = new double[finalW];
		double[] precomputedAbsSinTheta = new double[finalW];
		for (int i = 0; i < finalW; i++) {
			// value range from -1 to 1
			float u = 2 * i / fW - 1;

			// convert to horizontal and vertical angle
			double theta = u * Math.PI;

			double ct = Math.cos(theta);
			double st = Math.sin(theta);
			precomputedCosTheta[i] = ct;
			precomputedSinTheta[i] = st;
			precomputedAbsCosTheta[i] = Math.abs(ct);
			precomputedAbsSinTheta[i] = Math.abs(st);
		}

		int[][] facePixels = new int[6][];
		BufferedImage[] inputImgs = {imBack, imBottom, imFront, imLeft, imRight, imTop};
		int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors());

		if (nThreads > 1) {
			Thread[] extractThreads = new Thread[6];
			for (int i = 0; i < 6; i++) {
				final int idx = i;
				extractThreads[i] = new Thread(() -> {
					facePixels[idx] = extractPixels(inputImgs[idx], w, h);
				});
				extractThreads[i].start();
			}
			for (int i = 0; i < 6; i++) {
				try {
					extractThreads[i].join();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Pixel extraction interrupted", e);
				}
			}
		} else {
			for (int i = 0; i < 6; i++) {
				facePixels[i] = extractPixels(inputImgs[i], w, h);
			}
		}

		int[] pBack = facePixels[0];
		int[] pBottom = facePixels[1];
		int[] pFront = facePixels[2];
		int[] pLeft = facePixels[3];
		int[] pRight = facePixels[4];
		int[] pTop = facePixels[5];

		BufferedImage imOutput = new BufferedImage(finalW, finalH, BufferedImage.TYPE_INT_RGB);
		int[] outPixels = ((DataBufferInt) imOutput.getRaster().getDataBuffer()).getData();

		if (nThreads == 1) {
			renderRows(0, finalH, finalW, w, h, precomputedCosPhi, precomputedSinPhi,
			           precomputedCosTheta, precomputedSinTheta, precomputedAbsCosTheta, precomputedAbsSinTheta,
			           pBack, pBottom, pFront, pLeft, pRight, pTop, outPixels);
		} else {
			int chunkSize = (finalH + nThreads - 1) / nThreads;
			Thread[] threads = new Thread[nThreads - 1];
			for (int t = 0; t < nThreads - 1; t++) {
				final int startJ = t * chunkSize;
				final int endJ = Math.min(finalH, startJ + chunkSize);
				threads[t] = new Thread(() -> renderRows(startJ, endJ, finalW, w, h, precomputedCosPhi, precomputedSinPhi,
				                                         precomputedCosTheta, precomputedSinTheta, precomputedAbsCosTheta, precomputedAbsSinTheta,
				                                         pBack, pBottom, pFront, pLeft, pRight, pTop, outPixels));
				threads[t].start();
			}
			int startJ = (nThreads - 1) * chunkSize;
			if (startJ < finalH) {
				renderRows(startJ, finalH, finalW, w, h, precomputedCosPhi, precomputedSinPhi,
				           precomputedCosTheta, precomputedSinTheta, precomputedAbsCosTheta, precomputedAbsSinTheta,
				           pBack, pBottom, pFront, pLeft, pRight, pTop, outPixels);
			}
			for (Thread thread : threads) {
				try {
					thread.join();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Rendering interrupted", e);
				}
			}
		}

		imOutput = smooth(imOutput);

		return imOutput;
	}

	private static int[] extractPixels(BufferedImage img, int w, int h) {
		if (img.getType() == BufferedImage.TYPE_INT_RGB && img.getRaster().getDataBuffer() instanceof DataBufferInt) {
			return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
		}
		int[] rgb = new int[w * h];
		if (img.getType() == BufferedImage.TYPE_3BYTE_BGR && img.getRaster().getDataBuffer() instanceof DataBufferByte) {
			byte[] bytes = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
			for (int i = 0, j = 0; i < rgb.length; i++, j += 3) {
				rgb[i] = ((bytes[j + 2] & 0xFF) << 16) | ((bytes[j + 1] & 0xFF) << 8) | (bytes[j] & 0xFF);
			}
			return rgb;
		}
		return img.getRGB(0, 0, w, h, rgb, 0, w);
	}

	private void renderRows(
		int startJ, int endJ, int finalW, int w, int h,
		double[] precomputedCosPhi, double[] precomputedSinPhi,
		double[] precomputedCosTheta, double[] precomputedSinTheta,
		double[] precomputedAbsCosTheta, double[] precomputedAbsSinTheta,
		int[] pBack, int[] pBottom, int[] pFront, int[] pLeft, int[] pRight, int[] pTop,
		int[] outPixels
	) {
		if (!doAvgColor) {
			for (int j = startJ; j < endJ; j++) {
				double cosPhi = precomputedCosPhi[j];
				double y = precomputedSinPhi[j];
				double absY = Math.abs(y);
				int rowOffset = j * finalW;

				for (int i = 0; i < finalW; i++) {
					double cosTheta = precomputedCosTheta[i];
					double sinTheta = precomputedSinTheta[i];
					double absX = cosPhi * precomputedAbsCosTheta[i];
					double absZ = cosPhi * precomputedAbsSinTheta[i];
					double x = cosPhi * cosTheta;
					double z = cosPhi * sinTheta;

					int rgb;
					if (absX > absY && absX > absZ) {
						boolean isLeft = x < 0;
						double iz = z / absX;
						double iy = y / absX;
						if (isLeft) iz = -iz;
						int xx = (int) (((iz + 1) * w) / 2);
						int yy = (int) (((iy + 1) * h) / 2);
						if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
						if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
						int[] target = isLeft ? pLeft : pRight;
						rgb = target[yy * w + xx];
					} else if (absY > absX && absY > absZ) {
						boolean isTop = y < 0;
						double iz = z / absY;
						double ix = x / absY;
						if (isTop) iz = -iz;
						int xx = (int) (((ix + 1) * w) / 2);
						int yy = (int) (((iz + 1) * h) / 2);
						if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
						if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
						int[] target = isTop ? pTop : pBottom;
						rgb = target[yy * w + xx];
					} else {
						boolean isFront = z < 0;
						double iy = y / absZ;
						double ix = x / absZ;
						if (!isFront) ix = -ix;
						int xx = (int) Math.round(((ix + 1) * w) / 2);
						int yy = (int) Math.round(((iy + 1) * h) / 2);
						if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
						if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
						int[] target = isFront ? pFront : pBack;
						rgb = target[yy * w + xx];
					}
					outPixels[rowOffset + i] = rgb;
				}
			}
		} else {
			for (int j = startJ; j < endJ; j++) {
				double cosPhi = precomputedCosPhi[j];
				double y = precomputedSinPhi[j];
				double absY = Math.abs(y);
				int rowOffset = j * finalW;

				for (int i = 0; i < finalW; i++) {
					double cosTheta = precomputedCosTheta[i];
					double sinTheta = precomputedSinTheta[i];
					double absX = cosPhi * precomputedAbsCosTheta[i];
					double absZ = cosPhi * precomputedAbsSinTheta[i];
					double x = cosPhi * cosTheta;
					double z = cosPhi * sinTheta;

					int[] target;
					int xx, yy;
					if (absX > absY && absX > absZ) {
						boolean isLeft = x < 0;
						double iz = z / absX;
						double iy = y / absX;
						if (isLeft) iz = -iz;
						xx = (int) (((iz + 1) * w) / 2);
						yy = (int) (((iy + 1) * h) / 2);
						if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
						if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
						target = isLeft ? pLeft : pRight;
					} else if (absY > absX && absY > absZ) {
						boolean isTop = y < 0;
						double iz = z / absY;
						double ix = x / absY;
						if (isTop) iz = -iz;
						xx = (int) (((ix + 1) * w) / 2);
						yy = (int) (((iz + 1) * h) / 2);
						if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
						if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
						target = isTop ? pTop : pBottom;
					} else {
						boolean isFront = z < 0;
						double iy = y / absZ;
						double ix = x / absZ;
						if (!isFront) ix = -ix;
						xx = (int) Math.round(((ix + 1) * w) / 2);
						yy = (int) Math.round(((iy + 1) * h) / 2);
						if (xx < 0) xx = 0; else if (xx >= w) xx = w - 1;
						if (yy < 0) yy = 0; else if (yy >= h) yy = h - 1;
						target = isFront ? pFront : pBack;
					}

					int rgb;
					if (xx > 0 && xx < w - 1 && yy > 0 && yy < h - 1) {
						int p00 = target[(yy - 1) * w + (xx - 1)];
						int p01 = target[(yy - 1) * w + xx];
						int p02 = target[(yy - 1) * w + (xx + 1)];
						int p10 = target[yy * w + (xx - 1)];
						int p11 = target[yy * w + xx];
						int p12 = target[yy * w + (xx + 1)];
						int p20 = target[(yy + 1) * w + (xx - 1)];
						int p21 = target[(yy + 1) * w + xx];
						int p22 = target[(yy + 1) * w + (xx + 1)];

						int r = (((p00 >> 16) & 0xFF) + ((p01 >> 16) & 0xFF) + ((p02 >> 16) & 0xFF)
						       + ((p10 >> 16) & 0xFF) + ((p11 >> 16) & 0xFF) + ((p12 >> 16) & 0xFF)
						       + ((p20 >> 16) & 0xFF) + ((p21 >> 16) & 0xFF) + ((p22 >> 16) & 0xFF)) / 9;
						int g = (((p00 >> 8) & 0xFF) + ((p01 >> 8) & 0xFF) + ((p02 >> 8) & 0xFF)
						       + ((p10 >> 8) & 0xFF) + ((p11 >> 8) & 0xFF) + ((p12 >> 8) & 0xFF)
						       + ((p20 >> 8) & 0xFF) + ((p21 >> 8) & 0xFF) + ((p22 >> 8) & 0xFF)) / 9;
						int b = ((p00 & 0xFF) + (p01 & 0xFF) + (p02 & 0xFF)
						       + (p10 & 0xFF) + (p11 & 0xFF) + (p12 & 0xFF)
						       + (p20 & 0xFF) + (p21 & 0xFF) + (p22 & 0xFF)) / 9;
						rgb = (r << 16) | (g << 8) | b;
					} else {
						rgb = target[yy * w + xx];
					}
					outPixels[rowOffset + i] = rgb;
				}
			}
		}
	}

	private int[] findColor(
		BufferedImage imBack,
		BufferedImage imBottom,
		BufferedImage imFront,
		BufferedImage imLeft,
		BufferedImage imRight,
		BufferedImage imTop,
		int w,
		int h,
		double x,
		double y,
		double z
	) {
		BufferedImage target;
		double absX = Math.abs(x);
		double absY = Math.abs(y);
		double absZ = Math.abs(z);
		int[] targetColor = new int[3];

		if (absX > absY && absX > absZ) { // on left or right face
			boolean isLeft = x < 0;

			// intersection point on x plane
			z /= absX;
			y /= absX;

			if (isLeft) {
				z = -z;
			}

			int xx = (int) (((z + 1) * w) / 2);
			int yy = (int) (((y + 1) * h) / 2);

			xx = Math.max(0, Math.min(w - 1, xx));
			yy = Math.max(0, Math.min(h - 1, yy));

			if (isLeft) {
				target = imLeft;
			} else {
				target = imRight;
			}

			selectColor(w, h, targetColor, target, xx, yy);
		} else if (absY > absX && absY > absZ) { // on top or bottom face
			boolean isTop = y < 0;

			// intersection point on y plane
			z /= absY;
			x /= absY;

			if (isTop) {
				z = -z;
			}

			int xx = (int) (((x + 1) * w) / 2);
			int yy = (int) (((z + 1) * h) / 2);

			xx = Math.max(0, Math.min(w - 1, xx));
			yy = Math.max(0, Math.min(h - 1, yy));

			if (isTop) {
				target = imTop;
			} else {
				target = imBottom;
			}

			selectColor(w, h, targetColor, target, xx, yy);
		} else { // on front or back face
			boolean isFront = z < 0;

			// intersection point on z plane
			y /= absZ;
			x /= absZ;

			if (!isFront) {
				x = -x;
			}

			int xx = (int) Math.round(((x + 1) * w) / 2);
			int yy = (int) Math.round(((y + 1) * h) / 2);

			xx = Math.max(0, Math.min(w - 1, xx));
			yy = Math.max(0, Math.min(h - 1, yy));

			if (isFront) {
				target = imFront;
			} else {
				target = imBack;
			}

			selectColor(w, h, targetColor, target, xx, yy);
		}
		return targetColor;
	}

	private BufferedImage smooth(BufferedImage before) {
		long start = System.currentTimeMillis();

		// scale down help reducing aliasing effect
		double scale = 0.5;
		BufferedImage after = new BufferedImage(
			(int) (before.getWidth() * scale),
			(int) (before.getHeight() * scale),
			BufferedImage.TYPE_INT_RGB
		);

		AffineTransform at = new AffineTransform();
		at.scale(scale, scale);
		AffineTransformOp scaleOp = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
		after = scaleOp.filter(before, after);
		
		System.out.println("Smoothing took " + (System.currentTimeMillis() - start) + " ms");
		return after;
	}

	// 3x3 average color
	private void selectColor(int w, int h, int[] targetColor, BufferedImage target, int xx, int yy) {
		if (doAvgColor && xx > 0 && xx < w - 1 && yy > 0 && yy < h - 1) {
			int[] color = new int[27];
			target.getRaster().getPixels(xx - 1, yy - 1, 3, 3, color);
			targetColor[0] = (color[0] + color[3] + color[6] + color[9] + color[12] + color[15] + color[18] + color[21] + color[24]) / 9;
			targetColor[1] = (color[1] + color[4] + color[7] + color[10] + color[13] + color[16] + color[19] + color[22] + color[25]) / 9;
			targetColor[2] = (color[2] + color[5] + color[8] + color[11] + color[14] + color[17] + color[20] + color[23] + color[26]) / 9;
		} else {
			target.getRaster().getPixel(xx, yy, targetColor);
		}
	}

	public static void main(String args[]) {
		if (args.length != 7) {
			System.err.println("Usage: Cube2Erect <back> <bottom> <front> <left> <right> <top> <output>");
			System.exit(-1);
		}

		try {
			Cube2Erect c = new Cube2Erect();

			long start = System.currentTimeMillis();

			BufferedImage[] images = new BufferedImage[6];
			Thread[] readers = new Thread[6];
			Throwable[] errors = new Throwable[6];
			for (int i = 0; i < 6; i++) {
				final int idx = i;
				readers[i] = new Thread(() -> {
					try {
						images[idx] = ImageIO.read(new File(args[idx]));
					} catch (Throwable t) {
						errors[idx] = t;
					}
				});
				readers[i].start();
			}
			for (int i = 0; i < 6; i++) {
				try {
					readers[i].join();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while reading input images", e);
				}
				if (errors[i] != null) {
					if (errors[i] instanceof IOException) {
						throw (IOException) errors[i];
					} else {
						throw new IOException(errors[i]);
					}
				}
			}

			System.out.println("Read files took " + (System.currentTimeMillis() - start) + " ms");
			start = System.currentTimeMillis();

			BufferedImage bi = c.convert(images[0], images[1], images[2], images[3], images[4], images[5]);
			System.out.println("Conversion took " + (System.currentTimeMillis() - start) + " ms");
			start = System.currentTimeMillis();

			ImageIO.write(bi, "JPG", new File(args[6]));
			System.out.println("Writing output took " + (System.currentTimeMillis() - start) + " ms");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
