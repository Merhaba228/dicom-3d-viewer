package com.shibaykin.dicom3d.service;

import com.shibaykin.dicom3d.model.DicomSlice;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Arrays;

public final class ImageProcessingService {
    private static final int GRAY_SIMILARITY_TOLERANCE = 24;
    private static final int MIN_SIMILAR_GRAY_NEIGHBORS = 2;

    public BufferedImage applyFilters(
            DicomSlice slice,
            int brightnessThreshold,
            int minHu,
            int maxHu,
            boolean gentleMedianEnabled,
            boolean standardMedianEnabled,
            int blurRadius
    ) {
        int width = slice.getWidth();
        int height = slice.getHeight();
        int[] pixels = toGrayArray(slice.getOriginalImage());

        int[] denoised = standardMedianEnabled
                ? standardMedian3x3(pixels, width, height)
                : gentleMedianEnabled ? gentleMedian3x3(pixels, width, height) : pixels;
        int[] blurred = blurRadius > 0 ? boxBlur(denoised, width, height, blurRadius) : denoised;
        float[] huPixels = standardMedianEnabled
                ? standardMedian3x3(slice.getHuPixels(), width, height)
                : slice.getHuPixels();
        int[] filtered = huBandPass(blurred, huPixels, brightnessThreshold, minHu, maxHu);

        return toGrayImage(filtered, width, height);
    }

    public BufferedImage applyFilters(
            DicomSlice slice,
            int brightnessThreshold,
            int minHu,
            int maxHu,
            boolean gentleMedianEnabled,
            int blurRadius
    ) {
        return applyFilters(
                slice,
                brightnessThreshold,
                minHu,
                maxHu,
                gentleMedianEnabled,
                false,
                blurRadius
        );
    }

    private BufferedImage toGrayImage(int[] pixels, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] output = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < output.length; i++) {
            output[i] = (byte) pixels[i];
        }
        return result;
    }

    private int[] toGrayArray(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] out = new int[width * height];

        if (source.getType() == BufferedImage.TYPE_BYTE_GRAY
                && source.getRaster().getDataBuffer() instanceof DataBufferByte buffer) {
            byte[] input = buffer.getData();
            for (int i = 0; i < out.length; i++) {
                out[i] = input[i] & 0xFF;
            }
            return out;
        }

        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = source.getRGB(x, y);
                int r = (rgb >>> 16) & 0xFF;
                int g = (rgb >>> 8) & 0xFF;
                int b = rgb & 0xFF;
                out[idx++] = (int) Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
            }
        }
        return out;
    }

    private int[] gentleMedian3x3(int[] input, int width, int height) {
        int[] output = Arrays.copyOf(input, input.length);
        int[] window = new int[9];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int count = 0;
                int similarNeighbors = 0;
                int center = input[y * width + x];
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = clampIndex(y + dy, height);
                    int row = yy * width;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = clampIndex(x + dx, width);
                        int value = input[row + xx];
                        window[count++] = value;
                        if ((dx != 0 || dy != 0) && Math.abs(value - center) <= GRAY_SIMILARITY_TOLERANCE) {
                            similarNeighbors++;
                        }
                    }
                }
                if (similarNeighbors < MIN_SIMILAR_GRAY_NEIGHBORS) {
                    Arrays.sort(window);
                    output[y * width + x] = window[4];
                }
            }
        }
        return output;
    }

    private int[] standardMedian3x3(int[] input, int width, int height) {
        int[] output = new int[input.length];
        int[] window = new int[9];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = clampIndex(y + dy, height);
                    int row = yy * width;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = clampIndex(x + dx, width);
                        window[count++] = input[row + xx];
                    }
                }
                Arrays.sort(window);
                output[y * width + x] = window[4];
            }
        }
        return output;
    }

    private float[] standardMedian3x3(float[] input, int width, int height) {
        float[] output = new float[input.length];
        float[] window = new float[9];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = clampIndex(y + dy, height);
                    int row = yy * width;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = clampIndex(x + dx, width);
                        window[count++] = input[row + xx];
                    }
                }
                Arrays.sort(window);
                output[y * width + x] = window[4];
            }
        }
        return output;
    }

    private int[] boxBlur(int[] input, int width, int height, int radius) {
        int[] temp = new int[input.length];
        int[] output = new int[input.length];
        int window = radius * 2 + 1;

        for (int y = 0; y < height; y++) {
            int row = y * width;
            int sum = 0;
            for (int x = -radius; x <= radius; x++) {
                int xx = clampIndex(x, width);
                sum += input[row + xx];
            }
            for (int x = 0; x < width; x++) {
                temp[row + x] = sum / window;
                int remove = clampIndex(x - radius, width);
                int add = clampIndex(x + radius + 1, width);
                sum += input[row + add] - input[row + remove];
            }
        }

        for (int x = 0; x < width; x++) {
            int sum = 0;
            for (int y = -radius; y <= radius; y++) {
                int yy = clampIndex(y, height);
                sum += temp[yy * width + x];
            }
            for (int y = 0; y < height; y++) {
                output[y * width + x] = sum / window;
                int remove = clampIndex(y - radius, height);
                int add = clampIndex(y + radius + 1, height);
                sum += temp[add * width + x] - temp[remove * width + x];
            }
        }

        return output;
    }

    private int[] huBandPass(int[] input, float[] huPixels, int threshold, int minHu, int maxHu) {
        int low = Math.min(minHu, maxHu);
        int high = Math.max(minHu, maxHu);
        int[] output = new int[input.length];
        int n = Math.min(input.length, huPixels.length);
        for (int i = 0; i < n; i++) {
            int value = input[i];
            float hu = huPixels[i];
            output[i] = value >= threshold && hu >= low && hu <= high ? value : 0;
        }
        return output;
    }

    private int clampIndex(int index, int limit) {
        if (index < 0) {
            return 0;
        }
        if (index >= limit) {
            return limit - 1;
        }
        return index;
    }
}
