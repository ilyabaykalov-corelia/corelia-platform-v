# Corelia Platform V adapter

Адаптер DataSpace, BPM и DAM. DocumentTypes получает виды из внешнего immutable registry; DocumentProjection использует storage mapping. DataSpaceClient передаёт зарегистрированные статические операции с переменными и JWT пользователя; runtime-генерации GraphQL нет.

Тела операций больше не входят в ресурсы библиотеки. Они находятся в customer package, выбранном CORELIA_CONFIG_PATH. Для СберНПФ исходники находятся в соседнем sber-npf-corelia-config; компилятор сохраняет точные тексты и условия доступа платформы.

[Контракт и компилятор](../docs/configuration.md). [Проверки](../docs/testing.md). Явно различать проверки PlatformStub и реальную модель/permissions/BPM.
