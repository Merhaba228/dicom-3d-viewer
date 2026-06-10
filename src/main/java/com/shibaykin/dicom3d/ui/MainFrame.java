package com.shibaykin.dicom3d.ui;

import com.shibaykin.dicom3d.model.DicomSlice;
import com.shibaykin.dicom3d.service.DicomImportService;
import com.shibaykin.dicom3d.service.ImageProcessingService;
import com.shibaykin.dicom3d.service.Model3DService;
import com.shibaykin.dicom3d.service.Model3DService.Model3D;
import com.shibaykin.dicom3d.service.Model3DService.ModelPreset;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class MainFrame extends JFrame {
    private static final int WINDOW_WIDTH = 1500;
    private static final int WINDOW_HEIGHT = 920;

    private static final Color BG_APP = new Color(234, 239, 238);
    private static final Color BG_PANEL = new Color(247, 249, 248);
    private static final Color BG_CANVAS = new Color(250, 251, 250);
    private static final Color ACCENT = new Color(42, 116, 105);
    private static final Color ACCENT_SOFT = new Color(222, 238, 233);

    private static final Color TEXT_PRIMARY = new Color(31, 43, 42);
    private static final Color TEXT_SECONDARY = new Color(78, 96, 93);
    private static final Color PANEL_BORDER = new Color(184, 201, 197);
    private static final Color BUTTON_BG = new Color(211, 231, 225);
    private static final Color BUTTON_BORDER = new Color(113, 161, 151);

    private final DicomImportService importService = new DicomImportService();
    private final ImageProcessingService processingService = new ImageProcessingService();
    private final Model3DService model3DService = new Model3DService();

    private final List<DicomSlice> slices = new ArrayList<>();
    private final List<Point> cropPoints = new ArrayList<>();

    private final JList<DicomSlice> thumbnailList = new JList<>();
    private final ImageCanvas originalCanvas = new ImageCanvas("Исходный срез");
    private final ImageCanvas filteredCanvas = new ImageCanvas("После обработки");

    private final JSlider thresholdSlider = slider(0, 255, 0);
    private final JSlider bandMinSlider = slider(-1000, 3000, 400);
    private final JSlider bandMaxSlider = slider(-1000, 3000, 3000);
    private final JSlider blurSlider = slider(0, 8, 0);
    private final JLabel thresholdValueLabel = new JLabel();
    private final JLabel bandMinValueLabel = new JLabel();
    private final JLabel bandMaxValueLabel = new JLabel();
    private final JLabel blurValueLabel = new JLabel();

    private final JSlider rangeStartSlider = slider(0, 0, 0);
    private final JSlider rangeEndSlider = slider(0, 0, 0);
    private final JLabel rangeStartValueLabel = new JLabel("Начальный срез: -");
    private final JLabel rangeEndValueLabel = new JLabel("Конечный срез: -");

    private final JSlider modelStepSlider = slider(1, 8, 3);
    private final JLabel modelStepValueLabel = new JLabel();
    private final JComboBox<ModelPreset> modelPresetCombo = new JComboBox<>(ModelPreset.values());
    private final JButton openModelButton = button("Построить облако точек", this::openModel3DAsync);

    private final JToggleButton maskModeToggle = new JToggleButton("Режим кисти");
    private final JToggleButton pointCropModeToggle = new JToggleButton("Обрезка по точкам");
    private final JSlider brushSizeSlider = slider(2, 40, 10);
    private final JLabel brushSizeValueLabel = new JLabel();

    private final JButton resetMaskButton = button("Сбросить текущую маску", this::recomputeCurrentSlice);
    private final JButton resetAllFiltersButton = button("Сбросить все фильтры", this::resetAllFilters);
    private final JButton clearCropPointsButton = button("Очистить точки", this::clearCropPoints);
    private final JButton applyPointCropButton = button("Оставить внутри контура", this::applyCropByPoints);
    private final JButton cutInsideByPointsButton = button("Вырезать внутри контура", this::cutInsideByPoints);
    private final JButton applyRangeButton = button("Применить к диапазону", this::applyFiltersToRangeAsync);

    private final JSlider sliceSlider = slider(0, 0, 0);
    private final JLabel sliceValueLabel = new JLabel("Срез: -");
    private final JLabel statusLabel = new JLabel("Загрузите DICOM-срезы для начала работы");

    private boolean filterInProgress;
    private boolean ignoreSliceSliderChange;
    private boolean ignoreListSelectionChange;
    private boolean ignoreRangeSliderChange;
    private boolean ignoreModelPresetChange;

    public MainFrame() {
        super("DICOM-просмотрщик - фильтры, маска и трехмерная визуализация");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(BG_APP);

        setJMenuBar(buildMenu());
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildSettingsScrollPane(), BorderLayout.WEST);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildThumbnailsPanel(), BorderLayout.EAST);
        add(buildStatusPanel(), BorderLayout.SOUTH);

        applyPalette();
        configureInteractions();
        refreshFilterLabels();
        refreshRangeLabels();
        refreshMaskLabels();
        refreshModelLabels();
        updateSliceSliderBounds();
        updateRangeSliderBounds();
    }

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu fileMenu = new JMenu("Файл");
        fileMenu.add(menuItem("Импорт папки DICOM", () -> importDicom(true)));
        fileMenu.add(menuItem("Импорт файлов DICOM", () -> importDicom(false)));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("Выход", this::dispose));
        bar.add(fileMenu);
        return bar;
    }

    private JToolBar buildToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(new EmptyBorder(6, 8, 6, 8));
        toolbar.setBackground(BG_PANEL);

        toolbar.add(button("Импорт папки", () -> importDicom(true)));
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(button("Импорт файлов", () -> importDicom(false)));
        toolbar.addSeparator();

        JLabel sliderLabel = new JLabel("Срез: ");
        sliderLabel.setForeground(TEXT_PRIMARY);
        sliderLabel.setBorder(new EmptyBorder(0, 8, 0, 0));
        toolbar.add(sliderLabel);

        sliceSlider.setPreferredSize(new Dimension(320, 24));
        toolbar.add(sliceSlider);
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(sliceValueLabel);

        return toolbar;
    }

    private JScrollPane buildSettingsScrollPane() {
        JScrollPane scrollPane = new JScrollPane(
                buildSettingsPanel(),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );
        scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        scrollPane.setPreferredSize(new Dimension(380, WINDOW_HEIGHT));
        return scrollPane;
    }

    private JPanel buildSettingsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        panel.setBackground(BG_PANEL);

        JLabel title = new JLabel("Настройки обработки");
        title.setForeground(TEXT_PRIMARY);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        panel.add(title);
        panel.add(Box.createVerticalStrut(10));

        JPanel filtersBlock = sectionPanel("Фильтры");
        modelPresetCombo.setSelectedItem(ModelPreset.BONES);
        modelPresetCombo.setAlignmentX(LEFT_ALIGNMENT);
        modelPresetCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, modelPresetCombo.getPreferredSize().height));
        addSliderRow(filtersBlock, "Порог яркости (0..255)", thresholdSlider, thresholdValueLabel);
        addSliderRow(filtersBlock, "Мин. HU", bandMinSlider, bandMinValueLabel);
        addSliderRow(filtersBlock, "Макс. HU", bandMaxSlider, bandMaxValueLabel);
        JLabel modelPresetLabel = new JLabel("Готовый диапазон HU");
        modelPresetLabel.setForeground(TEXT_PRIMARY);
        modelPresetLabel.setAlignmentX(LEFT_ALIGNMENT);
        filtersBlock.add(modelPresetLabel);
        filtersBlock.add(modelPresetCombo);
        filtersBlock.add(Box.createVerticalStrut(8));
        addSliderRow(filtersBlock, "Размытие (0..8)", blurSlider, blurValueLabel);
        filtersBlock.add(resetAllFiltersButton);
        panel.add(filtersBlock);
        panel.add(Box.createVerticalStrut(10));

        JPanel rangeBlock = sectionPanel("Диапазон срезов");
        addSliderRow(rangeBlock, "От", rangeStartSlider, rangeStartValueLabel);
        addSliderRow(rangeBlock, "До", rangeEndSlider, rangeEndValueLabel);
        rangeBlock.add(applyRangeButton);
        panel.add(rangeBlock);
        panel.add(Box.createVerticalStrut(10));

        JPanel modelBlock = sectionPanel("Трехмерная визуализация");
        addSliderRow(modelBlock, "Шаг выборки точек", modelStepSlider, modelStepValueLabel);
        modelBlock.add(openModelButton);
        panel.add(modelBlock);
        panel.add(Box.createVerticalStrut(10));

        JPanel maskBlock = sectionPanel("Ручная маска / обрезка");
        styleToggle(maskModeToggle);
        styleToggle(pointCropModeToggle);
        maskBlock.add(maskModeToggle);
        maskBlock.add(Box.createVerticalStrut(8));
        addSliderRow(maskBlock, "Размер кисти", brushSizeSlider, brushSizeValueLabel);
        maskBlock.add(pointCropModeToggle);
        maskBlock.add(Box.createVerticalStrut(6));
        maskBlock.add(clearCropPointsButton);
        maskBlock.add(Box.createVerticalStrut(6));
        maskBlock.add(applyPointCropButton);
        maskBlock.add(Box.createVerticalStrut(6));
        maskBlock.add(cutInsideByPointsButton);
        maskBlock.add(Box.createVerticalStrut(6));
        maskBlock.add(resetMaskButton);
        panel.add(maskBlock);

        panel.add(Box.createVerticalStrut(80));
        Dimension preferred = panel.getPreferredSize();
        panel.setPreferredSize(new Dimension(360, preferred.height));
        return panel;
    }

    private Component buildCenterPanel() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, originalCanvas, filteredCanvas);
        splitPane.setResizeWeight(0.5);
        splitPane.setBorder(new EmptyBorder(8, 8, 8, 8));
        splitPane.setBackground(BG_APP);
        return splitPane;
    }

    private Component buildThumbnailsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(260, WINDOW_HEIGHT));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.setBackground(BG_PANEL);

        JLabel title = new JLabel("Срезы", SwingConstants.CENTER);
        title.setForeground(TEXT_PRIMARY);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setBorder(new EmptyBorder(0, 0, 6, 0));
        panel.add(title, BorderLayout.NORTH);

        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        thumbnailList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = (JLabel) renderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DicomSlice) {
                DicomSlice slice = (DicomSlice) value;
                label.setText(String.format(Locale.US, "%03d | %s", index + 1, slice.shortName()));
                label.setIcon(createThumbIcon(slice.getProcessedImage(), 82));
                label.setHorizontalTextPosition(SwingConstants.CENTER);
                label.setVerticalTextPosition(SwingConstants.BOTTOM);
                label.setBorder(new EmptyBorder(4, 4, 6, 4));
            }
            return label;
        });
        thumbnailList.setFixedCellHeight(118);
        thumbnailList.setFixedCellWidth(214);
        thumbnailList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        panel.add(new JScrollPane(thumbnailList), BorderLayout.CENTER);
        return panel;
    }

    private Component buildStatusPanel() {
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(new EmptyBorder(6, 10, 6, 10));
        statusPanel.setBackground(BG_PANEL);
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        return statusPanel;
    }

    private void applyPalette() {
        for (JLabel label : new JLabel[] {
                thresholdValueLabel,
                bandMinValueLabel,
                bandMaxValueLabel,
                blurValueLabel,
                rangeStartValueLabel,
                rangeEndValueLabel,
                modelStepValueLabel,
                brushSizeValueLabel,
                sliceValueLabel,
                statusLabel
        }) {
            label.setForeground(TEXT_SECONDARY);
        }
        statusLabel.setForeground(TEXT_PRIMARY);

        for (JSlider slider : new JSlider[] {
                thresholdSlider,
                bandMinSlider,
                bandMaxSlider,
                blurSlider,
                rangeStartSlider,
                rangeEndSlider,
                modelStepSlider,
                brushSizeSlider,
                sliceSlider
        }) {
            slider.setBackground(BG_PANEL);
            slider.setForeground(ACCENT);
        }

        thumbnailList.setBackground(new Color(239, 244, 242));
        thumbnailList.setForeground(TEXT_PRIMARY);
        thumbnailList.setSelectionBackground(new Color(202, 226, 219));
        thumbnailList.setSelectionForeground(TEXT_PRIMARY);
        modelPresetCombo.setBackground(Color.WHITE);
        modelPresetCombo.setForeground(TEXT_PRIMARY);

        JMenuBar bar = getJMenuBar();
        if (bar != null) {
            bar.setBackground(BG_PANEL);
            bar.setForeground(TEXT_PRIMARY);
            for (int i = 0; i < bar.getMenuCount(); i++) {
                JMenu menu = bar.getMenu(i);
                if (menu == null) {
                    continue;
                }
                menu.setForeground(TEXT_PRIMARY);
                for (int j = 0; j < menu.getItemCount(); j++) {
                    JMenuItem item = menu.getItem(j);
                    if (item != null) {
                        item.setForeground(TEXT_PRIMARY);
                        item.setBackground(BG_PANEL);
                    }
                }
            }
        }
    }

    private void configureInteractions() {
        for (JSlider slider : new JSlider[] { thresholdSlider, bandMinSlider, bandMaxSlider, blurSlider }) {
            slider.addChangeListener(this::onFilterSliderChanged);
        }

        rangeStartSlider.addChangeListener(this::onRangeSliderChanged);
        rangeEndSlider.addChangeListener(this::onRangeSliderChanged);
        brushSizeSlider.addChangeListener(event -> refreshMaskLabels());
        modelStepSlider.addChangeListener(event -> refreshModelLabels());
        modelPresetCombo.addActionListener(event -> applySelectedModelPreset());

        pointCropModeToggle.addActionListener(event -> {
            if (!pointCropModeToggle.isSelected()) {
                clearCropPoints();
            }
        });

        sliceSlider.addChangeListener(event -> {
            if (!ignoreSliceSliderChange && !slices.isEmpty()) {
                setSelectedIndex(sliceSlider.getValue());
            }
        });

        thumbnailList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !ignoreListSelectionChange) {
                setSelectedIndex(thumbnailList.getSelectedIndex());
            }
        });

        MouseAdapter maskMouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (pointCropModeToggle.isSelected()) {
                    addCropPoint(event.getPoint());
                } else {
                    applyBrushMask(event.getPoint());
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (!pointCropModeToggle.isSelected()) {
                    applyBrushMask(event.getPoint());
                }
            }
        };
        filteredCanvas.addMouseListener(maskMouse);
        filteredCanvas.addMouseMotionListener(maskMouse);
    }

    private void onFilterSliderChanged(ChangeEvent event) {
        enforceBandOrder(event);
        if (!ignoreModelPresetChange
                && (event.getSource() == bandMinSlider || event.getSource() == bandMaxSlider)) {
            ignoreModelPresetChange = true;
            modelPresetCombo.setSelectedItem(ModelPreset.FILTERED_MASK);
            ignoreModelPresetChange = false;
        }
        refreshFilterLabels();
        updateSelectedPreview();
    }

    private void applySelectedModelPreset() {
        if (ignoreModelPresetChange) {
            return;
        }
        ModelPreset preset = (ModelPreset) modelPresetCombo.getSelectedItem();
        if (preset == null || preset == ModelPreset.FILTERED_MASK) {
            return;
        }

        ignoreModelPresetChange = true;
        bandMinSlider.setValue(recommendedMinHu(preset));
        bandMaxSlider.setValue(preset.maxHu());
        modelStepSlider.setValue(preset.recommendedStep());
        ignoreModelPresetChange = false;
        refreshFilterLabels();
        refreshModelLabels();
        updateSelectedPreview();
    }

    private int recommendedMinHu(ModelPreset preset) {
        if (preset != ModelPreset.BONES || slices.isEmpty()) {
            return preset.minHu();
        }
        float maxHu = Float.NEGATIVE_INFINITY;
        for (DicomSlice slice : slices) {
            for (float hu : slice.getHuPixels()) {
                maxHu = Math.max(maxHu, hu);
            }
        }
        return maxHu < 1000 ? 200 : preset.minHu();
    }

    private void onRangeSliderChanged(ChangeEvent event) {
        if (ignoreRangeSliderChange) {
            return;
        }
        ensureOrderedPair(rangeStartSlider, rangeEndSlider, event);
        refreshRangeLabels();
    }

    private void enforceBandOrder(ChangeEvent event) {
        ensureOrderedPair(bandMinSlider, bandMaxSlider, event);
    }

    private void ensureOrderedPair(JSlider left, JSlider right, ChangeEvent event) {
        if (left.getValue() <= right.getValue()) {
            return;
        }
        if (event.getSource() == left) {
            right.setValue(left.getValue());
        } else {
            left.setValue(right.getValue());
        }
    }

    private void refreshFilterLabels() {
        FilterConfig config = currentFilterConfig();
        thresholdValueLabel.setText("Текущее значение: " + config.threshold());
        bandMinValueLabel.setText("Текущее значение: " + config.bandMin() + " HU");
        bandMaxValueLabel.setText("Текущее значение: " + config.bandMax() + " HU");
        blurValueLabel.setText("Текущее значение: " + config.blur());
    }

    private void refreshRangeLabels() {
        if (slices.isEmpty()) {
            rangeStartValueLabel.setText("Начальный срез: -");
            rangeEndValueLabel.setText("Конечный срез: -");
            return;
        }
        rangeStartValueLabel.setText(String.format(Locale.US, "Начальный срез: %d", rangeStartSlider.getValue() + 1));
        rangeEndValueLabel.setText(String.format(Locale.US, "Конечный срез: %d", rangeEndSlider.getValue() + 1));
    }

    private void refreshMaskLabels() {
        brushSizeValueLabel.setText("Текущее значение: " + brushSizeSlider.getValue());
    }

    private void refreshModelLabels() {
        modelStepValueLabel.setText("Текущее значение: " + modelStepSlider.getValue());
    }

    private void importDicom(boolean directoryMode) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(directoryMode ? "Выберите папку DICOM" : "Выберите файлы DICOM");
        chooser.setFileSelectionMode(directoryMode ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(!directoryMode);
        if (!directoryMode) {
            chooser.setFileFilter(new FileNameExtensionFilter("Файлы DICOM (*.dcm)", "dcm"));
        }

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        if (directoryMode) {
            Path directory = chooser.getSelectedFile().toPath();
            loadSlicesAsync(() -> importService.loadFromDirectory(directory), "Импорт папки: " + directory);
            return;
        }

        List<Path> paths = new ArrayList<>();
        for (var file : chooser.getSelectedFiles()) {
            paths.add(file.toPath());
        }
        loadSlicesAsync(() -> importService.loadFromPaths(paths), "Импорт выбранных файлов");
    }

    private void loadSlicesAsync(LoaderTask loaderTask, String loadingText) {
        statusLabel.setText(loadingText);

        new SwingWorker<List<DicomSlice>, Void>() {
            @Override
            protected List<DicomSlice> doInBackground() throws Exception {
                return loaderTask.load();
            }

            @Override
            protected void done() {
                try {
                    slices.clear();
                    slices.addAll(get());
                    for (DicomSlice slice : slices) {
                        slice.setProcessedImage(slice.getOriginalImage());
                    }

                    repopulateThumbnails();
                    updateSliceSliderBounds();
                    updateRangeSliderBounds();

                    if (slices.isEmpty()) {
                        originalCanvas.setImage(null);
                        filteredCanvas.setImage(null);
                    } else {
                        setSelectedIndex(0);
                        applySelectedModelPreset();
                    }

                    statusLabel.setText(String.format(
                            Locale.US,
                            "Загружено срезов: %d. Сортировка: координата Z -> номер среза.",
                            slices.size()
                    ));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    showError("Импорт прерван.");
                } catch (ExecutionException executionException) {
                    showError("Ошибка импорта: " + executionException.getCause().getMessage());
                }
            }
        }.execute();
    }

    private void updateSelectedPreview() {
        if (slices.isEmpty() || filterInProgress) {
            return;
        }
        DicomSlice slice = selectedSlice();
        if (slice == null) {
            return;
        }

        BufferedImage result = runFilters(slice, currentFilterConfig());
        slice.setProcessedImage(result);
        filteredCanvas.setImage(result);
        thumbnailList.repaint();
    }

    private void applyFiltersToRangeAsync() {
        if (slices.isEmpty()) {
            return;
        }
        if (filterInProgress) {
            statusLabel.setText("Пакетная обработка уже выполняется...");
            return;
        }

        FilterConfig config = currentFilterConfig();
        SliceRange range = currentRange();

        filterInProgress = true;
        setFilterControlsEnabled(false);
        statusLabel.setText(String.format(
                Locale.US,
                "Обработка диапазона [%d..%d] (%d срезов)...",
                range.start() + 1,
                range.end() + 1,
                range.total()
        ));

        new SwingWorker<Void, Integer>() {
            @Override
            protected Void doInBackground() {
                for (int i = range.start(); i <= range.end(); i++) {
                    slices.get(i).setProcessedImage(runFilters(slices.get(i), config));
                    int done = i - range.start() + 1;
                    if (done == range.total() || done % 5 == 0) {
                        publish(done);
                    }
                }
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                int done = chunks.get(chunks.size() - 1);
                statusLabel.setText(String.format(
                        Locale.US,
                        "Обработка диапазона [%d..%d]: %d/%d...",
                        range.start() + 1,
                        range.end() + 1,
                        done,
                        range.total()
                ));
            }

            @Override
            protected void done() {
                try {
                    get();
                    int current = selectedIndex();
                    repopulateThumbnails();
                    setSelectedIndex(current >= 0 ? current : 0);

                    statusLabel.setText(String.format(
                            Locale.US,
                            "Готово: порог=%d, HU=[%d..%d], размытие=%d, диапазон=[%d..%d].",
                            config.threshold(),
                            config.bandMin(),
                            config.bandMax(),
                            config.blur(),
                            range.start() + 1,
                            range.end() + 1
                    ));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    showError("Обработка прервана.");
                } catch (ExecutionException executionException) {
                    showError("Ошибка обработки: " + executionException.getCause().getMessage());
                } finally {
                    filterInProgress = false;
                    setFilterControlsEnabled(true);
                }
            }
        }.execute();
    }

    private void openModel3DAsync() {
        if (slices.isEmpty()) {
            statusLabel.setText("Перед построением облака точек загрузите DICOM-срезы.");
            return;
        }
        if (filterInProgress) {
            statusLabel.setText("Обработка уже выполняется...");
            return;
        }

        FilterConfig config = currentFilterConfig();
        SliceRange range = currentRange();
        int step = modelStepSlider.getValue();
        ModelPreset preset = (ModelPreset) modelPresetCombo.getSelectedItem();

        filterInProgress = true;
        setFilterControlsEnabled(false);
        statusLabel.setText(String.format(
                Locale.US,
                "Построение облака точек по срезам [%d..%d]...",
                range.start() + 1,
                range.end() + 1
        ));

        new SwingWorker<Model3D, Void>() {
            @Override
            protected Model3D doInBackground() {
                for (int i = range.start(); i <= range.end(); i++) {
                    slices.get(i).setProcessedImage(runFilters(slices.get(i), config));
                }
                return model3DService.buildSurfaceModel(slices, range.start(), range.end(), step, preset);
            }

            @Override
            protected void done() {
                try {
                    Model3D model = get();
                    repopulateThumbnails();
                    setSelectedIndex(Math.max(0, selectedIndex()));

                    ModelViewerFrame viewer = new ModelViewerFrame(model);
                    viewer.setVisible(true);

                    statusLabel.setText(String.format(
                            Locale.US,
                            "Трехмерная визуализация построена: %,d точек, срезы [%d..%d], шаг=%d%s.",
                            model.points().size(),
                            range.start() + 1,
                            range.end() + 1,
                            step,
                            model.truncated() ? ", ограничено для скорости" : ""
                    ));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    showError("Построение облака точек прервано.");
                } catch (ExecutionException executionException) {
                    showError("Ошибка построения облака точек: " + executionException.getCause().getMessage());
                } finally {
                    filterInProgress = false;
                    setFilterControlsEnabled(true);
                }
            }
        }.execute();
    }

    private BufferedImage runFilters(DicomSlice slice, FilterConfig config) {
        BufferedImage result = processingService.applyFilters(
                slice,
                config.threshold(),
                config.bandMin(),
                config.bandMax(),
                config.blur()
        );
        applyManualMask(result, slice.getManualMask());
        return result;
    }

    private void applyManualMask(BufferedImage image, BufferedImage manualMask) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (manualMask.getRaster().getSample(x, y, 0) == 0) {
                    image.getRaster().setSample(x, y, 0, 0);
                }
            }
        }
    }

    private FilterConfig currentFilterConfig() {
        int threshold = thresholdSlider.getValue();
        int bandMin = bandMinSlider.getValue();
        int bandMax = bandMaxSlider.getValue();
        int blur = blurSlider.getValue();

        return new FilterConfig(
                threshold,
                bandMin,
                bandMax,
                blur
        );
    }

    private SliceRange currentRange() {
        int start = Math.min(rangeStartSlider.getValue(), rangeEndSlider.getValue());
        int end = Math.max(rangeStartSlider.getValue(), rangeEndSlider.getValue());
        return new SliceRange(start, end);
    }

    private int selectedIndex() {
        return thumbnailList.getSelectedIndex();
    }

    private DicomSlice selectedSlice() {
        int index = selectedIndex();
        return index >= 0 && index < slices.size() ? slices.get(index) : null;
    }

    private void recomputeCurrentSlice() {
        DicomSlice slice = selectedSlice();
        if (slice != null) {
            slice.resetManualMask();
        }
        updateSelectedPreview();
        clearCropPoints();
        statusLabel.setText("Текущая маска сброшена.");
    }

    private void addCropPoint(Point panelPoint) {
        DicomSlice slice = selectedSlice();
        if (slice == null || slice.getProcessedImage() == null) {
            return;
        }

        Point imagePoint = filteredCanvas.panelToImage(panelPoint);
        if (imagePoint == null) {
            return;
        }

        cropPoints.add(imagePoint);
        filteredCanvas.setOverlayPoints(cropPoints);
        statusLabel.setText("Точек контура: " + cropPoints.size() + " (нужно минимум 3)");
    }

    private void clearCropPoints() {
        cropPoints.clear();
        filteredCanvas.setOverlayPoints(cropPoints);
    }

    private void applyCropByPoints() {
        applyPolygonCrop(true);
    }

    private void cutInsideByPoints() {
        applyPolygonCrop(false);
    }

    private void applyPolygonCrop(boolean keepInside) {
        DicomSlice slice = selectedSlice();
        if (slice == null || slice.getProcessedImage() == null) {
            return;
        }
        if (cropPoints.size() < 3) {
            statusLabel.setText("Для обрезки по контуру нужно минимум 3 точки.");
            return;
        }

        Polygon polygon = new Polygon();
        for (Point point : cropPoints) {
            polygon.addPoint(point.x, point.y);
        }

        BufferedImage manualMask = slice.getManualMask();
        for (int y = 0; y < manualMask.getHeight(); y++) {
            for (int x = 0; x < manualMask.getWidth(); x++) {
                boolean inside = polygon.contains(x, y);
                if ((keepInside && !inside) || (!keepInside && inside)) {
                    manualMask.getRaster().setSample(x, y, 0, 0);
                }
            }
        }

        updateSelectedPreview();
        thumbnailList.repaint();
        statusLabel.setText(keepInside
                ? "Применено: оставлена область внутри контура."
                : "Применено: вырезана область внутри контура.");
        clearCropPoints();
    }

    private void resetAllFilters() {
        thresholdSlider.setValue(0);
        bandMinSlider.setValue(-1000);
        bandMaxSlider.setValue(3000);
        ignoreModelPresetChange = true;
        modelPresetCombo.setSelectedItem(ModelPreset.FILTERED_MASK);
        ignoreModelPresetChange = false;
        blurSlider.setValue(0);
        refreshFilterLabels();

        if (slices.isEmpty()) {
            statusLabel.setText("Фильтры сброшены.");
            return;
        }

        for (DicomSlice slice : slices) {
            slice.resetManualMask();
            slice.setProcessedImage(slice.getOriginalImage());
        }

        repopulateThumbnails();
        int index = selectedIndex();
        if (index < 0) {
            index = 0;
        }
        setSelectedIndex(index);

        DicomSlice selected = selectedSlice();
        if (selected != null) {
            selected.setProcessedImage(selected.getOriginalImage());
            filteredCanvas.setImage(selected.getOriginalImage());
            thumbnailList.repaint();
        }

        clearCropPoints();
        statusLabel.setText("Все фильтры сброшены. Срезы возвращены к исходному виду.");
    }

    private void applyBrushMask(Point panelPoint) {
        if (!maskModeToggle.isSelected()) {
            return;
        }

        DicomSlice slice = selectedSlice();
        if (slice == null || slice.getProcessedImage() == null) {
            return;
        }

        Point pixelPoint = filteredCanvas.panelToImage(panelPoint);
        if (pixelPoint == null) {
            return;
        }

        int radius = Math.max(1, brushSizeSlider.getValue());
        Graphics2D graphics = slice.getManualMask().createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillOval(pixelPoint.x - radius, pixelPoint.y - radius, radius * 2, radius * 2);
        graphics.dispose();

        updateSelectedPreview();
        thumbnailList.repaint();
    }

    private void setFilterControlsEnabled(boolean enabled) {
        for (JSlider slider : new JSlider[] {
                thresholdSlider,
                bandMinSlider,
                bandMaxSlider,
                blurSlider,
                rangeStartSlider,
                rangeEndSlider,
                modelStepSlider,
                brushSizeSlider
        }) {
            slider.setEnabled(enabled);
        }

        applyRangeButton.setEnabled(enabled);
        openModelButton.setEnabled(enabled);
        modelPresetCombo.setEnabled(enabled);
        maskModeToggle.setEnabled(enabled);
        pointCropModeToggle.setEnabled(enabled);
        resetAllFiltersButton.setEnabled(enabled);
        resetMaskButton.setEnabled(enabled);
        clearCropPointsButton.setEnabled(enabled);
        applyPointCropButton.setEnabled(enabled);
        cutInsideByPointsButton.setEnabled(enabled);
        sliceSlider.setEnabled(enabled && !slices.isEmpty());
        thumbnailList.setEnabled(enabled);
    }

    private void repopulateThumbnails() {
        int index = selectedIndex();
        thumbnailList.setListData(slices.toArray(new DicomSlice[0]));
        if (index >= 0 && index < slices.size()) {
            thumbnailList.setSelectedIndex(index);
        }
    }

    private void updateSliceSliderBounds() {
        int max = Math.max(0, slices.size() - 1);
        withIgnoredSliceSlider(() -> {
            sliceSlider.setMinimum(0);
            sliceSlider.setMaximum(max);
            sliceSlider.setValue(Math.min(sliceSlider.getValue(), max));
            sliceSlider.setEnabled(!slices.isEmpty());
        });

        sliceValueLabel.setText(slices.isEmpty()
                ? "Срез: -"
                : String.format(Locale.US, "Срез: %d/%d", sliceSlider.getValue() + 1, slices.size()));
    }

    private void updateRangeSliderBounds() {
        int max = Math.max(0, slices.size() - 1);
        ignoreRangeSliderChange = true;

        rangeStartSlider.setMinimum(0);
        rangeStartSlider.setMaximum(max);
        rangeStartSlider.setValue(0);
        rangeStartSlider.setEnabled(!slices.isEmpty());

        rangeEndSlider.setMinimum(0);
        rangeEndSlider.setMaximum(max);
        rangeEndSlider.setValue(max);
        rangeEndSlider.setEnabled(!slices.isEmpty());

        ignoreRangeSliderChange = false;
        refreshRangeLabels();
    }

    private void setSelectedIndex(int index) {
        if (index < 0 || index >= slices.size()) {
            originalCanvas.setImage(null);
            filteredCanvas.setImage(null);
            clearCropPoints();
            sliceValueLabel.setText("Срез: -");
            return;
        }

        if (selectedIndex() != index) {
            ignoreListSelectionChange = true;
            thumbnailList.setSelectedIndex(index);
            ignoreListSelectionChange = false;
        }

        withIgnoredSliceSlider(() -> sliceSlider.setValue(index));

        DicomSlice selected = slices.get(index);
        originalCanvas.setImage(selected.getOriginalImage());
        filteredCanvas.setImage(selected.getProcessedImage());
        clearCropPoints();

        sliceValueLabel.setText(String.format(Locale.US, "Срез: %d/%d", index + 1, slices.size()));
        statusLabel.setText(String.format(
                Locale.US,
                "Срез: %s | Z=%.3f мм | номер=%d",
                selected.shortName(),
                selected.getSliceZ(),
                selected.getInstanceNumber()
        ));
    }

    private void withIgnoredSliceSlider(Runnable runnable) {
        ignoreSliceSliderChange = true;
        try {
            runnable.run();
        } finally {
            ignoreSliceSliderChange = false;
        }
    }

    private JPanel sectionPanel(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PANEL_BORDER),
                new EmptyBorder(10, 10, 10, 10)
        ));

        JLabel header = new JLabel(title);
        header.setForeground(TEXT_PRIMARY);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 13.5f));
        header.setBorder(new EmptyBorder(0, 0, 8, 0));
        panel.add(header);
        return panel;
    }

    private void addSliderRow(JPanel panel, String label, JSlider slider, JLabel valueLabel) {
        JLabel caption = new JLabel(label);
        caption.setAlignmentX(LEFT_ALIGNMENT);
        slider.setAlignmentX(LEFT_ALIGNMENT);
        valueLabel.setAlignmentX(LEFT_ALIGNMENT);
        caption.setForeground(TEXT_PRIMARY);
        valueLabel.setForeground(TEXT_SECONDARY);

        panel.add(caption);
        panel.add(slider);
        panel.add(valueLabel);
        panel.add(Box.createVerticalStrut(8));
    }

    private void styleToggle(JToggleButton toggleButton) {
        toggleButton.setFocusPainted(false);
        toggleButton.setOpaque(true);
        toggleButton.setContentAreaFilled(true);
        toggleButton.setBorder(BorderFactory.createLineBorder(BUTTON_BORDER));
        toggleButton.addActionListener(event -> updateToggleVisual(toggleButton));
        updateToggleVisual(toggleButton);
    }

    private void updateToggleVisual(JToggleButton toggleButton) {
        if (toggleButton.isSelected()) {
            toggleButton.setBackground(ACCENT);
            toggleButton.setForeground(Color.WHITE);
        } else {
            toggleButton.setBackground(ACCENT_SOFT);
            toggleButton.setForeground(TEXT_PRIMARY);
        }
    }

    private void showError(String message) {
        statusLabel.setText(message);
        JOptionPane.showMessageDialog(this, message, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    private JMenuItem menuItem(String title, Runnable action) {
        return new JMenuItem(new AbstractAction(title) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                action.run();
            }
        });
    }

    private JButton button(String title, Runnable action) {
        JButton button = new JButton(new AbstractAction(title) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                action.run();
            }
        });
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBackground(BUTTON_BG);
        button.setForeground(TEXT_PRIMARY);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BUTTON_BORDER),
                new EmptyBorder(6, 12, 6, 12)
        ));
        return button;
    }

    private static JSlider slider(int min, int max, int value) {
        JSlider slider = new JSlider(min, max, value);
        slider.setPaintTicks(false);
        slider.setPaintLabels(false);
        return slider;
    }

    private ImageIcon createThumbIcon(BufferedImage source, int size) {
        if (source == null) {
            return null;
        }

        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(size / (double) width, size / (double) height);
        int targetW = Math.max(1, (int) Math.round(width * scale));
        int targetH = Math.max(1, (int) Math.round(height * scale));

        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        graphics.drawImage(source, (size - targetW) / 2, (size - targetH) / 2, targetW, targetH, null);
        graphics.setColor(new Color(220, 220, 220));
        graphics.drawRect(0, 0, size - 1, size - 1);
        graphics.dispose();
        return new ImageIcon(canvas);
    }

    @FunctionalInterface
    private interface LoaderTask {
        List<DicomSlice> load() throws Exception;
    }

    private record FilterConfig(
            int threshold,
            int bandMin,
            int bandMax,
            int blur
    ) {
    }

    private record SliceRange(int start, int end) {
        int total() {
            return end - start + 1;
        }
    }

    private static final class ImageCanvas extends JPanel {
        private static final int PAD_X = 20;
        private static final int PAD_Y = 40;

        private BufferedImage image;
        private final List<Point> overlayPoints = new ArrayList<>();

        private ImageCanvas(String title) {
            setOpaque(true);
            setBackground(BG_CANVAS);
            setBorder(BorderFactory.createTitledBorder(title));
        }

        private void setImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        private void setOverlayPoints(List<Point> points) {
            overlayPoints.clear();
            overlayPoints.addAll(points);
            repaint();
        }

        private Point panelToImage(Point panelPoint) {
            Rectangle rect = computeDrawRect();
            if (rect == null || !rect.contains(panelPoint)) {
                return null;
            }

            int imageX = (int) Math.floor((panelPoint.x - rect.x) * (image.getWidth() / (double) rect.width));
            int imageY = (int) Math.floor((panelPoint.y - rect.y) * (image.getHeight() / (double) rect.height));
            if (imageX < 0 || imageY < 0 || imageX >= image.getWidth() || imageY >= image.getHeight()) {
                return null;
            }
            return new Point(imageX, imageY);
        }

        private Rectangle computeDrawRect() {
            if (image == null) {
                return null;
            }

            int availableW = Math.max(1, getWidth() - PAD_X);
            int availableH = Math.max(1, getHeight() - PAD_Y);
            double scale = Math.min(availableW / (double) image.getWidth(), availableH / (double) image.getHeight());

            int drawW = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int drawH = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int drawX = (getWidth() - drawW) / 2;
            int drawY = (getHeight() - drawH) / 2 + 12;

            return new Rectangle(drawX, drawY, drawW, drawH);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(BG_CANVAS);
            g2.fillRect(0, 0, getWidth(), getHeight());

            Rectangle rect = computeDrawRect();
            if (rect != null) {
                g2.drawImage(image, rect.x, rect.y, rect.width, rect.height, null);
                if (!overlayPoints.isEmpty()) {
                    g2.setColor(new Color(230, 57, 70));
                    g2.setStroke(new BasicStroke(2f));

                    Point prev = null;
                    for (Point p : overlayPoints) {
                        int px = rect.x + (int) Math.round((p.x / (double) image.getWidth()) * rect.width);
                        int py = rect.y + (int) Math.round((p.y / (double) image.getHeight()) * rect.height);
                        g2.fillOval(px - 4, py - 4, 8, 8);
                        if (prev != null) {
                            int prevX = rect.x + (int) Math.round((prev.x / (double) image.getWidth()) * rect.width);
                            int prevY = rect.y + (int) Math.round((prev.y / (double) image.getHeight()) * rect.height);
                            g2.drawLine(prevX, prevY, px, py);
                        }
                        prev = p;
                    }

                    if (overlayPoints.size() > 2) {
                        Point first = overlayPoints.get(0);
                        Point last = overlayPoints.get(overlayPoints.size() - 1);
                        int firstX = rect.x + (int) Math.round((first.x / (double) image.getWidth()) * rect.width);
                        int firstY = rect.y + (int) Math.round((first.y / (double) image.getHeight()) * rect.height);
                        int lastX = rect.x + (int) Math.round((last.x / (double) image.getWidth()) * rect.width);
                        int lastY = rect.y + (int) Math.round((last.y / (double) image.getHeight()) * rect.height);
                        g2.drawLine(lastX, lastY, firstX, firstY);
                    }
                }
            }
            g2.dispose();
        }
    }
}
