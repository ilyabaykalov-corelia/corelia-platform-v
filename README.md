# corelia-platform-v

Адаптер HTTP API Platform V: DataSpace, BPMX/BPMU, DAM и контракт договора ПДС.
Бизнес-процессы и изменения данных исполняет платформа.

## Контракт стабильной платформы

GraphQL-ресурсы соответствуют `sber-npf-platform-v`, ветка `dev`, коммит `e366b493027870256c8686ae564466e89b79e44e`.
Тела операций копируются без изменений из `model.graphql-permissions.json`.
Используются восемь операций: `refDocumentTypeListGet`, `searchDocumentProcessSettings`,
`searchPdsContract`, `updatePdsContract`, `searchAttachment`, `createAttachment`,
`replaceAttachmentVersion`, `deleteAttachment`.

Создание договора выполняет BPMN `Process_pds_contract_approval`, выбранный через
`DocumentProcessSettings`. Адаптер не создаёт договор отдельной мутацией.
После очистки DataSpace должны быть загружены словари `DocumentType` и
`DocumentProcessSettings` из стабильного проекта платформы.

История атрибутов и документов, журнал состава вложений и снимки карточки не используются.
Версии файлов сохраняются через `logicalAttachmentId`, `version` и `current`.
Замена файла создаёт новую запись и снимает признак текущей у предыдущей в одном packet.

Сборка и проверка из родительского каталога: `mvn clean verify` (Java 25).
Системные тесты в `corelia-system-tests` сверяют полное тело каждого исполняемого
GraphQL-запроса со снимком разрешений стабильной платформы.
