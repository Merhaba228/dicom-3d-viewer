package com.shibaykin.dicom3d.model;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

public final class DicomSlice {
    private final Path sourcePath;
    private final BufferedImage originalImage;
    private BufferedImage processedImage;
    private final float[] huPixels;
    private final int width;
    private final int height;
    private final int instanceNumber;
    private double sliceZ;
    private final double pixelSpacingX;
    private final double pixelSpacingY;
    private final double sliceThickness;
    private final double rescaleSlope;
    private final double rescaleIntercept;

    public DicomSlice(
            Path sourcePath,
            BufferedImage originalImage,
            float[] huPixels,
            int width,
            int height,
            int instanceNumber,
            double sliceZ,
            double pixelSpacingX,
            double pixelSpacingY,
            double sliceThickness,
            double rescaleSlope,
            double rescaleIntercept
    ) {
        this.sourcePath = sourcePath;
        this.originalImage = originalImage;
        this.processedImage = originalImage;
        this.huPixels = huPixels;
        this.width = width;
        this.height = height;
        this.instanceNumber = instanceNumber;
        this.sliceZ = sliceZ;
        this.pixelSpacingX = pixelSpacingX;
        this.pixelSpacingY = pixelSpacingY;
        this.sliceThickness = sliceThickness;
        this.rescaleSlope = rescaleSlope;
        this.rescaleIntercept = rescaleIntercept;
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public BufferedImage getOriginalImage() {
        return originalImage;
    }

    public BufferedImage getProcessedImage() {
        return processedImage;
    }

    public void setProcessedImage(BufferedImage processedImage) {
        this.processedImage = processedImage;
    }

    public float[] getHuPixels() {
        return huPixels;
    }

    public float getHu(int row, int col) {
        return huPixels[row * width + col];
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getInstanceNumber() {
        return instanceNumber;
    }

    public double getSliceZ() {
        return sliceZ;
    }

    public void setSliceZ(double sliceZ) {
        this.sliceZ = sliceZ;
    }

    public double getPixelSpacingX() {
        return pixelSpacingX;
    }

    public double getPixelSpacingY() {
        return pixelSpacingY;
    }

    public double getSliceThickness() {
        return sliceThickness;
    }

    public double getRescaleSlope() {
        return rescaleSlope;
    }

    public double getRescaleIntercept() {
        return rescaleIntercept;
    }

    public String shortName() {
        return sourcePath.getFileName().toString();
    }

    @Override
    public String toString() {
        return shortName();
    }
}
