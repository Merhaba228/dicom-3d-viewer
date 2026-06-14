package com.shibaykin.dicom3d.ui;

import com.shibaykin.dicom3d.service.Model3DService.Model3D;
import com.shibaykin.dicom3d.service.Model3DService.ModelPoint;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Locale;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

public final class ModelViewerFrame extends JFrame {
    private static final Color VIEW_BACKGROUND = new Color(17, 24, 27);
    private static final Color STATUS_BACKGROUND = new Color(24, 33, 36);

    public ModelViewerFrame(Model3D model) {
        super("Трехмерная визуализация: облако точек");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(980, 760);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        ModelCanvas canvas = new ModelCanvas(model);
        JLabel status = new JLabel(String.format(
                Locale.US,
                "Точек: %,d%s",
                model.points().size(),
                model.truncated() ? " (ограничено для скорости)" : ""
        ));
        status.setBorder(new EmptyBorder(8, 12, 8, 12));
        status.setForeground(new Color(230, 236, 245));
        status.setBackground(STATUS_BACKGROUND);
        status.setOpaque(true);

        add(canvas, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    private static final class ModelCanvas extends JPanel {
        private final Model3D model;
        private final double[] pointX;
        private final double[] pointY;
        private final double[] pointZ;
        private final int[] pointColors;
        private double angleX = Math.toRadians(-18);
        private double angleY = Math.toRadians(34);
        private double zoom = 1.0;
        private Point lastMouse;
        private BufferedImage frameBuffer;
        private float[] depthBuffer;

        private ModelCanvas(Model3D model) {
            this.model = model;
            int pointCount = model.points().size();
            pointX = new double[pointCount];
            pointY = new double[pointCount];
            pointZ = new double[pointCount];
            pointColors = new int[pointCount];
            for (int i = 0; i < pointCount; i++) {
                ModelPoint point = model.points().get(i);
                pointX[i] = point.x();
                pointY[i] = point.y();
                pointZ[i] = point.z();
                pointColors[i] = point.r() << 16 | point.g() << 8 | point.b();
            }
            setBackground(VIEW_BACKGROUND);

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    lastMouse = event.getPoint();
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    if (lastMouse == null) {
                        lastMouse = event.getPoint();
                        return;
                    }
                    angleY += (event.getX() - lastMouse.x) * 0.01;
                    angleX += (event.getY() - lastMouse.y) * 0.01;
                    lastMouse = event.getPoint();
                    repaint();
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent event) {
                    zoom *= event.getWheelRotation() < 0 ? 1.1 : 0.9;
                    zoom = Math.max(0.25, Math.min(5.0, zoom));
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (model.points().isEmpty()) {
                g2.setColor(new Color(230, 236, 245));
                g2.drawString("Нет данных для облака точек. Настройте фильтры так, чтобы маска содержала ненулевые пиксели.", 24, 32);
                g2.dispose();
                return;
            }

            double scale = Math.min(getWidth(), getHeight()) * 0.42 * zoom / model.radius();
            drawPoints(g2, scale, getWidth(), getHeight());
            g2.dispose();
        }

        private void drawPoints(Graphics2D g2, double scale, int width, int height) {
            ensureBuffers(width, height);
            int[] pixels = ((DataBufferInt) frameBuffer.getRaster().getDataBuffer()).getData();
            Arrays.fill(pixels, VIEW_BACKGROUND.getRGB());
            Arrays.fill(depthBuffer, Float.NEGATIVE_INFINITY);

            double sinX = Math.sin(angleX);
            double cosX = Math.cos(angleX);
            double sinY = Math.sin(angleY);
            double cosY = Math.cos(angleY);
            double radius = Math.max(1.0, model.radius());
            double centerX = width / 2.0;
            double centerY = height / 2.0;

            for (int i = 0; i < pointX.length; i++) {
                double rx = pointX[i] * cosY + pointZ[i] * sinY;
                double rz = -pointX[i] * sinY + pointZ[i] * cosY;
                double ry = pointY[i] * cosX - rz * sinX;
                double depth = pointY[i] * sinX + rz * cosX;

                int px = (int) Math.round(centerX + rx * scale);
                int py = (int) Math.round(centerY - ry * scale);
                if (px < -2 || px > width + 2 || py < -2 || py > height + 2) {
                    continue;
                }

                double normalizedDepth = depth / radius;
                double shade = Math.max(0.48, Math.min(1.22, 0.82 + normalizedDepth * 0.16));
                int size = normalizedDepth > 0.35 ? 3 : 2;
                drawPoint(pixels, width, height, px, py, (float) depth, shadeColor(pointColors[i], shade), size);
            }
            g2.drawImage(frameBuffer, 0, 0, null);
        }

        private void ensureBuffers(int width, int height) {
            if (frameBuffer == null || frameBuffer.getWidth() != width || frameBuffer.getHeight() != height) {
                frameBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                depthBuffer = new float[width * height];
            }
        }

        private void drawPoint(
                int[] pixels,
                int width,
                int height,
                int centerX,
                int centerY,
                float depth,
                int color,
                int size
        ) {
            int minOffset = -size / 2;
            int maxOffset = (size - 1) / 2;
            for (int dy = minOffset; dy <= maxOffset; dy++) {
                int y = centerY + dy;
                if (y < 0 || y >= height) {
                    continue;
                }
                for (int dx = minOffset; dx <= maxOffset; dx++) {
                    int x = centerX + dx;
                    if (x < 0 || x >= width) {
                        continue;
                    }
                    int index = y * width + x;
                    if (depth >= depthBuffer[index]) {
                        depthBuffer[index] = depth;
                        pixels[index] = color;
                    }
                }
            }
        }

        private int shadeColor(int color, double shade) {
            int red = clampColor(((color >> 16) & 0xFF) * shade);
            int green = clampColor(((color >> 8) & 0xFF) * shade);
            int blue = clampColor((color & 0xFF) * shade);
            return red << 16 | green << 8 | blue;
        }

        private int clampColor(double value) {
            return Math.max(0, Math.min(255, (int) Math.round(value)));
        }
    }
}
