package com.shibaykin.dicom3d.service;

import com.shibaykin.dicom3d.model.DicomSlice;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.List;

public final class Model3DService {
    private static final int MAX_FACES = 450_000;
    private static final int MAX_POINTS = 260_000;
    private static final int MIN_3D_NEIGHBORS = 2;

    public Model3D buildSurfaceModel(
            List<DicomSlice> slices,
            int startIndex,
            int endIndex,
            int step,
            ModelPreset preset
    ) {
        if (slices.isEmpty()) {
            return new Model3D(List.of(), List.of(), 1.0, false);
        }

        int start = Math.max(0, Math.min(startIndex, endIndex));
        int end = Math.min(slices.size() - 1, Math.max(startIndex, endIndex));
        int sampleStep = Math.max(1, step);
        ModelPreset actualPreset = preset == null ? ModelPreset.BONES : preset;

        DicomSlice first = slices.get(start);
        int nx = sampledCount(first.getWidth(), sampleStep);
        int ny = sampledCount(first.getHeight(), sampleStep);
        int nz = end - start + 1;
        if (nx < 2 || ny < 2 || nz < 2) {
            return new Model3D(List.of(), List.of(), 1.0, false);
        }

        BitSet active = buildActiveVolume(slices, start, nx, ny, nz, sampleStep, actualPreset);
        active = removeIsolatedVoxels(active, nx, ny, nz, MIN_3D_NEIGHBORS);
        active = keepLargestComponent(active, nx, ny, nz);

        double zCenter = (sliceZ(slices, start, start) + sliceZ(slices, end, start)) / 2.0;
        AxisBounds xBounds = buildXBounds(first, nx, sampleStep);
        AxisBounds yBounds = buildYBounds(first, ny, sampleStep);
        AxisBounds zBounds = buildZBounds(slices, start, nz, zCenter);

        Bounds bounds = new Bounds();
        List<ModelPoint> points = buildSurfacePoints(
                slices,
                active,
                start,
                nx,
                ny,
                nz,
                sampleStep,
                actualPreset,
                xBounds,
                yBounds,
                zBounds,
                bounds
        );
        if (points.isEmpty()) {
            return new Model3D(List.of(), List.of(), 1.0, false);
        }

        List<Face> faces = List.of();
        boolean truncated = points.size() > MAX_POINTS;
        if (points.size() > MAX_POINTS) {
            points = decimatePointsEvenly(points, MAX_POINTS);
        }

        return new Model3D(List.copyOf(faces), List.copyOf(points), Math.max(1.0, bounds.radius()), truncated);
    }

