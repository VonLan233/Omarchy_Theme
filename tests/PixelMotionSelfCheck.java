package com.feiyu.nightterminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Run on the host with the app's compiled classes and android.jar on the classpath. */
public final class PixelMotionSelfCheck {
    public static void main(String[] args) throws Exception {
        check(PixelMotion.progress(100, -1) == 0, "idle does not unlock");
        check(PixelMotion.progress(99, 100) == 0, "no negative progress");
        check(PixelMotion.progress(100, 100) == 0, "burst starts assembled");
        check(PixelMotion.progress(550, 100) == .5f, "halfway at 450ms");
        check(PixelMotion.progress(1000, 100) == 1, "home at 900ms");
        check(PixelMotion.progress(5000, 100) == 1, "progress stays settled");
        check(PixelMotion.scatter(0) == 0 && PixelMotion.scatter(1) == 0, "burst reassembles exactly");
        check(Math.abs(PixelMotion.scatter(.5f) - 1) < .0001, "midpoint scatters fully");
        check(!PixelMotion.animationAllowed(0, false), "system animations disabled");
        check(!PixelMotion.animationAllowed(1, true), "battery saver stays still");
        check(PixelMotion.animationAllowed(1, false), "normal mode animates");
        System.out.println("PixelMotion: 11 timing and reduced-motion checks passed");

        int[] fixture = new int[80 * 80];
        square(fixture, 80, 20, 20, 13); square(fixture, 80, 48, 48, 13);
        ArrayList<int[]> tiles = PixelMotion.wholeTiles(fixture, 80, 80);
        check(tiles.size() == 2, "whole squares remain separate");
        check(Arrays.equals(tiles.get(0), new int[]{20, 20, 33, 33}), "first complete boundary");
        check(Arrays.equals(tiles.get(1), new int[]{48, 48, 61, 61}), "second complete boundary");
        Arrays.fill(fixture, 0); square(fixture, 80, 20, 20, 13); square(fixture, 80, 30, 30, 13);
        check(PixelMotion.wholeTiles(fixture, 80, 80).isEmpty(), "connected irregular art stays intact");
        BufferedImage source = ImageIO.read(new File(args[0]));
        int[] pixels = source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth());
        tiles = PixelMotion.wholeTiles(pixels, source.getWidth(), source.getHeight());
        check(tiles.size() == 109, "109 complete isolated squares in the approved source");
        for (int[] tile : tiles) {
            int count = 0;
            for (int y = tile[1]; y < tile[3]; y++) for (int x = tile[0]; x < tile[2]; x++)
                if ((pixels[y * source.getWidth() + x] & 255) >= 36) count++;
            check(count >= (tile[2] - tile[0]) * (tile[3] - tile[1]) * .9f, "no partial grid crop");
        }
        System.out.println("PixelMotion: whole-square fixtures and 109 approved-source bounds passed");
    }

    private static void square(int[] pixels, int width, int left, int top, int size) {
        for (int y = top; y < top + size; y++) for (int x = left; x < left + size; x++)
            pixels[y * width + x] = 0xFF8877AA;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
