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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        private double angleX = Math.toRadians(-18);
        private double angleY = Math.toRadians(34);
        private double zoom = 1.0;
        private Point lastMouse;

        private ModelCanvas(Model3D model) {
            this.model = model;
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
            drawPoints(g2, scale);
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
                int size = point.size();
                g2.fillOval(point.x() - size / 2, point.y() - size / 2, size, size);
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
            double depth = rz2 / Math.max(1.0, model.radius());
            double shade = Math.max(0.48, Math.min(1.22, 0.82 + depth * 0.16));
            int size = depth > 0.35 ? 3 : 2;
            Color color = new Color(
                    clampColor(point.r() * shade),
                    clampColor(point.g() * shade),
                    clampColor(point.b() * shade)
            );
            return new DrawablePoint(px, py, rz2, color, size);
        }

        private int clampColor(double value) {
            return Math.max(0, Math.min(255, (int) Math.round(value)));
        }
    }

    private record DrawablePoint(int x, int y, double depth, Color color, int size) {
    }
}