    private List<Face> decimateEvenly(List<Face> faces, int maxFaces) {
        List<Face> result = new ArrayList<>(maxFaces);
        double stride = faces.size() / (double) maxFaces;
        for (int i = 0; i < maxFaces; i++) {
            result.add(faces.get(Math.min(faces.size() - 1, (int) Math.floor(i * stride))));
        }
        return result;
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
            ModelPreset preset
    ) {
        BitSet active = new BitSet(nx * ny * nz);
        for (int z = 0; z < nz; z++) {
            DicomSlice slice = slices.get(start + z);
            BufferedImage processed = slice.getProcessedImage();
            for (int y = 0; y < ny; y++) {
                int sourceY = sampleCoord(y, step, slice.getHeight());
                for (int x = 0; x < nx; x++) {
                    int sourceX = sampleCoord(x, step, slice.getWidth());
                    if (isActive(slice, processed, sourceX, sourceY, preset)) {
                        active.set(pointIndex(x, y, z, nx, ny));
                    }
                }
            }
        }
        return active;
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

    private BitSet keepLargestComponent(BitSet active, int nx, int ny, int nz) {
        if (active.isEmpty()) {
            return active;
        }

        BitSet visited = new BitSet(nx * ny * nz);
        BitSet largest = new BitSet(nx * ny * nz);
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

            if (component.cardinality() > largest.cardinality()) {
                largest = component;
            }
        }

        return largest;
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

    private AxisBounds buildZBounds(List<DicomSlice> slices, int start, int count, double zCenter) {
        double[] centers = new double[count];
        for (int i = 0; i < count; i++) {
            centers[i] = sliceZ(slices, start + i, start) - zCenter;
        }

        double[] min = new double[count];
        double[] max = new double[count];
        for (int i = 0; i < count; i++) {
            double previousStep = i > 0
                    ? Math.abs(centers[i] - centers[i - 1])
                    : Math.max(0.0001, slices.get(start + i).getSliceThickness());
            double nextStep = i < count - 1
                    ? Math.abs(centers[i + 1] - centers[i])
                    : Math.max(0.0001, slices.get(start + i).getSliceThickness());
            min[i] = centers[i] - previousStep / 2.0;
            max[i] = centers[i] + nextStep / 2.0;
        }
        return new AxisBounds(min, max);
    }

    private List<Face> buildVoxelSurface(
            List<DicomSlice> slices,
            BitSet active,
            int start,
            int nx,
            int ny,
            int nz,
            int step,
            ModelPreset preset,
            AxisBounds xBounds,
            AxisBounds yBounds,
            AxisBounds zBounds,
            Bounds bounds
    ) {
        List<Face> faces = new ArrayList<>();
        for (int z = 0; z < nz; z++) {
            DicomSlice slice = slices.get(start + z);
            for (int y = 0; y < ny; y++) {
                int sourceY = sampleCoord(y, step, slice.getHeight());
                for (int x = 0; x < nx; x++) {
                    if (!isActive(active, x, y, z, nx, ny, nz)) {
                        continue;
                    }

                    int sourceX = sampleCoord(x, step, slice.getWidth());
                    int intensity = modelIntensity(slice, sourceX, sourceY, preset);
                    double x0 = xBounds.min[x];
                    double x1 = xBounds.max[x];
                    double y0 = yBounds.min[y];
                    double y1 = yBounds.max[y];
                    double z0 = zBounds.min[z];
                    double z1 = zBounds.max[z];

                    if (!isActive(active, x - 1, y, z, nx, ny, nz)) {
                        addFace(faces, bounds, intensity, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
                    }
                    if (!isActive(active, x + 1, y, z, nx, ny, nz)) {
                        addFace(faces, bounds, intensity, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
                    }
                    if (!isActive(active, x, y - 1, z, nx, ny, nz)) {
                        addFace(faces, bounds, intensity, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1);
                    }
                    if (!isActive(active, x, y + 1, z, nx, ny, nz)) {
                        addFace(faces, bounds, intensity, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
                    }
                    if (!isActive(active, x, y, z - 1, nx, ny, nz)) {
                        addFace(faces, bounds, intensity, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
                    }
                    if (!isActive(active, x, y, z + 1, nx, ny, nz)) {
                        addFace(faces, bounds, intensity, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
                    }
                }
            }
        }
        return faces;
    }

    private List<ModelPoint> buildSurfacePoints(
            List<DicomSlice> slices,
            BitSet active,
            int start,
            int nx,
            int ny,
            int nz,
            int step,
            ModelPreset preset,
            AxisBounds xBounds,
            AxisBounds yBounds,
            AxisBounds zBounds,
            Bounds bounds
    ) {
        List<ModelPoint> points = new ArrayList<>();
        for (int z = 0; z < nz; z++) {
            DicomSlice slice = slices.get(start + z);
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

    private void addFace(
            List<Face> faces,
            Bounds bounds,
            int intensity,
            double ax, double ay, double az,
            double bx, double by, double bz,
            double cx, double cy, double cz,
            double dx, double dy, double dz
    ) {
        Vertex a = new Vertex(ax, ay, az);
        Vertex b = new Vertex(bx, by, bz);
        Vertex c = new Vertex(cx, cy, cz);
        bounds.add(ax, ay, az);
        bounds.add(bx, by, bz);
        bounds.add(cx, cy, cz);
        bounds.add(dx, dy, dz);
        faces.add(new Face(
                new double[] { ax, bx, cx, dx },
                new double[] { ay, by, cy, dy },
                new double[] { az, bz, cz, dz },
                intensity,
                normalShade(a, b, c)
        ));
    }

    private double normalShade(Vertex a, Vertex b, Vertex c) {
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        double uz = b.z - a.z;
        double vx = c.x - a.x;
        double vy = c.y - a.y;
        double vz = c.z - a.z;
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 0.000001) {
            return 0.8;
        }
        double light = Math.abs((nx * 0.25 + ny * -0.45 + nz * 0.85) / length);
        return 0.52 + light * 0.72;
    }

    private boolean isActive(DicomSlice slice, BufferedImage processed, int x, int y, ModelPreset preset) {
        if (intensityAt(processed, x, y) == 0) {
            return false;
        }
        if (preset == ModelPreset.FILTERED_MASK) {
            return true;
        }
        float hu = slice.getHu(y, x);
        return hu >= preset.minHu() && hu <= preset.maxHu();
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
        if (preset == ModelPreset.BONES || preset == ModelPreset.FILTERED_MASK) {
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

    private int intensityAt(BufferedImage image, int x, int y) {
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

    public record Model3D(List<Face> faces, List<ModelPoint> points, double radius, boolean truncated) {
    }

    public record Face(double[] x, double[] y, double[] z, int intensity, double shade) {
    }

    public record ModelPoint(double x, double y, double z, int intensity, int r, int g, int b) {
    }

    public enum ModelPreset {
        BONES("Кости (HU 250..3000)", 250, 3000),
        SOFT_TISSUE("Мягкие ткани (HU -150..250)", -150, 250),
        BODY("Тело целиком (HU -500..3000)", -500, 3000),
        FILTERED_MASK("Текущая маска фильтра", Integer.MIN_VALUE, Integer.MAX_VALUE);

        private final String label;
        private final int minHu;
        private final int maxHu;

        ModelPreset(String label, int minHu, int maxHu) {
            this.label = label;
            this.minHu = minHu;
            this.maxHu = maxHu;
        }

        public int minHu() {
            return minHu;
        }

        public int maxHu() {
            return maxHu;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record AxisBounds(double[] min, double[] max) {
    }

    private record Vertex(double x, double y, double z) {
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
