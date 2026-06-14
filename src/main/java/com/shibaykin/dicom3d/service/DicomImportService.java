package com.shibaykin.dicom3d.service;

import com.shibaykin.dicom3d.model.DicomSlice;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;

public final class DicomImportService {
    static {
        ImageIO.scanForPlugins();
    }

    public List<DicomSlice> loadFromDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Указанный путь не является папкой: " + directory);
        }

        List<Path> dicomFiles;
        try (Stream<Path> stream = Files.list(directory)) {
            dicomFiles = stream
                    .filter(path -> Files.isRegularFile(path) && isDicom(path))
                    .collect(Collectors.toList());
        }

        return loadDicomPaths(dicomFiles);
    }

    public List<DicomSlice> loadFromPaths(List<Path> paths) throws IOException {
        List<Path> dicomPaths = paths.stream()
                .filter(path -> Files.isRegularFile(path) && isDicom(path))
                .collect(Collectors.toList());
        return loadDicomPaths(dicomPaths);
    }

    private List<DicomSlice> loadDicomPaths(List<Path> dicomPaths) throws IOException {
        if (dicomPaths.isEmpty()) {
            throw new IOException("Файлы .dcm не найдены");
        }

        List<DicomSlice> slices;
        try {
            slices = dicomPaths.parallelStream()
                    .map(this::loadSingleSliceUnchecked)
                    .collect(Collectors.toCollection(() -> new ArrayList<>(dicomPaths.size())));
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }

        slices.sort(Comparator
                .comparingDouble(this::sortZ)
                .thenComparingInt(DicomSlice::getInstanceNumber)
                .thenComparing(slice -> slice.getSourcePath().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        normalizeMissingZ(slices);
        return slices;
    }

    private DicomSlice loadSingleSliceUnchecked(Path path) {
        try {
            return loadSingleSlice(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private DicomSlice loadSingleSlice(Path dicomPath) throws IOException {
        Attributes attributes;
        try (DicomInputStream dis = new DicomInputStream(dicomPath.toFile())) {
            attributes = dis.readDataset();
        }

        BufferedImage decodedImage = readDicomImage(dicomPath);
        int width = decodedImage.getWidth();
        int height = decodedImage.getHeight();

        double rescaleSlope = attributes.getDouble(Tag.RescaleSlope, 1.0);
        double rescaleIntercept = attributes.getDouble(Tag.RescaleIntercept, 0.0);
        float[] huPixels = extractHuPixels(decodedImage, attributes, width, height, rescaleSlope, rescaleIntercept);

        double windowCenter = firstDicomDouble(attributes, Tag.WindowCenter, 300.0);
        double windowWidth = firstDicomDouble(attributes, Tag.WindowWidth, 1600.0);
        BufferedImage grayImage = renderHuToGray8(huPixels, width, height, windowCenter, windowWidth);

        int instanceNumber = attributes.getInt(Tag.InstanceNumber, Integer.MAX_VALUE);
        double[] imagePositionPatient = attributes.getDoubles(Tag.ImagePositionPatient);
        double[] imageOrientationPatient = attributes.getDoubles(Tag.ImageOrientationPatient);
        double z = slicePosition(imagePositionPatient, imageOrientationPatient);

        double[] pixelSpacing = attributes.getDoubles(Tag.PixelSpacing);
        double spacingY = pixelSpacing != null && pixelSpacing.length >= 1 ? pixelSpacing[0] : 1.0;
        double spacingX = pixelSpacing != null && pixelSpacing.length >= 2 ? pixelSpacing[1] : 1.0;
        double sliceThickness = attributes.getDouble(Tag.SliceThickness, 1.0);

        return new DicomSlice(
                dicomPath,
                grayImage,
                huPixels,
                width,
                height,
                instanceNumber,
                z,
                spacingX,
                spacingY,
                sliceThickness
        );
    }

    private double slicePosition(double[] position, double[] orientation) {
        if (position == null || position.length < 3) {
            return Double.NaN;
        }
        if (orientation == null || orientation.length < 6) {
            return position[2];
        }

        double normalX = orientation[1] * orientation[5] - orientation[2] * orientation[4];
        double normalY = orientation[2] * orientation[3] - orientation[0] * orientation[5];
        double normalZ = orientation[0] * orientation[4] - orientation[1] * orientation[3];
        double normalLength = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        if (!Double.isFinite(normalLength) || normalLength < 0.000001) {
            return position[2];
        }

        return (position[0] * normalX + position[1] * normalY + position[2] * normalZ) / normalLength;
    }

    private BufferedImage readDicomImage(Path dicomPath) throws IOException {
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(dicomPath.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                throw new IOException("Не найден ImageReader для файла: " + dicomPath);
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInputStream);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage renderHuToGray8(float[] huPixels, int width, int height, double center, double windowWidth) {
        BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] output = ((java.awt.image.DataBufferByte) normalized.getRaster().getDataBuffer()).getData();
        double actualCenter = Double.isFinite(center) ? center : 300.0;
        double actualWidth = Double.isFinite(windowWidth) && windowWidth > 1.0 ? windowWidth : 1600.0;
        double low = actualCenter - actualWidth / 2.0;
        double high = actualCenter + actualWidth / 2.0;
        if (high <= low) {
            high = low + 1.0;
        }

        for (int idx = 0; idx < output.length; idx++) {
            double hu = huPixels[idx];
            int gray = (int) Math.round(((hu - low) / (high - low)) * 255.0);
            output[idx] = (byte) Math.max(0, Math.min(255, gray));
        }
        return normalized;
    }

    private float[] extractHuPixels(
            BufferedImage source,
            Attributes attributes,
            int width,
            int height,
            double slope,
            double intercept
    ) {
        float[] rawPixels = extractRawPixelsFromDataset(attributes, width, height);
        if (rawPixels != null) {
            for (int i = 0; i < rawPixels.length; i++) {
                rawPixels[i] = (float) (rawPixels[i] * slope + intercept);
            }
            return rawPixels;
        }

        float[] huPixels = new float[width * height];
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double raw = sampleGray(source, x, y);
                huPixels[idx++] = (float) (raw * slope + intercept);
            }
        }
        return huPixels;
    }

    private float[] extractRawPixelsFromDataset(Attributes attributes, int width, int height) {
        Object pixelData = attributes.getValue(Tag.PixelData);
        if (!(pixelData instanceof byte[] bytes)) {
            return null;
        }

        int bitsAllocated = attributes.getInt(Tag.BitsAllocated, 0);
        int samplesPerPixel = attributes.getInt(Tag.SamplesPerPixel, 1);
        int pixelRepresentation = attributes.getInt(Tag.PixelRepresentation, 0);
        if (bitsAllocated != 16 || samplesPerPixel != 1) {
            return null;
        }

        int expected = width * height;
        if (bytes.length < expected * 2) {
            return null;
        }

        boolean bigEndian = attributes.bigEndian();
        float[] raw = new float[expected];
        for (int i = 0; i < expected; i++) {
            int b0 = bytes[i * 2] & 0xFF;
            int b1 = bytes[i * 2 + 1] & 0xFF;
            int value = bigEndian ? ((b0 << 8) | b1) : ((b1 << 8) | b0);
            if (pixelRepresentation == 1) {
                value = (short) value;
            }
            raw[i] = value;
        }
        return raw;
    }

    private double firstDicomDouble(Attributes attributes, int tag, double defaultValue) {
        double[] values = attributes.getDoubles(tag);
        if (values != null) {
            for (double value : values) {
                if (Double.isFinite(value)) {
                    return value;
                }
            }
        }

        double singleValue = attributes.getDouble(tag, defaultValue);
        return Double.isFinite(singleValue) ? singleValue : defaultValue;
    }

    private double sampleGray(BufferedImage image, int x, int y) {
        int bands = image.getRaster().getNumBands();
        if (bands == 1) {
            return image.getRaster().getSampleDouble(x, y, 0);
        }
        int rgb = image.getRGB(x, y);
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private void normalizeMissingZ(List<DicomSlice> slices) {
        double currentZ = 0.0;
        for (int i = 0; i < slices.size(); i++) {
            DicomSlice slice = slices.get(i);
            if (Double.isFinite(slice.getSliceZ())) {
                currentZ = slice.getSliceZ();
                continue;
            }
            if (i == 0) {
                currentZ = 0.0;
            } else {
                currentZ += Math.max(0.0001, slices.get(i - 1).getSliceThickness());
            }
            slice.setSliceZ(currentZ);
        }
    }

    private double sortZ(DicomSlice slice) {
        if (Double.isFinite(slice.getSliceZ())) {
            return slice.getSliceZ();
        }
        if (slice.getInstanceNumber() != Integer.MAX_VALUE) {
            return slice.getInstanceNumber();
        }
        return Double.POSITIVE_INFINITY;
    }

    private boolean isDicom(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".dcm")) {
            return true;
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(132);
            return header.length == 132
                    && header[128] == 'D'
                    && header[129] == 'I'
                    && header[130] == 'C'
                    && header[131] == 'M';
        } catch (IOException ignored) {
            return false;
        }
    }
}
