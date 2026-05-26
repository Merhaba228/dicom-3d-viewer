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

Если надо собрать jar:

```powershell
mvn clean package
java -jar target\dicom-3d-viewer-1.0.0-SNAPSHOT-all.jar
```
