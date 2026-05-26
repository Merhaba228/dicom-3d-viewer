# DICOM Slice Viewer (MVP)

Java desktop prototype for:
- importing DICOM slices (`.dcm`),
- 2D preview and filtering (brightness threshold + HU band-pass + blur + median),
- quick slice navigation with a slider,
- internal 3D point-cloud preview from the filtered slice mask.

## Run

```bash
mvn clean package
mvn exec:java
```

## Windows packaging (`.exe`)

Build on Windows with JDK 17+:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -Type app-image
```

This creates:
- `dist/Dicom3DViewer/Dicom3DViewer.exe` (portable app with bundled runtime),
- `dist/Dicom3DViewer-portable.jar`,
- `dist/run-portable.bat`.

Optional installer (`.exe` setup):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -Type exe
```

Note: installer mode may require WiX Toolset installed (jpackage requirement on Windows).

## UI layout

- Left: filter controls (`Brightness Threshold`, `HU Band MIN`, `HU Band MAX`, `Blur`, contour mode), slice range, 3D model presets (`Bones`, `Soft Tissue`, `Body`, `Filtered Mask`), and manual mask/crop tools.
- Center: original and filtered image panels.
- Right: slice thumbnails.
- Top: import buttons + slice slider.

## Notes

- Long numeric filenames like `1.3.12...dcm` are typically DICOM UIDs, not coordinates.
- Slice order is based on DICOM metadata (`ImagePositionPatient` / `InstanceNumber`).
- 3D preview is built as a filtered surface point cloud: HU range -> 3D neighbor cleanup -> largest connected component -> surface points.
- This is an engineering prototype, not a medical diagnostic tool.
