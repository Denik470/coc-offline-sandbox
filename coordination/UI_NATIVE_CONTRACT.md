# UI/native — договор интеграции v0 (проект)

Для UI-001 это ориентир, а не требование переделывать уже выбранную архитектуру. Native-реализация команд ещё не готова. UI не вызывает libg по адресам.

- VillageState: schemaVersion, villageId, bounds, resources, buildings[].
- BuildingState: instanceId (стабильный строковый ID), dataId (тип из таблицы), level, tileX, tileY, footprintWidth, footprintHeight, visualKey.
- Метаданные типа берутся из logic/buildings.csv по GlobalID, а не по порядку строк: таблица содержит строки продолжений уровней. Поля SWF и ExportName описывают ресурс и экспорт; SC не является готовым PNG.
- Native ClientHome хранит buildings / buildings2 отдельно; data — тип, id — экземпляр, lvl — индекс уровня. Уточнение отображаемого уровня делается адаптером по BuildingLevel; не прибавлять 1 без проверки таблицы.
- Координаты ClientHome и native world не считать тождественными: в native-мире есть масштаб и смещение, а препятствия могут выходить за строительную область. Не зашивать произвольное ограничение 0..43 в адаптер реальной деревни. Bounds возвращает источник состояния.
- Синтетические UI-данные держать отдельно от пользовательской деревни.

Команды: selectBuilding(instanceId), previewMove(instanceId,tileX,tileY), commitMove(commandId,instanceId,tileX,tileY,expectedRevision), cancelPreview(), openPanel(panelId).

CommitMove возвращает success + новую revision и состояние либо ошибку OUT_OF_BOUNDS / COLLISION / UNKNOWN_BUILDING / STALE_REVISION / NOT_IMPLEMENTED. UI показывает preview немедленно, но фиксирует положение только после success. Никаких списаний ресурсов из одного визуального изменения.

События: selectionChanged, stateChanged, commandRejected, panelChanged. Pan/zoom остаются в представлении и не меняют игровое состояние. Сохранение и native-команды реализует Codex; UI-001 может использовать отдельный DemoVillageRepository и явно отмечать, что данные демонстрационные.
