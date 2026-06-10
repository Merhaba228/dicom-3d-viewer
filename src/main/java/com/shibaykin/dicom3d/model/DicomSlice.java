package com.shibaykin.dicom3d.model;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

public final class DicomSlice {
    private final Path sourcePath;
    private final BufferedImage originalImage;
    private BufferedImage processedImage;
    private final BufferedImage manualMask;
    private final float[] huPixels;
    private final int width;
    private final int height;
    private final int instanceNumber;
    private double sliceZ;
    private final double pixelSpacingX;
    private final double pixelSpacingY;
    private final double sliceThickness;

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
            double sliceThickness
    ) {
        this.sourcePath = sourcePath;
        this.originalImage = originalImage;
        this.processedImage = originalImage;
        this.manualMask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        resetManualMask();
        this.huPixels = huPixels;
        this.width = width;
        this.height = height;
        this.instanceNumber = instanceNumber;
        this.sliceZ = sliceZ;
        this.pixelSpacingX = pixelSpacingX;
        this.pixelSpacingY = pixelSpacingY;
        this.sliceThickness = sliceThickness;
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

    public BufferedImage getManualMask() {
        return manualMask;
    }

    public void resetManualMask() {
        Graphics2D graphics = manualMask.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, manualMask.getWidth(), manualMask.getHeight());
        graphics.dispose();
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

    public String shortName() {
        return sourcePath.getFileName().toString();
    }

    @Override
    public String toString() {
        return shortName();
    }
}
