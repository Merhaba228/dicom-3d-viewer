# Запуск

Нужна Java 17+ и Maven.

```powershell
mvn exec:java
```

Данные DICOM в репозиторий не залиты. Их надо иметь отдельно.

В программе открыть:

```text
Файл -> Импорт папки DICOM
```

И выбрать папку со срезами.

Подробное описание работы программы и шпаргалка для защиты:

[Шпаргалка для защиты DICOM-проекта](docs/Шпаргалка%20для%20защиты%20DICOM-проекта.docx)

Если надо собрать jar:

```powershell
mvn clean package
java -jar target\dicom-3d-viewer-1.0.0-SNAPSHOT-all.jar
```

## Готовые сборки

Готовые архивы находятся в папке [`releases`](releases):

- `Dicom3DViewer-Windows.zip` — рекомендуемый вариант для Windows. Java, `dcm4che` и остальные библиотеки уже встроены. После распаковки запустить `Dicom3DViewer\Dicom3DViewer.exe`.
- `Dicom3DViewer-Portable-JAR.zip` — компактный вариант, требующий установленную Java 17+. Запустить `run-portable.bat`.

Файлы DICOM в архивы не включены. Их нужно выбрать через `Файл -> Импорт папки DICOM`.
