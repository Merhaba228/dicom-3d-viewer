package com.shibaykin.dicom3d.service;

import com.shibaykin.dicom3d.model.DicomSlice;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.IntStream;

public final class Model3DService {
    private static final int MAX_POINTS = 500_000;
    private static final int MIN_3D_NEIGHBORS = 2;
    private static final int MIN_COMPONENT_VOXELS = 64;
    private static final int BODY_MASK_THRESHOLD_HU = -350;
    private final Map<DicomSlice, BitSet> bodyMaskCache = Collections.synchronizedMap(new WeakHashMap<>());

    public Model3D buildSurfaceModel(
            List<DicomSlice> slices,
            int startIndex,
            int endIndex,
            int step,
            ModelPreset preset
    ) {
        if (slices.isEmpty()) {
            return new Model3D(List.of(), 1.0, false);
        }

        int start = Math.max(0, Math.min(startIndex, endIndex));
        int end = Math.min(slices.size() - 1, Math.max(startIndex, endIndex));
        int sampleStep = Math.max(1, step);
        int sliceStep = Math.max(1, sampleStep / 2);
        ModelPreset actualPreset = preset == null ? ModelPreset.BONES : preset;

        DicomSlice first = slices.get(start);
        int nx = sampledCount(first.getWidth(), sampleStep);
        int ny = sampledCount(first.getHeight(), sampleStep);
        int nz = ((end - start) / sliceStep) + 1;
        if (nx < 2 || ny < 2 || nz < 2) {
            return new Model3D(List.of(), 1.0, false);
        }

        BitSet active = buildActiveVolume(slices, start, nx, ny, nz, sampleStep, sliceStep, actualPreset);
        active = removeIsolatedVoxels(active, nx, ny, nz, MIN_3D_NEIGHBORS);
        active = removeSmallComponents(active, nx, ny, nz, MIN_COMPONENT_VOXELS);

        int sampledEnd = start + (nz - 1) * sliceStep;
        double zCenter = (sliceZ(slices, start, start) + sliceZ(slices, sampledEnd, start)) / 2.0;
        AxisBounds xBounds = buildXBounds(first, nx, sampleStep);
        AxisBounds yBounds = buildYBounds(first, ny, sampleStep);
        AxisBounds zBounds = buildZBounds(slices, start, nz, sliceStep, zCenter);

        Bounds bounds = new Bounds();
        List<ModelPoint> points = buildSurfacePoints(
                slices,
                active,
                start,
                nx,
                ny,
                nz,
                sampleStep,
                sliceStep,
                actualPreset,
                xBounds,
                yBounds,
                zBounds,
                bounds
        );
        if (points.isEmpty()) {
            return new Model3D(List.of(), 1.0, false);
        }

        boolean truncated = points.size() > MAX_POINTS;
        if (points.size() > MAX_POINTS) {
            points = decimatePointsEvenly(points, MAX_POINTS);
        }

        return new Model3D(List.copyOf(points), Math.max(1.0, bounds.radius()), truncated);
    }

    private List<ModelPoint> decimatePointsEvenly(List<ModelPoint> points, int maxPoints) {
        List<ModelPoint> result = new ArrayList<>(maxPoints);
        double stride = points.size() / (double) maxPoints;
        for (int i = 0; i < maxPoints; i++) {
            result.add(points.get(Math.min(points.size() - 1, (int) Math.floor(i * stride))));
        }
        return result;
    }

    private BitSet buildActiveVolume(
            List<DicomSlice> slices,
            int start,
            int nx,
            int ny,
            int nz,
            int step,
            int sliceStep,
            ModelPreset preset
    ) {
        BitSet[] activeSlices = new BitSet[nz];
        IntStream.range(0, nz).parallel().forEach(z -> {
            DicomSlice slice = slices.get(start + z * sliceStep);
            BufferedImage processed = slice.getProcessedImage();
            byte[] processedPixels = processed.getType() == BufferedImage.TYPE_BYTE_GRAY
                    && processed.getRaster().getDataBuffer() instanceof DataBufferByte buffer
                    ? buffer.getData()
                    : null;
            BitSet bodyMask = cachedBodyMask(slice);
            BitSet sliceActive = new BitSet(nx * ny);
            for (int y = 0; y < ny; y++) {
                int sourceY = sampleCoord(y, step, slice.getHeight());
                for (int x = 0; x < nx; x++) {
                    int sourceX = sampleCoord(x, step, slice.getWidth());
                    if (bodyMask.get(sourceY * slice.getWidth() + sourceX)
                            && intensityAt(processed, processedPixels, sourceX, sourceY) != 0) {
                        sliceActive.set(y * nx + x);
                    }
                }
            }
            activeSlices[z] = sliceActive;
        });

        BitSet active = new BitSet(nx * ny * nz);
        int sliceSize = nx * ny;
        for (int z = 0; z < nz; z++) {
            BitSet sliceActive = activeSlices[z];
            for (int index = sliceActive.nextSetBit(0); index >= 0; index = sliceActive.nextSetBit(index + 1)) {
                active.set(z * sliceSize + index);
            }
        }
        return active;
    }

