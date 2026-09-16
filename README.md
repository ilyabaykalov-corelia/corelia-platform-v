# corelia-platform-v

Библиотека интеграции Corelia с DataSpace, BPMX/BPMU и DAM. Содержит DocumentTypes, контракт ПДС, проекции документов и зарегистрированные GraphQL-ресурсы. КИД ОПС также зарегистрирован в DocumentTypes; его валидация находится в corelia-common. Новые правила изменения документов размещаются в document-service.

## Контракты

Тексты в `src/main/resources/graphql` должны точно соответствовать разрешённым операциям соседнего комплекта `sber-npf-platform-v/model.graphql-permissions.json`. Тестовая фикстура `corelia-system-tests/src/test/resources/platform-v/allowed-requests.json` проверяет имя и полное тело запроса.

Используются общие Document, DocumentVersion и DocumentCommand, дочерние реквизиты PdsContract/KidOps и независимые метаданные Attachment. Команды commitDocumentAttributes, commitKidOpsAttributes, commitDocumentNoChange и commitDocumentFileUpload/Replace/Delete фиксируют изменения через атомарные пакеты. Инициализация снимка использует initializeDocumentVersion. Полный перечень ресурсов определяется каталогом graphql, а не историческим числом операций.

Создание выполняется соответствующим BPMN, выбранным через DocumentProcessSettings. КИД ОПС создаётся с подготовленным первым файлом. Справочники DocumentType и DocumentProcessSettings, модель, permissions и процессы должны быть совместимы с ядром.

## Сборка и документация

Из корня Corelia: `mvn -pl corelia-platform-v -am package -DskipTests`; общая проверка — `./scripts/test.sh` на Java 25.

- [Реализация](../docs/implementation.md)
- [Версии](../docs/document-versioning.md)
- [КИД ОПС](../docs/kid-ops.md)
- [Проверки](../docs/testing.md)

Локальная имитация не доказывает принятие модели реальным SDK-генератором или исполнение permissions платформы.
