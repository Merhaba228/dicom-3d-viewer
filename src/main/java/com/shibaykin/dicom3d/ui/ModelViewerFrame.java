package com.shibaykin.dicom3d.ui;

import com.shibaykin.dicom3d.service.Model3DService.Face;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

public final class ModelViewerFrame extends JFrame {
    public ModelViewerFrame(Model3D model) {
        super("Просмотр 3D-модели");
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
        status.setBackground(new Color(20, 27, 39));
        status.setOpaque(true);

        add(canvas, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    private static final class ModelCanvas extends JPanel {
        private final Model3D model;
        private double angleX = Math.toRadians(-18);
        private double angleY = Math.toRadians(34);
        private double zoom = 1.0;
        private Point lastMouse;

        private ModelCanvas(Model3D model) {
            this.model = model;
            setBackground(new Color(15, 22, 33));

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

            if (model.points().isEmpty() && model.faces().isEmpty()) {
                g2.setColor(new Color(230, 236, 245));
                g2.drawString("Нет данных для 3D. Настройте фильтры так, чтобы маска содержала ненулевые пиксели.", 24, 32);
                g2.dispose();
                return;
            }

            double scale = Math.min(getWidth(), getHeight()) * 0.42 * zoom / model.radius();
            if (!model.points().isEmpty()) {
                drawPoints(g2, scale);
                g2.dispose();
                return;
            }

            List<DrawableFace> faces = new ArrayList<>(model.faces().size());
            for (Face face : model.faces()) {
                faces.add(project(face, scale));
            }
            faces.sort(Comparator.comparingDouble(DrawableFace::depth));

            for (DrawableFace face : faces) {
                g2.setColor(face.color());
                g2.fillPolygon(face.x(), face.y(), 4);
            }

            g2.dispose();
        }

        private void drawPoints(Graphics2D g2, double scale) {
            List<DrawablePoint> points = new ArrayList<>(model.points().size());
            for (ModelPoint point : model.points()) {
                points.add(project(point, scale));
            }
            points.sort(Comparator.comparingDouble(DrawablePoint::depth));

            for (DrawablePoint point : points) {
                if (point.x() < -2 || point.x() > getWidth() + 2 || point.y() < -2 || point.y() > getHeight() + 2) {
                    continue;
                }
                g2.setColor(point.color());
                g2.fillRect(point.x(), point.y(), 2, 2);
            }
        }

        private DrawablePoint project(ModelPoint point, double scale) {
            double sinX = Math.sin(angleX);
            double cosX = Math.cos(angleX);
            double sinY = Math.sin(angleY);
            double cosY = Math.cos(angleY);

            double rx = point.x() * cosY + point.z() * sinY;
            double rz = -point.x() * sinY + point.z() * cosY;
            double ry = point.y() * cosX - rz * sinX;
            double rz2 = point.y() * sinX + rz * cosX;

            int px = (int) Math.round(getWidth() / 2.0 + rx * scale);
            int py = (int) Math.round(getHeight() / 2.0 - ry * scale);
            double shade = Math.max(0.55, Math.min(1.18, 0.86 + rz2 / (model.radius() * 5.5)));
            Color color = new Color(
                    clampColor(point.r() * shade),
                    clampColor(point.g() * shade),
                    clampColor(point.b() * shade)
            );
            return new DrawablePoint(px, py, rz2, color);
        }

        private DrawableFace project(Face face, double scale) {
            int[] px = new int[4];
            int[] py = new int[4];
            double depth = 0.0;

            double sinX = Math.sin(angleX);
            double cosX = Math.cos(angleX);
            double sinY = Math.sin(angleY);
            double cosY = Math.cos(angleY);

            for (int i = 0; i < 4; i++) {
                double x = face.x()[i];
                double y = face.y()[i];
                double z = face.z()[i];

                double rx = x * cosY + z * sinY;
                double rz = -x * sinY + z * cosY;
                double ry = y * cosX - rz * sinX;
                double rz2 = y * sinX + rz * cosX;

                px[i] = (int) Math.round(getWidth() / 2.0 + rx * scale);
                py[i] = (int) Math.round(getHeight() / 2.0 - ry * scale);
                depth += rz2;
            }

            int value = Math.max(40, Math.min(245, (int) Math.round(face.intensity() * face.shade())));
            Color color = new Color(value, value, value);
            return new DrawableFace(px, py, depth / 4.0, color);
        }

        private int clampColor(double value) {
            return Math.max(0, Math.min(255, (int) Math.round(value)));
        }
    }

    private record DrawableFace(int[] x, int[] y, double depth, Color color) {
    }

    private record DrawablePoint(int x, int y, double depth, Color color) {
    }
}