    private BitSet cachedBodyMask(DicomSlice slice) {
        synchronized (bodyMaskCache) {
            BitSet cached = bodyMaskCache.get(slice);
            if (cached != null) {
                return cached;
            }
        }
        BitSet computed = buildLargestBodyMask(slice, BODY_MASK_THRESHOLD_HU);
        synchronized (bodyMaskCache) {
            BitSet cached = bodyMaskCache.get(slice);
            if (cached != null) {
                return cached;
            }
            bodyMaskCache.put(slice, computed);
            return computed;
        }
    }

    private BitSet buildLargestBodyMask(DicomSlice slice, int thresholdHu) {
        int width = slice.getWidth();
        int height = slice.getHeight();
        int size = width * height;
        BitSet candidates = new BitSet(size);
        float[] hu = slice.getHuPixels();
        for (int i = 0; i < Math.min(size, hu.length); i++) {
            if (hu[i] > thresholdHu) {
                candidates.set(i);
            }
        }

        BitSet visited = new BitSet(size);
        BitSet largest = new BitSet(size);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int seed = candidates.nextSetBit(0); seed >= 0; seed = candidates.nextSetBit(seed + 1)) {
            if (visited.get(seed)) {
                continue;
            }
            BitSet component = new BitSet(size);
            visited.set(seed);
            queue.add(seed);
            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                component.set(index);
                int y = index / width;
                int x = index - y * width;
                addBodyNeighbor(candidates, visited, queue, x - 1, y, width, height);
                addBodyNeighbor(candidates, visited, queue, x + 1, y, width, height);
                addBodyNeighbor(candidates, visited, queue, x, y - 1, width, height);
                addBodyNeighbor(candidates, visited, queue, x, y + 1, width, height);
            }
            if (component.cardinality() > largest.cardinality()) {
                largest = component;
            }
        }
        return largest;
    }

    private void addBodyNeighbor(
            BitSet candidates,
            BitSet visited,
            ArrayDeque<Integer> queue,
            int x,
            int y,
            int width,
            int height
    ) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        int index = y * width + x;
        if (candidates.get(index) && !visited.get(index)) {
            visited.set(index);
            queue.add(index);
        }
    }

    private BitSet removeIsolatedVoxels(BitSet active, int nx, int ny, int nz, int minNeighbors) {
        if (minNeighbors <= 0 || active.isEmpty()) {
            return active;
        }

        BitSet kept = new BitSet(nx * ny * nz);
        for (int z = 0; z < nz; z++) {
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++) {
                    if (!isActive(active, x, y, z, nx, ny, nz)) {
                        continue;
                    }
                    int neighbors = 0;
                    for (int dz = -1; dz <= 1 && neighbors < minNeighbors; dz++) {
                        for (int dy = -1; dy <= 1 && neighbors < minNeighbors; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                if (dx == 0 && dy == 0 && dz == 0) {
                                    continue;
                                }
                                if (isActive(active, x + dx, y + dy, z + dz, nx, ny, nz)) {
                                    neighbors++;
                                    if (neighbors >= minNeighbors) {
                                        kept.set(pointIndex(x, y, z, nx, ny));
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return kept;
    }

    private BitSet removeSmallComponents(BitSet active, int nx, int ny, int nz, int minSize) {
        if (active.isEmpty()) {
            return active;
        }

        BitSet visited = new BitSet(nx * ny * nz);
        BitSet kept = new BitSet(nx * ny * nz);
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int seed = active.nextSetBit(0); seed >= 0; seed = active.nextSetBit(seed + 1)) {
            if (visited.get(seed)) {
                continue;
            }

            BitSet component = new BitSet(nx * ny * nz);
            queue.clear();
            queue.add(seed);
            visited.set(seed);

            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                component.set(index);

                int z = index / (nx * ny);
                int rem = index - z * nx * ny;
                int y = rem / nx;
                int x = rem - y * nx;

                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0 && dz == 0) {
                                continue;
                            }
                            int nxp = x + dx;
                            int nyp = y + dy;
                            int nzp = z + dz;
                            if (nxp < 0 || nyp < 0 || nzp < 0 || nxp >= nx || nyp >= ny || nzp >= nz) {
                                continue;
                            }
                            int neighbor = pointIndex(nxp, nyp, nzp, nx, ny);
                            if (active.get(neighbor) && !visited.get(neighbor)) {
                                visited.set(neighbor);
                                queue.add(neighbor);
                            }
                        }
                    }
                }
            }

            if (component.cardinality() >= minSize) {
                kept.or(component);
            }
        }

        return kept;
    }

    private AxisBounds buildXBounds(DicomSlice slice, int count, int step) {
        double[] min = new double[count];
        double[] max = new double[count];
        double half = slice.getPixelSpacingX() * step / 2.0;
        for (int i = 0; i < count; i++) {
            double center = worldX(sampleCoord(i, step, slice.getWidth()), slice);
            min[i] = center - half;
            max[i] = center + half;
        }
        return new AxisBounds(min, max);
    }

    private AxisBounds buildYBounds(DicomSlice slice, int count, int step) {
        double[] min = new double[count];
        double[] max = new double[count];
        double half = slice.getPixelSpacingY() * step / 2.0;
        for (int i = 0; i < count; i++) {
            double center = worldY(sampleCoord(i, step, slice.getHeight()), slice);
            min[i] = center - half;
            max[i] = center + half;
        }
        return new AxisBounds(min, max);
    }

    private AxisBounds buildZBounds(List<DicomSlice> slices, int start, int count, int sliceStep, double zCenter) {
        double[] centers = new double[count];
        for (int i = 0; i < count; i++) {
            centers[i] = sliceZ(slices, start + i * sliceStep, start) - zCenter;
        }

        double[] min = new double[count];
        double[] max = new double[count];
        for (int i = 0; i < count; i++) {
            double previousStep = i > 0
                    ? Math.abs(centers[i] - centers[i - 1])
                    : Math.max(0.0001, slices.get(start + i * sliceStep).getSliceThickness() * sliceStep);
            double nextStep = i < count - 1
                    ? Math.abs(centers[i + 1] - centers[i])
                    : Math.max(0.0001, slices.get(start + i * sliceStep).getSliceThickness() * sliceStep);
            min[i] = centers[i] - previousStep / 2.0;
            max[i] = centers[i] + nextStep / 2.0;
        }
        return new AxisBounds(min, max);
    }

    private List<ModelPoint> buildSurfacePoints(
            List<DicomSlice> slices,
            BitSet active,
            int start,
            int nx,
            int ny,
            int nz,
            int step,
            int sliceStep,
            ModelPreset preset,
            AxisBounds xBounds,
            AxisBounds yBounds,
            AxisBounds zBounds,
            Bounds bounds
    ) {
        List<ModelPoint> points = new ArrayList<>();
        for (int z = 0; z < nz; z++) {
            DicomSlice slice = slices.get(start + z * sliceStep);
            for (int y = 0; y < ny; y++) {
                int sourceY = sampleCoord(y, step, slice.getHeight());
                for (int x = 0; x < nx; x++) {
                    if (!isActive(active, x, y, z, nx, ny, nz) || !isSurfaceVoxel(active, x, y, z, nx, ny, nz)) {
                        continue;
                    }

                    int sourceX = sampleCoord(x, step, slice.getWidth());
                    int intensity = modelIntensity(slice, sourceX, sourceY, preset);
                    int[] color = colorFor(slice, sourceX, sourceY, preset);
                    double px = (xBounds.min[x] + xBounds.max[x]) / 2.0;
                    double py = (yBounds.min[y] + yBounds.max[y]) / 2.0;
                    double pz = (zBounds.min[z] + zBounds.max[z]) / 2.0;
                    bounds.add(px, py, pz);
                    points.add(new ModelPoint(px, py, pz, intensity, color[0], color[1], color[2]));
                }
            }
        }
        return points;
    }

    private boolean isSurfaceVoxel(BitSet active, int x, int y, int z, int nx, int ny, int nz) {
        return !isActive(active, x - 1, y, z, nx, ny, nz)
                || !isActive(active, x + 1, y, z, nx, ny, nz)
                || !isActive(active, x, y - 1, z, nx, ny, nz)
                || !isActive(active, x, y + 1, z, nx, ny, nz)
                || !isActive(active, x, y, z - 1, nx, ny, nz)
                || !isActive(active, x, y, z + 1, nx, ny, nz);
    }

    private boolean isActive(BitSet active, int x, int y, int z, int nx, int ny, int nz) {
        if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) {
            return false;
        }
        return active.get(pointIndex(x, y, z, nx, ny));
    }

    private int modelIntensity(DicomSlice slice, int x, int y, ModelPreset preset) {
        if (preset == ModelPreset.FILTERED_MASK) {
            return 190;
        }
        float hu = slice.getHu(y, x);
        double t = (hu - preset.minHu()) / Math.max(1.0, preset.maxHu() - preset.minHu());
        return Math.max(80, Math.min(250, (int) Math.round(90 + t * 160)));
    }

    private int[] colorFor(DicomSlice slice, int x, int y, ModelPreset preset) {
        int value = modelIntensity(slice, x, y, preset);
        if (preset == ModelPreset.BONES) {
            return new int[] {
                    Math.min(255, (int) Math.round(value * 1.08)),
                    Math.min(245, (int) Math.round(value * 0.98)),
                    Math.min(225, (int) Math.round(value * 0.84))
            };
        }
        if (preset == ModelPreset.FILTERED_MASK) {
            return new int[] { value, value, value };
        }
        if (preset == ModelPreset.SOFT_TISSUE) {
            return new int[] {
                    Math.min(255, (int) Math.round(value * 1.08)),
                    Math.max(35, (int) Math.round(value * 0.92)),
                    Math.max(30, (int) Math.round(value * 0.82))
            };
        }

        float hu = slice.getHu(y, x);
        double t = (hu - preset.minHu()) / Math.max(1.0, preset.maxHu() - preset.minHu());
        t = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(70 + 180 * t);
        int g = (int) Math.round(90 + 130 * (1.0 - Math.abs(t - 0.5) * 2.0));
        int b = (int) Math.round(145 + 70 * (1.0 - t));
        return new int[] { r, g, b };
    }

    private int intensityAt(BufferedImage image, byte[] pixels, int x, int y) {
        if (pixels != null) {
            return pixels[y * image.getWidth() + x] & 0xFF;
        }
        return image.getRaster().getNumBands() == 1
                ? image.getRaster().getSample(x, y, 0)
                : image.getRGB(x, y) & 0xFF;
    }

    private int sampledCount(int size, int step) {
        return Math.max(2, ((size - 1) / step) + 1);
    }

    private int sampleCoord(int index, int step, int limit) {
        return Math.min(limit - 1, index * step);
    }

    private double worldX(int x, DicomSlice slice) {
        return x * slice.getPixelSpacingX() - (slice.getWidth() - 1) * slice.getPixelSpacingX() / 2.0;
    }

    private double worldY(int y, DicomSlice slice) {
        return (slice.getHeight() - 1) * slice.getPixelSpacingY() / 2.0 - y * slice.getPixelSpacingY();
    }

    private double sliceZ(List<DicomSlice> slices, int sliceIndex, int startIndex) {
        DicomSlice slice = slices.get(sliceIndex);
        if (Double.isFinite(slice.getSliceZ())) {
            return slice.getSliceZ();
        }
        return (sliceIndex - startIndex) * Math.max(0.0001, slice.getSliceThickness());
    }

    private int pointIndex(int x, int y, int z, int nx, int ny) {
        return (z * ny + y) * nx + x;
    }

    public record Model3D(List<ModelPoint> points, double radius, boolean truncated) {
    }

    public record ModelPoint(double x, double y, double z, int intensity, int r, int g, int b) {
    }

    public enum ModelPreset {
        BONES("Кости (HU 400..3000)", 400, 3000, 3),
        SOFT_TISSUE("Органы / мягкие ткани (HU 0..120)", 0, 120, 6),
        BODY("Тело целиком (HU -500..3000)", -500, 3000, 5),
        FILTERED_MASK("Ручной диапазон HU", Integer.MIN_VALUE, Integer.MAX_VALUE, 3);

        private final String label;
        private final int minHu;
        private final int maxHu;
        private final int recommendedStep;

        ModelPreset(String label, int minHu, int maxHu, int recommendedStep) {
            this.label = label;
            this.minHu = minHu;
            this.maxHu = maxHu;
            this.recommendedStep = recommendedStep;
        }

        public int minHu() {
            return minHu;
        }

        public int maxHu() {
            return maxHu;
        }

        public int recommendedStep() {
            return recommendedStep;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record AxisBounds(double[] min, double[] max) {
    }

    private static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        private void add(double x, double y, double z) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

        private double radius() {
            return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) / 2.0;
        }
    }
}
